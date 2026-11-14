package com.monti.servecoach.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A short track's speed, and the verdict it used to guarantee.
 *
 * speeds() stepped four frames at a time and returned zero below five points. Zero is not "no
 * reading" to anything downstream: notABall's first test is `median < 4.5 * perFrame`, so a
 * zero made every track of four points or fewer "slow (a body?)" -- a ball crossing the picture
 * and a hand on the table, reported identically, from no evidence either way. A phone that
 * cannot keep up makes short tracks by the hundred, which is where this was found.
 */
class SpeedsTest {
    private val tr = ServeTracker(480, 270, 120)

    /** a ball crossing at 8 px a tracker frame, [n] points of it */
    private fun flight(n: Int) = (0 until n).map { floatArrayOf(it.toFloat(), 60f + it * 8f, 100f + it * 0.4f, 3.2f) }

    @Test fun `a long track reads the speed it is moving at`() {
        val s = tr.speeds(flight(20))
        assertEquals("median", 8.0, s[0].toDouble(), 0.3)
    }

    @Test fun `a short track reads the same speed, not zero`() {
        for (n in 2..5) {
            val s = tr.speeds(flight(n))
            assertTrue("$n points gave ${s[0]}, which would read as a body", s[0] > 4f)
            assertEquals("$n points", 8.0, s[0].toDouble(), 0.5)
        }
    }

    @Test fun `one point still has no speed to give`() {
        assertEquals(0f, tr.speeds(flight(1))[0], 1e-6f)
        assertEquals(0f, tr.speeds(emptyList())[0], 1e-6f)
    }

    @Test fun `something that is not moving still reads as not moving`() {
        // the guard against the obvious objection: this must not make everything look fast
        val still = (0 until 6).map { floatArrayOf(it.toFloat(), 200f, 150f, 6f) }
        assertEquals(0f, tr.speeds(still)[0], 1e-6f)
    }
}
