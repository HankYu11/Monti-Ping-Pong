package com.monti.servecoach.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Where the phone stands relative to the table (18 Nov): from the table's outline in a picture,
 * the phone's gravity and the camera's field of view.
 *
 * Why: on 18 Nov the phone stood by the net looking down the table, so serves flew 30-65 degrees
 * toward it. The setup check said "Looks good" (it judges the table's box, not its direction), and
 * the ball's speed and spin were read mostly from its size changing, which is the weakest thing
 * the engine measures. A ball serves along the table's long axis, so the axis tells, before any
 * serve, how far toward the camera the serves will fly.
 *
 * How: the table patch's edge pixels (not those on the picture's border: a cut-off edge is not a
 * table edge) are projected onto the table's plane, which gravity fixes. There perspective is gone
 * and a table's edges, its end lines and the net's shadow in the patch run in two perpendicular
 * directions; the direction that lines the edges up best is found by a scan. The longer of the two
 * extents is the long axis; when they are too close to call, [TablePose.sure] is false.
 *
 * Camera axes are the engine's: x right, y down, z toward the camera (out of the picture).
 */
object TablePose {
    /**
     * [axis]: the table's long axis, a horizontal unit vector in camera axes (sign arbitrary).
     * [towardDeg]: how far a ball flying along that axis heads toward (or away from) the camera,
     * 0 = straight across the picture, 90 = straight at it. [aspect]: the longer extent over the
     * shorter, on the table's plane. [sure]: the long axis could be told from the short one.
     * [edgePoints]: how many edge pixels the fit used.
     */
    class Pose(val axis: DoubleArray, val towardDeg: Double, val aspect: Double, val sure: Boolean, val edgePoints: Int) {
        override fun toString() = "table axis toward ${"%.0f".format(towardDeg)} deg, aspect ${"%.2f".format(aspect)}, " +
            (if (sure) "sure" else "unsure") + ", $edgePoints edge points"
        /** for a capture header or a report: the fields as JSON members (leading comma) */
        fun json() = ",\"tableAxis\":[${axis.joinToString(",") { "%.4f".format(it) }}],\"tableTowardDeg\":${"%.1f".format(towardDeg)}," +
            "\"tableAspect\":${"%.2f".format(aspect)},\"tableSure\":$sure"
    }

    /**
     * The phone's "down" in camera axes, from its accelerometer in landscape with the top of the
     * phone to the left (Android ROTATION_90, iOS landscape right). [ax], [ay], [az]: the device
     * axes' reading, which is +9.8 along the axis pointing up (Android's sign; for iOS Core Motion
     * gravity, pass minus gravity).
     */
    fun downFromAccel(ax: Double, ay: Double, az: Double): DoubleArray = unit(doubleArrayOf(ay, ax, -az))

