package com.monti.servecoach.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A windowed capture built from the parts the chip hands back (LiveWindows) against one built
 * from the crops themselves.
 *
 * The two paths have to agree, because the engine was only ever checked against the second: the
 * windows must be the same bytes, and the background and the row brightness must be the same
 * numbers to within what four-row sampling and a re-cut can cost. This is the only place that
 * can be checked without a phone -- on a phone the two paths never run on the same serve.
 */
class WindowsPartsTest {
    private val frameW = 320; private val frameH = 240
    private val rect = intArrayOf(32, 16, 256, 192)      // left a multiple of 16, top of 4
    private val n = 48
    private var rowStep = 2
    private val stride = Windows.ROW_STRIDE
    private val ringSide = 96

    /** a flight across the crop, as the tracker would report it (whole-picture pixels) */
    private val track = (0 until n).map {
        floatArrayOf(it.toFloat(), 60f + 4.2f * it, 90f + 0.9f * it + 0.012f * it * it, 9f)
    }

    /**
     * A frame: a table, a dark body on the left, a bright ball, and rolling-shutter flicker --
     * a band across the rows, moving between frames, which is the whole reason a capture
     * carries how bright each row was.
     */
    private fun frame(i: Int): ByteArray {
        val y = ByteArray(frameW * frameH)
        val cw = frameW / 2; val ch = frameH / 2
        val u = ByteArray(cw * ch); val v = ByteArray(cw * ch)
        val p = track[i]
        for (row in 0 until frameH) {
            // 0.4 cycles across the picture and 0.42 of a cycle on between frames, which is
            // 100 Hz flicker seen at 240 frames a second
            val lit = 1.0 + 0.18 * sin(2 * Math.PI * (0.4 * row / frameH + 0.4167 * i))
            for (col in 0 until frameW) {
                var level = 96.0 + 18 * sin(col * 0.11) + 7 * sin(row * 0.23)
                if (col < 40) level = 38.0 + 9 * sin((row + 3 * i) * 0.31)       // the body
                val d = Math.hypot(col - p[1].toDouble(), row - p[2].toDouble())
                if (d < p[3]) level = 232.0 - 40 * d / p[3]
                y[row * frameW + col] = (level * lit).roundToInt().coerceIn(0, 255).toByte()
            }
        }
        for (row in 0 until ch) for (col in 0 until cw) {
            val d = Math.hypot(2.0 * col - p[1], 2.0 * row - p[2])
            u[row * cw + col] = (if (d < p[3]) 150 else 124).toByte()
            v[row * cw + col] = (if (d < p[3]) 108 else 130).toByte()
        }
        return y + u + v
    }

    private fun crop(full: ByteArray): ByteArray {
        val (x0, y0, w, h) = listOf(rect[0], rect[1], rect[2], rect[3])
        val out = ByteArray(w * h + 2 * (w / 2) * (h / 2))
        for (r in 0 until h) System.arraycopy(full, (y0 + r) * frameW + x0, out, r * w, w)
        val cw = frameW / 2; val ch = frameH / 2; val fc = cw * ch
        val w2 = w / 2; val h2 = h / 2; val x2 = x0 / 2; val y2 = y0 / 2
        for (r in 0 until h2) {
            System.arraycopy(full, frameW * frameH + (y2 + r) * cw + x2, out, w * h + r * w2, w2)
            System.arraycopy(full, frameW * frameH + fc + (y2 + r) * cw + x2, out, w * h + w2 * h2 + r * w2, w2)
        }
        return out
    }

    /** the whole picture's row sample, exactly as assets/shaders/crop_rows.frag reads it back */
    private fun ringRowSample(full: ByteArray): ByteArray {
        val cols = (frameW + stride - 1) / stride
        val pitch = ((cols + 3) / 4) * 4
        val sampled = (frameH + rowStep - 1) / rowStep
        val out = ByteArray(pitch * sampled)
        for (j in 0 until sampled) for (k in 0 until cols) {
            val col = minOf(k * stride, frameW - 1); val row = minOf(j * rowStep, frameH - 1)
            out[j * pitch + k] = full[row * frameW + col]
        }
        return out
    }

    /** the window the chip cuts, around where the ball is PREDICTED to be */
    private fun ringWindow(full: ByteArray, at: IntArray): ByteArray {
        val side = ringSide
        val out = ByteArray(side * side + 2 * (side / 2) * (side / 2))
        for (r in 0 until side) System.arraycopy(full, (at[1] + r) * frameW + at[0], out, r * side, side)
        val cw = frameW / 2; val fc = cw * (frameH / 2)
        val s2 = side / 2; val x2 = at[0] / 2; val y2 = at[1] / 2
        for (r in 0 until s2) {
            System.arraycopy(full, frameW * frameH + (y2 + r) * cw + x2, out, side * side + r * s2, s2)
            System.arraycopy(full, frameW * frameH + fc + (y2 + r) * cw + x2, out, side * side + s2 * s2 + r * s2, s2)
        }
        return out
    }

