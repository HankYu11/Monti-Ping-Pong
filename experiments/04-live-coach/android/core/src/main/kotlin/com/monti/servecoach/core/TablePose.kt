package com.monti.servecoach.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Where the phone stands relative to the table (19 Nov): from the table in a person-free picture,
 * the phone's gravity and the camera's field of view.
 *
 * Why: on 18 Nov the phone stood past the net looking down the table, serves flew toward it, and
 * the setup check said "Looks good": it judges the table's box in the picture, not which way the
 * table runs. A serve flies along the table's long axis, give or take a cross-court angle, so the
 * axis says before any serve how far toward the camera serves will fly. It is recorded with every
 * capture so later sessions can be checked against it.
 *
 * How, from the 15/18 Nov club and home clips:
 *  - [median]: each pixel's median over a second or two of frames takes out people walking past.
 *  - [tableRegion]: the setup check's table patch, grown into same-hue pixels however pale (glare
 *    turns a blue top grey-blue) but never across a sharp edge (a table's edge, its white line, the
 *    net, anything standing in front). Colour alone stopped well short of the edges under glare.
 *  - [fromMask]: the region's edge pixels (not those on the picture's border) projected onto the
 *    table's plane, which gravity fixes. There a table's edges run in two perpendicular directions,
 *    found by a scan. Which is the long axis: the centre line (white, inside the region) runs along
 *    it; else the net, a line across the region's middle; else the longer extent (often wrong from
 *    by the net, where half a table is wider than it is long), then marked unsure.
 *  - [cornerAngle]: those two directions found separately must meet at 90 degrees. A door curtain
 *    passed as a table at home; it isn't square on the table's plane, so no pose.
 *
 * Camera axes are the engine's: x right, y down, z toward the camera (out of the picture).
 */
object TablePose {
    /**
     * [axis]: the table's long axis, a horizontal unit vector in camera axes (sign arbitrary).
     * [towardDeg]: how far a ball flying along it heads toward (or away from) the camera: 0 straight
     * across the picture, 90 straight at it. [sure]: the long axis was told from the short one by
     * the centre line or the net (or a clear difference in extent). [by]: which. [cornerDeg]: the
     * angle the table's edges meet at on its plane (90 when gravity is right). [aspect]: the longer
     * extent over the shorter. [edgePoints]: edge pixels used.
     * The plane geometry, for measuring on the table later: [fwd] and [right] span the table's plane
     * (camera axes), points on it one unit below the camera; [longRad] is the long axis's angle from
     * [fwd] toward [right]; [alongLo] and [alongHi] the table region's ends along it, same units.
     */
    class Pose(val axis: DoubleArray, val towardDeg: Double, val aspect: Double, val sure: Boolean, val by: String,
               val cornerDeg: Double, val edgePoints: Int,
               val fwd: DoubleArray, val right: DoubleArray, val longRad: Double, val alongLo: Double, val alongHi: Double) {
        override fun toString() = "table axis toward ${"%.0f".format(towardDeg)} deg (${if (sure) "by $by" else "unsure"}), " +
            "corner ${"%.0f".format(cornerDeg)} deg, aspect ${"%.2f".format(aspect)}, $edgePoints edge points"
        /** for a capture header or the report */
        fun json() = "{\"towardDeg\":${num(towardDeg, 1)},\"axis\":[${axis.joinToString(",") { num(it, 4) }}]," +
            "\"sure\":$sure,\"by\":\"$by\",\"cornerDeg\":${num(cornerDeg, 1)},\"aspect\":${num(aspect, 2)},\"edgePoints\":$edgePoints}"
    }

    /**
     * The phone's "down" in camera axes, from its accelerometer in landscape with the top of the
     * phone to the left (Android ROTATION_90, iOS landscape right). [ax], [ay], [az]: the device
     * axes' reading, +9.8 along the axis pointing up (Android's sign; from iOS Core Motion gravity,
     * pass minus gravity).
     */
    fun downFromAccel(ax: Double, ay: Double, az: Double): DoubleArray = unit(doubleArrayOf(ay, ax, -az))

