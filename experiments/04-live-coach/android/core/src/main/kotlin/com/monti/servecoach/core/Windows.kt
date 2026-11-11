package com.monti.servecoach.core

import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * A capture as small windows round the ball instead of the whole crop.
 *
 * The crop is the box round the whole flight, which is mostly empty table: a real serve came to
 * 242 MB to measure a ball 30 px across, and cutting and fetching it is 4.5 s of the 6.4 s a
 * serve takes. The windows are about 1.3 MB.
 *
 * A window on its own is not enough, and this was measured on all 29 captures either phone has
 * produced. Blanking the rest outright fails on every one: the engine's background is the
 * per-pixel median across frames, which then reads black wherever the window went, so the whole
 * window counts as foreground and there is no ball to separate out. So the capture also carries
 * one background frame — the per-pixel median of a spread of frames — and, per frame, how bright
 * each row was, without which the rows outside the window stop flickering with the light and the
 * flicker correction inside it goes wrong. With all three, 27 of the 29 match the whole crop to
 * a median of 0.2%.
 *
 * Windows go round every track handed in, not only the one picked: one capture's measured stretch
 * sits on a near track. Three times the ball's radius is the smallest to trust — at two, one
 * serve read double.
 */
object Windows {
    const val DEFAULT_RADII = 3.0

    /** the window's size for a track's ball: a square of [radii] times the radius, even-sided */
    fun boxFor(tracks: List<List<FloatArray>>, radii: Double = DEFAULT_RADII, cap: Int = 512): Int {
        val rs = tracks.flatten().map { it[3] }.sorted()
        if (rs.isEmpty()) return 0
        val med = rs[rs.size / 2]
        var side = Math.ceil(2 * radii * med).toInt()
        if (side % 2 == 1) side++
        return side.coerceIn(32, cap)
    }

    /**
     * Where each frame's window sits in the crop, or null for a frame no track covers.
     * [tracks] are in whole-picture pixels as [frame, x, y, radius]; frames between a track's
     * points are filled in, and a frame that falls in no track takes the nearest within [reach].
     */
    fun offsets(tracks: List<List<FloatArray>>, rect: IntArray, n: Int, side: Int, reach: Int = 6): Array<IntArray?> {
        val w = rect[2]; val h = rect[3]
        val at = HashMap<Int, FloatArray>()
        for (tr in tracks) {
            for (k in tr.indices) {
                val a = tr[k]
                at.putIfAbsent(a[0].toInt(), a)
                val b = tr.getOrNull(k + 1) ?: continue
                var f = a[0].toInt() + 1
                while (f < b[0].toInt()) {                       // fill in the frames between
                    val u = (f - a[0]) / (b[0] - a[0])
                    at.putIfAbsent(f, floatArrayOf(f.toFloat(), a[1] + u * (b[1] - a[1]),
                        a[2] + u * (b[2] - a[2]), a[3] + u * (b[3] - a[3])))
                    f++
                }
            }
        }
        return Array(n) { i ->
            var q = at[i]
            var d = 1
            while (q == null && d <= reach) { q = at[i - d] ?: at[i + d]; d++ }
            q?.let {
                val x = Math.round(it[1] - rect[0] - side / 2f).coerceIn(0, maxOf(0, w - side)) and 1.inv()
                val y = Math.round(it[2] - rect[1] - side / 2f).coerceIn(0, maxOf(0, h - side)) and 1.inv()
                intArrayOf(x, y)
            }
        }
    }

    /** the pixels of one window, in the capture's layout, out of a whole-crop frame */
    fun cut(frame: ByteArray, rect: IntArray, at: IntArray, side: Int): ByteArray {
        val w = rect[2]; val h = rect[3]
        val ySize = w * h; val cw2 = w / 2; val cSize = cw2 * (h / 2)
        val s2 = side / 2
        val out = ByteArray(side * side + 2 * s2 * s2)
        val x0 = at[0]; val y0 = at[1]
        var o = 0
        for (y in 0 until side) { System.arraycopy(frame, (y0 + y) * w + x0, out, o, side); o += side }
        val cx0 = x0 / 2; val cy0 = y0 / 2
        for (y in 0 until s2) { System.arraycopy(frame, ySize + (cy0 + y) * cw2 + cx0, out, o, s2); o += s2 }
        for (y in 0 until s2) { System.arraycopy(frame, ySize + cSize + (cy0 + y) * cw2 + cx0, out, o, s2); o += s2 }
        return out
    }

    /** the background: what most frames show at each pixel, from a spread of up to [take] of them */
    fun background(frames: List<ByteArray>, frameBytes: Int, take: Int = 15): ByteArray {
        val n = frames.size
        if (n == 0) return ByteArray(frameBytes)
        val m = minOf(take, n)
        val pick = (0 until m).map { frames[if (m == 1) 0 else Math.round(it.toDouble() * (n - 1) / (m - 1)).toInt()] }
        val bg = ByteArray(frameBytes)
        val tmp = IntArray(m)
        for (p in 0 until frameBytes) {
            for (k in 0 until m) tmp[k] = pick[k][p].toInt() and 255
            tmp.sort()
            bg[p] = tmp[m / 2].toByte()
        }
        return bg
    }

    /** how bright each row of a frame is against the background: one number a row */
    fun rowBright(frame: ByteArray, bgRowMean: DoubleArray, rect: IntArray): FloatArray {
        val w = rect[2]; val h = rect[3]
        val out = FloatArray(h)
        for (y in 0 until h) {
            var s = 0L
            val o = y * w
            for (x in 0 until w) s += (frame[o + x].toInt() and 255)
            out[y] = (s.toDouble() / w / bgRowMean[y]).toFloat()
        }
        return out
    }

    fun rowMeans(frame: ByteArray, rect: IntArray): DoubleArray {
        val w = rect[2]; val h = rect[3]
        return DoubleArray(h) { y ->
            var s = 0L
            val o = y * w
            for (x in 0 until w) s += (frame[o + x].toInt() and 255)
            val m = s.toDouble() / w
            if (m < 1.0) 1.0 else m
        }
    }

    /**
     * A windowed capture file: the header, then the background frame, then the row brightness
     * (n x height, floats, most significant byte first, as DataOutputStream writes them), then one window a frame. A frame no track covers has
     * no window and reads as background alone.
     */
    fun write(file: File, cap: Capture, tracks: List<List<FloatArray>>, extra: String = "",
              radii: Double = DEFAULT_RADII): Int {
        val side = boxFor(tracks, radii)
        val n = cap.frames.size
        val rect = cap.rect
        val at = offsets(tracks, rect, n, side)
        val bg = background(cap.frames, cap.frameBytes)
        val bgRow = rowMeans(bg, rect)
        val wins = StringBuilder("[")
        for (i in 0 until n) {
            if (i > 0) wins.append(',')
            wins.append(at[i]?.let { "[${it[0]},${it[1]}]" } ?: "null")
        }
        wins.append(']')
        val head = cap.header(""","winW":$side,"winH":$side,"windows":$wins""" + extra).toByteArray()
        DataOutputStream(FileOutputStream(file).buffered(1 shl 20)).use { out ->
            out.writeInt(head.size); out.write(head)
            out.write(bg)
            for (i in 0 until n) for (v in rowBright(cap.frames[i], bgRow, rect)) out.writeInt(java.lang.Float.floatToIntBits(v))
            for (i in 0 until n) at[i]?.let { out.write(cut(cap.frames[i], rect, it, side)) }
        }
        return side
    }
}
