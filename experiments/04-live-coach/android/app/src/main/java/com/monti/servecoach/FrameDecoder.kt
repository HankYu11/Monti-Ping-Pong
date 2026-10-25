package com.monti.servecoach

import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import com.monti.servecoach.core.Plane
import com.monti.servecoach.core.Yuv
import kotlin.math.min

/**
 * Decodes encoded frames into pictures the core can read (Y, U, V planes as the decoder hands
 * them out; no copying). Synchronous: feed frames, then drain what's ready.
 */
class FrameDecoder(format: MediaFormat) {
    private val mime = format.getString(MediaFormat.KEY_MIME)!!
    private val codec = MediaCodec.createDecoderByType(mime)
    private val info = MediaCodec.BufferInfo()
    var width = format.getInteger(MediaFormat.KEY_WIDTH); private set
    var height = format.getInteger(MediaFormat.KEY_HEIGHT); private set
    /** whether brightness spans 0-255 (else 16-235, as phones record) */
    var fullRange = false; private set
    var ended = false; private set

    init {
        val f = MediaFormat.createVideoFormat(mime, width, height)
        for (k in listOf("csd-0", "csd-1", "csd-2")) if (format.containsKey(k)) f.setByteBuffer(k, format.getByteBuffer(k))
        f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
        f.setInteger(MediaFormat.KEY_PRIORITY, 0)
        codec.configure(f, null, null, 0)
        codec.start()
    }

    /** queue one frame; false when the decoder has no room yet (drain, then try again) */
    fun feed(fr: EncodedFrame, timeoutUs: Long = 0): Boolean {
        val i = codec.dequeueInputBuffer(timeoutUs)
        if (i < 0) return false
        val buf = codec.getInputBuffer(i)!!
        buf.clear(); buf.put(fr.data)
        codec.queueInputBuffer(i, 0, fr.data.size, fr.ptsUs, if (fr.key) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
        return true
    }

    fun endOfStream(timeoutUs: Long = 10_000): Boolean {
        val i = codec.dequeueInputBuffer(timeoutUs)
        if (i < 0) return false
        codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        return true
    }

    /** hands each decoded picture to [each] (valid only during the call); returns how many */
    fun drain(timeoutUs: Long, each: (ptsUs: Long, yuv: Yuv) -> Unit): Int {
        var n = 0
        var wait = timeoutUs
        while (true) {
            val i = codec.dequeueOutputBuffer(info, wait)
            wait = 0
            if (i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val f = codec.outputFormat
                if (f.containsKey(MediaFormat.KEY_COLOR_RANGE)) fullRange = f.getInteger(MediaFormat.KEY_COLOR_RANGE) == MediaFormat.COLOR_RANGE_FULL
                continue
            }
            if (i < 0) return n
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) ended = true
            if (info.size > 0) {
                val img = codec.getOutputImage(i)
                if (img != null) {
                    try { each(info.presentationTimeUs, toYuv(img)) } finally { img.close() }
                    n++
                }
            }
            codec.releaseOutputBuffer(i, false)
            if (ended) return n
        }
    }

    private fun toYuv(img: Image): Yuv {
        val p = img.planes
        val w = min(img.width, img.cropRect.width()); val h = min(img.height, img.cropRect.height())
        width = w; height = h
        fun plane(k: Int) = Plane(p[k].buffer, 0, p[k].rowStride, p[k].pixelStride)
        return Yuv(w, h, plane(0), plane(1), plane(2), fullRange)
    }

    fun release() { try { codec.stop() } catch (_: Exception) {}; try { codec.release() } catch (_: Exception) {} }
}