    /** gravity for a capture header: [x,y,z] (camera axes, unit, down) */
    fun gravityJson(down: DoubleArray): String = "[${unit(down).joinToString(",") { num(it, 4) }}]"

    /** a number for JSON: NaN and infinities (which JSON has no word for) as null */
    fun num(x: Double, places: Int): String = if (x.isNaN() || x.isInfinite()) "null" else "%.${places}f".format(x)

    /**
     * The whole thing from a second or two of frames (each a Y, U, V triple at w x h; 640 x 360
     * suits): the median picture, the table's region, its pose. Null when there is no table, the
     * camera can't see its plane, or what was found isn't square (not a table).
     */
    fun analyse(frames: List<Array<ByteArray>>, w: Int, h: Int, down: DoubleArray, hfovDeg: Double): Pose? = look(frames, w, h, down, hfovDeg).pose

    /** one look: the pose, or why there is none (for the report: a session with no pose can say why) */
    class Look(val pose: Pose?, val why: String) { override fun toString() = pose?.toString() ?: "no table pose: $why" }

    /** [analyse], with the reason when there is no pose */
    fun look(frames: List<Array<ByteArray>>, w: Int, h: Int, down: DoubleArray, hfovDeg: Double): Look {
        if (frames.isEmpty()) return Look(null, "no pictures")
        val m = if (frames.size == 1) frames[0] else median(frames)
        return lookAt(m[0], m[1], m[2], w, h, down, hfovDeg)
    }

    /** the pose from one person-free picture: its region, squareness, then [fromMask] with the centre line */
    fun ofPicture(y: ByteArray, u: ByteArray, v: ByteArray, w: Int, h: Int, down: DoubleArray, hfovDeg: Double): Pose? =
        lookAt(y, u, v, w, h, down, hfovDeg).pose

    fun lookAt(y: ByteArray, u: ByteArray, v: ByteArray, w: Int, h: Int, down: DoubleArray, hfovDeg: Double): Look {
        val reg = tableRegion(y, u, v, w, h) ?: return Look(null, "no table colour")
        // a table's top is below the camera's horizon; a curtain or a wall reaches above it
        val pl = Plane.of(down, w, hfovDeg) ?: return Look(null, "the phone points too far down")
        var n = 0; var above = 0
        for (yy in 0 until h step 2) for (x in 0 until w step 2) if (reg[yy * w + x]) { n++; if (pl.project(x + 0.5, yy + 0.5, w, h) == null) above++ }
        if (n == 0) return Look(null, "no table colour")
        if (above > n / 20) return Look(null, "the patch reaches above the horizon (${100 * above / n}%): a wall or a curtain, not a table top")
        val corner = cornerAngle(reg, w, h, down, hfovDeg) ?: return Look(null, "too little edge")
        if (abs(corner - 90) > MAX_CORNER_ERR) return Look(null, "not square on the table's plane (corner ${corner.roundToInt()} deg)")
        val pose = fromMask(reg, w, h, down, hfovDeg, centreLine(y, u, v, w, h, reg), corner) ?: return Look(null, "too little edge")
        return Look(pose, "")
    }

