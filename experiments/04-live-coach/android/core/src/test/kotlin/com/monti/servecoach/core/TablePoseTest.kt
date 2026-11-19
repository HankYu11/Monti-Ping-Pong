package com.monti.servecoach.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * The table's pose from its outline, checked on tables drawn from a camera whose pose is known:
 * a 2.74 x 1.525 m top, the net's gap across its middle, seen from the side, from by the net as on
 * 18 Nov, and from the end. No real pictures: the club clips show a child.
 */
class TablePoseTest {
    private val w = 320; private val h = 180; private val hfov = 70.7

    private class Cam(val mask: BooleanArray, val down: DoubleArray, val towardDeg: Double)

    /**
     * World: X along the table, Y across it, Z up, the table's top at Z = 0, centred on the origin.
     * The camera at ([cx], [cy], [cz]) looks along heading [yawDeg] (0 = +X), [pitchDeg] down,
     * rolled [rollDeg].
     */
    private fun render(cx: Double, cy: Double, cz: Double, yawDeg: Double, pitchDeg: Double, rollDeg: Double = 0.0): Cam {
        val yaw = Math.toRadians(yawDeg); val p = Math.toRadians(pitchDeg); val r = Math.toRadians(rollDeg)
        val fwd = doubleArrayOf(cos(p) * cos(yaw), cos(p) * sin(yaw), -sin(p))
        var right = unit(cross(fwd, doubleArrayOf(0.0, 0.0, 1.0)))
        var dn = cross(fwd, right)
        // roll about the forward axis
        val r2 = add(scale(right, cos(r)), scale(dn, sin(r))); val d2 = add(scale(dn, cos(r)), scale(right, -sin(r)))
        right = r2; dn = d2
        val f = (w / 2.0) / tan(Math.toRadians(hfov) / 2)
        val mask = BooleanArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val rx = (x + 0.5 - w / 2.0) / f; val ry = (y + 0.5 - h / 2.0) / f
            val ray = add(add(scale(right, rx), scale(dn, ry)), fwd)
            if (ray[2] >= -1e-6) continue
            val t = -cz / ray[2]
            val X = cx + t * ray[0]; val Y = cy + t * ray[1]
            mask[y * w + x] = abs(X) <= 1.37 && abs(Y) <= 0.7625 && abs(X) > 0.03
        }
        // gravity in camera axes (x right, y down, z toward the camera)
        val g = doubleArrayOf(0.0, 0.0, -1.0)
        val down = doubleArrayOf(dot(g, right), dot(g, dn), -dot(g, fwd))
        val toward = Math.toDegrees(kotlin.math.asin(abs(cos(yaw))))
        return Cam(mask, down, toward)
    }

    private fun check(name: String, c: Cam, tol: Double = 6.0): TablePose.Pose {
        val pose = TablePose.fromMask(c.mask, w, h, c.down, hfov)
        assertNotNull("$name: no pose", pose)
        println("$name: want toward ${"%.0f".format(c.towardDeg)}, got $pose")
        assertEquals("$name: toward angle", c.towardDeg, pose!!.towardDeg, tol)
        return pose
    }

    @Test fun `side on, level with the net`() {
        val p = check("side on", render(0.0, -2.2, 0.55, 90.0, 12.0))
        assertTrue("side on: the long axis should be sure", p.sure)
    }

    @Test fun `side on, a little off the net and pitched down`() {
        check("side, off centre", render(-0.6, -2.0, 0.7, 80.0, 20.0))
    }

    @Test fun `past the net looking down the table, as on 18 Nov`() {
        // the net down the right of the picture, the server's half beyond it
        val p = check("18 Nov", render(0.9, -1.2, 0.35, 150.0, 10.0))
        assertTrue("18 Nov: the net should make it sure", p.sure)
    }

    @Test fun `right by the net, the net out of view, is unsure rather than wrong`() {
        // half a table seen from beside the net: nearly square, nothing to tell its long side by
        val pose = TablePose.fromMask(render(0.15, -1.1, 0.3, 145.0, 10.0).let { it.mask }, w, h, render(0.15, -1.1, 0.3, 145.0, 10.0).down, hfov)
        assertNotNull(pose); println("net out of view: $pose")
        assertTrue("with no net in view it must not claim to be sure", !pose!!.sure)
    }

    @Test fun `from the end`() {
        check("end on", render(3.6, 0.0, 0.7, 180.0, 15.0), tol = 8.0)
    }

    @Test fun `a slightly rolled phone does not move the answer much`() {
        check("rolled 4 deg", render(0.0, -2.2, 0.55, 90.0, 12.0, rollDeg = 4.0), tol = 8.0)
    }

    @Test fun `gravity from an Android accelerometer, level in landscape`() {
        // top of the phone to the left: the device's x axis points up, so it reads +9.8 there
        val d = TablePose.downFromAccel(9.81, 0.0, 0.0)
        assertEquals(0.0, d[0], 1e-9); assertEquals(1.0, d[1], 1e-9); assertEquals(0.0, d[2], 1e-9)
        // pitched down: the back camera looks below level, so "down" leans away from the camera (-z)
        val p = Math.toRadians(20.0)
        val d2 = TablePose.downFromAccel(9.81 * cos(p), 0.0, 9.81 * sin(p))
        assertTrue("pitched down: down has a -z part, got ${d2.toList()}", d2[2] < -0.3)
    }


    // ------------------------------------------------------------------ drawn pictures (Y, U, V)
    private val pw = 640; private val ph = 360

    /**
     * A club scene as Y, U, V planes from a camera at ([cx], [cy], [cz]), heading [yawDeg], pitched
     * [pitchDeg] down: a grey wall and floor, a blue table with 2 cm white edges and a centre line,
     * a soft glare patch on its top, the net standing on it, a second table 4 m behind. [curtain]:
     * a blue curtain on the far wall instead of any table (the 15 Nov home sessions). [person]:
     * a dark figure standing at X = [person] across the view (for the median).
     */
    private fun scene(cx: Double, cy: Double, cz: Double, yawDeg: Double, pitchDeg: Double,
                      glare: Boolean = true, curtain: Boolean = false, person: Double? = null): Array<ByteArray> {
        val yaw = Math.toRadians(yawDeg); val p = Math.toRadians(pitchDeg)
        val fwd = doubleArrayOf(cos(p) * cos(yaw), cos(p) * sin(yaw), -sin(p))
        val right = unit(cross(fwd, doubleArrayOf(0.0, 0.0, 1.0))); val dn = cross(fwd, right)
        val f = (pw / 2.0) / tan(Math.toRadians(hfov) / 2)
        val Y = ByteArray(pw * ph); val U = ByteArray(pw * ph); val V = ByteArray(pw * ph)
        fun put(i: Int, y: Int, u: Int, v: Int) { Y[i] = y.toByte(); U[i] = u.toByte(); V[i] = v.toByte() }
        for (yy in 0 until ph) for (x in 0 until pw) {
            val i = yy * pw + x
            val rx = (x + 0.5 - pw / 2.0) / f; val ry = (yy + 0.5 - ph / 2.0) / f
            val ray = add(add(scale(right, rx), scale(dn, ry)), fwd)
            put(i, 200, 128, 128)                                       // the wall
            if (ray[2] < 0) put(i, 95, 120, 140)                        // the floor, brown-grey
            if (curtain) {                                              // a blue curtain on the wall at Y = +3 m
                if (ray[1] > 1e-6) { val t = (3.0 - cy) / ray[1]; val X = cx + t * ray[0]; val Z = cz + t * ray[2]
                    if (abs(X) < 0.5 && Z in -0.6..1.3) put(i, 120, 165, 108) }
                continue
            }
            // the net: the plane X = 0, |Y| < 0.9125, 0 < Z < 0.1525 (above the table top at Z = 0)
            var tNet = Double.MAX_VALUE
            if (abs(ray[0]) > 1e-9) { val t = -cx / ray[0]
                if (t > 0) { val Yn = cy + t * ray[1]; val Zn = cz + t * ray[2]; if (abs(Yn) < 0.9125 && Zn in 0.0..0.1525) tNet = t } }
            for ((oy, top) in listOf(4.0 to false, 0.0 to true)) {   // the far table first, the near one over it
                if (ray[2] >= -1e-6) continue
                val t = -cz / ray[2]
                if (t > tNet) continue
                val X = cx + t * ray[0]; val Yw = cy + t * ray[1] - oy
                if (abs(X) <= 1.37 && abs(Yw) <= 0.7625) {
                    var yv = 70; var uv = 165; var vv = 110                    // blue top
                    if (glare && top) { val d = kotlin.math.hypot(X + 0.7, Yw); val k = kotlin.math.max(0.0, 1 - d / 0.6)
                        yv += (90 * k).toInt(); uv -= (25 * k).toInt(); vv += (12 * k).toInt() }
                    val edge = abs(X) > 1.37 - 0.02 || abs(Yw) > 0.7625 - 0.02 || abs(Yw) < 0.008
                    if (edge) { yv = 225; uv = 128; vv = 128 }
                    put(i, yv, uv, vv)
                }
            }
            if (tNet < Double.MAX_VALUE) put(i, 35, 128, 128)            // the net, dark
            person?.let { px ->                                           // a figure standing at X = px, Y = 0
                val t = ((px - cx) * ray[0] + (0.0 - cy) * ray[1]) / (ray[0] * ray[0] + ray[1] * ray[1])
                val Xp = cx + t * ray[0] - px; val Yp = cy + t * ray[1]; val Zp = cz + t * ray[2]
                if (t > 0 && kotlin.math.hypot(Xp, Yp) < 0.25 && Zp in -0.8..0.9) put(i, 50, 140, 150)
            }
        }
        return arrayOf(Y, U, V)
    }
    private fun down(pitchDeg: Double): DoubleArray { val p = Math.toRadians(pitchDeg); return doubleArrayOf(0.0, cos(p), -sin(p)) }

    @Test fun `a drawn table from the side, glare and all`() {
        val pic = scene(0.0, -2.2, 0.55, 90.0, 12.0)
        val pose = TablePose.analyse(listOf(pic), pw, ph, down(12.0), hfov)
        println("drawn side on: $pose"); assertNotNull(pose)
        assertEquals(0.0, pose!!.towardDeg, 6.0); assertTrue("side on should be sure, got $pose", pose.sure)
        assertEquals("square on its plane", 90.0, pose.cornerDeg, 4.0)
    }

    @Test fun `a drawn table past the net, as on 18 Nov, told by its centre line`() {
        val pic = scene(0.9, -1.2, 0.35, 150.0, 10.0)
        val pose = TablePose.analyse(listOf(pic), pw, ph, down(10.0), hfov)
        println("drawn 18 Nov: $pose"); assertNotNull(pose)
        assertEquals(60.0, pose!!.towardDeg, 7.0); assertTrue("should be sure, got $pose", pose.sure)
    }

    @Test fun `glare does not stop the region short of the table's edges`() {
        val pic = scene(0.0, -2.2, 0.55, 90.0, 12.0)
        val reg = TablePose.tableRegion(pic[0], pic[1], pic[2], pw, ph)!!
        // the glare's centre, on the table at X = -0.7, Y = 0: find its pixel and check it is in the region
        val yaw = Math.toRadians(90.0); val p = Math.toRadians(12.0)
        val fwd = doubleArrayOf(cos(p) * cos(yaw), cos(p) * sin(yaw), -sin(p))
        val right = unit(cross(fwd, doubleArrayOf(0.0, 0.0, 1.0))); val dn = cross(fwd, right)
        val pt = doubleArrayOf(-0.7 - 0.0, 0.0 - (-2.2), 0.0 - 0.55)
        val z = dot(pt, fwd); val f = (pw / 2.0) / tan(Math.toRadians(hfov) / 2)
        val x = (pw / 2.0 + f * dot(pt, right) / z).toInt(); val y = (ph / 2.0 + f * dot(pt, dn) / z).toInt()
        assertTrue("the glare's centre ($x, $y) should be table", reg[y * pw + x])
    }

    @Test fun `a blue curtain on the wall is not a table`() {
        val pic = scene(0.0, 0.0, 0.0, 90.0, 0.0, curtain = true)
        assertEquals(null, TablePose.analyse(listOf(pic), pw, ph, down(0.0), hfov))
    }

    @Test fun `a curtain reaching above the horizon is not a table, whatever its shape`() {
        // the phone held level (pitch 0): the curtain's top half is above the horizon
        val pic = scene(0.0, 0.0, 0.0, 90.0, 0.0, curtain = true)
        val reg = TablePose.tableRegion(pic[0], pic[1], pic[2], pw, ph)
        println("curtain region: ${reg?.count { it } ?: 0} px")
        assertEquals(null, TablePose.ofPicture(pic[0], pic[1], pic[2], pw, ph, down(0.0), hfov))
    }

    @Test fun `the median takes out someone walking past`() {
        val frames = listOf(-1.0, -0.3, 0.4, 9.0, 9.0).map { scene(0.0, -2.2, 0.55, 90.0, 12.0, person = if (it > 5) null else it) }
        val m = TablePose.median(frames)
        val clean = scene(0.0, -2.2, 0.55, 90.0, 12.0)
        var diff = 0; for (i in m[0].indices) if (m[0][i] != clean[0][i]) diff++
        // three of five frames have the figure, each in a different place: the median keeps none of it
        assertTrue("$diff pixels differ from the empty scene", diff < 50)
    }

    @Test fun `face the side needs two agreeing looks, and a band to leave it`() {
        val good = SetupCheck.Advice(SetupCheck.Kind.GOOD, "Looks good.")
        val pose18 = TablePose.analyse(listOf(scene(0.9, -1.2, 0.35, 150.0, 10.0)), pw, ph, down(10.0), hfov)!!
        val side = TablePose.analyse(listOf(scene(0.0, -2.2, 0.55, 90.0, 12.0)), pw, ph, down(12.0), hfov)!!
        val c = SetupCheck.SideCheck()
        c.look(pose18); assertEquals("one look is not enough", SetupCheck.Kind.GOOD, SetupCheck.withPose(good, c).kind)
        c.look(null); c.look(pose18)
        assertEquals("a failed look between changes nothing", SetupCheck.Kind.END_ON, SetupCheck.withPose(good, c).kind)
        c.look(side); assertEquals("one side-on look doesn't undo it", SetupCheck.Kind.END_ON, SetupCheck.withPose(good, c).kind)
        c.look(side); assertEquals(SetupCheck.Kind.GOOD, SetupCheck.withPose(good, c).kind)
        val dark = SetupCheck.Advice(SetupCheck.Kind.DARK, "dark")
        c.look(pose18); c.look(pose18)
        assertEquals("other advice stands", SetupCheck.Kind.DARK, SetupCheck.withPose(dark, c).kind)
        c.reset(); assertEquals("the phone moved: start again", SetupCheck.Kind.GOOD, SetupCheck.withPose(good, c).kind)
    }

    @Test fun `a pose's JSON never holds NaN`() {
        val m = render(0.0, -2.2, 0.55, 90.0, 12.0)
        val p = TablePose.fromMask(m.mask, w, h, m.down, hfov)!!          // no corner measured: NaN
        val j = p.json()
        assertTrue("JSON must not say NaN: $j", !j.contains("NaN") && !j.contains("Infinity"))
        assertTrue(j.contains("\"cornerDeg\":null"))
        assertEquals("[0.0000,1.0000,0.0000]", TablePose.gravityJson(doubleArrayOf(0.0, 2.0, 0.0)))
    }

    @Test fun `a look says why it found no table`() {
        val look = TablePose.look(listOf(scene(0.0, 0.0, 0.0, 90.0, 0.0, curtain = true)), pw, ph, down(0.0), hfov)
        println("curtain: $look")
        assertEquals(null, look.pose); assertTrue(look.why.isNotEmpty())
    }

    private fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
    private fun cross(a: DoubleArray, b: DoubleArray) = doubleArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
    private fun add(a: DoubleArray, b: DoubleArray) = doubleArrayOf(a[0] + b[0], a[1] + b[1], a[2] + b[2])
    private fun scale(a: DoubleArray, k: Double) = doubleArrayOf(a[0] * k, a[1] * k, a[2] * k)
    private fun unit(a: DoubleArray): DoubleArray { val n = sqrt(dot(a, a)); return scale(a, 1 / n) }
}
