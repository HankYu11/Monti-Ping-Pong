package com.monti.servecoach

import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.opengl.EGLExt
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLES30
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * The camera's second stream (the one meant for the preview), shrunk by the graphics chip: each
 * frame arrives as a texture, is drawn at a quarter of its size (each pixel the mean of a 4x4
 * block: 4 filtered reads of 2x2) as brightness and colour in the tracker's units, and read
 * back: 480x270x4 bytes, against 1920x1080 frames decoded and read by the processor before
 * (on a Pixel 8 that couldn't keep up at 240 a second). Every [previewEvery]th frame is also
 * drawn on the screen.
 *
 * [onFrame] gets every frame's time (ns, by the camera's clock: not the recording's) and number;
 * [onPicture], every [every]th frame's small picture (R brightness, G and B colour + 128), two
 * pictures later: it is read back while the chip goes on (waiting for each took 4 ms, and frames
 * arriving meanwhile were lost). The bytes belong to the receiver.
 *
 * With [encoder] (its input surface), the frames come through the camera's *recording* stream
 * instead (0.6): in slow motion only that one is lossless (the camera gives a preview consumer
 * no queue: with frames arriving in batches, 0.5.4 lost 42% of them). The camera fills a queue of
 * ours (an ImageReader made to look like an encoder); the chip reads each frame's buffer straight
 * from it (HwTex: no copy), shrinks it, draws it whole into the real encoder, timed as the camera
 * timed it, and the buffer goes back to the camera. The screen's preview is then the camera's own
 * (30 a second). (0.6 passed frames on with an ImageWriter; the screen froze: frames didn't come
 * back to the queue, and in slow motion a stalled stream stalls the camera.)
 */
class GlTap(
    private val width: Int, private val height: Int,
    private val every: Int = 2,
    private val fps: Int = 240,
    private val previewEvery: Int = 8,
    private val onFrame: (tsNs: Long, frameNo: Long) -> Unit,
    private val onPicture: (tsNs: Long, frameNo: Long, rgba: ByteArray) -> Unit,
    private val onError: (String) -> Unit,
    private val encoder: Surface? = null,
) {
    /** recording stream through here (see above), else the preview stream */
    val throughRecording get() = encoder != null
    val qw = width / 4
    val qh = height / 4
    private val thread = HandlerThread("gl-tap").apply { start() }
    private val handler = Handler(thread.looper)

    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var config: EGLConfig? = null
    private var pbuffer: EGLSurface = EGL14.EGL_NO_SURFACE
    private var window: EGLSurface = EGL14.EGL_NO_SURFACE
    private var tex = 0
    private var fbo = 0
    private var fboTex = 0
    private var tapProgram = 0
    private var showProgram = 0
    private var texture: SurfaceTexture? = null
    private var textureSurface: Surface? = null
    /** the surface the camera fills: the texture's, or (through the recording stream) the queue's */
    lateinit var surface: Surface
        private set
    private var queue: ImageReader? = null
    private var encWindow: EGLSurface = EGL14.EGL_NO_SURFACE
    @Volatile var queued = 0L; private set               // frames taken from the camera's queue
    @Volatile var encodeMsAvg = 0.0; private set
    private var encodeN = 0L
    private val pixels: ByteBuffer = ByteBuffer.allocateDirect(qw * qh * 4).order(ByteOrder.nativeOrder())

    /** which way the picture is turned against the recorded frames (bit 0: x, bit 1: y), from the
     *  pipeline's check: the preview is drawn as the recording is (the camera's own transform
     *  assumes a portrait screen: 0.5 showed it turned a quarter) */
    @Volatile var flip = 0; private set
    @Volatile private var previewTarget: Surface? = null

    // for the report
    @Volatile var frames = 0L; private set
    @Volatile var tapped = 0L; private set
    @Volatile var missed = 0L; private set                // frames that never reached us (by their times)
    @Volatile var tapMsAvg = 0.0; private set
    @Volatile var latchMsAvg = 0.0; private set           // taking the frame in (updateTexImage)
    @Volatile var showMsAvg = 0.0; private set
    @Volatile var readMsAvg = 0.0; private set            // on the reader's thread: waiting for the chip, copying out
    @Volatile var busySkips = 0L; private set             // pictures not taken: every read-back buffer still in use
    val readBack get() = if (gl3) "on its own thread (ES 3)" else "waiting (ES 2)"
    private var latchN = 0L
    private var showN = 0L
    private var readN = 0L

    // reading back on a thread of its own (a context sharing the buffers): the frames' thread only
    // starts each copy (0.5.3: taking them out there took 3.9 ms a picture, and the camera keeps
    // only its newest preview frame, so frames arriving meanwhile were lost)
    private val reader = HandlerThread("gl-read").apply { start() }
    private val readerHandler = Handler(reader.looper)
    private var readerContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var readerSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var t0Ns = -1L
    private var lastNo = -1L

    /** Sets up EGL and the camera's surface; blocks until ready. */
    fun start() {
        val done = java.util.concurrent.CountDownLatch(1)
        var err: Exception? = null
        handler.post { try { setUp() } catch (e: Exception) { err = e }; done.countDown() }
        // never hang the screen on it (0.6 froze after Start): 3 s, then the next way of watching
        if (!done.await(3, java.util.concurrent.TimeUnit.SECONDS)) { thread.quitSafely(); reader.quitSafely(); throw IllegalStateException("setting up took over 3 s") }
        err?.let { throw it }
    }

    fun setFlip(f: Int) { flip = f }

    /** Draws the preview on [s] (a SurfaceView's), or stops with null. */
    fun showOn(s: Surface?) = handler.post {
        if (window != EGL14.EGL_NO_SURFACE) { EGL14.eglDestroySurface(display, window); window = EGL14.EGL_NO_SURFACE }
        previewTarget = s
        if (s != null && s.isValid) window = EGL14.eglCreateWindowSurface(display, config, s, intArrayOf(EGL14.EGL_NONE), 0)
    }

    fun stop() {
        val done = java.util.concurrent.CountDownLatch(1)
        handler.post {
            try {
                texture?.setOnFrameAvailableListener(null)
                queue?.setOnImageAvailableListener(null, null)
                while (inFlight.isNotEmpty()) { val f = inFlight.removeFirst(); GLES30.glDeleteSync(f.fence); f.img.close() }
                images.values.forEach { HwTex.release(it) }; images.clear()
                queue?.close()
                if (encWindow != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, encWindow)
                textureSurface?.release(); texture?.release()
                if (window != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, window)
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                EGL14.eglDestroySurface(display, pbuffer)
                if (readerContext != EGL14.EGL_NO_CONTEXT) {
                    val r = java.util.concurrent.CountDownLatch(1)
                    readerHandler.post { EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT); r.countDown() }
                    r.await(500, java.util.concurrent.TimeUnit.MILLISECONDS)
                    EGL14.eglDestroyContext(display, readerContext); EGL14.eglDestroySurface(display, readerSurface)
                }
                EGL14.eglDestroyContext(display, context)
                EGL14.eglTerminate(display)
            } catch (_: Exception) {}
            done.countDown()
        }
        done.await(1, java.util.concurrent.TimeUnit.SECONDS)
        thread.quitSafely()
        reader.quitSafely()
    }

    private fun setUp() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val v = IntArray(2)
        check(EGL14.eglInitialize(display, v, 0, v, 1)) { "no EGL" }
        val cfgs = arrayOfNulls<EGLConfig>(1); val n = IntArray(1)
        for (es3 in listOf(true, false)) {
            val attrs = intArrayOf(EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, if (es3) 0x40 /* EGL_OPENGL_ES3_BIT_KHR */ else EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT or EGL14.EGL_WINDOW_BIT,
                0x3142 /* EGL_RECORDABLE_ANDROID */, if (encoder != null) 1 else -1 /* EGL_DONT_CARE */, EGL14.EGL_NONE)
            if (!EGL14.eglChooseConfig(display, attrs, 0, cfgs, 0, 1, n, 0) || n[0] == 0) continue
            config = cfgs[0]
            context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, if (es3) 3 else 2, EGL14.EGL_NONE), 0)
            if (context != EGL14.EGL_NO_CONTEXT) { gl3 = es3; break }
        }
        check(context != EGL14.EGL_NO_CONTEXT) { "no EGL context" }
        pbuffer = EGL14.eglCreatePbufferSurface(display, config, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        check(EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)) { "EGL context" }

        val t = IntArray(1)
        GLES20.glGenTextures(1, t, 0); tex = t[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        GLES20.glGenTextures(1, t, 0); fboTex = t[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTex)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, qw, qh, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        GLES20.glGenFramebuffers(1, t, 0); fbo = t[0]
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, fboTex, 0)
        check(GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE) { "framebuffer" }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)

        if (gl3) {
            GLES30.glGenBuffers(pbo.size, pbo, 0)
            busy = java.util.concurrent.atomic.AtomicIntegerArray(pbo.size)
            for (b in pbo) {
                GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, b)
                GLES30.glBufferData(GLES30.GL_PIXEL_PACK_BUFFER, qw * qh * 4, null, GLES30.GL_STREAM_READ)
            }
            GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)
        }
        if (gl3) {
            readerContext = EGL14.eglCreateContext(display, config, context, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
            readerSurface = EGL14.eglCreatePbufferSurface(display, config, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
            if (readerContext == EGL14.EGL_NO_CONTEXT) gl3 = false
            else {
                val ready = java.util.concurrent.CountDownLatch(1)
                var ok = false
                readerHandler.post { ok = EGL14.eglMakeCurrent(display, readerSurface, readerSurface, readerContext); ready.countDown() }
                ready.await()
                if (!ok) gl3 = false
            }
        }
        tapProgram = program(VERTEX_TAP, FRAGMENT_TAP)
        showProgram = program(VERTEX_SHOW, FRAGMENT_SHOW)

        if (encoder == null) {
            val st = SurfaceTexture(tex)
            st.setDefaultBufferSize(width, height)
            st.setOnFrameAvailableListener({ onFrameAvailable() }, handler)
            texture = st
            textureSurface = Surface(st)
            surface = textureSurface!!
        } else {
            check(HwTex.loaded) { "native part not loaded" }
            encWindow = EGL14.eglCreateWindowSurface(display, config, encoder, intArrayOf(EGL14.EGL_NONE), 0)
            check(encWindow != EGL14.EGL_NO_SURFACE) { "encoder surface" }
            // the encoder's surface stays current (switching to it and back each frame cost time),
            // and handing it a frame doesn't wait for a screen refresh
            check(EGL14.eglMakeCurrent(display, encWindow, encWindow, context)) { "encoder surface current" }
            EGL14.eglSwapInterval(display, 0)
            val q = ImageReader.newInstance(width, height, ImageFormat.PRIVATE, 12,
                HardwareBuffer.USAGE_VIDEO_ENCODE or HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE)
            q.setOnImageAvailableListener({ drainQueue() }, handler)
            queue = q
            surface = q.surface
        }
    }

    /** the preview stream: the newest frame into the texture */
    private fun onFrameAvailable() {
        try {
            val st = texture ?: return
            val tl = SystemClock.elapsedRealtimeNanos()
            st.updateTexImage()
            latchMsAvg += ((SystemClock.elapsedRealtimeNanos() - tl) / 1e6 - latchMsAvg) / minOf(++latchN, 240L)
            handleFrame(st.timestamp)
        } catch (e: Exception) { onError("tap: ${e.message}") }
    }

    // Each buffer's EGLImage, made the first time it comes round (by its id) and kept: making one
    // took 1.5 ms a frame. Frames in flight: their buffer goes back to the camera once the chip is
    // done with it (a fence), not waited for frame by frame (0.6.3: the camera, which only sends a
    // frame when a buffer comes back, slowed to 60 a second).
    private val images = HashMap<Long, Long>()
    private class InFlight(val img: android.media.Image, val fence: Long)
    private val inFlight = ArrayDeque<InFlight>()
    @Volatile var fenceWaitMsAvg = 0.0; private set
    private var fenceN = 0L

    /** the recording stream: every frame waiting in the queue, in order */
    private fun drainQueue() {
        val q = queue ?: return
        val start = SystemClock.elapsedRealtimeNanos()
        if (busySince == 0L) busySince = start
        var got = 0
        try { drainLoop(q) { got++ } } finally {
            busyNs += SystemClock.elapsedRealtimeNanos() - start
            drains++; if (got > maxBurst) maxBurst = got.toLong()
        }
    }
    private var busySince = 0L
    private var busyNs = 0L
    @Volatile var drains = 0L; private set
    @Volatile var maxBurst = 0L; private set
    /** the share of the time the frames' thread was at work (through the recording stream) */
    val busyShare get() = if (busySince == 0L) 0.0 else busyNs.toDouble() / maxOf(1L, SystemClock.elapsedRealtimeNanos() - busySince)

    private inline fun drainLoop(q: ImageReader, counted: () -> Unit) {
        while (true) {
            retire(2)                                            // at most 2 frames in flight before the next
            val img = try { q.acquireNextImage() } catch (e: Exception) { onError("queue: ${e.message}"); null } ?: break
            counted()
            var kept = false
            try {
                queued++
                val tl = SystemClock.elapsedRealtimeNanos()
                val hb = img.hardwareBuffer ?: continue
                val id = hb.id
                var eglImage = images[id] ?: 0L
                if (eglImage == 0L) { eglImage = HwTex.image(hb); if (eglImage != 0L) images[id] = eglImage }
                hb.close()
                if (eglImage == 0L) { bindFailures++; if (bindFailures == 1L) onError("couldn't read a camera frame on the chip"); continue }
                HwTex.attach(eglImage, tex)
                latchMsAvg += ((SystemClock.elapsedRealtimeNanos() - tl) / 1e6 - latchMsAvg) / minOf(++latchN, 240L)
                handleFrame(img.timestamp)
                if (gl3) {
                    val f = GLES30.glFenceSync(GLES30.GL_SYNC_GPU_COMMANDS_COMPLETE, 0)
                    GLES20.glFlush()
                    inFlight.addLast(InFlight(img, f)); kept = true
                } else GLES20.glFinish()
            } catch (e: Exception) { onError("tap: ${e.message}") }
            finally { if (!kept) img.close() }
        }
        retire(0, wait = false)                                  // whatever is already done
    }

    /** buffers back to the camera: all but [keep] of those in flight (waiting for the chip), and any already done */
    private fun retire(keep: Int, wait: Boolean = true) {
        while (inFlight.isNotEmpty()) {
            val f = inFlight.first()
            val t0 = SystemClock.elapsedRealtimeNanos()
            val mustWait = wait && inFlight.size > keep
            val r = GLES30.glClientWaitSync(f.fence, GLES30.GL_SYNC_FLUSH_COMMANDS_BIT, if (mustWait) 50_000_000L else 0L)
            if (r == GLES30.GL_TIMEOUT_EXPIRED && !mustWait) break
            if (mustWait) fenceWaitMsAvg += ((SystemClock.elapsedRealtimeNanos() - t0) / 1e6 - fenceWaitMsAvg) / minOf(++fenceN, 240L)
            GLES30.glDeleteSync(f.fence)
            f.img.close()
            inFlight.removeFirst()
        }
    }

    @Volatile var bindFailures = 0L; private set

    private fun handleFrame(ts: Long) {
        frames++
        if (t0Ns < 0) t0Ns = ts
        // the frame's number by its time, so frames that never arrived leave gaps
        val no = Math.round((ts - t0Ns) * fps / 1e9)
        if (lastNo >= 0 && no > lastNo + 1) missed += no - lastNo - 1
        if (no <= lastNo) return                                  // a repeat
        lastNo = no
        onFrame(ts, no)
        if (no % every == 0L) {
            val t0 = SystemClock.elapsedRealtimeNanos()
            tap(ts, no)
            tapped++
            tapMsAvg += ((SystemClock.elapsedRealtimeNanos() - t0) / 1e6 - tapMsAvg) / minOf(tapped, 240L)
        }
        if (encoder != null) {
            val te = SystemClock.elapsedRealtimeNanos()
            encode(ts)
            encodeMsAvg += ((SystemClock.elapsedRealtimeNanos() - te) / 1e6 - encodeMsAvg) / minOf(++encodeN, 240L)
        }
        if (encoder == null && no % previewEvery == 1L && window != EGL14.EGL_NO_SURFACE) {
            val ts0 = SystemClock.elapsedRealtimeNanos()
            show()
            showMsAvg += ((SystemClock.elapsedRealtimeNanos() - ts0) / 1e6 - showMsAvg) / minOf(++showN, 240L)
        }
    }

    @Volatile var stalls = 0L; private set

    /** the whole frame into the encoder, as the camera timed it, the right way round */
    private fun encode(ts: Long) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, width, height)
        GLES20.glUseProgram(showProgram)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(showProgram, "flip"), 0f, 0f)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(showProgram, "tex"), 0)
        val pos = GLES20.glGetAttribLocation(showProgram, "pos")
        GLES20.glEnableVertexAttribArray(pos)
        GLES20.glVertexAttribPointer(pos, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        EGLExt.eglPresentationTimeANDROID(display, encWindow, ts)
        val t0 = SystemClock.elapsedRealtimeNanos()
        EGL14.eglSwapBuffers(display, encWindow)
        swapMsAvg += ((SystemClock.elapsedRealtimeNanos() - t0) / 1e6 - swapMsAvg) / minOf(++swapN, 240L)
    }
    @Volatile var swapMsAvg = 0.0; private set
    private var swapN = 0L

    private val quad: FloatBuffer = ByteBuffer.allocateDirect(4 * 8).order(ByteOrder.nativeOrder()).asFloatBuffer()
        .apply { put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0) }

    // read-back buffers (OpenGL ES 3): each picture is copied into one while the chip goes on;
    // the reader takes it out when the chip is done (a fence) and frees the buffer
    private var gl3 = false
    private val pbo = IntArray(4)
    private var busy = java.util.concurrent.atomic.AtomicIntegerArray(4)
    private var slot = 0

    /** the small picture: drawn into the framebuffer, read back (see [onPicture]) */
    private fun tap(ts: Long, no: Long) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        GLES20.glViewport(0, 0, qw, qh)
        GLES20.glUseProgram(tapProgram)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(tapProgram, "tex"), 0)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(tapProgram, "texel"), 1f / width, 1f / height)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(tapProgram, "flip"), 0f, 0f)
        val pos = GLES20.glGetAttribLocation(tapProgram, "pos")
        GLES20.glEnableVertexAttribArray(pos)
        GLES20.glVertexAttribPointer(pos, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        if (!gl3) {
            pixels.position(0)
            GLES20.glReadPixels(0, 0, qw, qh, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            val out = ByteArray(qw * qh * 4)
            pixels.position(0); pixels.get(out)
            onPicture(ts, no, out)
            return
        }
        val k = slot
        if (busy.get(k) != 0) { busySkips++; GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0); return }
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, pbo[k])
        GLES30.glReadPixels(0, 0, qw, qh, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, 0)
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        val fence = GLES30.glFenceSync(GLES30.GL_SYNC_GPU_COMMANDS_COMPLETE, 0)
        GLES20.glFlush()
        busy.set(k, 1)
        slot = (k + 1) % pbo.size
        readerHandler.post { takeOut(k, fence, ts, no) }
    }

    /** on the reader's thread: once the chip is done, the picture copied out and the buffer freed */
    private fun takeOut(k: Int, fence: Long, ts: Long, no: Long) {
        val t0 = SystemClock.elapsedRealtimeNanos()
        var out: ByteArray? = null
        try {
            GLES30.glClientWaitSync(fence, GLES30.GL_SYNC_FLUSH_COMMANDS_BIT, 100_000_000L)
            GLES30.glDeleteSync(fence)
            GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, pbo[k])
            val m = GLES30.glMapBufferRange(GLES30.GL_PIXEL_PACK_BUFFER, 0, qw * qh * 4, GLES30.GL_MAP_READ_BIT) as ByteBuffer?
            if (m != null) { out = ByteArray(qw * qh * 4); m.order(ByteOrder.nativeOrder()).position(0); m.get(out) }
            GLES30.glUnmapBuffer(GLES30.GL_PIXEL_PACK_BUFFER)
            GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)
        } catch (e: Exception) { onError("read-back: ${e.message}") }
        busy.set(k, 0)
        readMsAvg += ((SystemClock.elapsedRealtimeNanos() - t0) / 1e6 - readMsAvg) / minOf(++readN, 240L)
        out?.let { onPicture(ts, no, it) }
    }

    /** the preview, as the camera's own transform says, without waiting for the screen */
    private fun show() {
        if (!EGL14.eglMakeCurrent(display, window, window, context)) { EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context); return }
        EGL14.eglSwapInterval(display, 0)
        val w = IntArray(1); val h = IntArray(1)
        EGL14.eglQuerySurface(display, window, EGL14.EGL_WIDTH, w, 0); EGL14.eglQuerySurface(display, window, EGL14.EGL_HEIGHT, h, 0)
        GLES20.glViewport(0, 0, w[0], h[0])
        GLES20.glUseProgram(showProgram)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(showProgram, "flip"), if (flip and 1 != 0) 1f else 0f, if (flip and 2 != 0) 1f else 0f)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(showProgram, "tex"), 0)
        val pos = GLES20.glGetAttribLocation(showProgram, "pos")
        GLES20.glEnableVertexAttribArray(pos)
        GLES20.glVertexAttribPointer(pos, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        EGL14.eglSwapBuffers(display, window)
        EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)
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
        GLES20.glLinkProgram(p)
        val ok = IntArray(1); GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        check(ok[0] != 0) { "program: " + GLES20.glGetProgramInfoLog(p) }
        return p
    }

    companion object {
        // the frame's own texture coordinates (no transform): row 0 of the read-back picture is
        // row 0 of the buffer, as the decoder hands it out; [flip] corrects it if not (checked live)
        private const val VERTEX_TAP = """
            attribute vec2 pos;
            uniform vec2 flip;
            varying vec2 uv;
            void main() {
                vec2 t = pos * 0.5 + 0.5;
                uv = mix(t, 1.0 - t, flip);
                gl_Position = vec4(pos, 0.0, 1.0);
            }"""
        // a 4x4 block's mean: 4 filtered reads, each between 4 pixels; then brightness and colour
        // as the tracker counts them (full range, BT.709): R = Y, G = U + 128, B = V + 128
        private const val FRAGMENT_TAP = """
            #extension GL_OES_EGL_image_external : require
            precision highp float;
            uniform samplerExternalOES tex;
            uniform vec2 texel;
            varying vec2 uv;
            void main() {
                vec2 d = texel;
                vec3 c = 0.25 * (texture2D(tex, uv + vec2(-d.x, -d.y)).rgb + texture2D(tex, uv + vec2(d.x, -d.y)).rgb
                               + texture2D(tex, uv + vec2(-d.x, d.y)).rgb + texture2D(tex, uv + vec2(d.x, d.y)).rgb);
                float y = dot(c, vec3(0.2126, 0.7152, 0.0722));
                float u = (c.b - y) / 1.8556;
                float v = (c.r - y) / 1.5748;
                gl_FragColor = vec4(y, u + 0.5, v + 0.5, 1.0);
            }"""
        // the recording's own way round: the top of the screen shows the buffer's first row (as the
        // tap reads it), then the check's flips
        private const val VERTEX_SHOW = """
            attribute vec2 pos;
            uniform vec2 flip;
            varying vec2 uv;
            void main() {
                vec2 t = vec2(pos.x * 0.5 + 0.5, 0.5 - pos.y * 0.5);
                uv = mix(t, 1.0 - t, flip);
                gl_Position = vec4(pos, 0.0, 1.0);
            }"""
        private const val FRAGMENT_SHOW = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES tex;
            varying vec2 uv;
            void main() { gl_FragColor = texture2D(tex, uv); }"""
    }
}
