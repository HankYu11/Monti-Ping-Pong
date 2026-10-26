package com.monti.servecoach

import android.media.MediaFormat

/** One encoded video frame: its time (microseconds), whether it starts afresh (keyframe), its bytes. */
class EncodedFrame(val ptsUs: Long, val key: Boolean, val data: ByteArray)

/** Where recent encoded frames are kept: the live recorder's memory, or a video file being replayed. */
interface FrameStore {
    val outputFormat: MediaFormat?
    /** the frames from the keyframe before fromUs up to toUs */
    fun between(fromUs: Long, toUs: Long): List<EncodedFrame>
}

/**
 * In decoding order: from the last keyframe at or before fromUs to the first keyframe after toUs
 * (frames may be stored out of display order; the decoder needs all of them in between).
 */
fun framesBetween(list: List<EncodedFrame>, fromUs: Long, toUs: Long): List<EncodedFrame> {
    var start = list.indexOfLast { it.key && it.ptsUs <= fromUs }
    if (start < 0) start = list.indexOfFirst { it.key }
    if (start < 0) return emptyList()
    var end = list.size
    for (i in start + 1 until list.size) if (list[i].key && list[i].ptsUs > toUs) { end = i; break }
    return list.subList(start, end)
}

/**
 * Writes encoded frames (decoding order, from a keyframe) to an MP4, keeping those up to toUs;
 * times start at 0. Returns the number of frames written (0: too few to be worth a file).
 */
fun writeMp4(frames: List<EncodedFrame>, format: MediaFormat, toUs: Long, out: java.io.File): Int {
    val pick = frames.filter { it.ptsUs <= toUs }
    if (pick.size < 10) return 0
    val mux = android.media.MediaMuxer(out.absolutePath, android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    try {
        val track = mux.addTrack(format)
        mux.start()
        val base = pick.minOf { it.ptsUs }
        val info = android.media.MediaCodec.BufferInfo()
        for (fr in pick) {
            info.set(0, fr.data.size, fr.ptsUs - base, if (fr.key) android.media.MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
            mux.writeSampleData(track, java.nio.ByteBuffer.wrap(fr.data), info)
        }
        mux.stop()
    } finally { mux.release() }
    return pick.size
}

/** A video file's frames, all in memory (a serve clip is a few MB): replayed as if live. */
class FileFrames(context: android.content.Context, uri: android.net.Uri) : FrameStore {
    val frames = ArrayList<EncodedFrame>()
    override var outputFormat: MediaFormat? = null; private set
    var captureFps = 240; private set

    init {
        val ex = android.media.MediaExtractor()
        ex.setDataSource(context, uri, null)
        var track = -1
        for (i in 0 until ex.trackCount) if (ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) { track = i; break }
        require(track >= 0) { "no video in the file" }
        ex.selectTrack(track)
        outputFormat = ex.getTrackFormat(track)
        val buf = java.nio.ByteBuffer.allocate(8 shl 20)
        while (true) {
            val n = ex.readSampleData(buf, 0)
            if (n < 0) break
            val b = ByteArray(n); buf.position(0); buf.get(b, 0, n)
            frames.add(EncodedFrame(ex.sampleTime, ex.sampleFlags and android.media.MediaExtractor.SAMPLE_FLAG_SYNC != 0, b))
            ex.advance()
        }
        ex.release()
        // phones store slow motion either at its real rate or stretched for playback; 120 fps only when that's what the times say
        val gaps = frames.map { it.ptsUs }.sorted().zipWithNext { a, b -> b - a }.filter { it > 0 }.sorted()
        val gap = if (gaps.isEmpty()) 0L else gaps[gaps.size / 2]
        captureFps = if (gap in 6_000..12_000) 120 else 240
    }

    override fun between(fromUs: Long, toUs: Long) = framesBetween(frames, fromUs, toUs)
}
