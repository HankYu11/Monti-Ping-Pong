package com.monti.servecoach

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * The spin drawing, checked against the ball it draws rather than against the reasoning that
 * produced it. The same arithmetic on the spin page was worked out on paper, looked right, and
 * had the ball turning one way while the chevrons pointed the other: the frame on screen is left
 * handed (y points down), so a rotation built the usual way turns the mirror of what the right
 * hand says. Only tracking the painted dots through the renderer caught it, so that is what runs
 * here — the marks have to agree with the pixels, not with an argument about the pixels.
 */
class SpinBallTest {
    private val n = 160

    /** where the dots of one colour sit in a rendered frame, as a canvas angle from the centre */
    private fun dotAngle(a: DoubleArray, t: Double, pick: (Int, Int, Int) -> Boolean): Double? {
        val px = IntArray(n * n)
        SpinMath.sphere(a, t, n, px)
        var sx = 0.0; var sy = 0.0; var k = 0
        for (i in px.indices) {
            val v = px[i]
            if (v ushr 24 == 0) continue
            val r = (v shr 16) and 255; val g = (v shr 8) and 255; val b = v and 255
            if (pick(r, g, b)) { sx += (i % n).toDouble(); sy += (i / n).toDouble(); k++ }
        }
        if (k < 20) return null
        return atan2(sy / k - n / 2.0, sx / k - n / 2.0)
    }

    private val red = { r: Int, g: Int, b: Int -> r > 90 && r > g * 17 / 10 && r > b * 17 / 10 }
    private val green = { r: Int, g: Int, b: Int -> g > 80 && g > r * 15 / 10 && g > b * 15 / 10 }

    private fun wrap(x: Double): Double {
        var v = x
        while (v > Math.PI) v -= 2 * Math.PI
        while (v < -Math.PI) v += 2 * Math.PI
        return v
    }

    @Test fun `the arrow points the way the face really moves`() {
        // every kind of spin, including the measured serve 3
        val spins = listOf(
            Spin(45.0, 0.0, 0.0, true),          // backspin
            Spin(-45.0, 0.0, 0.0, true),         // topspin
            Spin(0.0, 40.0, 0.0, true),          // sidespin, curving right
            Spin(0.0, -40.0, 0.0, true),         // sidespin, curving left
            Spin(29.9, -30.6, 15.2, true),       // serve 5
            Spin(-21.3, -12.2, 46.3, true))      // serve 3
        for (s in spins) {
            val a = SpinMath.axisOf(s)!!
            // the point you are looking straight at, a moment later
            val moved = SpinMath.turn(a, 0.004, doubleArrayOf(0.0, 0.0, 1.0))
            val gone = doubleArrayOf(moved[0], moved[1])
            val want = SpinMath.faceTravel(a)
            val gl = hypot(gone[0], gone[1]); val wl = hypot(want[0], want[1])
            if (wl < 1e-9) continue                       // pure gyro: the face turns on the spot
            val agree = (gone[0] * want[0] + gone[1] * want[1]) / (gl * wl)
            assertTrue("$s: the arrow and the face disagree (cos $agree)", agree > 0.999)
        }
    }

    @Test fun `backspin rolls the face you can see downward`() {
        val a = SpinMath.axisOf(Spin(45.0, 0.0, 0.0, true))!!
        val moved = SpinMath.turn(a, 0.01, doubleArrayOf(0.0, 0.0, 1.0))
        assertTrue("backspin must roll the near face down (y is down), got ${moved[1]}", moved[1] > 0)
        assertTrue("and not sideways, got ${moved[0]}", Math.abs(moved[0]) < 1e-9)
    }

    @Test fun `the chevrons turn the way the ball turns`() {
        for (gyro in listOf(40.0, -40.0)) {
            val a = SpinMath.axisOf(Spin(0.0, 0.0, gyro, true))!!
            val before = dotAngle(a, 0.0, red) ?: error("no red dot at rest for gyro $gyro")
            val after = dotAngle(a, 0.30, red) ?: error("red dot lost after turning, gyro $gyro")
            // the canvas angle grows clockwise, because y points down
            val clockwise = wrap(after - before) > 0
            assertEquals("gyro $gyro: the ball turns ${if (clockwise) "clockwise" else "anticlockwise"} " +
                "but the chevrons say ${if (SpinMath.chevronsClockwise(a)) "clockwise" else "anticlockwise"}",
                clockwise, SpinMath.chevronsClockwise(a))
        }
    }

    @Test fun `a positive gyro points away down the table, so it reads clockwise`() {
        // the engine's sign: gyro = spin . direction of travel, and the ball travels away from you
        val a = SpinMath.axisOf(Spin(0.0, 0.0, 46.3, true))!!
        assertTrue("positive gyro must read clockwise from behind the server", SpinMath.chevronsClockwise(a))
    }

    @Test fun `every dot of the ball turns together`() {
        // not a tautology: it catches a renderer that mirrors or skews instead of rotating
        val a = SpinMath.axisOf(Spin(0.0, 0.0, 40.0, true))!!
        val r0 = dotAngle(a, 0.0, red)!!; val r1 = dotAngle(a, 0.30, red)!!
        val g0 = dotAngle(a, 0.0, green)!!; val g1 = dotAngle(a, 0.30, green)!!
        assertEquals("the red and green dots must sweep the same angle", wrap(r1 - r0), wrap(g1 - g0), 0.05)
        assertEquals("and that angle is the turn itself", 0.30, Math.abs(wrap(r1 - r0)), 0.03)
    }

    @Test fun `a pure gyro leaves no axle to draw`() {
        val a = SpinMath.axisOf(Spin(0.0, 0.0, 46.3, true))!!
        assertEquals("the axle points straight away from you, so it has no length on screen",
            0.0, hypot(a[0], a[1]), 1e-9)
    }

    @Test fun `no spin is nothing to draw`() {
        assertEquals(null, SpinMath.axisOf(Spin(0.0, 0.0, 0.0, true)))
    }
}