    /**
     * The pose from a picture. [mask]: the table's pixels (w x h), [down]: gravity in camera axes,
     * [hfovDeg]: the picture's horizontal field of view. Null when too little edge is seen, or the
     * camera looks too far up to see the table's plane.
     */
    fun fromMask(mask: BooleanArray, w: Int, h: Int, down: DoubleArray, hfovDeg: Double, tableBeyond: ((Int, Int) -> Pair<Int, Int>?)? = null,
                 centreLine: List<Int>? = null): Pose? {
        val g = unit(down)
        val f = (w / 2.0) / tan(rad(hfovDeg) / 2)
        // the plane's own axes: e1 ahead (the camera's forward, -z, made level), e2 to the right
        val fwd0 = doubleArrayOf(0.0, 0.0, -1.0)
        val fwd = unit(sub(fwd0, scale(g, dot(fwd0, g))))
        if (norm(fwd) < 0.5) return null                     // looking straight down: no "ahead"
        val right = unit(cross(g, fwd).let { if (dot(it, doubleArrayOf(1.0, 0.0, 0.0)) < 0) scale(it, -1.0) else it })
        val xs = ArrayList<Double>(); val ys = ArrayList<Double>()
        val nx = ArrayList<Double>(); val ny = ArrayList<Double>()      // edge points with the same table just beyond: the net
        netPx.clear()
        fun onPlane(x: Double, y: Double): DoubleArray? {
            val ray = doubleArrayOf((x + 0.5 - w / 2.0) / f, (y + 0.5 - h / 2.0) / f, -1.0)
            val d = dot(ray, g); if (d <= 1e-3) return null
            val pt = scale(ray, 1.0 / d); return doubleArrayOf(dot(pt, fwd), dot(pt, right))
        }
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val p = y * w + x
            if (!mask[p]) continue
            if (mask[p - 1] && mask[p + 1] && mask[p - w] && mask[p + w]) continue   // inside
            if (x <= 1 || y <= 1 || x >= w - 2 || y >= h - 2) continue                // the picture's border
            val ray = doubleArrayOf((x + 0.5 - w / 2.0) / f, (y + 0.5 - h / 2.0) / f, -1.0)
            val down1 = dot(ray, g)
            if (down1 <= 1e-3) continue                       // at or above the horizon
            val pt = scale(ray, 1.0 / down1)                  // on the plane one unit below the camera
            xs.add(dot(pt, fwd)); ys.add(dot(pt, right))
            tableBeyond?.invoke(x, y)?.let { (bx, by) -> onPlane(bx.toDouble(), by.toDouble())?.let { q ->
                val a = doubleArrayOf(xs.last(), ys.last())
                // close on the table's plane: the net's width, not a gap of floor to the next table
                if (kotlin.math.hypot(q[0] - a[0], q[1] - a[1]) < 0.12 * kotlin.math.hypot(a[0], a[1])) { nx.add(a[0]); ny.add(a[1]); netPx.add(y * w + x) }
            } }
        }
        val n = xs.size
        if (n < 40) return null
        // the direction (0-90 degrees: its perpendicular is the other edge family) that piles the
        // edge points onto the fewest lines, judged on both families at once
        val ext = max(range(xs), range(ys))
        if (ext <= 0) return null
        val bin = ext / 120.0
        var bestTh = 0.0; var bestScore = -1.0
        var th = 0.0
        while (th < 90.0) {
            val s = score(xs, ys, rad(th), bin) + score(xs, ys, rad(th + 90.0), bin)
            if (s > bestScore) { bestScore = s; bestTh = th }
            th += 0.5
        }
        // Which family is the end lines: the one with a line inside the table, not only at its two
        // ends -- the net's gap. Seen from by the net the visible table is nearly square (half a
        // table, 1.37 x 1.525 m), so the extents alone can't tell; the net can.
        val a = rad(bestTh); val b = a + PI / 2
        val ea = extent(xs, ys, a); val eb = extent(xs, ys, b)
        val netA = interiorLine(xs, ys, a, bin); val netB = interiorLine(xs, ys, b, bin)
        // the net at the region's edge (half a table in view): its points lie along a line, so
        // they hardly spread along the long axis
        val seen = nx.size >= max(12, n / 25)
        debugNet = "net points ${nx.size} of $n, spread a ${if (nx.size > 2) spread(nx, ny, a) else 0.0} b ${if (nx.size > 2) spread(nx, ny, b) else 0.0}, ea $ea eb $eb"
        val sa = if (seen) spread(nx, ny, a) else 0.0; val sb = if (seen) spread(nx, ny, b) else 0.0
        // the centre line, white inside the table, runs along the long axis
        var centre = Double.NaN
        if (centreLine != null && centreLine.size >= 20) {
            val cx = ArrayList<Double>(); val cy = ArrayList<Double>()
            for (p in centreLine) onPlane((p % w).toDouble(), (p / w).toDouble())?.let { cx.add(it[0]); cy.add(it[1]) }
            if (cx.size >= 20) {
                // the direction the points line up along: least spread across it
                var bt = 0.0; var bs = Double.MAX_VALUE; var t = 0.0
                while (t < 180.0) { val sp = spread(cx, cy, rad(t + 90)); if (sp < bs) { bs = sp; bt = t }; t += 1.0 }
                val along = spread(cx, cy, rad(bt))
                if (along > 6 * bs) centre = rad(bt)       // a line, not a blob
            }
        }
        debugCentre = if (centre.isNaN()) "no centre line" else "centre line ${"%.0f".format(deg(centre))} deg (families ${"%.0f".format(bestTh)} / ${"%.0f".format(bestTh + 90)})"
        fun near(x: Double, y: Double) = abs(((x - y) % PI + PI) % PI).let { min(it, PI - it) } < rad(12.0)
        val long: Double; val sure: Boolean
        when {
            !centre.isNaN() && near(centre, a) -> { long = a; sure = true }
            !centre.isNaN() && near(centre, b) -> { long = b; sure = true }
            seen && sa < 0.5 * sb -> { long = a; sure = true }
            seen && sb < 0.5 * sa -> { long = b; sure = true }
            netA > 0.3 && netA > 2 * netB -> { long = a; sure = true }      // lines across direction a inside: a is the long axis
            netB > 0.3 && netB > 2 * netA -> { long = b; sure = true }
            else -> { long = if (ea >= eb) a else b; sure = max(ea, eb) / max(1e-9, min(ea, eb)) >= 1.6 }
        }
        val aspect = max(ea, eb) / max(1e-9, min(ea, eb))
        run {   // for tools: the plane's axes, the long axis and the region's ends along it
            val c = cos(long); val s2 = sin(long); val al = xs.indices.map { xs[it] * c + ys[it] * s2 }.sorted()
            lastGeometry = "fwd ${fwd.joinToString(",")} right ${right.joinToString(",")} f $f long $long lo ${al[(al.size * 0.02).toInt()]} hi ${al[(al.size * 0.98).toInt().coerceAtMost(al.size - 1)]}"
        }
        // in camera axes; toward = the axis's angle from the plane's "right"
        val axis = unit(add(scale(fwd, cos(long)), scale(right, sin(long))))
        val toward = deg(kotlin.math.asin(min(1.0, abs(cos(long)))))
        return Pose(axis, toward, aspect, sure, n)
    }

    /** the mask of the table [SetupCheck.findTable] found: its colour's pixels, the patch inside its box */
    fun tableMask(y: ByteArray, u: ByteArray, v: ByteArray, w: Int, h: Int, t: SetupCheck.Table): BooleanArray {
        val m = BooleanArray(w * h)
        val x0 = (t.x0 * w).toInt(); val x1 = (t.x1 * w).toInt(); val y0 = (t.y0 * h).toInt(); val y1 = (t.y1 * h).toInt()
        for (yy in y0 until min(h, y1)) for (x in x0 until min(w, x1)) {
            val p = yy * w + x
            m[p] = SetupCheck.colourOf(y[p].toInt() and 0xff, u[p].toInt() and 0xff, v[p].toInt() and 0xff) == t.colour
        }
        // the largest 4-connected patch only (shirts, other tables in the box out)
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

    /** the whole thing from a setup-check picture: the table found, then its pose */
    fun of(y: ByteArray, u: ByteArray, v: ByteArray, w: Int, h: Int, down: DoubleArray, hfovDeg: Double): Pose? {
        val t = SetupCheck.findTable(y, u, v, w, h) ?: return null
        return fromMask(tableMask(y, u, v, w, h, t), w, h, down, hfovDeg)
    }


    // ------------------------------------------------------------------ from the table's white lines
    /**
     * The pose from the table's white lines: its two long edges and the centre line run along the
     * long axis, the end lines across it. Glare turns parts of a table's top pale, so its colour
     * patch can stop well short of its edges (18 Nov club frames); the lines stay white on blue.
     * [y], [u], [v]: full-size planes, w x h (640 x 360 works; 320 x 180 is marginal for 2 cm lines).
     */
    fun fromLines(y: ByteArray, u: ByteArray, v: ByteArray, w: Int, h: Int, down: DoubleArray, hfovDeg: Double): Pose? {
        val n = w * h
        val table = BooleanArray(n) { p -> SetupCheck.colourOf(y[p].toInt() and 0xff, u[p].toInt() and 0xff, v[p].toInt() and 0xff) != null }
        // white pixels with table colour within 4 px: the lines painted on the table, not the wall
        val near = BooleanArray(n)
        val r = 4
        for (yy in 0 until h) for (x in 0 until w) if (table[yy * w + x]) {
            for (dy in -r..r step 2) for (dx in -r..r step 2) { val X = x + dx; val Y = yy + dy; if (X in 0 until w && Y in 0 until h) near[Y * w + X] = true }
        }
        val pts = ArrayList<Int>()
        for (p in 0 until n) {
            val Y = y[p].toInt() and 0xff; val cb = (u[p].toInt() and 0xff) - 128; val cr = (v[p].toInt() and 0xff) - 128
            if (Y > 150 && abs(cb) < 22 && abs(cr) < 22 && near[p] && !table[p]) pts.add(p)
        }
        if (pts.size < 60) return null
        // Hough: 1 px, 0.5 degree
        val nth = 360; val diag = sqrt((w * w + h * h).toDouble()).toInt(); val nr = 2 * diag + 1
        val acc = IntArray(nth * nr)
        val cs = DoubleArray(nth) { cos(rad(it * 0.5)) }; val sn = DoubleArray(nth) { sin(rad(it * 0.5)) }
        for (p in pts) { val x = p % w; val yy = p / w
            for (t in 0 until nth) { val rr = (x * cs[t] + yy * sn[t]).toInt() + diag; acc[t * nr + rr]++ } }
        // the strongest lines, each suppressing its neighbourhood (3 degrees, 6 px)
        val lines = ArrayList<IntArray>()          // theta index, rho, votes
        val minVotes = max(40, w / 12)
        repeat(8) {
            var best = -1; var bv = minVotes - 1
            for (i in acc.indices) if (acc[i] > bv) { bv = acc[i]; best = i }
            if (best < 0) return@repeat
            val t = best / nr; val rr = best % nr
            lines.add(intArrayOf(t, rr - diag, bv))
            for (dt in -6..6) for (dr in -6..6) { val tt = ((t + dt) % nth + nth) % nth; val r2 = rr + dr; if (r2 in 0 until nr) acc[tt * nr + r2] = 0 }
        }
        debugLines = lines.map { it.copyOf() }
        debugPts = pts
        if (lines.size < 2) return null
        val g = unit(down)
        val f = (w / 2.0) / tan(rad(hfovDeg) / 2)
        val fwd0 = doubleArrayOf(0.0, 0.0, -1.0)
        val fwd = unit(sub(fwd0, scale(g, dot(fwd0, g))))
        val right = unit(cross(g, fwd).let { if (dot(it, doubleArrayOf(1.0, 0.0, 0.0)) < 0) scale(it, -1.0) else it })
        // each line as a horizontal direction on the table's plane: its angle from "ahead", 0-180
        class L(val ang: Double, val votes: Int)
        val ls = lines.mapNotNull { (t, rho, votes) ->
            val th = rad(t * 0.5); val c = cos(th); val s = sin(th)
            // two points on x*c + y*s = rho, as rays (x right, y down, z toward the camera)
            val x0 = rho * c - 1000 * s; val y0 = rho * s + 1000 * c; val x1 = rho * c + 1000 * s; val y1 = rho * s - 1000 * c
            val r0 = doubleArrayOf((x0 - w / 2.0) / f, (y0 - h / 2.0) / f, -1.0); val r1 = doubleArrayOf((x1 - w / 2.0) / f, (y1 - h / 2.0) / f, -1.0)
            val d = cross(cross(r0, r1), g)
            if (norm(d) < 1e-9) null else { val dd = unit(d); val a = deg(kotlin.math.atan2(dot(dd, right), dot(dd, fwd))); L(((a % 180) + 180) % 180, votes) }
        }
        // the family (lines within 8 degrees) with the most votes, given at least two lines or a
        // perpendicular partner: the long axis has three (both edges and the centre line)
        var bestAng = 0.0; var bestW = 0; var bestCount = 0
        for (a in ls) {
            val fam = ls.filter { angDiff(it.ang, a.ang) < 8 }
            val wsum = fam.sumOf { it.votes }
            if (fam.size > bestCount || (fam.size == bestCount && wsum > bestW)) { bestCount = fam.size; bestW = wsum
                bestAng = fam.sumOf { it.ang.let { x -> if (angDiff(x, a.ang) == abs(x - a.ang)) x else if (x < a.ang) x + 180 else x - 180 } * it.votes } / wsum }
        }
        val perp = ls.filter { angDiff(it.ang, bestAng + 90) < 10 }
        val sure = bestCount >= 3 || (bestCount >= 2 && perp.isNotEmpty())
        val long = rad(bestAng)
        val axis = unit(add(scale(fwd, cos(long)), scale(right, sin(long))))
        val toward = deg(kotlin.math.asin(min(1.0, abs(cos(long)))))
        return Pose(axis, toward, bestCount.toDouble(), sure, pts.size)
    }
    /** the last [fromLines] call's lines (theta index of 0.5 deg, rho, votes) and line pixels, for tools */
    var debugNet = ""; var debugCentre = ""
    val netPx = HashSet<Int>()
    var debugLines: List<IntArray> = emptyList(); var debugPts: List<Int> = emptyList()
    private fun angDiff(a: Double, b: Double): Double { val d = abs(((a - b) % 180 + 180) % 180); return min(d, 180 - d) }


    // ------------------------------------------------------------------ the table's region, glare and all
    /**
     * The table's top as a region, from a picture with nobody in it (see [median]): the patch
     * [SetupCheck.findTable] finds, grown into pixels of the same hue however pale (glare turns a
     * blue top grey-blue, 18 Nov), but never across a sharp edge. A glare patch fades in; a table's
     * edge, its white line, the net and anything in front of it are sharp, so the region stops there.
     */
    fun tableRegion(y: ByteArray, u: ByteArray, v: ByteArray, w: Int, h: Int): BooleanArray? {
        val sw = 320; val sh = 180
        // the seed: the setup check's table, found on a 320 x 180 copy
        val ys = ByteArray(sw * sh); val us = ByteArray(sw * sh); val vs = ByteArray(sw * sh)
        for (j in 0 until sh) for (i in 0 until sw) { val p = (j * h / sh) * w + i * w / sw; ys[j * sw + i] = y[p]; us[j * sw + i] = u[p]; vs[j * sw + i] = v[p] }
        val t = SetupCheck.findTable(ys, us, vs, sw, sh) ?: return null
        val seedSmall = tableMask(ys, us, vs, sw, sh, t)
        val n = w * h
        val seed = BooleanArray(n) { p -> seedSmall[((p / w) * sh / h) * sw + (p % w) * sw / w] &&
            SetupCheck.colourOf(y[p].toInt() and 0xff, u[p].toInt() and 0xff, v[p].toInt() and 0xff) == t.colour }
        // the seed's hue
        var scb = 0.0; var scr = 0.0; var k = 0
        for (p in 0 until n) if (seed[p]) { scb += (u[p].toInt() and 0xff) - 128; scr += (v[p].toInt() and 0xff) - 128; k++ }
        if (k < 50) return null
        val hue = kotlin.math.atan2(scr / k, scb / k)
        // sharp edges: Sobel on Y, plus a jump in chroma
        val edge = BooleanArray(n)
        fun Y(x: Int, yy: Int) = y[yy * w + x].toInt() and 0xff
        fun U(x: Int, yy: Int) = u[yy * w + x].toInt() and 0xff
        fun V(x: Int, yy: Int) = v[yy * w + x].toInt() and 0xff
        for (yy in 1 until h - 1) for (x in 1 until w - 1) {
            val gx = (Y(x + 1, yy - 1) + 2 * Y(x + 1, yy) + Y(x + 1, yy + 1)) - (Y(x - 1, yy - 1) + 2 * Y(x - 1, yy) + Y(x - 1, yy + 1))
            val gy = (Y(x - 1, yy + 1) + 2 * Y(x, yy + 1) + Y(x + 1, yy + 1)) - (Y(x - 1, yy - 1) + 2 * Y(x, yy - 1) + Y(x + 1, yy - 1))
            val cu = abs(U(x + 1, yy) - U(x - 1, yy)) + abs(U(x, yy + 1) - U(x, yy - 1))
            val cv = abs(V(x + 1, yy) - V(x - 1, yy)) + abs(V(x, yy + 1) - V(x, yy - 1))
            edge[yy * w + x] = sqrt((gx * gx + gy * gy).toDouble()) > EDGE || cu + cv > CHROMA_EDGE
        }
        // grow from the seed through same-hue pixels that are not edges
        val reg = BooleanArray(n); val stack = IntArray(n); var top = 0
        for (p in 0 until n) if (seed[p] && !edge[p]) { reg[p] = true; stack[top++] = p }
        fun like(p: Int): Boolean {
            if (edge[p]) return false
            val cb = (u[p].toInt() and 0xff) - 128.0; val cr = (v[p].toInt() and 0xff) - 128.0
            val m = sqrt(cb * cb + cr * cr)
            if (m < MIN_CHROMA) return false
            var d = abs(kotlin.math.atan2(cr, cb) - hue); if (d > PI) d = 2 * PI - d
            return d < rad(HUE_DEG)
        }
        while (top > 0) {
            val q = stack[--top]; val qx = q % w; val qy = q / w
            for (nb in intArrayOf(if (qx > 0) q - 1 else -1, if (qx < w - 1) q + 1 else -1, if (qy > 0) q - w else -1, if (qy < h - 1) q + w else -1)) {
                if (nb < 0 || reg[nb]) continue
                if (seed[nb] || like(nb)) { reg[nb] = true; stack[top++] = nb }
            }
        }
        // close small holes (the centre line, specks of glare): 2 px dilate then erode
        return close(reg, w, h, 2)
    }
    const val EDGE = 90.0          // Sobel on Y, 0-255 levels: a table's edge is well over, a glare fade under
    const val CHROMA_EDGE = 26
    const val MIN_CHROMA = 3.0
    const val HUE_DEG = 30.0

    private fun close(m: BooleanArray, w: Int, h: Int, r: Int): BooleanArray {
        fun morph(a: BooleanArray, dil: Boolean): BooleanArray = BooleanArray(w * h) { p ->
            val x = p % w; val yy = p / w; var res = !dil
            loop@ for (dy in -r..r) for (dx in -r..r) { val X = x + dx; val Y = yy + dy
                val v = if (X in 0 until w && Y in 0 until h) a[Y * w + X] else !dil
                if (dil && v) { res = true; break@loop }; if (!dil && !v) { res = false; break@loop } }
            res
        }
        return morph(morph(m, true), false)
    }

    /** a picture with nobody in it: each pixel's median over [frames] (each a Y, U, V triple, same size) */
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

    /** the pose from a person-free picture: the table's region (glare included), then [fromMask] */
    fun ofRegion(y: ByteArray, u: ByteArray, v: ByteArray, w: Int, h: Int, down: DoubleArray, hfovDeg: Double): Pose? {
        val m = tableRegion(y, u, v, w, h) ?: return null
        lastRegion = m
        // a table's top is square on its plane; a door curtain or a wall isn't (15 Nov, at home)
        val corner = cornerAngle(m, w, h, down, hfovDeg) ?: return null
        lastCorner = corner
        if (abs(corner - 90) > MAX_CORNER_ERR) return null
        val beyond = { x: Int, yy: Int ->
            var hit: Pair<Int, Int>? = null
            loop@ for (r in 3..14 step 1) for ((dx, dy) in DIRS) {
                val X = x + dx * r; val Y = yy + dy * r
                if (X !in 0 until w || Y !in 0 until h) continue
                val p = Y * w + X
                if (!m[p] && SetupCheck.colourOf(y[p].toInt() and 0xff, u[p].toInt() and 0xff, v[p].toInt() and 0xff) != null) { hit = X to Y; break@loop }
            }
            hit
        }
        return fromMask(m, w, h, down, hfovDeg, beyond, centreLine(y, u, v, w, h, m))
    }
    /**
     * The centre line's pixels: white (bright, no colour) inside the region at least 5 px from its
     * edge, and on the one straight line most of them share (glare specks and a ball lying on the
     * table don't line up).
     */
    private fun centreLine(y: ByteArray, u: ByteArray, v: ByteArray, w: Int, h: Int, m: BooleanArray): List<Int>? {
        val r = 5
        val pts = ArrayList<Int>()
        for (yy in r until h - r) for (x in r until w - r) {
            val p = yy * w + x
            if (!m[p] || !m[p - r] || !m[p + r] || !m[p - r * w] || !m[p + r * w]) continue
            val Y = y[p].toInt() and 0xff; val cb = (u[p].toInt() and 0xff) - 128; val cr = (v[p].toInt() and 0xff) - 128
            // whiter than the table round it: brighter by a margin, chroma near none
            val Yn = ((y[p - 3].toInt() and 0xff) + (y[p + 3].toInt() and 0xff) + (y[p - 3 * w].toInt() and 0xff) + (y[p + 3 * w].toInt() and 0xff)) / 4
            if (Y > Yn + 18 && abs(cb) + abs(cr) < 30) pts.add(p)
        }
        if (pts.size < 20) return null
        val nth = 180; val diag = sqrt((w * w + h * h).toDouble()).toInt(); val nr = 2 * diag + 1
        val acc = IntArray(nth * nr)
        for (p in pts) { val x = p % w; val yy = p / w; for (t in 0 until nth) { val th = rad(t.toDouble()); acc[t * nr + (x * cos(th) + yy * sin(th)).toInt() + diag]++ } }
        var best = 0; for (i in acc.indices) if (acc[i] > acc[best]) best = i
        if (acc[best] < 20) return null
        val th = rad((best / nr).toDouble()); val rho = (best % nr - diag).toDouble()
        return pts.filter { p -> abs((p % w) * cos(th) + (p / w) * sin(th) - rho) < 2.0 }
    }
    /**
     * The angle between the table's two edge families on the table's plane, each found on its own
     * (not forced square): 90 when [down] is right. Checks the phone's gravity, and stands in for it
     * on clips that carry none (the pitch that makes the table square).
     */
    fun cornerAngle(mask: BooleanArray, w: Int, h: Int, down: DoubleArray, hfovDeg: Double): Double? {
        val g = unit(down); val f = (w / 2.0) / tan(rad(hfovDeg) / 2)
        val fwd0 = doubleArrayOf(0.0, 0.0, -1.0); val fwd = unit(sub(fwd0, scale(g, dot(fwd0, g))))
        val right = unit(cross(g, fwd).let { if (dot(it, doubleArrayOf(1.0, 0.0, 0.0)) < 0) scale(it, -1.0) else it })
        val xs = ArrayList<Double>(); val ys = ArrayList<Double>()
        for (y in 2 until h - 2) for (x in 2 until w - 2) {
            val p = y * w + x
            if (!mask[p] || (mask[p - 1] && mask[p + 1] && mask[p - w] && mask[p + w])) continue
            val ray = doubleArrayOf((x + 0.5 - w / 2.0) / f, (y + 0.5 - h / 2.0) / f, -1.0)
            val d = dot(ray, g); if (d <= 1e-3) continue
            val pt = scale(ray, 1.0 / d); xs.add(dot(pt, fwd)); ys.add(dot(pt, right))
        }
        if (xs.size < 40) return null
        val ext = max(range(xs), range(ys)); val bin = ext / 120.0
        val sc = DoubleArray(360) { score(xs, ys, rad(it * 0.5), bin) }
        val t1 = sc.indices.maxByOrNull { sc[it] }!!
        val t2 = sc.indices.filter { angDiff(it * 0.5, t1 * 0.5) > 30 }.maxByOrNull { sc[it] } ?: return null
        return angDiff(t1 * 0.5, t2 * 0.5)
    }
    private val DIRS = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1, 1 to 1, 1 to -1, -1 to 1, -1 to -1)
    /** how far from square the table may look before it isn't taken for one, degrees */
    const val MAX_CORNER_ERR = 10.0
    var lastCorner = Double.NaN
    var lastGeometry = ""
    /** the last region [ofRegion] used, for tools */
    var lastRegion: BooleanArray? = null

    // how strongly the points pile up along lines perpendicular to direction [th]: the sum of the
    // squares of a histogram of their offsets (a few full bins score far above many thin ones)
    private fun score(xs: List<Double>, ys: List<Double>, th: Double, bin: Double): Double {
        val c = cos(th); val s = sin(th)
        val counts = HashMap<Int, Int>()
        for (i in xs.indices) { val k = kotlin.math.floor((-xs[i] * s + ys[i] * c) / bin).toInt(); counts[k] = (counts[k] ?: 0) + 1 }
        var sum = 0.0; for (v in counts.values) sum += v.toDouble() * v
        return sum
    }
    /**
     * How strong a line of edge points is across direction [th] inside the table (between 20% and
     * 80% of the way along), against the stronger of the two at its ends: 0 when there is none.
     */
    private fun interiorLine(xs: List<Double>, ys: List<Double>, th: Double, bin: Double): Double {
        val c = cos(th); val s = sin(th)
        val v = xs.indices.map { xs[it] * c + ys[it] * s }.sorted()
        val lo = v[(v.size * 0.02).toInt()]; val hi = v[(v.size * 0.98).toInt().coerceAtMost(v.size - 1)]
        if (hi - lo <= 0) return 0.0
        val nb = max(8, ((hi - lo) / bin).toInt())
        val counts = IntArray(nb)
        for (x in v) { val k = ((x - lo) / (hi - lo) * nb).toInt(); if (k in 0 until nb) counts[k]++ }
        // smooth over 3 bins: an edge sits across a bin boundary as often as not
        val sm = IntArray(nb) { k -> counts[k] + (if (k > 0) counts[k - 1] else 0) + (if (k < nb - 1) counts[k + 1] else 0) }
        val ends = max(sm.take(nb / 5).maxOrNull() ?: 0, sm.drop(nb - nb / 5).maxOrNull() ?: 0)
        val inside = sm.slice(nb / 5 until nb - nb / 5).maxOrNull() ?: 0
        return if (ends == 0) 0.0 else inside.toDouble() / ends
    }
    private fun spread(xs: List<Double>, ys: List<Double>, th: Double): Double {
        val c = cos(th); val s = sin(th); val v = xs.indices.map { xs[it] * c + ys[it] * s }.sorted()
        return v[(v.size * 0.9).toInt().coerceAtMost(v.size - 1)] - v[(v.size * 0.1).toInt()]
    }
    // the spread of the points along direction [th], trimmed (2nd-98th percentile) against strays
    private fun extent(xs: List<Double>, ys: List<Double>, th: Double): Double {
        val c = cos(th); val s = sin(th)
        val v = xs.indices.map { xs[it] * c + ys[it] * s }.sorted()
        return v[(v.size * 0.98).toInt().coerceAtMost(v.size - 1)] - v[(v.size * 0.02).toInt()]
    }
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
    @Suppress("unused") private fun ang(a: DoubleArray) = atan2(a[1], a[0])
}
