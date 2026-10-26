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
    fun quarter(f: Yuv): Quarter = quarter(rows(f))

    /** For tests: as the graphics chip makes it (GlTap), every pixel of each 4x4 block averaged, colour over its 2x2. */
    fun quarterMean(f: Yuv): Quarter {
        val w = f.width / 4; val h = f.height / 4
        val y = FloatArray(w * h); val u = FloatArray(w * h); val v = FloatArray(w * h)
        for (qy in 0 until h) for (qx in 0 until w) {
            var s = 0
            for (dy in 0 until 4) for (dx in 0 until 4) s += f.y[4 * qx + dx, 4 * qy + dy]
            var su = 0; var sv = 0
            for (dy in 0 until 2) for (dx in 0 until 2) { su += f.u[2 * qx + dx, 2 * qy + dy]; sv += f.v[2 * qx + dx, 2 * qy + dy] }
            val q = qy * w + qx
            val m = s / 16f
            y[q] = if (f.fullRange) m else ((m - 16f) * 255f / 219f).coerceIn(0f, 255f)
            u[q] = if (f.fullRange) su / 4f - 128f else (su / 4f - 128f) * 255f / 224f
            v[q] = if (f.fullRange) sv / 4f - 128f else (sv / 4f - 128f) * 255f / 224f
        }
        return Quarter(w, h, y, u, v)
    }

    /** The small picture as the graphics chip makes it (R brightness, G and B colour + 128, full range), flipped as [flip] says (bit 0: x, bit 1: y). */
    fun fromRgba(b: ByteArray, w: Int, h: Int, flip: Int = 0): Quarter {
        val y = FloatArray(w * h); val u = FloatArray(w * h); val v = FloatArray(w * h)
        for (qy in 0 until h) {
            val sy = if (flip and 2 != 0) h - 1 - qy else qy
            for (qx in 0 until w) {
                val sx = if (flip and 1 != 0) w - 1 - qx else qx
                val i = (sy * w + sx) * 4; val q = qy * w + qx
                y[q] = (b[i].toInt() and 0xff).toFloat()
                u[q] = ((b[i + 1].toInt() and 0xff) - 128).toFloat()
                v[q] = ((b[i + 2].toInt() and 0xff) - 128).toFloat()
            }
        }
        return Quarter(w, h, y, u, v)
    }

    /**
     * Just the rows the quarter-size picture is made from, copied in bulk (1 in 2 brightness rows,
     * 1 in 2 colour rows): quick, for the decoder's thread, which must hand its picture back soon.
     * [quarter] of them is the rest of the work, on any thread.
     */
    class Rows(val w: Int, val h: Int, val full: Boolean, val yp: Int, val up: Int, val vp: Int,
               val yLen: Int, val uLen: Int, val vLen: Int, val y: ByteArray, val u: ByteArray, val v: ByteArray)

    fun rows(f: Yuv): Rows {
        val w = f.width / 4; val h = f.height / 4
        val yp = f.y.pixelStride; val up = f.u.pixelStride; val vp = f.v.pixelStride
        val yLen = (4 * (w - 1) + 3) * yp + 1
        val uLen = (2 * (w - 1) + 1) * up + 1; val vLen = (2 * (w - 1) + 1) * vp + 1
        val y = ByteArray(2 * h * yLen); val u = ByteArray(h * uLen); val v = ByteArray(h * vLen)
        val yb = f.y.buf.duplicate(); val ub = f.u.buf.duplicate(); val vb = f.v.buf.duplicate()
        for (qy in 0 until h) {
            yb.position(f.y.offset + (4 * qy + 1) * f.y.rowStride); yb.get(y, 2 * qy * yLen, yLen)
            yb.position(f.y.offset + (4 * qy + 3) * f.y.rowStride); yb.get(y, (2 * qy + 1) * yLen, yLen)
            ub.position(f.u.offset + (2 * qy + 1) * f.u.rowStride); ub.get(u, qy * uLen, uLen)
            vb.position(f.v.offset + (2 * qy + 1) * f.v.rowStride); vb.get(v, qy * vLen, vLen)
        }
        return Rows(w, h, f.fullRange, yp, up, vp, yLen, uLen, vLen, y, u, v)
    }

    /**
     * Brightness: the mean of 4 pixels spread over each 4x4 block (a quarter of the reads of a full
     * average); colour: one sample.
     */
    fun quarter(r: Rows): Quarter {
        val w = r.w; val h = r.h
        val y = FloatArray(w * h); val u = FloatArray(w * h); val v = FloatArray(w * h)
        val full = r.full; val yp = r.yp; val up = r.up; val vp = r.vp
        val ry = r.y; val ru = r.u; val rv = r.v
        for (qy in 0 until h) {
            val r0 = 2 * qy * r.yLen; val r1 = r0 + r.yLen; val cu = qy * r.uLen; val cv = qy * r.vLen
            var q = qy * w
            for (qx in 0 until w) {
                val c0 = (4 * qx + 1) * yp; val c1 = c0 + 2 * yp
                val s = (ry[r0 + c0].toInt() and 0xff) + (ry[r0 + c1].toInt() and 0xff) + (ry[r1 + c0].toInt() and 0xff) + (ry[r1 + c1].toInt() and 0xff)
                val m = (s + 2) shr 2
                y[q] = if (full) m.toFloat() else lumaVideo[m]
                val mu = ru[cu + (2 * qx + 1) * up].toInt() and 0xff
                val mv = rv[cv + (2 * qx + 1) * vp].toInt() and 0xff
                u[q] = if (full) (mu - 128).toFloat() else chromaVideo[mu]
                v[q] = if (full) (mv - 128).toFloat() else chromaVideo[mv]
                q++
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

    private var line = ByteArray(0)

    /** one frame's crop, in the file's layout (rows read in bulk: a byte at a time took seconds a serve) */
    fun crop(f: Yuv): ByteArray {
        val x0 = rect[0]; val y0 = rect[1]; val w = rect[2]; val h = rect[3]
        val out = ByteArray(frameBytes)
        var o = 0
        fun plane(p: Plane, px0: Int, py0: Int, pw: Int, ph: Int) {
            val b = p.buf.duplicate()
            val ps = p.pixelStride
            val len = (pw - 1) * ps + 1
            if (ps != 1 && line.size < len) line = ByteArray(len)
            for (y in 0 until ph) {
                b.position(p.offset + (py0 + y) * p.rowStride + px0 * ps)
                if (ps == 1) { b.get(out, o, pw); o += pw }
                else { b.get(line, 0, len); var i = 0; for (x in 0 until pw) { out[o++] = line[i]; i += ps } }
            }
        }
        plane(f.y, x0, y0, w, h)
        plane(f.u, x0 / 2, y0 / 2, w / 2, h / 2)
        plane(f.v, x0 / 2, y0 / 2, w / 2, h / 2)
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
