package com.monti.servecoach

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Handler
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer

/**
 * Encodes the slow-motion stream and keeps the last few seconds in memory, so the stretch around
 * a serve can be cut out after it happened. Keyframes every 0.1 s, so a cut can start close to
 * where it should.
 */
class ClipRecorder(private val cfg: HighSpeedCamera.Config, private val keepSeconds: Double = 5.0) {
    private class Frame(val ptsUs: Long, val key: Boolean, val data: ByteArray)

    private val frames = ArrayDeque<Frame>()
    private val lock = Object()
    private var codec: MediaCodec? = null
    private var format: MediaFormat? = null
    lateinit var inputSurface: Surface
        private set

    // frame-rate check: frames written in the last second (by their timestamps)
    @Volatile var framesTotal = 0L; private set
    @Volatile var firstPtsUs = -1L; private set
    @Volatile var lastPtsUs = -1L; private set
    /** arrival time minus frame time (ns), the latest; for checking the clock the frames use */
    @Volatile var arrivalMinusPtsNs = 0L; private set

    fun start(handler: Handler, onError: (String) -> Unit) {
        val f = MediaFormat.createVideoFormat(cfg.mime, cfg.size.width, cfg.size.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            // plenty of bits: the ball's marking must survive (about 0.07 bits per pixel)
            setInteger(MediaFormat.KEY_BIT_RATE, (cfg.size.width.toLong() * cfg.size.height * cfg.fps / 14).coerceAtMost(120_000_000L).toInt())
            setInteger(MediaFormat.KEY_FRAME_RATE, cfg.fps)
            setInteger(MediaFormat.KEY_OPERATING_RATE, cfg.fps)
            setInteger(MediaFormat.KEY_CAPTURE_RATE, cfg.fps)
            setFloat(MediaFormat.KEY_I_FRAME_INTERVAL, 0.1f)
            setInteger(MediaFormat.KEY_PRIORITY, 0)                        // real time
        }
        val c = MediaCodec.createByCodecName(cfg.encoder)
        c.setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {}
            override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                try {
                    if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        val buf = codec.getOutputBuffer(index)!!
                        val bytes = ByteArray(info.size)
                        buf.position(info.offset); buf.get(bytes, 0, info.size)
                        val fr = Frame(info.presentationTimeUs, info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0, bytes)
                        synchronized(lock) {
                            frames.addLast(fr)
                            val keepUs = (keepSeconds * 1e6).toLong()
                            while (frames.size > 2 && fr.ptsUs - frames.first().ptsUs > keepUs) frames.removeFirst()
                        }
                        framesTotal++
                        if (firstPtsUs < 0) firstPtsUs = info.presentationTimeUs
                        lastPtsUs = info.presentationTimeUs
                        arrivalMinusPtsNs = System.nanoTime() - info.presentationTimeUs * 1000
                    }
                    codec.releaseOutputBuffer(index, false)
                } catch (e: Exception) { onError("encoder: ${e.message}") }
            }
            override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) { onError("encoder error: ${e.diagnosticInfo}") }
            override fun onOutputFormatChanged(codec: MediaCodec, f: MediaFormat) { format = f }
        }, handler)
        c.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = c.createInputSurface()
        c.start()
        codec = c
    }

    fun stop() {
        try { codec?.stop() } catch (_: Exception) {}
        try { codec?.release() } catch (_: Exception) {}
        codec = null
        synchronized(lock) { frames.clear() }
    }

    /** frames per second over the last second written */
    fun recentFps(): Double = synchronized(lock) {
        if (frames.size < 2) return 0.0
        val last = frames.last().ptsUs
        val n = frames.count { last - it.ptsUs <= 1_000_000 }
        n.toDouble()
    }

    fun newestPtsUs(): Long = synchronized(lock) { if (frames.isEmpty()) -1 else frames.last().ptsUs }

    /**
     * Writes the frames from fromUs to toUs (frame time, microseconds) to an MP4, starting at the
     * keyframe before fromUs. Returns the number of frames written, or 0.
     */
    fun cut(fromUs: Long, toUs: Long, out: File): Int {
        val fmt = format ?: return 0
        val pick: List<Frame> = synchronized(lock) {
            val list = frames.toList()
            var start = list.indexOfLast { it.key && it.ptsUs <= fromUs }
            if (start < 0) start = list.indexOfFirst { it.key }
            if (start < 0) return 0
            list.subList(start, list.size).filter { it.ptsUs <= toUs }
        }
        if (pick.size < 10) return 0
        val mux = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val track = mux.addTrack(fmt)
        mux.start()
        val base = pick.first().ptsUs
        val info = MediaCodec.BufferInfo()
        for (fr in pick) {
            info.set(0, fr.data.size, fr.ptsUs - base, if (fr.key) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
            mux.writeSampleData(track, ByteBuffer.wrap(fr.data), info)
        }
        mux.stop(); mux.release()
        return pick.size
    }
}
