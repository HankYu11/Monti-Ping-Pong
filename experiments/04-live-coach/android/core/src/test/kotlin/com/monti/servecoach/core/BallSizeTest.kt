package com.monti.servecoach.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * How big the tracker thinks the ball is, and the serves that turned on it.
 *
 * The three radius sequences below are measurements, not inventions. Each is one track the
 * shipping tracker built from a serve rendered by `experiments/02-spin-meter/servesim.py`, in
 * the tracker's own quarter-size pixels, dumped straight out of the desktop runner. All three
 * are the SAME ball at the SAME distance: 23.65 px across the picture, so 2.96 here.
 *
 *   backspin 40 rps   median 2.59   percentile 85  3.09     was accepted, by 0.08
 *   gyro 40 rps       median 2.46   percentile 85  2.71     was rejected, by 0.04
 *   full blur         median 2.46   percentile 85  2.93     was rejected, by 0.04
 *
 * A candidate's radius comes from the area of the moving mask, which only ever LOSES pixels to
 * a background the ball happens to match -- so every one of these is an underestimate, and the
 * median is an underestimate of 13 to 17%. Against a 2.5 bar meant to mean "20 px across" that
 * left a real 20 px ball passing or failing on which way its marking was facing when it flew.
 *
 * Spin along the flight line is what made it show: with the axis pointing at the camera the
 * inked half holds still and the blob is steady, but with the axis along the flight the inked
 * half sweeps through the view once a revolution, so the ball is alternately almost all white
 * and almost all dark, and whichever one matches the room drops out.
 */
class BallSizeTest {
    /** gyro 40 rps: the track that was thrown away. 41 points. */
    private val GYRO = listOf(
        2.11f, 2.52f, 2.52f, 2.19f, 2.46f, 2.46f, 2.33f, 2.39f, 2.46f, 1.95f, 2.33f, 1.95f, 2.19f,
        2.39f, 2.11f, 2.19f, 2.46f, 2.65f, 2.59f, 2.76f, 2.71f, 2.65f, 2.82f, 2.82f, 2.99f, 2.33f,
        2.03f, 2.46f, 2.59f, 2.82f, 2.52f, 2.19f, 2.52f, 2.65f, 2.46f, 2.39f, 2.71f, 2.39f, 2.26f,
        2.33f, 2.82f)

    /** backspin 40 rps: the same ball, the same room, kept. 69 points. */
    private val BACKSPIN = listOf(
        2.33f, 2.33f, 2.33f, 2.39f, 2.46f, 2.39f, 2.03f, 2.46f, 2.46f, 2.82f, 2.26f, 2.59f, 2.82f,
        2.26f, 2.52f, 2.65f, 2.59f, 2.76f, 2.33f, 2.65f, 2.11f, 2.46f, 2.59f, 2.03f, 2.03f, 2.59f,
        2.03f, 2.26f, 2.82f, 2.03f, 2.65f, 2.71f, 2.11f, 2.88f, 2.88f, 2.11f, 2.65f, 2.76f, 2.26f,
        2.65f, 2.03f, 2.03f, 2.71f, 2.11f, 2.52f, 2.93f, 2.82f, 2.19f, 3.14f, 3.19f, 2.26f, 3.04f,
        2.59f, 3.04f, 3.29f, 2.76f, 3.14f, 3.19f, 3.09f, 3.14f, 3.29f, 3.09f, 3.09f, 3.14f, 2.33f,
        2.52f, 2.82f, 3.09f, 3.09f)

    /** the same serve shot with the shutter wide open (1/240 s). 50 points. */
    private val BLUR = listOf(
        2.26f, 2.33f, 2.39f, 2.03f, 2.39f, 2.03f, 2.76f, 2.39f, 2.03f, 2.65f, 2.03f, 1.95f, 2.03f,
        2.65f, 1.95f, 2.88f, 2.11f, 2.46f, 2.11f, 2.46f, 2.33f, 2.19f, 2.03f, 2.19f, 2.03f, 2.39f,
        2.39f, 2.88f, 2.59f, 2.03f, 2.71f, 2.03f, 2.99f, 2.76f, 3.04f, 2.99f, 2.71f, 2.93f, 3.14f,
        2.99f, 2.93f, 2.88f, 2.93f, 2.52f, 2.33f, 2.59f, 2.82f, 2.76f, 3.14f, 2.76f)

    private val TRUE_R = 2.96f          // what the ball really was, in these pixels

    /** a straight flight across the picture carrying the given radii, as pushFound takes them */
    private fun flight(radii: List<Float>, at: Int = 20): ServeTracker.Serve? {
        val tr = ServeTracker(480, 270, 120)
        var got: ServeTracker.Serve? = null
        for (i in 0L until 400L) {
            val c = ArrayList<FloatArray>()
            val k = (i - at).toInt()
            if (k in radii.indices) c.add(floatArrayOf(60f + k * 7.5f, 150f + k * 0.4f, radii[k]))
            tr.pushFound(i, c)?.let { got = it }
        }
        return got
    }

    @Test fun `the ball is measured at the top of what was seen, not the middle`() {
        // Every frame is an underestimate, so the middle is an underestimate too. This is the
        // number the fix turns on: if these drift back toward the median the serves below go.
        for ((name, v) in listOf("gyro" to GYRO, "backspin" to BACKSPIN, "blur" to BLUR)) {
            val med = v.sorted()[v.size / 2]
            val top = v.sorted()[((v.size - 1) * 0.85f).toInt()]
            println("%-9s median %.2f (%+.0f%%)  p85 %.2f (%+.0f%%)  of a real %.2f"
                .format(name, med, 100 * (med / TRUE_R - 1), top, 100 * (top / TRUE_R - 1), TRUE_R))
            assert(med < 0.9f * TRUE_R) { "$name: the median was meant to be the low reading" }
            assert(top > 0.9f * TRUE_R) { "$name: percentile 85 should be close to the real ball" }
        }
    }

    @Test fun `a serve whose spin runs along the flight is not thrown away as tiny`() {
        // The one this was written for: 41 frames of a real 20 px ball, rejected because its
        // inked half kept sweeping through the view and taking the blob's area with it.
        val s = flight(GYRO)
        assertNotNull("the gyro serve was dropped again", s)
        assertEquals("it should be the flight, not something near it", 41, s!!.track.size)
    }

    @Test fun `the serves that already worked still work`() {
        assertNotNull("backspin, which passed by 0.08 before", flight(BACKSPIN))
        assertNotNull("the same serve under full blur", flight(BLUR))
    }

    @Test fun `a ball too small for the meter is still refused`() {
        // The bar has to keep meaning something, or this is not a better measurement but a
        // quietly lower threshold. The same flight, the same frames, the same everything --
        // only the ball two thirds the size, 13 px across, well under the 20 the meter wants.
        // Size is the one thing that differs between this and the gyro serve above, so a serve
        // here and none there could only be the size rule.
        assertNull("a 13 px ball was taken as a serve", flight(GYRO.map { it * 0.66f }))
        // and the edge: just under the bar is refused, just over it is not
        assertNull("2.4 is under the 2.5 bar", flight(List(41) { 2.40f }))
        assertNotNull("2.6 is over it", flight(List(41) { 2.60f }))
    }
}
