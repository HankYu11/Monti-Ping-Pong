@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
// What the iOS app calls to hand the core a picture, and to get bytes in and out of Kotlin
// without a byte-at-a-time copy across the Swift boundary.
package com.monti.servecoach.core

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.get
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

/**
 * The table's pose on iOS (SetupGuide.kt's part): the setup check's 640 x 360 copies, filled here
 * from a locked bi-planar buffer (Swift would copy them a byte at a time), the last [keep] kept for
 * [TablePose.analyse]'s median. Not thread-safe: the caller keeps it to one queue.
 */
class IosTablePose(private val keep: Int = 8) {
    val w = 640; val h = 360
    private val frames = ArrayDeque<Array<ByteArray>>()
    val count get() = frames.size
    fun clear() = frames.clear()

    fun add(width: Int, height: Int, y: COpaquePointer, yRowStride: Int, cbcr: COpaquePointer, cRowStride: Int) {
        val yp = y.reinterpret<ByteVar>(); val cp = cbcr.reinterpret<ByteVar>()
        val yy = ByteArray(w * h); val uu = ByteArray(w * h); val vv = ByteArray(w * h)
        for (j in 0 until h) {
            val sy = j * height / h; val cy = sy / 2
            for (i in 0 until w) {
                val sx = i * width / w; val cx = (sx / 2) * 2; val p = j * w + i
                yy[p] = yp[sy * yRowStride + sx]
                uu[p] = cp[cy * cRowStride + cx]; vv[p] = cp[cy * cRowStride + cx + 1]
            }
        }
        frames.addLast(arrayOf(yy, uu, vv))
        while (frames.size > keep) frames.removeFirst()
    }

    /** the kept frames, for [TablePose.analyse] on another queue */
    fun snapshot(): List<Array<ByteArray>> = frames.toList()

    /** a look at [snapshot] (on any queue): down in camera axes, the field of view in degrees */
    fun look(frames: List<Array<ByteArray>>, dx: Double, dy: Double, dz: Double, hfovDeg: Double): TablePose.Look =
        TablePose.look(frames, w, h, doubleArrayOf(dx, dy, dz), hfovDeg)

    /** for a capture header: ,"gravity":[..],"tablePose":{..},"tablePoseGravity":[..] (null when unknown) */
    fun header(down: DoubleArray?, pose: TablePose.Pose?, poseDown: DoubleArray?): String =
        ",\"gravity\":" + (down?.let { TablePose.gravityJson(it) } ?: "null") + ",\"tablePose\":" + (pose?.json() ?: "null") +
            ",\"tablePoseGravity\":" + (if (pose != null) poseDown?.let { TablePose.gravityJson(it) } ?: "null" else "null")

    /** the angle between two "down"s, degrees */
    fun angleDeg(a: DoubleArray, b: DoubleArray): Double {
        val c = ((a[0] * b[0] + a[1] * b[1] + a[2] * b[2]) / (kotlin.math.sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2]) * kotlin.math.sqrt(b[0] * b[0] + b[1] * b[1] + b[2] * b[2]))).coerceIn(-1.0, 1.0)
        return kotlin.math.acos(c) * 180.0 / kotlin.math.PI
    }
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