    /**
     * The pose from the table's pixels. [mask]: w x h, [down]: gravity in camera axes,
     * [hfovDeg]: horizontal field of view, [centreLine]: the centre line's pixels if seen.
     * Null when too little edge is seen or the camera can't see the table's plane.
     */
    fun fromMask(mask: BooleanArray, w: Int, h: Int, down: DoubleArray, hfovDeg: Double,
                 centreLine: List<Int>? = null, cornerDeg: Double = Double.NaN): Pose? {
        val pl = Plane.of(down, w, hfovDeg) ?: return null
        val (xs, ys) = edgesOnPlane(mask, w, h, pl)
        val n = xs.size
        if (n < 40) return null
        val bin = max(range(xs), range(ys)) / 120.0
        if (bin <= 0) return null
        // the direction (and its perpendicular) that piles the edge points onto the fewest lines
        var bestTh = 0.0; var bestScore = -1.0; var th = 0.0
        while (th < 90.0) {
            val s = score(xs, ys, rad(th), bin) + score(xs, ys, rad(th + 90.0), bin)
            if (s > bestScore) { bestScore = s; bestTh = th }
            th += 0.5
        }
        val a = rad(bestTh); val b = a + PI / 2
        val ea = extent(xs, ys, a); val eb = extent(xs, ys, b)
        // the centre line's direction on the plane, if its pixels line up
        var centre = Double.NaN
        if (centreLine != null && centreLine.size >= 20) {
            val cx = ArrayList<Double>(); val cy = ArrayList<Double>()
            for (p in centreLine) pl.project((p % w) + 0.5, (p / w) + 0.5, w, h)?.let { cx.add(it[0]); cy.add(it[1]) }
            if (cx.size >= 20) {
                var bt = 0.0; var bs = Double.MAX_VALUE; var t = 0.0
                while (t < 180.0) { val sp = spread(cx, cy, rad(t + 90)); if (sp < bs) { bs = sp; bt = t }; t += 1.0 }
                if (spread(cx, cy, rad(bt)) > 6 * bs) centre = rad(bt)       // a line, not a blob
            }
        }
        fun near(x: Double, y: Double) = abs(((x - y) % PI + PI) % PI).let { min(it, PI - it) } < rad(12.0)
        val netA = interiorLine(xs, ys, a, bin); val netB = interiorLine(xs, ys, b, bin)
        val aspect = max(ea, eb) / max(1e-9, min(ea, eb))
        val long: Double; val sure: Boolean; val by: String
        when {
            !centre.isNaN() && near(centre, a) -> { long = a; sure = true; by = "centre line" }
            !centre.isNaN() && near(centre, b) -> { long = b; sure = true; by = "centre line" }
            netA > 0.3 && netA > 2 * netB -> { long = a; sure = true; by = "net" }
            netB > 0.3 && netB > 2 * netA -> { long = b; sure = true; by = "net" }
            else -> { long = if (ea >= eb) a else b; sure = aspect >= 1.6; by = "extent" }
        }
        val c = cos(long); val s = sin(long)
        val al = xs.indices.map { xs[it] * c + ys[it] * s }.sorted()
        val axis = unit(add(scale(pl.fwd, c), scale(pl.right, s)))
        val toward = deg(kotlin.math.asin(min(1.0, abs(c))))
        return Pose(axis, toward, aspect, sure, by, cornerDeg, n, pl.fwd, pl.right, long,
            al[(al.size * 0.02).toInt()], al[(al.size * 0.98).toInt().coerceAtMost(al.size - 1)])
    }

    /**
     * The angle between the table's two edge families on its plane, each found on its own (not
     * forced square): 90 when [down] is right and the patch is a table.
     */
    fun cornerAngle(mask: BooleanArray, w: Int, h: Int, down: DoubleArray, hfovDeg: Double): Double? {
        val pl = Plane.of(down, w, hfovDeg) ?: return null
        val (xs, ys) = edgesOnPlane(mask, w, h, pl)
        if (xs.size < 40) return null
        val bin = max(range(xs), range(ys)) / 120.0
        if (bin <= 0) return null
        val sc = DoubleArray(360) { score(xs, ys, rad(it * 0.5), bin) }
        val t1 = sc.indices.maxByOrNull { sc[it] }!!
        val t2 = sc.indices.filter { angDiff(it * 0.5, t1 * 0.5) > 30 }.maxByOrNull { sc[it] } ?: return null
        return angDiff(t1 * 0.5, t2 * 0.5)
    }
    /** how far from square a table may look before it isn't taken for one, degrees */
    const val MAX_CORNER_ERR = 10.0

