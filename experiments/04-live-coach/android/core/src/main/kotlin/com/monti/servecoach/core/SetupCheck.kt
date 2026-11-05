package com.monti.servecoach.core

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Setting the phone up, checked on a small picture of the preview (0.9.1): one piece of advice at a
 * time, most important first. The table is found by its blue (the largest blue patch: the near
 * table; tables behind, blue shirts are smaller). Rules from the clips that read well (club, angled:
 * the table 11-28% of the picture, its top at 38-54% of the height, 2.5-3.7 times as wide as high)
 * and the one that didn't (the phone low by the net post: 5%, 1.7). At home, a plain wall and no
 * table read well too, so no table is advice, not a fault.
 */
object SetupCheck {
    enum class Kind { DARK, TILTED, MOVING, NO_TABLE, FAR, NEAR, TABLE_HIGH, TABLE_LOW, END_ON, GOOD }
    class Advice(val kind: Kind, val text: String) {
        val ok get() = kind == Kind.GOOD || kind == Kind.NO_TABLE
        override fun toString() = "$kind: $text"
    }
    /** the table as found: its share of the picture, and its box as fractions of the picture */
    class Table(val share: Float, val x0: Float, val y0: Float, val x1: Float, val y1: Float) {
        val aspect get() = (x1 - x0) / maxOf(1e-3f, y1 - y0) * 16f / 9f   // in the picture's proportions
        override fun toString() = "table ${(share * 100).roundToInt()}% x ${"%.2f".format(x0)}-${"%.2f".format(x1)} y ${"%.2f".format(y0)}-${"%.2f".format(y1)} aspect ${"%.1f".format(aspect)}"
    }

    /** the table's blue: chroma towards blue (U, Cb, above 137) and away from red (V, Cr, below 118), not in shadow */
    fun findTable(y: ByteArray, u: ByteArray, v: ByteArray, w: Int, h: Int): Table? {
        val n = w * h
        val m = BooleanArray(n) { p -> (u[p].toInt() and 0xff) > 137 && (v[p].toInt() and 0xff) < 118 && (y[p].toInt() and 0xff) > 40 }
        // an opening (3x3): lone pixels and thin lines out
        val er = BooleanArray(n); val op = BooleanArray(n)
        for (yy in 1 until h - 1) for (x in 1 until w - 1) {
            var all = true
            loop@ for (dy in -1..1) for (dx in -1..1) if (!m[(yy + dy) * w + x + dx]) { all = false; break@loop }
            er[yy * w + x] = all
        }
        for (yy in 1 until h - 1) for (x in 1 until w - 1) if (er[yy * w + x]) for (dy in -1..1) for (dx in -1..1) op[(yy + dy) * w + x + dx] = true
        // the largest 4-connected patch
        val lab = IntArray(n); var best = 0; var bestId = 0; var id = 0
        val stack = IntArray(n)
        for (p in 0 until n) {
            if (!op[p] || lab[p] != 0) continue
            id++; var top = 0; stack[top++] = p; lab[p] = id; var size = 0
            while (top > 0) {
                val q = stack[--top]; size++
                val qx = q % w; val qy = q / w
                if (qx > 0 && op[q - 1] && lab[q - 1] == 0) { lab[q - 1] = id; stack[top++] = q - 1 }
                if (qx < w - 1 && op[q + 1] && lab[q + 1] == 0) { lab[q + 1] = id; stack[top++] = q + 1 }
                if (qy > 0 && op[q - w] && lab[q - w] == 0) { lab[q - w] = id; stack[top++] = q - w }
                if (qy < h - 1 && op[q + w] && lab[q + w] == 0) { lab[q + w] = id; stack[top++] = q + w }
            }
            if (size > best) { best = size; bestId = id }
        }
        if (best < n / 200) return null                    // under half a percent: no table
        var x0 = w; var y0 = h; var x1 = 0; var y1 = 0
        for (p in 0 until n) if (lab[p] == bestId) { val x = p % w; val yy = p / w; if (x < x0) x0 = x; if (x > x1) x1 = x; if (yy < y0) y0 = yy; if (yy > y1) y1 = yy }
        return Table(best.toFloat() / n, x0.toFloat() / w, y0.toFloat() / h, (x1 + 1).toFloat() / w, (y1 + 1).toFloat() / h)
    }

    /**
     * The advice for one picture. [tiltDeg]: the phone's roll from level (landscape); [moving]: it
     * was moved in the last moment (then nothing else is judged yet).
     */
    fun advise(y: ByteArray, u: ByteArray, v: ByteArray, w: Int, h: Int, tiltDeg: Float = 0f, moving: Boolean = false): Pair<Advice, Table?> {
        if (moving) return Advice(Kind.MOVING, "Hold the phone still, or set it on a tripod.") to null
        var sum = 0L; for (p in 0 until w * h) sum += y[p].toInt() and 0xff
        if (sum / (w * h) < 55) return Advice(Kind.DARK, "It's too dark. Turn on more lights or face the light.") to null
        if (abs(tiltDeg) > 6f) return Advice(Kind.TILTED, "Level the phone. It's tilted about ${abs(tiltDeg).roundToInt()} degrees.") to null
        val t = findTable(y, u, v, w, h)
            ?: return Advice(Kind.NO_TABLE, "I can't see the table. Best: the table low in the picture, the ball flying across above it. A plain wall behind works too.") to null
        val a = when {
            // a strip along the bottom edge: aimed too high; the rest is judged once more of it shows
            t.y1 > 0.97f && t.y0 > 0.6f && t.share < 0.12f -> Advice(Kind.TABLE_LOW, "Tilt the phone down a little: more of the table should show.")
            t.share < 0.08f && t.aspect < 1.8f -> Advice(Kind.END_ON, "Face the side of the table, about level with the net, so the ball flies across the picture.")
            t.share < 0.08f -> Advice(Kind.FAR, "Move closer: the table looks small. About 1.5 m from the ball's path.")
            t.aspect < 1.8f -> Advice(Kind.END_ON, "Turn to face the side of the table, so the ball flies across the picture.")
            t.share > 0.45f -> Advice(Kind.NEAR, "Move back a little: the table fills the picture.")
            t.y0 < 0.30f -> Advice(Kind.TABLE_HIGH, "Tilt the phone up a little: leave room above the table for the toss.")
            t.y0 > 0.75f -> Advice(Kind.TABLE_LOW, "Tilt the phone down a little: more of the table should show.")
            else -> Advice(Kind.GOOD, "Looks good. Press Start when you're ready.")
        }
        return a to t
    }
}
