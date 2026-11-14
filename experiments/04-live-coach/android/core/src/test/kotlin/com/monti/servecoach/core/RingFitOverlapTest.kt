package com.monti.servecoach.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The window the ring's colour fit is made on, and the session where there wasn't one.
 *
 * The chip's bytes and the decoder's are the same picture down two different paths, so the ring
 * needs a gain and an offset per plane before its windows can be used. That fit is made once a
 * session, on one frame of one serve cut the old way, over the pixels the live window and the
 * capture's crop both hold.
 *
 * On 14 Nov all three serves of a session reported the same thing:
 *
 *     "the ring has no colour fit yet: the window and the crop overlap by only -20x340"
 *
 * A negative width: the window sat off the left edge of the crop entirely. The frame to fit on
 * was chosen as the middle of the capture, before anything was known about where that frame's
 * window actually was, and there was no second attempt. So the fit never happened, the ring was
 * never used, and every capture of the session fell back to decode-and-cut at 2.2 to 3.0 s --
 * the ring carried all session for nothing.
 *
 * The numbers below are that session's, read back out of its report.
 */
class RingFitOverlapTest {
    /** serve #1's crop, from the report: x 384, y 382, 1528 x 484 */
    private val RECT = intArrayOf(384, 382, 1528, 484)
    private val SIDE = 352

    @Test fun `the window that session fitted on misses the crop`() {
        // the only placement that yields -20 x 340 for that crop and window size
        val o = Windows.overlap(intArrayOf(12, 526), SIDE, RECT)
        assertEquals("width", -20, o[0])
        assertEquals("height", 340, o[1])
        assertTrue("a negative overlap must not pass the 64 px bar", o[0] < 64)
    }

    @Test fun `a window over the flight overlaps the crop properly`() {
        // the same crop, with the window where the ball actually was: well inside
        for (x in intArrayOf(500, 900, 1400)) {
            val o = Windows.overlap(intArrayOf(x, 500), SIDE, RECT)
            assertEquals("a window inside the crop overlaps by its whole side", SIDE, o[0])
            assertTrue("and by at least the bar vertically", o[1] >= 64)
        }
    }

    @Test fun `picking the largest overlap finds a usable frame among bad ones`() {
        // what fitFrameFor does, over a flight that starts with the bad window above and then
        // crosses the crop. The old code took the middle of the list regardless.
        val windows = listOf(
            intArrayOf(12, 526),      // the one that session chose: misses
            intArrayOf(300, 500),     // creeping in from the left: partial
            intArrayOf(900, 500),     // squarely inside
            intArrayOf(1700, 500),    // running off the right: partial
            intArrayOf(1900, 500))    // nearly out
        var best = -1; var bestArea = 0
        windows.forEachIndexed { i, at ->
            val o = Windows.overlap(at, SIDE, RECT)
            if (o[0] >= 64 && o[1] >= 64 && o[0] * o[1] > bestArea) { bestArea = o[0] * o[1]; best = i }
        }
        assertEquals("the window squarely inside the crop", 2, best)
        assertTrue("and it is a real area to fit on", bestArea >= 64 * 64)
    }

    @Test fun `the overlap is even on both axes, so the colour planes divide`() {
        // the crop's own size is even, but a window can land on an odd pixel
        for (x in 0..40) {
            val o = Windows.overlap(intArrayOf(384 + x, 382 + x), SIDE, RECT)
            assertEquals("width $x", 0, o[0] and 1)
            assertEquals("height $x", 0, o[1] and 1)
        }
    }
}