    // the mask's edge pixels (not inside it, not on the picture's border) on the table's plane
    private fun edgesOnPlane(mask: BooleanArray, w: Int, h: Int, pl: Plane): Pair<ArrayList<Double>, ArrayList<Double>> {
        val xs = ArrayList<Double>(); val ys = ArrayList<Double>()
        for (y in 2 until h - 2) for (x in 2 until w - 2) {
            val p = y * w + x
            if (!mask[p] || (mask[p - 1] && mask[p + 1] && mask[p - w] && mask[p + w])) continue
            pl.project(x + 0.5, y + 0.5, w, h)?.let { xs.add(it[0]); ys.add(it[1]) }
        }
        return xs to ys
    }

    // ------------------------------------------------------------------ the table's region, glare and all
    /**
     * The table's top as a region of a person-free picture (w x h): the patch [SetupCheck.findTable]
     * finds, grown into pixels of the same hue however pale, never across a sharp edge.
     */
    fun tableRegion(y: ByteArray, u: ByteArray, v: ByteArray, w: Int, h: Int): BooleanArray? {
        val sw = 320; val sh = 180
        val ys = ByteArray(sw * sh); val us = ByteArray(sw * sh); val vs = ByteArray(sw * sh)
        for (j in 0 until sh) for (i in 0 until sw) { val p = (j * h / sh) * w + i * w / sw; ys[j * sw + i] = y[p]; us[j * sw + i] = u[p]; vs[j * sw + i] = v[p] }
        val t = SetupCheck.findTable(ys, us, vs, sw, sh) ?: return null
        val seedSmall = tableMask(ys, us, vs, sw, sh, t)
        val n = w * h
        val seed = BooleanArray(n) { p -> seedSmall[((p / w) * sh / h) * sw + (p % w) * sw / w] &&
            SetupCheck.colourOf(y[p].toInt() and 0xff, u[p].toInt() and 0xff, v[p].toInt() and 0xff) == t.colour }
        var scb = 0.0; var scr = 0.0; var k = 0
        for (p in 0 until n) if (seed[p]) { scb += (u[p].toInt() and 0xff) - 128; scr += (v[p].toInt() and 0xff) - 128; k++ }
        if (k < 50) return null
        val hue = kotlin.math.atan2(scr / k, scb / k)
        // sharp edges: Sobel on Y, or a jump in chroma
        val edge = BooleanArray(n)
        for (yy in 1 until h - 1) for (x in 1 until w - 1) {
            val p = yy * w + x
            fun Y(q: Int) = y[q].toInt() and 0xff
            val gx = (Y(p - w + 1) + 2 * Y(p + 1) + Y(p + w + 1)) - (Y(p - w - 1) + 2 * Y(p - 1) + Y(p + w - 1))
            val gy = (Y(p + w - 1) + 2 * Y(p + w) + Y(p + w + 1)) - (Y(p - w - 1) + 2 * Y(p - w) + Y(p - w + 1))
            val cu = abs((u[p + 1].toInt() and 0xff) - (u[p - 1].toInt() and 0xff)) + abs((u[p + w].toInt() and 0xff) - (u[p - w].toInt() and 0xff))
            val cv = abs((v[p + 1].toInt() and 0xff) - (v[p - 1].toInt() and 0xff)) + abs((v[p + w].toInt() and 0xff) - (v[p - w].toInt() and 0xff))
            edge[p] = sqrt((gx * gx + gy * gy).toDouble()) > EDGE || cu + cv > CHROMA_EDGE
        }
        // grow from the seed through same-hue pixels that are not edges
        val like = BooleanArray(n) { p ->
            if (edge[p]) false else {
                val cb = (u[p].toInt() and 0xff) - 128.0; val cr = (v[p].toInt() and 0xff) - 128.0
                if (sqrt(cb * cb + cr * cr) < MIN_CHROMA) false
                else { var d = abs(kotlin.math.atan2(cr, cb) - hue); if (d > PI) d = 2 * PI - d; d < rad(HUE_DEG) }
            }
        }
        val reg = BooleanArray(n); val stack = IntArray(n); var top = 0
        for (p in 0 until n) if (seed[p] && !edge[p]) { reg[p] = true; stack[top++] = p }
        while (top > 0) {
            val q = stack[--top]; val qx = q % w; val qy = q / w
            val nbs = intArrayOf(if (qx > 0) q - 1 else -1, if (qx < w - 1) q + 1 else -1, if (qy > 0) q - w else -1, if (qy < h - 1) q + w else -1)
            for (nb in nbs) if (nb >= 0 && !reg[nb] && (seed[nb] || like[nb])) { reg[nb] = true; stack[top++] = nb }
        }
        return close(reg, w, h, 2)       // the centre line and specks of glare filled in
    }
    const val EDGE = 90.0          // Sobel on Y: a table's edge is well over, a glare fade under
    const val CHROMA_EDGE = 26
    const val MIN_CHROMA = 3.0
    const val HUE_DEG = 30.0

