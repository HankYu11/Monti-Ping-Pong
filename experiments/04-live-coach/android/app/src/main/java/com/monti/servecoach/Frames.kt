package com.monti.servecoach

import android.media.MediaFormat

/** One encoded video frame: its time (microseconds), whether it starts afresh (keyframe), its bytes. */
open class EncodedFrame(val ptsUs: Long, val key: Boolean, private val bytes: ByteArray?) {
    /** the frame's bytes; a replayed video's frame fetches them from disk on each call, so read once */
    open val data: ByteArray get() = bytes!!
}

/**
 * A replayed video's frame whose bytes wait on disk until they are wanted.
 *
 * A video test used to read the whole video into memory first ("a serve clip is a few MB"). A
 * stock slow-motion video is not: 127 and 178 MB for the two of 14 Nov, against a 256 MB heap, so
 * the first capture's frames, or the room-background thread's, found no room and the app died of
 * OutOfMemoryError in Capture.crop -- 0.12.8 the same as 0.13.x. Live never kept more than the last
 * five seconds (ClipRecorder), so this is the video test's problem, and it is solved by keeping the
 * video where it already is: a file. FileChannel.read at a position is safe from several threads.
 */
class SpilledFrame(ptsUs: Long, key: Boolean, private val ch: java.nio.channels.FileChannel,
                   private val at: Long, private val size: Int) : EncodedFrame(ptsUs, key, null) {
    override val data: ByteArray get() {
        val out = ByteArray(size)
        val b = java.nio.ByteBuffer.wrap(out)
        while (b.hasRemaining()) if (ch.read(b, at + b.position()) < 0) throw java.io.EOFException("replay spill ended early")
        return out
    }
}

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
            val bytes = fr.data
            info.set(0, bytes.size, fr.ptsUs - base, if (fr.key) android.media.MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
            mux.writeSampleData(track, java.nio.ByteBuffer.wrap(bytes), info)
        }
        mux.stop()
    } finally { mux.release() }
    return pick.size
}

/**
 * A video file's frames, replayed as if live. Their times and keyframe flags are kept in memory;
 * their bytes are copied once to a file in the app's cache and read back as they are needed
 * (SpilledFrame). Each test gets a file of its own and the previous ones are deleted here: a test's
 * captures and clips are still being written after its frames have played, and a file deleted while
 * open stays readable to whoever has it open, so nothing in flight loses its frames.
 */
class FileFrames(context: android.content.Context, uri: android.net.Uri) : FrameStore {
    val frames = ArrayList<EncodedFrame>()
    override var outputFormat: MediaFormat? = null; private set
    var captureFps = 240; private set
    /** how many bytes of video were copied to the cache instead of into memory */
    var spilledBytes = 0L; private set

    init {
        val ex = android.media.MediaExtractor()
        ex.setDataSource(context, uri, null)
        var track = -1
        for (i in 0 until ex.trackCount) if (ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) { track = i; break }
        require(track >= 0) { "no video in the file" }
        ex.selectTrack(track)
        outputFormat = ex.getTrackFormat(track)
        val dir = java.io.File(context.cacheDir, "replay").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val spill = java.io.File.createTempFile("frames", ".bin", dir)
        val ch = java.io.RandomAccessFile(spill, "rw").channel
        spill.delete()                           // gone from the folder now; readable while ch is open
        // the previous test's file: its space comes back only when it is closed. Its captures were
        // finished long before another test can be started.
        synchronized(FileFrames) { runCatching { open?.close() }; open = ch }
        val buf = java.nio.ByteBuffer.allocateDirect(8 shl 20)
        var at = 0L
        try {
            while (true) {
                buf.clear()
                val n = ex.readSampleData(buf, 0)
                if (n < 0) break
                buf.position(0); buf.limit(n)
                while (buf.hasRemaining()) ch.write(buf, at + buf.position())
                frames.add(SpilledFrame(ex.sampleTime, ex.sampleFlags and android.media.MediaExtractor.SAMPLE_FLAG_SYNC != 0, ch, at, n))
                at += n
                ex.advance()
            }
        } finally { ex.release() }
        spilledBytes = at
        // phones store slow motion either at its real rate or stretched for playback; 120 fps only when that's what the times say
        val gaps = frames.map { it.ptsUs }.sorted().zipWithNext { a, b -> b - a }.filter { it > 0 }.sorted()
        val gap = if (gaps.isEmpty()) 0L else gaps[gaps.size / 2]
        captureFps = if (gap in 6_000..12_000) 120 else 240
    }

    override fun between(fromUs: Long, toUs: Long) = framesBetween(frames, fromUs, toUs)

    companion object { private var open: java.nio.channels.FileChannel? = null }
}