    private fun body(b: Windows.Builder): ByteArray {
        val o = ByteArrayOutputStream()
        DataOutputStream(o).use { b.writeBody(it) }
        return o.toByteArray()
    }

    @Test
    fun `how coarse the rows can be`() {
        val fulls = (0 until n).map { frame(it) }
        val crops = fulls.map { crop(it) }
        val tracks = listOf(track.map { floatArrayOf(it[0], it[1], it[2], it[3]) })
        val whole = Windows.Builder(rect, n, tracks); for (c in crops) whole.add(c)
        val a = body(whole)
        val frameBytes = rect[2] * rect[3] + 2 * (rect[2] / 2) * (rect[3] / 2)
        for (st in listOf(1, 2, 4, 8, 16)) {
            rowStep = st
            val parts = Windows.Builder(rect, n, tracks)
            val wanted = parts.backgroundFrames.toHashSet()
            for (i in 0 until n) parts.addParts(Windows.ringRows(ringRowSample(fulls[i]), rect, frameW, frameH, st),
                null, if (i in wanted) crops[i] else null)
            val b = body(parts)
            var worst = 0.0; var sum = 0.0; var o = frameBytes
            for (i in 0 until n) for (y in 0 until rect[3]) {
                fun f(src: ByteArray): Float { var v = 0; for (k in 0 until 4) v = (v shl 8) or (src[o + k].toInt() and 255); return java.lang.Float.intBitsToFloat(v) }
                val d = abs(f(a) - f(b)).toDouble(); if (d > worst) worst = d; sum += d; o += 4
            }
            println("row step %2d: mean %.5f worst %.5f".format(st, sum / (n * rect[3]), worst))
            // every row sampled must give back exactly what reading every row gives: the
            // columns and the rows line up, or they do not
            if (st == 1) assertEquals(0.0, worst, 0.0)
        }
        rowStep = 2
    }