    /** the mask of the table [SetupCheck.findTable] found: its colour's largest patch inside its box */
    fun tableMask(y: ByteArray, u: ByteArray, v: ByteArray, w: Int, h: Int, t: SetupCheck.Table): BooleanArray {
        val m = BooleanArray(w * h)
        val x0 = (t.x0 * w).toInt(); val x1 = (t.x1 * w).toInt(); val y0 = (t.y0 * h).toInt(); val y1 = (t.y1 * h).toInt()
        for (yy in y0 until min(h, y1)) for (x in x0 until min(w, x1)) {
            val p = yy * w + x
            m[p] = SetupCheck.colourOf(y[p].toInt() and 0xff, u[p].toInt() and 0xff, v[p].toInt() and 0xff) == t.colour
        }
        val lab = IntArray(w * h); val stack = IntArray(w * h); var best = 0; var bestSize = 0; var id = 0
        for (p in 0 until w * h) {
            if (!m[p] || lab[p] != 0) continue
            id++; var top = 0; stack[top++] = p; lab[p] = id; var size = 0
            while (top > 0) {
                val q = stack[--top]; size++
                val qx = q % w; val qy = q / w
                if (qx > 0 && m[q - 1] && lab[q - 1] == 0) { lab[q - 1] = id; stack[top++] = q - 1 }
                if (qx < w - 1 && m[q + 1] && lab[q + 1] == 0) { lab[q + 1] = id; stack[top++] = q + 1 }
                if (qy > 0 && m[q - w] && lab[q - w] == 0) { lab[q - w] = id; stack[top++] = q - w }
                if (qy < h - 1 && m[q + w] && lab[q + w] == 0) { lab[q + w] = id; stack[top++] = q + w }
            }
            if (size > bestSize) { bestSize = size; best = id }
        }
        for (p in 0 until w * h) m[p] = lab[p] == best && best != 0
        return m
    }

    /** each pixel's median over [frames] (each a Y, U, V triple, the same size): nobody walking past */
    fun median(frames: List<Array<ByteArray>>): Array<ByteArray> {
        val n = frames[0][0].size; val k = frames.size
        val out = Array(3) { ByteArray(n) }
        val buf = IntArray(k)
        for (c in 0 until 3) for (p in 0 until n) {
            for (i in 0 until k) buf[i] = frames[i][c][p].toInt() and 0xff
            buf.sort(); out[c][p] = buf[k / 2].toByte()
        }
        return out
    }

