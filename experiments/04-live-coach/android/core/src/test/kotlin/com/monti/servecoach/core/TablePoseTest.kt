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

    private fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
    private fun cross(a: DoubleArray, b: DoubleArray) = doubleArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
    private fun add(a: DoubleArray, b: DoubleArray) = doubleArrayOf(a[0] + b[0], a[1] + b[1], a[2] + b[2])
    private fun scale(a: DoubleArray, k: Double) = doubleArrayOf(a[0] * k, a[1] * k, a[2] * k)
    private fun unit(a: DoubleArray): DoubleArray { val n = sqrt(dot(a, a)); return scale(a, 1 / n) }
}
