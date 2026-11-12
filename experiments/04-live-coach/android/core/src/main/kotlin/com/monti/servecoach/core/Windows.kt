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

    /** every 16th column: see rowLevel for why that is free, and why it is worth taking */
    const val ROW_STRIDE = 16

    /**
     * How bright a row is, from a sample of its columns, as a median rather than a mean.
     *
     * The stride is where most of a windowed capture's remaining cost goes. This is the one part
     * that still reads the whole crop -- about 14 million pixels a serve at full width -- and
     * measurement says every 16th column reads the same as every one: over 13 rendered clips with
     * rolling-shutter flicker injected at 10% and 25%, strides of 1, 4, 8, 16 and 32 all landed
     * within 0.05 rev/s of the whole-crop reading. The reason they can is that rowBright is a
     * RATIO to the background's level, so sampling the same columns on both sides makes the bias
     * common-mode and it cancels. Leaving the correction out entirely is not free: at 25% flicker
     * that wrecked 2 of the 13.
     *
     * The median rather than the mean is a guard those clips could not test. Their flicker is
     * uniform along a row, which is the very assumption the stride rests on; a real row has a
     * player moving across part of it, and a mean over sampled columns is pulled by wherever the
     * body falls. A median ignores a body covering a minority of the sample.
     *
     * Now tested on real footage, which is what this comment used to ask for. Four serves of
     * session 20261112 cut at strides of 2, 4, 8 and 16 and measured against the whole crop: the
     * readings move by less than 0.1 rev/s and the spin split by less than 0.1 across the whole
     * range, so every 16th column really is as good as every other one here. (What did matter
     * was the estimator, not the stride: see rowBright.)
     */
    fun rowLevel(frame: ByteArray, rect: IntArray, stride: Int = ROW_STRIDE): DoubleArray {
        val w = rect[2]; val h = rect[3]
        val buf = IntArray((w + stride - 1) / stride)
        return DoubleArray(h) { y ->
            val o = y * w
            var k = 0; var x = 0
            while (x < w) { buf[k++] = frame[o + x].toInt() and 255; x += stride }
            java.util.Arrays.sort(buf, 0, k)
            val m = buf[k / 2].toDouble()
            if (m < 1.0) 1.0 else m
        }
    }

    /**
     * How bright each row of a frame is against the background: one number a row.
     *
     * The median of the ratio at each sampled pixel, NOT the ratio of the two medians. They look
     * interchangeable and are not, and the difference broke the reading in a way that took a real
     * serve to see. A median of the row's values is the median of whatever that row crosses --
     * table, floor, a player -- so on a row the two pictures cross differently the two medians
     * can come from different things, and the ratio of them is off by a few percent. The engine's
     * own deflickerRows takes the median of the per-pixel ratio, where every sample compares like
     * with like.
     *
     * A few percent sounds harmless. It is not: the error depends on the row, the ball changes
     * rows as it flies, so it lands as a drift in the ball's measured SIZE over the flight. Size
     * against time is how the engine works out whether the serve comes toward the camera, and
     * that angle is how the spin is split into lift, curve and gyro. Session 20261112's serve 3
     * read 36 degrees toward the camera from windows against 13 from the whole crop, which moved
     * its gyro from -35 to -52 rev/s while the rate itself stayed within 0.1. The windowed format
     * was validated on the rate alone, so this sat unnoticed until the split was compared too.
     */
    fun rowBright(frame: ByteArray, bg: ByteArray, rect: IntArray, stride: Int = ROW_STRIDE): FloatArray {
        val w = rect[2]; val h = rect[3]
        val buf = DoubleArray((w + stride - 1) / stride)
        return FloatArray(h) { y ->
            val o = y * w
            var k = 0; var x = 0
            while (x < w) {
                val b = (bg[o + x].toInt() and 255).toDouble()
                buf[k++] = (frame[o + x].toInt() and 255) / (if (b < 1.0) 1.0 else b)
                x += stride
            }
            java.util.Arrays.sort(buf, 0, k)
            buf[k / 2].toFloat()
        }
    }

    /**
     * A windowed capture file: the header, then the background frame, then the row brightness
     * (n x height, floats, most significant byte first, as DataOutputStream writes them), then one window a frame. A frame no track covers has
     * no window and reads as background alone.
     */
    fun write(file: File, cap: Capture, tracks: List<List<FloatArray>>, extra: String = "",
              radii: Double = DEFAULT_RADII, bg: ByteArray? = null): Int {
        val side = boxFor(tracks, radii)
        val n = cap.frames.size
        val rect = cap.rect
        val at = offsets(tracks, rect, n, side)
        // The background can come from outside: on the phone, from frames taken BEFORE the serve,
        // where the scene is the same (a tripod, a static room), the ball is not crossing it, and
        // the work sits off the path between the serve and the answer.
        //
        // Failing that it comes from the frames the ball was tracked through, NOT from a spread of
        // the whole capture. A capture starts before the flight and holds the toss and the swing,
        // and a median across those has the arm in it wherever the arm went -- which is where the
        // ball goes a moment later. The engine's own background is a median of the frames it
        // measures, so this matches it, and on session 20261112's serve 5 it moved the gyro from
        // 0.0 back to -4.3 against the whole crop's -5.2.
        val lo = at.indexOfFirst { it != null }
        val hi = at.indexOfLast { it != null }
        val back = bg ?: background(
            if (lo in 0..hi) cap.frames.subList(lo, hi + 1) else cap.frames, cap.frameBytes)
        val wins = StringBuilder("[")
        for (i in 0 until n) {
            if (i > 0) wins.append(',')
            wins.append(at[i]?.let { "[${it[0]},${it[1]}]" } ?: "null")
        }
        wins.append(']')
        val head = cap.header(""","winW":$side,"winH":$side,"windows":$wins""" + extra).toByteArray()
        DataOutputStream(FileOutputStream(file).buffered(1 shl 20)).use { out ->
            out.writeInt(head.size); out.write(head)
            out.write(back)
            for (i in 0 until n) for (v in rowBright(cap.frames[i], back, rect)) out.writeInt(java.lang.Float.floatToIntBits(v))
            for (i in 0 until n) at[i]?.let { out.write(cut(cap.frames[i], rect, it, side)) }
        }
        return side
    }
}