    /**
     * The centre line's pixels: whiter than the table round it, inside the region at least 5 px
     * from its edge, and on the one straight line most of them share (glare specks and a ball lying
     * on the table don't line up).
     */
    fun centreLine(y: ByteArray, u: ByteArray, v: ByteArray, w: Int, h: Int, m: BooleanArray): List<Int>? {
        val r = 5
        val pts = ArrayList<Int>()
        for (yy in r until h - r) for (x in r until w - r) {
            val p = yy * w + x
            if (!m[p] || !m[p - r] || !m[p + r] || !m[p - r * w] || !m[p + r * w]) continue
            val yv = y[p].toInt() and 0xff; val cb = (u[p].toInt() and 0xff) - 128; val cr = (v[p].toInt() and 0xff) - 128
            val yn = ((y[p - 3].toInt() and 0xff) + (y[p + 3].toInt() and 0xff) + (y[p - 3 * w].toInt() and 0xff) + (y[p + 3 * w].toInt() and 0xff)) / 4
            if (yv > yn + 18 && abs(cb) + abs(cr) < 30) pts.add(p)
        }
        if (pts.size < 20) return null
        val nth = 180; val diag = sqrt((w * w + h * h).toDouble()).toInt(); val nr = 2 * diag + 1
        val acc = IntArray(nth * nr)
        val cs = DoubleArray(nth) { cos(rad(it.toDouble())) }; val sn = DoubleArray(nth) { sin(rad(it.toDouble())) }
        for (p in pts) { val x = p % w; val yy = p / w; for (t in 0 until nth) acc[t * nr + (x * cs[t] + yy * sn[t]).toInt() + diag]++ }
        var best = 0; for (i in acc.indices) if (acc[i] > acc[best]) best = i
        if (acc[best] < 20) return null
        val t = best / nr; val rho = (best % nr - diag).toDouble()
        return pts.filter { p -> abs((p % w) * cs[t] + (p / w) * sn[t] - rho) < 2.0 }
    }

    // ------------------------------------------------------------------ the table's plane
    /** the plane one unit below the camera, gravity fixing it: [fwd] ahead (level), [right] */
    private class Plane(val g: DoubleArray, val fwd: DoubleArray, val right: DoubleArray, val f: Double) {
        /** pixel (x, y) of a w x h picture onto the plane, as (ahead, right); null at or above the horizon */
        fun project(x: Double, y: Double, w: Int, h: Int): DoubleArray? {
            val ray = doubleArrayOf((x - w / 2.0) / f, (y - h / 2.0) / f, -1.0)
            val d = dot(ray, g); if (d <= 1e-3) return null
            val pt = scale(ray, 1.0 / d)
            return doubleArrayOf(dot(pt, fwd), dot(pt, right))
        }
        companion object {
            fun of(down: DoubleArray, w: Int, hfovDeg: Double): Plane? {
                val g = unit(down)
                val fwd0 = doubleArrayOf(0.0, 0.0, -1.0)
                val fl = sub(fwd0, scale(g, dot(fwd0, g)))
                if (norm(fl) < 0.2) return null                       // looking straight down
                val fwd = unit(fl)
                val r0 = cross(g, fwd)
                val right = unit(if (r0[0] < 0) scale(r0, -1.0) else r0)
                return Plane(g, fwd, right, (w / 2.0) / tan(rad(hfovDeg) / 2))
            }
        }
    }

