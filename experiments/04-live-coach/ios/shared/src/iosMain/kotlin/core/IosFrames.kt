@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
// What the iOS app calls to hand the core a picture, and to get bytes in and out of Kotlin
// without a byte-at-a-time copy across the Swift boundary.
package com.monti.servecoach.core

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.posix.memcpy

object IosFrames {
    /**
     * A 4:2:0 bi-planar picture (a CVPixelBuffer's Y plane and interleaved CbCr plane, both locked
     * by the caller) as the core's Yuv, read in place: U is CbCr from byte 0, V from byte 1, each
     * with a pixel stride of 2 -- what Android's decoder hands out for NV12.
     */
    fun yuv(width: Int, height: Int, y: COpaquePointer, yRowStride: Int, cbcr: COpaquePointer, cRowStride: Int, fullRange: Boolean): Yuv {
        val yb = java.nio.ByteBuffer.wrap(y.reinterpret<ByteVar>())
        val cb = java.nio.ByteBuffer.wrap(cbcr.reinterpret<ByteVar>())
        return Yuv(width, height, Plane(yb, 0, yRowStride, 1), Plane(cb, 0, cRowStride, 2), Plane(cb, 1, cRowStride, 2), fullRange)
    }

    /** a copy of [len] bytes at [p] */
    fun bytes(p: COpaquePointer, len: Int): ByteArray = ByteArray(len).also { a -> if (len > 0) a.usePinned { memcpy(it.addressOf(0), p, len.toULong()) } }

    /** [a]'s bytes copied to [p], which has room for them */
    fun copyOut(a: ByteArray, p: COpaquePointer) { if (a.isNotEmpty()) a.usePinned { memcpy(p, it.addressOf(0), a.size.toULong()) } }
}

/** The parts of LivePipeline.writeCapture that are plain Kotlin, kept in Kotlin so they stay exactly Android's. */
object IosCapture {
    /** LivePipeline.writeCapture's conv: the serve's tracks in whole frames from [firstFrame] and whole-picture pixels, 2 points or more */
    fun tracks(serve: ServeTracker.Serve, step: Int, firstFrame: Long, w: Int): List<List<FloatArray>> {
        val scale = w.toFloat() / (w / 4)
        fun conv(t: List<FloatArray>) = t.map { floatArrayOf((it[0] * step - firstFrame).toFloat(), it[1] * scale, it[2] * scale, it[3] * scale) }
        return (listOf(serve.track) + serve.near).map(::conv).filter { it.size >= 2 }
    }

    /** the frames any track covers (whole frames), for trimming a capture to them */
    fun trackFrames(serve: ServeTracker.Serve, step: Int): List<Long> = (listOf(serve.track) + serve.near).flatten().map { it[0].toLong() * step }

    fun builder(rect: IntArray, frames: Int, tracks: List<List<FloatArray>>) = Windows.Builder(rect, frames, tracks)

    /** a windowed capture's body, as Android writes it */
    fun writeWindowed(b: Windows.Builder, path: String) {
        java.io.DataOutputStream(java.io.FileOutputStream(java.io.File(path))).use { b.writeBody(it) }
    }
}

/**
 * A finder's result, kept a Kotlin object: ServeTracker.pushFound reads the share of the picture
 * that moved off the list itself ((cands as? Found)?.moving), which a trip through an NSArray could lose.
 */
class FoundBox internal constructor(internal val found: Found) {
    val moving: Float get() = found.moving
    val count: Int get() = found.size
}

object IosTrack {
    fun find(d: Detector, q: Quarter, bg: Background): FoundBox = FoundBox(d.find(q, bg))
    /** LivePipeline.link: the frame's candidates, or none when the background wasn't ready */
    fun push(tr: ServeTracker, index: Long, found: FoundBox?): ServeTracker.Serve? = tr.pushFound(index, found?.found ?: emptyList())
    fun rejected(tr: ServeTracker): Map<String, Int> = HashMap(tr.rejected)
}

/** debugging: one tracker frame as the desktop harness's DUMPQ line prints it */
object IosDump {
    fun line(index: Long, q: Quarter, found: FoundBox?): String {
        var sy = 0.0; for (v in q.y) sy += v; var su = 0.0; for (v in q.u) su += v
        val c: List<FloatArray> = found?.found ?: emptyList()
        return "Q $index ${"%.2f".format(sy)} ${"%.2f".format(su)} ${c.size} " + c.joinToString(";") { "%.2f,%.2f,%.2f".format(it[0], it[1], it[2]) }
    }
}