    @Test
    fun `parts from the chip build the capture the crops build`() {
        val fulls = (0 until n).map { frame(it) }
        val crops = fulls.map { crop(it) }
        val tracks = listOf(track.map { floatArrayOf(it[0], it[1], it[2], it[3]) })

        val whole = Windows.Builder(rect, n, tracks)
        for (c in crops) whole.add(c)

        val parts = Windows.Builder(rect, n, tracks)
        val side = parts.side
        assertTrue("the live window has to be the bigger one", ringSide >= side)
        val wanted = parts.backgroundFrames.toHashSet()
        var recut = 0
        for (i in 0 until n) {
            // where the chip would have put it: the prediction, which misses by a few pixels
            val p = Predict.at(track, i.toFloat()) ?: run { parts.addParts(Windows.ringRows(ringRowSample(fulls[i]), rect, frameW, frameH, rowStep), null, if (i in wanted) crops[i] else null); null } ?: continue
            val ax = (Math.round(p[0] - ringSide / 2f).coerceIn(0, frameW - ringSide)) and 1.inv()
            val ay = (Math.round(p[1] - ringSide / 2f).coerceIn(0, frameH - ringSide)) and 1.inv()
            val place = parts.placeOf(i)
            var win: ByteArray? = null
            if (place != null) {
                win = Windows.reCut(ringWindow(fulls[i], intArrayOf(ax, ay)), ringSide,
                    rect[0] + place[0] - ax, rect[1] + place[1] - ay, side)
                if (win != null) recut++
            }
            parts.addParts(Windows.ringRows(ringRowSample(fulls[i]), rect, frameW, frameH, rowStep),
                win, if (i in wanted) crops[i] else null)
        }
        assertEquals("every frame handed in", n, parts.count)
        assertTrue("most windows re-cut out of the live one, not $recut", recut >= 0.8 * n)
        // The live windows start late on purpose: a prediction needs four points behind it, and
        // the engine skips the frames right after the hit anyway. So the two layouts are the
        // same except where the live one has nothing, and the header must say so -- a place
        // named without its bytes would shift every window after it.
        val wantAt = places(whole.headerExtra()); val gotAt = places(parts.headerExtra())
        assertEquals(n, wantAt.size); assertEquals(n, gotAt.size)
        var holes = 0
        for (i in 0 until n) {
            if (gotAt[i] == null) { holes++; continue }
            assertEquals("frame $i goes in the same place", wantAt[i], gotAt[i])
        }
        println("windows: ${n - holes} of $n, $holes with nothing live to cut")
        assertTrue("too many frames with no live window: $holes", holes <= 8)

        val a = body(whole); val b = body(parts)

        val frameBytes = rect[2] * rect[3] + 2 * (rect[2] / 2) * (rect[3] / 2)
        var bgWorst = 0; var bgSum = 0.0
        for (i in 0 until frameBytes) {
            val d = abs((a[i].toInt() and 255) - (b[i].toInt() and 255))
            if (d > bgWorst) bgWorst = d
            bgSum += d.toDouble()
        }
        println("background: mean ${"%.3f".format(bgSum / frameBytes)}, worst $bgWorst")
        assertTrue("the background drifts by $bgWorst levels", bgWorst <= 3)

        var rowWorst = 0.0; var rowSum = 0.0
        var o = frameBytes
        for (i in 0 until n) for (y in 0 until rect[3]) {
            fun f(src: ByteArray): Float {
                var v = 0
                for (k in 0 until 4) v = (v shl 8) or (src[o + k].toInt() and 255)
                return java.lang.Float.intBitsToFloat(v)
            }
            val d = abs(f(a) - f(b)).toDouble()
            if (d > rowWorst) rowWorst = d
            rowSum += d
            o += 4
        }
        println("row brightness: mean ${"%.5f".format(rowSum / (n * rect[3]))}, worst ${"%.5f".format(rowWorst)}")
        // the whole of this is the interpolation between sampled rows (at a step of 1 it is
        // exactly zero): a few thousandths, against row-to-row noise of 0.0066 on the captures
        // recorded. What made this worth measuring is that the row brightness has gone wrong
        // once before, by a few percent, and moved a serve's flight angle by 23 degrees.
        assertTrue("the row brightness drifts by $rowWorst", rowWorst <= 0.02)
        assertTrue("the row brightness drifts by ${rowSum / (n * rect[3])} on average", rowSum / (n * rect[3]) <= 0.005)

        // the windows themselves, walked side by side: each body holds only the ones its own
        // header names, so the two run at different offsets
        val wb = side * side + 2 * (side / 2) * (side / 2)
        var oa = o; var ob = o; var same = 0
        for (i in 0 until n) {
            if (gotAt[i] != null) {
                assertTrue("whole has a window for frame $i", wantAt[i] != null)
                for (k in 0 until wb) assertEquals("frame $i, byte $k", a[oa + k], b[ob + k])
                same++; ob += wb
            }
            if (wantAt[i] != null) oa += wb
        }
        assertEquals("every live window compared", n - holes, same)
        assertEquals("nothing left over", a.size, oa)
        assertEquals("nothing left over", b.size, ob)
    }

    /** the places out of a headerExtra, "null" for a frame with no window */
    private fun places(extra: String): List<String?> {
        val inner = extra.substringAfter(""""windows":[""").removeSuffix("]")
        val out = ArrayList<String?>()
        var i = 0
        while (i < inner.length) {
            if (inner[i] == '[') { val j = inner.indexOf(']', i); out.add(inner.substring(i, j + 1)); i = j + 1 }
            else if (inner.startsWith("null", i)) { out.add(null); i += 4 }
            else i++
        }
        return out
    }

    @Test
    fun `a window re-cut out of a bigger one is the same pixels`() {
        val full = frame(10)
        val at = intArrayOf(40, 32)
        val big = ringWindow(full, at)
        val side = 48
        val cut = Windows.reCut(big, ringSide, 10, 14, side)!!
        // the same pixels straight out of the picture
        val want = ringWindowOf(full, intArrayOf(at[0] + 10, at[1] + 14), side)
        assertTrue("the same bytes", cut.contentEquals(want))
        assertEquals(null, Windows.reCut(big, ringSide, ringSide - side + 2, 0, side))
        assertEquals(null, Windows.reCut(big, ringSide, -2, 0, side))
        assertEquals(null, Windows.reCut(big, ringSide, 3, 0, side))      // an odd offset splits the colour
    }

    private fun ringWindowOf(full: ByteArray, at: IntArray, side: Int): ByteArray {
        val out = ByteArray(side * side + 2 * (side / 2) * (side / 2))
        for (r in 0 until side) System.arraycopy(full, (at[1] + r) * frameW + at[0], out, r * side, side)
        val cw = frameW / 2; val fc = cw * (frameH / 2)
        val s2 = side / 2; val x2 = at[0] / 2; val y2 = at[1] / 2
        for (r in 0 until s2) {
            System.arraycopy(full, frameW * frameH + (y2 + r) * cw + x2, out, side * side + r * s2, s2)
            System.arraycopy(full, frameW * frameH + fc + (y2 + r) * cw + x2, out, side * side + s2 * s2 + r * s2, s2)
        }
        return out
    }
}
