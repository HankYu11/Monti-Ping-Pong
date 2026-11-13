package com.monti.servecoach

import android.content.res.AssetManager
import android.graphics.ImageFormat
import android.hardware.HardwareBuffer
import android.media.Image
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaFormat
import android.opengl.EGL14
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLES30
import android.os.SystemClock
import com.monti.servecoach.core.Capture
import com.monti.servecoach.core.ColourFit
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A serve's capture cut on the graphics chip: the decoder renders each frame into a graphics
 * buffer, two shaders (assets/shaders, also run on a PC against the decoder's bytes) cut out the
 * crop as the capture's planes, and the read-back is the capture itself. Before, every decoded
 * frame was copied out by the processor from the decoder's (slow) memory: 1.4-6.5 s a serve.
 *
 * The chip sees colour, converted its own way; so the first capture of a session decodes one
 * frame the old way too, and fits how the chip's numbers map to the decoder's bytes (ColourFit,
 * and which matrix), so captures stay what the engine was checked with.
 *
 * Runs on the calling thread (its own EGL context for the call). Throws if anything isn't
 * there (ES 3, the external sampler in ES 3 shaders, the native part): the caller falls back.
 */
class CaptureGl(private val assets: AssetManager) {
    class Calib(val bt601: Boolean, val y: ColourFit.Fit, val u: ColourFit.Fit, val v: ColourFit.Fit) {
        override fun toString() = "${if (bt601) "BT.601" else "BT.709"}, Y ${"%.3f".format(y.gain)}x+${"%.1f".format(y.offset)} (rms ${"%.2f".format(y.rms)}), " +
            "U ${"%.3f".format(u.gain)}x+${"%.1f".format(u.offset)}, V ${"%.3f".format(v.gain)}x+${"%.1f".format(v.offset)}"
    }
    companion object {
        /** the session's fit (from the first capture) */
        @Volatile var calib: Calib? = null
        @Volatile var calibNote = ""
        /**
         * The same for the windows cut live off the camera's own frames (LiveWindows), which are
         * not the decoder's: the chip sees the camera's buffer before it is encoded, so a window
         * needs its own map onto the bytes the background frames are decoded into. Fitted by the
         * first capture that decodes a frame the ring also holds, and until then the ring's
         * windows are the chip's own numbers and no capture is built from them.
         */
        @Volatile var ringCalib: Calib? = null
        @Volatile var ringNote = ""
    }

    /** [frames] cut and handed to the sink; [seen] decoded in the span, cut or not */
    class Result(val frames: Int, val fullRange: Boolean, val decodeMs: Long, val cutMs: Long, val seen: Int = frames,
                 /** of [cutMs]'s wall time, how long was spent waiting for a frame to reach the chip */
                 val waitMs: Long = 0)

    private lateinit var display: EGLDisplay
    private lateinit var context: EGLContext
    private lateinit var surface: EGLSurface
    private var tex = 0
    private var progY = 0
    private var progC = 0

    /**
     * Writes the frames with times in [fromUs, toUs] (from [frames], decoding order from a
     * keyframe), cropped to [rect], to [out] in the capture's layout. [reference]: the first
     * of them decoded the old way (its time and planes), when there's no fit yet.
     */
    /**
     * [sink] takes each cropped frame as it comes off the chip. It used to be an OutputStream and
     * the crop went straight into the capture file; a windowed capture reduces each crop to a
     * window and a row sample instead and never writes the crop at all, so the caller decides.
     */
    /**
     * [keep] (by the frame's place in the span) says which frames to cut at all. A capture built
     * from windows cut live wants only the handful the background is medianed from, and the rest
     * are decoded and dropped: the decode cannot be skipped (a frame is predicted from the ones
     * before it) but the draw and the read-back can, which is most of the cutting.
     */
    fun write(frames: List<EncodedFrame>, format: MediaFormat, fromUs: Long, toUs: Long, rect: IntArray, W: Int, H: Int,
              sink: (ByteArray) -> Unit, reference: Pair<Long, ByteArray>?, keep: ((Int) -> Boolean)? = null): Result {
        check(calib != null || reference != null) { "no colour fit and no reference frame" }
        // Four pixels to a texel, and colour is half width: a row comes back as width/4 texels,
        // so a width that is not a multiple of 8 reads short and every row after the first
        // lands early. The plane skews and nothing says so -- in session 20261113 the colour
        // fit read rms 40 where 0.3 is normal, and that was the only sign.
        check(rect[2] % 8 == 0 && rect[3] % 2 == 0) { "the crop must be a multiple of 8 wide and even tall, not ${rect[2]}x${rect[3]}" }
        check(keep == null || calib != null) { "cutting only some frames needs a colour fit already" }
        setUpGl()
        val reader = ImageReader.newInstance(W, H, ImageFormat.PRIVATE, 4, HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE)
        val mime = format.getString(MediaFormat.KEY_MIME)!!
        val codec = MediaCodec.createDecoderByType(mime)
        val images = HashMap<Long, Long>()
        val targets = Targets(rect)
        var fullRange = false
        var written = 0
        var seen = 0
        var waitNs = 0L                                   // waiting for a decoded frame to reach the chip
        var bufW = W; var bufH = H
        val t0 = SystemClock.elapsedRealtime()
        var cutNs = 0L
        try {
            val f = MediaFormat.createVideoFormat(mime, W, H)
            for (k in listOf("csd-0", "csd-1", "csd-2")) if (format.containsKey(k)) f.setByteBuffer(k, format.getByteBuffer(k))
            f.setInteger(MediaFormat.KEY_PRIORITY, 0)
            codec.configure(f, reader.surface, null, 0)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var k = 0
            var eosIn = false
            var done = false
            val inFlight = ArrayDeque<Slot>()
            while (!done) {
                if (!eosIn) {
                    val i = codec.dequeueInputBuffer(1_000)
                    if (i >= 0) {
                        if (k < frames.size) {
                            val fr = frames[k++]
                            codec.getInputBuffer(i)!!.apply { clear(); put(fr.data) }
                            codec.queueInputBuffer(i, 0, fr.data.size, fr.ptsUs, if (fr.key) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                        } else { codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); eosIn = true }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 2_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val of = codec.outputFormat
                    if (of.containsKey(MediaFormat.KEY_COLOR_RANGE)) fullRange = of.getInteger(MediaFormat.KEY_COLOR_RANGE) == MediaFormat.COLOR_RANGE_FULL
                    continue
                }
                if (o < 0) continue
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) done = true
                val pts = info.presentationTimeUs
                val inSpan = info.size > 0 && pts >= fromUs && pts <= toUs
                val wanted = inSpan && (keep == null || keep(seen))
                if (inSpan) seen++
                codec.releaseOutputBuffer(o, wanted)
                if (!wanted) continue
                val tw = System.nanoTime()
                val img = awaitImage(reader) ?: throw IllegalStateException("a decoded frame never reached the chip")
                val tc = System.nanoTime()
                waitNs += tc - tw
                val hb = img.hardwareBuffer ?: throw IllegalStateException("no buffer")
                var eglImage = images[hb.id] ?: 0L
                if (eglImage == 0L) { eglImage = HwTex.image(hb); if (eglImage == 0L) throw IllegalStateException("the chip can't read the decoder's frames"); images[hb.id] = eglImage }
                // the buffer's own size: a decoder pads 1080 rows to 1088, and the shaders address
                // the texture by it (taken as 1080 on the phone: the fit was off by 7 levels)
                bufW = hb.width; bufH = hb.height
                hb.close()
                HwTex.attach(eglImage, tex)
                if (calib == null && reference != null && pts == reference.first) calibrate(targets, bufW, bufH, rect, reference.second)
                // never the chip's own numbers unmapped: without a fit the capture is made the old way
                val c = calib ?: throw IllegalStateException("no colour fit yet (the reference frame wasn't decoded)")
                if (keep != null) {
                    // Only the handful the background needs. Read it back then and there: the
                    // fences and the three buffers exist to keep the chip busy across hundreds
                    // of frames, and across fifteen scattered ones they only add waiting. On a
                    // 1824x756 crop the pipelined path spent 1746 ms on twelve frames, 145 ms
                    // each, against 4.6 ms a frame cutting all 188 of a crop two thirds the
                    // size the ordinary way.
                    sink(targets.cutNow(c, bufW, bufH, rect)); written++
                    img.close()
                } else {
                    inFlight.addLast(targets.cut(c, bufW, bufH, rect, img))
                    while (inFlight.size > 2) { written += inFlight.removeFirst().takeOut(sink) }
                }
                cutNs += System.nanoTime() - tc
            }
            while (inFlight.isNotEmpty()) written += inFlight.removeFirst().takeOut(sink)
        } finally {
            try { codec.stop() } catch (_: Exception) {}
            codec.release()
            images.values.forEach { HwTex.release(it) }
            targets.release()
            reader.close()
            tearDownGl()
        }
        return Result(written, fullRange, SystemClock.elapsedRealtime() - t0, cutNs / 1_000_000, seen, waitNs / 1_000_000)
    }

    private fun awaitImage(reader: ImageReader): Image? {
        val until = SystemClock.elapsedRealtime() + 500
        while (SystemClock.elapsedRealtime() < until) {
            reader.acquireNextImage()?.let { return it }
            Thread.sleep(1)
        }
        return null
    }

    /** the first frame cut both ways (BT.709, BT.601) with no mapping, fitted to the decoder's bytes */
    private fun calibrate(t: Targets, W: Int, H: Int, rect: IntArray, decoded: ByteArray) {
        val w = rect[2]; val h = rect[3]; val ny = w * h; val nc = (w / 2) * (h / 2)
        val id = ColourFit.Fit(1.0, 0.0, 0.0)
        val fits = listOf(false, true).map { bt601 ->
            val chip = t.cutNow(Calib(bt601, id, id, id), W, H, rect)
            val y = ColourFit.fit(chip, decoded, 0, ny)
            val u = ColourFit.fit(chip, decoded, ny, nc)
            val v = ColourFit.fit(chip, decoded, ny + nc, nc)
            Calib(bt601, y, u, v)
        }
        val best = fits.minByOrNull { it.y.rms }!!
        calibNote = "$best; other matrix rms ${"%.2f".format(fits.first { it !== best }.y.rms)}; buffer ${W}x$H"
        // the chip's picture must line up with the decoder's (here 0.26-0.46 levels): if not, it's
        // read wrongly on this phone, and the captures are made the old way
        check(best.y.rms <= 3.0) { "the chip's frames don't match the decoder's (rms ${"%.1f".format(best.y.rms)}): $calibNote" }
        calib = best
    }

    /** one frame's worth of read-back, in flight until the chip is done */
    private inner class Slot(val pbo: Int, val fence: Long, val bytes: Int, val img: Image) {
        fun takeOut(sink: (ByteArray) -> Unit): Int {
            GLES30.glClientWaitSync(fence, GLES30.GL_SYNC_FLUSH_COMMANDS_BIT, 100_000_000L)
            GLES30.glDeleteSync(fence)
            img.close()
            GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, pbo)
            val m = GLES30.glMapBufferRange(GLES30.GL_PIXEL_PACK_BUFFER, 0, bytes, GLES30.GL_MAP_READ_BIT) as ByteBuffer
            val a = ByteArray(bytes)
            m.order(ByteOrder.nativeOrder()).position(0); m.get(a)
            GLES30.glUnmapBuffer(GLES30.GL_PIXEL_PACK_BUFFER)
            GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)
            sink(a)
            return 1
        }
    }

    /** the crop's render targets (brightness w/4 x h; colour w/8 x h/2, two) and read-back buffers */
    private inner class Targets(rect: IntArray) {
        val w = rect[2]; val h = rect[3]
        val bytes = w * h + 2 * (w / 2) * (h / 2)
        val tY = texture(w / 4, h); val tU = texture(w / 8, h / 2); val tV = texture(w / 8, h / 2)
        val fY = IntArray(1).also { GLES20.glGenFramebuffers(1, it, 0) }[0]
        val fC = IntArray(1).also { GLES20.glGenFramebuffers(1, it, 0) }[0]
        val pbos = IntArray(3).also { GLES30.glGenBuffers(3, it, 0) }
        var next = 0
        init {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fY)
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, tY, 0)
            check(GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE) { "framebuffer (brightness)" }
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fC)
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, tU, 0)
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT1, GLES20.GL_TEXTURE_2D, tV, 0)
            GLES30.glDrawBuffers(2, intArrayOf(GLES20.GL_COLOR_ATTACHMENT0, GLES30.GL_COLOR_ATTACHMENT1), 0)
            check(GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE) { "framebuffer (colour)" }
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            for (b in pbos) {
                GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, b)
                GLES30.glBufferData(GLES30.GL_PIXEL_PACK_BUFFER, bytes, null, GLES30.GL_STREAM_READ)
            }
            GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)
        }

        private fun draw(c: Calib, W: Int, H: Int, rect: IntArray) {
            val wY = if (c.bt601) floatArrayOf(0.299f, 0.587f, 0.114f) else floatArrayOf(0.2126f, 0.7152f, 0.0722f)
            val cs = if (c.bt601) floatArrayOf(1.772f, 1.402f) else floatArrayOf(1.8556f, 1.5748f)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex)
            // brightness
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fY)
            GLES20.glViewport(0, 0, w / 4, h)
            GLES20.glUseProgram(progY)
            uniforms(progY, W, H, rect, wY)
            GLES20.glUniform2f(GLES20.glGetUniformLocation(progY, "ym"), c.y.gain.toFloat(), (c.y.offset / 255).toFloat())
            quad()
            // colour
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fC)
            GLES20.glViewport(0, 0, w / 8, h / 2)
            GLES20.glUseProgram(progC)
            uniforms(progC, W, H, rect, wY)
            GLES20.glUniform2fv(GLES20.glGetUniformLocation(progC, "cs"), 1, cs, 0)
            GLES20.glUniform2f(GLES20.glGetUniformLocation(progC, "um"), c.u.gain.toFloat(), (c.u.offset / 255).toFloat())
            GLES20.glUniform2f(GLES20.glGetUniformLocation(progC, "vm"), c.v.gain.toFloat(), (c.v.offset / 255).toFloat())
            quad()
        }

        /** read back into a buffer: brightness, then U, then V, as the capture lays them out */
        private fun readInto(pbo: Int) {
            GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, pbo)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fY)
            GLES30.glReadBuffer(GLES20.GL_COLOR_ATTACHMENT0)
            GLES30.glReadPixels(0, 0, w / 4, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, 0)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fC)
            GLES30.glReadBuffer(GLES20.GL_COLOR_ATTACHMENT0)
            GLES30.glReadPixels(0, 0, w / 8, h / 2, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, w * h)
            GLES30.glReadBuffer(GLES30.GL_COLOR_ATTACHMENT1)
            GLES30.glReadPixels(0, 0, w / 8, h / 2, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, w * h + (w / 2) * (h / 2))
            GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        }

        /** this frame cut, its read-back started */
        fun cut(c: Calib, W: Int, H: Int, rect: IntArray, img: Image): Slot {
            draw(c, W, H, rect)
            val pbo = pbos[next]; next = (next + 1) % pbos.size
            readInto(pbo)
            val fence = GLES30.glFenceSync(GLES30.GL_SYNC_GPU_COMMANDS_COMPLETE, 0)
            GLES20.glFlush()
            return Slot(pbo, fence, bytes, img)
        }

        /** this frame cut and read back now (for the fit) */
        fun cutNow(c: Calib, W: Int, H: Int, rect: IntArray): ByteArray {
            draw(c, W, H, rect)
            val bb = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fY)
            GLES30.glReadBuffer(GLES20.GL_COLOR_ATTACHMENT0)
            bb.position(0); GLES20.glReadPixels(0, 0, w / 4, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, bb)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fC)
            GLES30.glReadBuffer(GLES20.GL_COLOR_ATTACHMENT0)
            bb.position(w * h); GLES20.glReadPixels(0, 0, w / 8, h / 2, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, bb.slice())
            GLES30.glReadBuffer(GLES30.GL_COLOR_ATTACHMENT1)
            bb.position(w * h + (w / 2) * (h / 2)); GLES20.glReadPixels(0, 0, w / 8, h / 2, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, bb.slice())
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            val a = ByteArray(bytes); bb.position(0); bb.get(a)
            return a
        }

        fun release() {
            GLES30.glDeleteBuffers(3, pbos, 0)
            GLES20.glDeleteFramebuffers(2, intArrayOf(fY, fC), 0)
            GLES20.glDeleteTextures(3, intArrayOf(tY, tU, tV), 0)
        }
    }

    private fun uniforms(p: Int, W: Int, H: Int, rect: IntArray, wY: FloatArray) {
        GLES20.glUniform1i(GLES20.glGetUniformLocation(p, "tex"), 0)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(p, "size"), W.toFloat(), H.toFloat())
        GLES20.glUniform2f(GLES20.glGetUniformLocation(p, "origin"), rect[0].toFloat(), rect[1].toFloat())
        GLES20.glUniform3fv(GLES20.glGetUniformLocation(p, "wY"), 1, wY, 0)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(p, "mirror"), 0f, 0f)  // a decoded frame is the right way round
    }

    private val quadBuf = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer()
        .apply { put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0) }
    private fun quad() {
        GLES20.glEnableVertexAttribArray(0)
        GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 0, quadBuf)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun texture(w: Int, h: Int): Int {
        val t = IntArray(1); GLES20.glGenTextures(1, t, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        return t[0]
    }

    private fun setUpGl() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val v = IntArray(2)
        check(EGL14.eglInitialize(display, v, 0, v, 1)) { "EGL" }
        val cfgs = arrayOfNulls<android.opengl.EGLConfig>(1); val n = IntArray(1)
        check(EGL14.eglChooseConfig(display, intArrayOf(EGL14.EGL_RENDERABLE_TYPE, 0x40 /* ES 3 */, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT, EGL14.EGL_NONE), 0, cfgs, 0, 1, n, 0) && n[0] > 0) { "no ES 3 config" }
        context = EGL14.eglCreateContext(display, cfgs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
        check(context != EGL14.EGL_NO_CONTEXT) { "no ES 3 context" }
        surface = EGL14.eglCreatePbufferSurface(display, cfgs[0], intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        check(EGL14.eglMakeCurrent(display, surface, surface, context)) { "EGL current" }
        val t = IntArray(1); GLES20.glGenTextures(1, t, 0); tex = t[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        val vs = assets.open("shaders/quad.vert").bufferedReader().readText()
        progY = program(vs, assets.open("shaders/crop_y.frag").bufferedReader().readText())
        progC = program(vs, assets.open("shaders/crop_c.frag").bufferedReader().readText())
    }

    private fun tearDownGl() {
        try {
            GLES20.glDeleteProgram(progY); GLES20.glDeleteProgram(progC)
            GLES20.glDeleteTextures(1, intArrayOf(tex), 0)
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, surface)
            EGL14.eglDestroyContext(display, context)
        } catch (_: Exception) {}
    }

    private fun program(vs: String, fs: String): Int {
        fun shader(type: Int, src: String): Int {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, src); GLES20.glCompileShader(s)
            val ok = IntArray(1); GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
            check(ok[0] != 0) { "shader: " + GLES20.glGetShaderInfoLog(s) }
            return s
        }
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, shader(GLES20.GL_VERTEX_SHADER, vs))
        GLES20.glAttachShader(p, shader(GLES20.GL_FRAGMENT_SHADER, fs))
        GLES20.glBindAttribLocation(p, 0, "pos")
        GLES20.glLinkProgram(p)
        val ok = IntArray(1); GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        check(ok[0] != 0) { "program: " + GLES20.glGetProgramInfoLog(p) }
        return p
    }
}