    // how strongly points pile up along lines perpendicular to [th]: the sum of squared histogram counts
    private fun score(xs: List<Double>, ys: List<Double>, th: Double, bin: Double): Double {
        val c = cos(th); val s = sin(th)
        val counts = HashMap<Int, Int>()
        for (i in xs.indices) { val k = kotlin.math.floor((-xs[i] * s + ys[i] * c) / bin).toInt(); counts[k] = (counts[k] ?: 0) + 1 }
        var sum = 0.0; for (v in counts.values) sum += v.toDouble() * v
        return sum
    }
    // a line across direction [th] inside the table (20-80% of the way), against the stronger at its ends: 0 for none
    private fun interiorLine(xs: List<Double>, ys: List<Double>, th: Double, bin: Double): Double {
        val c = cos(th); val s = sin(th)
        val v = xs.indices.map { xs[it] * c + ys[it] * s }.sorted()
        val lo = v[(v.size * 0.02).toInt()]; val hi = v[(v.size * 0.98).toInt().coerceAtMost(v.size - 1)]
        if (hi - lo <= 0) return 0.0
        val nb = max(8, ((hi - lo) / bin).toInt())
        val counts = IntArray(nb)
        for (x in v) { val k = ((x - lo) / (hi - lo) * nb).toInt(); if (k in 0 until nb) counts[k]++ }
        val sm = IntArray(nb) { k -> counts[k] + (if (k > 0) counts[k - 1] else 0) + (if (k < nb - 1) counts[k + 1] else 0) }
        val ends = max(sm.take(nb / 5).maxOrNull() ?: 0, sm.drop(nb - nb / 5).maxOrNull() ?: 0)
        val inside = sm.slice(nb / 5 until nb - nb / 5).maxOrNull() ?: 0
        return if (ends == 0) 0.0 else inside.toDouble() / ends
    }
    private fun spread(xs: List<Double>, ys: List<Double>, th: Double): Double {
        val c = cos(th); val s = sin(th); val v = xs.indices.map { xs[it] * c + ys[it] * s }.sorted()
        return v[(v.size * 0.9).toInt().coerceAtMost(v.size - 1)] - v[(v.size * 0.1).toInt()]
    }
    // the spread along [th], 2nd to 98th percentile
    private fun extent(xs: List<Double>, ys: List<Double>, th: Double): Double {
        val c = cos(th); val s = sin(th)
        val v = xs.indices.map { xs[it] * c + ys[it] * s }.sorted()
        return v[(v.size * 0.98).toInt().coerceAtMost(v.size - 1)] - v[(v.size * 0.02).toInt()]
    }
    private fun close(m: BooleanArray, w: Int, h: Int, r: Int): BooleanArray {
        fun morph(a: BooleanArray, dil: Boolean): BooleanArray = BooleanArray(w * h) { p ->
            val x = p % w; val yy = p / w; var res = !dil
            loop@ for (dy in -r..r) for (dx in -r..r) { val xx = x + dx; val y2 = yy + dy
                val v = if (xx in 0 until w && y2 in 0 until h) a[y2 * w + xx] else !dil
                if (dil && v) { res = true; break@loop }; if (!dil && !v) { res = false; break@loop } }
            res
        }
        return morph(morph(m, true), false)
    }
    private fun angDiff(a: Double, b: Double): Double { val d = abs(((a - b) % 180 + 180) % 180); return min(d, 180 - d) }
    private fun rad(d: Double) = d / 180.0 * PI
    private fun deg(r: Double) = r * 180.0 / PI
    private fun range(v: List<Double>): Double { var lo = Double.MAX_VALUE; var hi = -Double.MAX_VALUE; for (x in v) { lo = min(lo, x); hi = max(hi, x) }; return hi - lo }
    private fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
    private fun cross(a: DoubleArray, b: DoubleArray) = doubleArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
    private fun add(a: DoubleArray, b: DoubleArray) = doubleArrayOf(a[0] + b[0], a[1] + b[1], a[2] + b[2])
    private fun sub(a: DoubleArray, b: DoubleArray) = doubleArrayOf(a[0] - b[0], a[1] - b[1], a[2] - b[2])
    private fun scale(a: DoubleArray, k: Double) = doubleArrayOf(a[0] * k, a[1] * k, a[2] * k)
    private fun norm(a: DoubleArray) = sqrt(dot(a, a))
    private fun unit(a: DoubleArray): DoubleArray { val n = norm(a); return if (n == 0.0) a else scale(a, 1 / n) }
}
