package com.monti.servecoach.core

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Setting the phone up, checked on a small picture of the preview (0.9.1): one piece of advice at a
 * time, most important first. The table is found by its colour, blue, green or dark blue-grey
 * (until 0.13.22 only blue), as the largest patch of one of them that isn't a wall or a floor.
 * Rules from the clips that read well (club, angled: the table 11-28% of the picture, its top at 38-54% of the height, 2.5-3.7 times as wide as high)
 * and the one that didn't (the phone low by the net post: 5%, 1.7). At home, a plain wall and no
 * table read well too, so no table is advice, not a fault.
 */
object SetupCheck {
    enum class Kind { DARK, TILTED, MOVING, NO_TABLE, FAR, NEAR, TABLE_HIGH, TABLE_LOW, END_ON, GOOD }
    class Advice(val kind: Kind, val text: String) {
        val ok get() = kind == Kind.GOOD || kind == Kind.NO_TABLE
        override fun toString() = "$kind: $text"
    }
    /** the table colours the setup check knows; black is left out (see [colourOf]) */
    enum class Colour { BLUE, GREEN, BLUE_GREY }

    /** the table as found: its share of the picture, its box as fractions of the picture, and its colour */
    class Table(val share: Float, val x0: Float, val y0: Float, val x1: Float, val y1: Float, val colour: Colour = Colour.BLUE) {
        val aspect get() = (x1 - x0) / maxOf(1e-3f, y1 - y0) * 16f / 9f   // in the picture's proportions
        override fun toString() = "table ${colour.name.lowercase()} ${(share * 100).roundToInt()}% x ${"%.2f".format(x0)}-${"%.2f".format(x1)} y ${"%.2f".format(y0)}-${"%.2f".format(y1)} aspect ${"%.1f".format(aspect)}"
    }

    /**
     * Which table colour one pixel could be, from its Y and its chroma (Cb = U - 128, Cr = V - 128);
     * null for none. The families don't overlap, so a blue table next to a green floor stays two patches.
     *  - BLUE: Cb > 9 and Cr < -10, Y > 40 (unchanged since 0.9.1, read well on the club clips).
     *  - GREEN: Cr <= -14 with Cb from -24 to 9, Y 41-169. A table green (about RGB 30,90,60) sits near
     *    Cb -5, Cr -28. Cb below -24 is yellow-green (grass, lime paint); Y 170 and up is a pale mint wall,
     *    not a table's dark green.
     *  - BLUE_GREY (dark slate tables, about RGB 50,60,80: Cb +12, Cr -7): Cb >= 5, Cr <= -3, Cb - Cr >= 12,
     *    Y 36-129. Grey under a cool white balance (Cb +3, Cr -2) stays out.
     *  - Black: no. With no colour, only darkness tells it apart, and a dark floor, the shadow under the
     *    table, dark surrounds and clothes are all as dark; it would pass more floors than tables.
     */
    fun colourOf(y: Int, u: Int, v: Int): Colour? {
        val cb = u - 128; val cr = v - 128
        return when {
            cb > 9 && cr < -10 && y > 40 -> Colour.BLUE
            cr <= -14 && cb in -24..9 && y in 41..169 -> Colour.GREEN
            cb >= 5 && cr <= -3 && cb - cr >= 12 && y in 36..129 -> Colour.BLUE_GREY
            else -> null
        }
    }

    /**
     * A patch that is a wall or a floor, not a table (after 0.13.22): over 60% of the picture (a table that big
     * is already "move back" from 45%), across half the top edge (the table sits low, with room above it
     * for the toss; a wall reaches the top), or across 90% of the bottom edge (a floor runs out of the
     * picture all along the bottom; a table seen at an angle crosses it only in part).
     */
    fun isBackground(share: Float, topEdge: Float, bottomEdge: Float) = share > 0.60f || topEdge >= 0.5f || bottomEdge >= 0.9f

    /**
     * A table seen from anywhere a phone on a tripod stands is wider than it is tall, even from its
     * end (that view still reads 1.2 and up; END_ON advice is for under 1.8). A taller patch is a
     * door, a curtain or a shirt: at home, a blue door curtain (aspect 0.4-1.0) passed as a table
     * on most frames of the 15 Nov sessions and drew "Turn to face the side of the table".
     */
    const val MIN_ASPECT = 1.1f

