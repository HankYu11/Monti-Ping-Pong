package com.monti.servecoach.core

import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt

/** One plane of a decoded picture, as decoders hand it out (Android's Image planes, or ffmpeg's bytes). */
class Plane(val buf: ByteBuffer, val offset: Int, val rowStride: Int, val pixelStride: Int) {
    constructor(data: ByteArray, offset: Int, rowStride: Int, pixelStride: Int) : this(ByteBuffer.wrap(data), offset, rowStride, pixelStride)
    inline fun at(i: Int): Int = buf.get(i).toInt() and 0xff
    inline operator fun get(x: Int, y: Int): Int = at(offset + y * rowStride + x * pixelStride)
}

/** A decoded 4:2:0 picture: Y at full size, U and V at half size. */
class Yuv(val width: Int, val height: Int, val y: Plane, val u: Plane, val v: Plane, val fullRange: Boolean)

/**
 * The picture at a quarter of its size each way (1920x1080 -> 480x270) for spotting the serve:
 * brightness 0-255 and colour around 0, in the spin meter engine's own units (its LUT_VIDEO and
 * CHROMA_VIDEO), so its thresholds carry over.
 */
class Quarter(val w: Int, val h: Int, val y: FloatArray, val u: FloatArray, val v: FloatArray)

object Downsample {
    private val lumaVideo = FloatArray(256) { (((it - 16) * 255.0 / 219.0).roundToInt()).coerceIn(0, 255).toFloat() }
    private val chromaVideo = FloatArray(256) { ((it - 128) * 255.0 / 224.0).toFloat() }

    /**
     * Brightness: the mean of 4 pixels spread over each 4x4 block (a quarter of the reads of a full
     * average: at 240 frames a second the phone has about 4 ms a frame); colour: one sample.
     */
    fun quarter(f: Yuv): Quarter {
        val w = f.width / 4; val h = f.height / 4
        val y = FloatArray(w * h); val u = FloatArray(w * h); val v = FloatArray(w * h)
        val full = f.fullRange
        val yb = f.y.buf; val yo = f.y.offset; val ys = f.y.rowStride; val yp = f.y.pixelStride
        val ub = f.u.buf; val uo = f.u.offset; val us = f.u.rowStride; val up = f.u.pixelStride
        val vb = f.v.buf; val vo = f.v.offset; val vs = f.v.rowStride; val vp = f.v.pixelStride
        for (qy in 0 until h) {
            val r0 = yo + (4 * qy + 1) * ys; val r1 = r0 + 2 * ys
            val cu = uo + (2 * qy + 1) * us; val cv = vo + (2 * qy + 1) * vs
            for (qx in 0 until w) {
                val c0 = (4 * qx + 1) * yp; val c1 = c0 + 2 * yp
                val s = (yb.get(r0 + c0).toInt() and 0xff) + (yb.get(r0 + c1).toInt() and 0xff) +
                    (yb.get(r1 + c0).toInt() and 0xff) + (yb.get(r1 + c1).toInt() and 0xff)
                val m = (s + 2) shr 2
                val q = qy * w + qx
                y[q] = if (full) m.toFloat() else lumaVideo[m]
                val mu = ub.get(cu + (2 * qx + 1) * up).toInt() and 0xff
                val mv = vb.get(cv + (2 * qx + 1) * vp).toInt() and 0xff
                u[q] = if (full) (mu - 128).toFloat() else chromaVideo[mu]
                v[q] = if (full) (mv - 128).toFloat() else chromaVideo[mv]
            }
        }
        return Quarter(w, h, y, u, v)
    }
}

/**
 * The frames of one serve, cropped, for the engine's SpinMeter.measureCapture: per frame the
 * crop's Y, then U and V at half size, raw bytes as the decoder gave them.
 * File: a 4-byte big-endian header length, the header (JSON), then the frames back to back.
 */
class Capture(val width: Int, val height: Int, val rect: IntArray, val fullRange: Boolean, val fps: Int) {
    /** a capture written to a file: its number, frames, bytes per frame, header, time to write, time span */
    data class Info(val id: Int, val n: Int, val frameBytes: Int, val head: String, val writeMs: Long, val fromUs: Long, val toUs: Long)

    val frames = ArrayList<ByteArray>()
    val frameBytes = rect[2] * rect[3] + 2 * (rect[2] / 2) * (rect[3] / 2)

    fun add(f: Yuv) { frames.add(crop(f)) }

    /** one frame's crop, in the file's layout */
    fun crop(f: Yuv): ByteArray {
        val x0 = rect[0]; val y0 = rect[1]; val w = rect[2]; val h = rect[3]
        val out = ByteArray(frameBytes)
        var o = 0
        val yb = f.y.buf
        for (y in 0 until h) { var i = f.y.offset + (y0 + y) * f.y.rowStride + x0 * f.y.pixelStride; for (x in 0 until w) { out[o++] = yb.get(i); i += f.y.pixelStride } }
        for (p in listOf(f.u, f.v)) for (y in 0 until h / 2) {
            var i = p.offset + (y0 / 2 + y) * p.rowStride + (x0 / 2) * p.pixelStride
            for (x in 0 until w / 2) { out[o++] = p.buf.get(i); i += p.pixelStride }
        }
        return out
    }

    fun header(extra: String = "", n: Int = frames.size): String =
        """{"W":$width,"H":$height,"rect":[${rect.joinToString(",")}],"n":$n,"full":$fullRange,"fps":$fps$extra}"""

    fun write(file: File, extra: String = "") {
        DataOutputStream(FileOutputStream(file).buffered(1 shl 20)).use { out ->
            val h = header(extra).toByteArray()
            out.writeInt(h.size); out.write(h)
            for (fr in frames) out.write(fr)
        }
    }
}
