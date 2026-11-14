package com.monti.servecoach.core

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What losing frames does to a track, and why the load guard drops them evenly.
 *
 * On 14 Nov a session ran at 70 ms a tracker frame against a budget of 4, skipped 5,338 of
 * 22,274 frames -- 24% -- and reported no serve at all. The app sat in Ready and looked well.
 * The rejection it recorded 2,363 times was "short".
 *
 * That is the whole mechanism. A track is finished after [ServeTracker.maxGap] unseen frames,
 * two by default, so a clump of three ends it. Frames were being skipped whenever the finders'
 * queue was full, which is bursty, so a flight did not lose a quarter of its points evenly --
 * it was cut into pieces, and a piece under twelve points is not judged, it is dropped.
 *
 * Losing a quarter of the points is survivable. Losing them three in a row is not. Which is why
 * dropping every OTHER frame on purpose looked like the answer: half the points, but evenly
 * spaced, and a track survives that.
 *
 * It was tried and it is not the answer, and these tests are what is left of finding out. Half
 * the points is half of every FLIGHT's points, and the bars a track is judged by count points:
 * at 120 Hz a flight needs 100 ms to clear twelve, thinned it needs 200, and a fast serve
 * crosses in about 200. Halving the bars to match let a body through on the real footage -- a
 * blob swelling from 16 px to 220 px came back as a serve -- and the sessions agreed: thinning
 * on caught one serve where thinning off caught four. The guard measures and warns now; it does
 * not touch the frames.
 *
 * These stay because they are still true about the tracker, and they are the reason not to try
 * it again: the first two say what clumped loss does, the rest say what it would cost to fix it
 * this way.
 */
class LoadGuardTest {
    private val FIRST = 20L
    private val LAST = 55L                                   // 36 tracker frames of flight

    /** the flight as the finder would hand it over, minus whatever [lost] says was not seen */
    private fun serveWith(lost: (Long) -> Boolean, maxGap: Int = 2): ServeTracker.Serve? {
        val tr = ServeTracker(480, 270, 120)
        tr.maxGap = maxGap
        var got: ServeTracker.Serve? = null
        for (i in 0L until 400L) {
            val c = ArrayList<FloatArray>()
            if (i in FIRST..LAST && !lost(i)) {
                c.add(floatArrayOf(100f + (i - FIRST) * 8.5f, 150f + (i - FIRST) * 0.5f, 3.2f))
            }
            tr.pushFound(i, c)?.let { got = it }
        }
        return got
    }

    @Test fun `a whole flight is a serve`() {
        assertNotNull("the flight with nothing lost", serveWith({ false }))
    }

    @Test fun `losing a quarter of the frames in clumps loses the serve`() {
        // what the queue was doing: three in a row gone, nine seen, over and over. A quarter of
        // the flight, which is what that session lost, but in the shape that ends tracks.
        val clumped = { i: Long -> ((i - FIRST) % 12L) < 3L }
        assertNull("a clump of three ends the track, and the pieces are too short", serveWith(clumped))
    }

    @Test fun `losing every other frame on purpose keeps the serve`() {
        // the guard: half the points, none of the clumps. Eighteen points of the thirty-six,
        // still over the twelve a track needs, and never more than two apart.
        val thinned = { i: Long -> ((i - FIRST) and 1L) == 1L }
        assertNotNull("evenly spaced losses leave a track to judge", serveWith(thinned))
    }

    @Test fun `a short flight survives thinning only if the point bar comes down with it`() {
        // 22 tracker frames: 0.18 s of ball in the air at 120 Hz, a fast serve crossing the
        // picture. Whole, that is 22 points and clears 12. Thinned it is 11, and 11 is under 12
        // -- which is the whole of it: the flight did not get shorter, the sampling did, and a
        // bar counted in points moved with it. (24 frames thinned is exactly 12 and squeaks
        // through, which is how near the edge every fast serve was sitting.)
        val first = 20L; val last = 41L
        fun run(lost: (Long) -> Boolean, minPoints: Int): ServeTracker.Serve? {
            val tr = ServeTracker(480, 270, 120)
            tr.minPoints = minPoints
            tr.maxGap = if (minPoints < 12) 4 else 2
            var got: ServeTracker.Serve? = null
            for (i in 0L until 400L) {
                val c = ArrayList<FloatArray>()
                if (i in first..last && !lost(i)) {
                    c.add(floatArrayOf(100f + (i - first) * 12f, 150f + (i - first) * 0.6f, 3.2f))
                }
                tr.pushFound(i, c)?.let { got = it }
            }
            return got
        }
        val whole = { _: Long -> false }
        val thinned = { i: Long -> ((i - first) and 1L) == 1L }
        assertNotNull("the short flight, every frame seen", run(whole, 12))
        assertNull("thinned, against a bar that did not move, it is thrown away as short",
            run(thinned, 12))
        assertNotNull("thinned, against a bar that halved with the sampling, it is kept",
            run(thinned, 6))
    }

    @Test fun `thinned, one further miss is what maxGap is raised for`() {
        // thinning leaves points exactly two apart, which is the default limit with nothing to
        // spare: one more miss and the gap is four.
        val thinnedPlusOne = { i: Long -> ((i - FIRST) and 1L) == 1L || i == FIRST + 16 }
        assertNull("at the default gap that one miss ends it", serveWith(thinnedPlusOne, maxGap = 2))
        assertNotNull("which is why the guard raises it", serveWith(thinnedPlusOne, maxGap = 4))
    }
}