    /**
     * The table: the largest patch of one table colour (the near table; tables behind, shirts are
     * smaller) that isn't a wall or a floor ([isBackground]).
     */
    fun findTable(y: ByteArray, u: ByteArray, v: ByteArray, w: Int, h: Int): Table? {
        val n = w * h
        val col = IntArray(n) { p -> colourOf(y[p].toInt() and 0xff, u[p].toInt() and 0xff, v[p].toInt() and 0xff)?.ordinal ?: -1 }
        val m = BooleanArray(n); val er = BooleanArray(n); val op = BooleanArray(n)
        val lab = IntArray(n); val stack = IntArray(n)
        var best: Table? = null; var bestSize = 0
        for (c in Colour.values()) {
            for (p in 0 until n) { m[p] = col[p] == c.ordinal; er[p] = false; op[p] = false; lab[p] = 0 }
            // an opening (3x3): lone pixels and thin lines out
            for (yy in 1 until h - 1) for (x in 1 until w - 1) {
                var all = true
                loop@ for (dy in -1..1) for (dx in -1..1) if (!m[(yy + dy) * w + x + dx]) { all = false; break@loop }
                er[yy * w + x] = all
            }
            for (yy in 1 until h - 1) for (x in 1 until w - 1) if (er[yy * w + x]) for (dy in -1..1) for (dx in -1..1) op[(yy + dy) * w + x + dx] = true
            // its 4-connected patches
            var id = 0
            for (p in 0 until n) {
                if (!op[p] || lab[p] != 0) continue
                id++; var top = 0; stack[top++] = p; lab[p] = id
                var size = 0; var x0 = w; var y0 = h; var x1 = 0; var y1 = 0; var topRow = 0; var bottomRow = 0
                while (top > 0) {
                    val q = stack[--top]; size++
                    val qx = q % w; val qy = q / w
                    if (qx < x0) x0 = qx; if (qx > x1) x1 = qx; if (qy < y0) y0 = qy; if (qy > y1) y1 = qy
                    if (qy == 0) topRow++; if (qy == h - 1) bottomRow++
                    if (qx > 0 && op[q - 1] && lab[q - 1] == 0) { lab[q - 1] = id; stack[top++] = q - 1 }
                    if (qx < w - 1 && op[q + 1] && lab[q + 1] == 0) { lab[q + 1] = id; stack[top++] = q + 1 }
                    if (qy > 0 && op[q - w] && lab[q - w] == 0) { lab[q - w] = id; stack[top++] = q - w }
                    if (qy < h - 1 && op[q + w] && lab[q + w] == 0) { lab[q + w] = id; stack[top++] = q + w }
                }
                if (size < n / 200 || size <= bestSize) continue          // under half a percent: no table
                val share = size.toFloat() / n
                if (isBackground(share, topRow.toFloat() / w, bottomRow.toFloat() / w)) continue
                val t = Table(share, x0.toFloat() / w, y0.toFloat() / h, (x1 + 1).toFloat() / w, (y1 + 1).toFloat() / h, c)
                if (t.aspect < MIN_ASPECT) continue                     // taller than wide: a door, a curtain, a shirt
                bestSize = size
                best = t
            }
        }
        return best
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
            else -> Advice(Kind.GOOD, "Looks good. Best with the dot-marked ball, bright light and a plain wall behind the flight. Press Start when you're ready.")
        }
        return a to t
    }

    /**
     * Serves fly more than this toward (or away from) the camera, by the table's axis: advise turning
     * to face the side. 18 Nov's placement read 15-25 here (the ball-size estimate said 45); the
     * number is a first guess until a session filmed side on and at an angle sets it.
     */
    const val FACE_SIDE_DEG = 30.0

    /**
     * The picture's advice, sharpened by the table's pose ([TablePose]): only a "looks good" can
     * become "face the side", and only on a pose that is sure which way the table runs. Advice, not
     * a gate: Start works either way.
     */
    fun withPose(a: Advice, pose: TablePose.Pose?): Advice {
        if (a.kind != Kind.GOOD || pose == null || !pose.sure || pose.towardDeg <= FACE_SIDE_DEG) return a
        return Advice(Kind.END_ON, "Turn the phone to face the side of the table: serves here would fly about " +
            "${(pose.towardDeg / 5).roundToInt() * 5} degrees toward it, and they read best flying across the picture.")
    }
}
