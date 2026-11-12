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
    /** frames the background is medianed from: enough for a median, few enough to hold */
    const val BG_FRAMES = 15

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
    fun background(frames: List<ByteArray>, frameBytes: Int, take: Int = BG_FRAMES): ByteArray {
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
     * Builds a windowed capture as the crops arrive, so no whole crop is ever written and only a
     * fraction of them is ever held.
     *
     * [Windows.write] takes a Capture that already holds every crop, which is how the desktop
     * runner uses it. The phone cannot afford that: a serve framed so the bounce is in view gives
     * a crop of 1688x644 and 176 frames, which is 287 MB, and cutting, writing, serving and
     * parsing that is seven of the nine seconds a serve took at 0.10.7.
     *
     * What a windowed capture needs from a frame is a window round the ball, and how bright each
     * row of the crop was. So each crop is reduced to those as it arrives and then dropped. The
     * background is the one thing that needs whole frames -- it is a per-pixel median -- so the
     * spread of frames it medians is kept, and nothing else: about 24 MB, plus 12 MB of row
     * samples and 4 MB of windows against 287 MB of crops.
     *
     * The row brightness cannot be worked out as the frames arrive, because it is a ratio to a
     * background that is not known until the last of them has. What is kept instead is each
     * row's sampled values, which is what the ratio is taken over, so the number that comes out
     * at the end is the one [rowBright] would have given.
     */
    class Builder(private val rect: IntArray, frames: Int, tracks: List<List<FloatArray>>,
                  radii: Double = DEFAULT_RADII, private val stride: Int = ROW_STRIDE) {
        val side = boxFor(tracks, radii)
        private val w = rect[2]; private val h = rect[3]
        private val cols = (w + stride - 1) / stride
        private val at = offsets(tracks, rect, frames, side)
        /** the frames the background is medianed from: a spread of the ones a track covers */
        private val bgWanted: List<Int> = run {
            val lo = at.indexOfFirst { it != null }.let { if (it < 0) 0 else it }
            val hi = at.indexOfLast { it != null }.let { if (it < 0) frames - 1 else it }
            val m = minOf(BG_FRAMES, maxOf(1, hi - lo + 1))
            (0 until m).map { lo + if (m == 1) 0 else Math.round(it.toDouble() * (hi - lo) / (m - 1)).toInt() }
        }
        private val bgKept = HashMap<Int, ByteArray>()
        /** a background from outside, when there is one: see [Windows.write] */
        var given: ByteArray? = null
        private val rows = ArrayList<ByteArray>()
        private val wins = ArrayList<ByteArray?>()
        /** how many crops have been handed in */
        val count get() = rows.size

        /** one crop, in the capture's layout. It is read and then let go of, except for the few the background needs. */
        fun add(crop: ByteArray) {
            val i = rows.size
            if (i in bgWanted) bgKept[i] = crop
            rows.add(sampleRows(crop))
            wins.add(at.getOrNull(i)?.let { cut(crop, rect, it, side) })
        }

        /** every [stride]th column of each row, which is what the brightness ratio is taken over */
        private fun sampleRows(crop: ByteArray): ByteArray {
            val out = ByteArray(h * cols)
            for (y in 0 until h) {
                val o = y * w; var k = y * cols; var x = 0
                while (x < w) { out[k++] = crop[o + x]; x += stride }
            }
            return out
        }

        /** the header fragment naming the windows, for Capture.header */
        fun headerExtra(): String {
            val sb = StringBuilder(""","winW":$side,"winH":$side,"windows":[""")
            for (i in wins.indices) { if (i > 0) sb.append(','); sb.append(at.getOrNull(i)?.let { "[${it[0]},${it[1]}]" } ?: "null") }
            return sb.append(']').toString()
        }

        /**
         * The bytes after the header: the background, then every frame's row brightness, then the
         * windows.
         *
         * The background's brightness is the median of the kept frames **after** each has had its
         * own row brightness divided out, because that is the background the engine compares a
         * frame against: it medians the frames it has already corrected. Shipping the median of
         * the raw frames instead is close but not the same, and the difference lands on the
         * threshold that separates ball from background -- on session 20261112_215446's serve 6
         * it was the difference between finding the ball in 30 of 55 frames and 45, and with it
         * whether the colour dots were seen often enough to claim which way the ball drilled.
         *
         * Colour is the median of the raw frames, since the engine never corrects chroma.
         */
        fun writeBody(out: DataOutputStream) {
            val frameBytes = w * h + 2 * (w / 2) * (h / 2)
            val kept = bgWanted.mapNotNull { bgKept[it] }
            val back = given ?: if (kept.isEmpty()) ByteArray(frameBytes) else run {
                val raw = background(kept, frameBytes, kept.size)
                val rawRow = sampleRows(raw)
                val flat = kept.map { f ->
                    val rows = rowsAgainst(sampleRows(f), rawRow)
                    val o = ByteArray(frameBytes)
                    System.arraycopy(f, w * h, o, w * h, 2 * (w / 2) * (h / 2))   // chroma as it was
                    for (y in 0 until h) {
                        val r = rows[y]; val base = y * w
                        for (x in 0 until w) {
                            val v = ((f[base + x].toInt() and 255) / r).toInt()
                            o[base + x] = (if (v > 255) 255 else if (v < 0) 0 else v).toByte()
                        }
                    }
                    o
                }
                val lit = background(flat, frameBytes, flat.size)
                System.arraycopy(raw, w * h, lit, w * h, 2 * (w / 2) * (h / 2))   // colour from the raw median
                lit
            }
            out.write(back)
            val bgRow = sampleRows(back)
            for (i in rows.indices) for (r in rowsAgainst(rows[i], bgRow)) out.writeInt(java.lang.Float.floatToIntBits(r))
            for (win in wins) if (win != null) out.write(win)
        }

        /** how bright each row of a frame is against a background, from their sampled columns */
        private fun rowsAgainst(sample: ByteArray, bgSample: ByteArray): FloatArray {
            val tmp = DoubleArray(cols)
            return FloatArray(h) { y ->
                val o = y * cols
                for (x in 0 until cols) {
                    val b = (bgSample[o + x].toInt() and 255).toDouble()
                    tmp[x] = (sample[o + x].toInt() and 255) / (if (b < 1.0) 1.0 else b)
                }
                java.util.Arrays.sort(tmp, 0, cols)
                tmp[cols / 2].toFloat()
            }
        }
    }

    /**
     * A windowed capture file: the header, then the background frame, then the row brightness
     * (n x height, floats, most significant byte first, as DataOutputStream writes them), then one window a frame. A frame no track covers has
     * no window and reads as background alone.
     */
    fun write(file: File, cap: Capture, tracks: List<List<FloatArray>>, extra: String = "",
              radii: Double = DEFAULT_RADII, bg: ByteArray? = null): Int {
        val b = Builder(cap.rect, cap.frames.size, tracks, radii)
        b.given = bg
        for (f in cap.frames) b.add(f)
        val head = cap.header(b.headerExtra() + extra, b.count).toByteArray()
        DataOutputStream(FileOutputStream(file).buffered(1 shl 20)).use { out ->
            out.writeInt(head.size); out.write(head); b.writeBody(out)
        }
        return b.side
    }
}
