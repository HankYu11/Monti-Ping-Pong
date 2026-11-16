package com.monti.servecoach.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the live ring puts its window, and when it first can: [LiveAim].
 *
 * The flight is 15 Nov 2026 23:04 serve 3, the serve that came back unclear on the phone with no
 * window for its first 21 frames. Its track as the phone measured it (whole-picture pixels,
 * capture frames, every second one), turned into the tracker's own units: a quarter the pixels,
 * half the frames.
 */
class LiveAimTest {
    private val flat = floatArrayOf(
        12f, 760.5f, 670.4f, 10.59f, 14f, 792.1f, 662.7f, 14.09f, 16f, 817.1f, 656.2f, 13.91f, 18f, 850f, 651.6f, 13.16f,
        20f, 876.5f, 640.5f, 14.8f, 22f, 903.6f, 639.2f, 12.36f, 24f, 933.1f, 629.7f, 13.91f, 26f, 956.7f, 624.2f, 14.27f,
        28f, 984.1f, 624.4f, 12.15f, 30f, 1011.7f, 615.2f, 14.63f, 32f, 1035f, 614.7f, 13.35f, 34f, 1064.1f, 610.6f, 12.77f,
        36f, 1086f, 605.5f, 13.91f, 38f, 1109.8f, 607.4f, 11.51f, 40f, 1136.2f, 600.7f, 14.27f, 44f, 1183.9f, 600.9f, 11.94f,
        46f, 1206.8f, 597.4f, 13.54f, 48f, 1228.3f, 599.1f, 12.77f, 50f, 1255.1f, 596.6f, 14.09f, 52f, 1264.5f, 596.5f, 14.45f,
        54f, 1285.5f, 599f, 13.35f, 56f, 1311.1f, 598.7f, 13.73f, 58f, 1331f, 599.9f, 14.09f, 60f, 1351.7f, 604.3f, 12.36f,
        62f, 1375.4f, 603.2f, 13.91f, 64f, 1395.1f, 606.9f, 13.16f, 66f, 1415.9f, 610.3f, 12.57f, 68f, 1436f, 610f, 13.91f,
        70f, 1455.4f, 616.4f, 12.57f, 72f, 1479.1f, 618.2f, 13.73f, 74f, 1496f, 622f, 13.16f, 76f, 1516.8f, 628.1f, 12.15f,
        78f, 1536.6f, 629.6f, 13.54f, 80f, 1555.4f, 637.4f, 12.77f, 82f, 1576.2f, 640.5f, 13.73f, 84f, 1594.8f, 646f, 13.54f,
        86f, 1612.1f, 653.9f, 12.15f)
    /** whole-picture pixels and capture frames */
    private val whole = (flat.indices step 4).map { floatArrayOf(flat[it], flat[it + 1], flat[it + 2], flat[it + 3]) }
    /** the tracker's units: half the frames, a quarter the pixels */
    private val ball = whole.map { floatArrayOf(it[0] / 2, it[1] / 4, it[2] / 4, it[3] / 4) }

    private val W = 1920; private val H = 1080
    private val trackerW = W / 4
    private val ringSide = 256          // ringSide(13.4) on the phone
    private val capSide = 82            // the capture's own window (Windows.boxFor), as the phone cut it

    /** where the ball really was at capture frame [f], between the measured points */
    private fun truth(f: Int): FloatArray? {
        for (k in 0 until whole.size - 1) {
            val a = whole[k]; val b = whole[k + 1]
            if (f < a[0] || f > b[0]) continue
            val u = (f - a[0]) / (b[0] - a[0])
            return floatArrayOf(a[1] + u * (b[1] - a[1]), a[2] + u * (b[2] - a[2]))
        }
        return null
    }

    /** does the live window at [at] hold the capture's window round where the ball really was */
    private fun holds(at: IntArray, f: Int): Boolean {
        val t = truth(f) ?: return false
        val x0 = Math.round(t[0] - capSide / 2f); val y0 = Math.round(t[1] - capSide / 2f)
        return x0 >= at[0] && y0 >= at[1] && x0 + capSide <= at[0] + ringSide && y0 + capSide <= at[1] + ringSide
    }

    /**
     * The ring as the phone runs it: at capture frame f the chip knows the track as it stood
     * [lag] tracker frames earlier, published only once it had [points] points. Returns, for
     * every capture frame of the flight, whether a window was cut there and whether it held.
     */
    private fun run(lag: Int, points: Int = LiveAim.MIN_POINTS, others: List<List<FloatArray>> = emptyList()): Map<Int, Boolean?> {
        val out = LinkedHashMap<Int, Boolean?>()
        val first = whole.first()[0].toInt(); val last = whole.last()[0].toInt()
        for (f in first..last) {
            val known = f / 2 - lag
            val live = (listOf(ball) + others).map { t -> t.filter { it[0] <= known } }.filter { it.size >= points }
            val t = LiveAim.pick(live, trackerW, points)
            val at = t?.let { LiveAim.place(it, f / 2f, ringSide, 4f, W, H, points) }
            out[f] = at?.let { holds(it, f) }
        }
        return out
    }

    @Test fun `the first window comes when the track is published, and not before`() {
        // The phone's gap. Nothing can be placed before the ring has heard of the ball: with
        // the track followed from MIN_POINTS points and the track running `lag` tracker frames
        // behind, the first window is 2 x (MIN_POINTS - 1 + lag) camera frames into the flight.
        // On this serve the phone's first fitting window came 21 frames in, which is lag 8 at
        // the four points the ring used to wait for.
        for (lag in listOf(2, 4, 8)) {
            val r = run(lag)
            val first = r.entries.first { it.value != null }.key - whole.first()[0].toInt()
            assertEquals("lag $lag", 2 * (LiveAim.MIN_POINTS - 1 + lag), first)
        }
    }

    @Test fun `once the ball is followed its window holds it`() {
        // Where the window goes is not the problem: at the lag most of 15 Nov's serves ran at,
        // every window cut once the track is followed holds the capture's own window. The 256 px
        // ring leaves 87 px either way round the 82 px capture window.
        for (lag in listOf(2, 4)) {
            val cut = run(lag).values.filterNotNull()
            assertTrue("lag $lag: ${cut.count { !it }} of ${cut.size} windows missed", cut.isNotEmpty() && cut.all { it })
        }
        // At eight frames behind, the most the phone ran, all but the odd one still hold.
        val cut8 = run(8).values.filterNotNull()
        assertTrue("lag 8: ${cut8.count { !it }} of ${cut8.size} missed", cut8.count { it } >= cut8.size * 9 / 10)
    }

    @Test fun `a slow long track does not take the window from the ball`() {
        // an arm swinging for a hundred frames, slow now, ball-sized: longer and further
        // travelled than the young flight, but not going anywhere fast enough to be a ball
        val arm = (0 until 100).map { floatArrayOf(it.toFloat() - 60f, 150f + it * 0.5f, 200f, 3.4f) }
        val r = run(2, others = listOf(arm))
        assertEquals(run(2), r)
        // and a body: fast, but the size of a body, which ServeTracker.notABall refuses too
        val body = ball.map { floatArrayOf(it[0], it[1] - 10f, it[2] + 15f, 12f) }
        assertSame(ball, LiveAim.pick(listOf(body, ball), trackerW))
    }

    @Test fun `too young a track places nothing, and a window stays inside the picture`() {
        assertNull(LiveAim.pick(listOf(ball.take(LiveAim.MIN_POINTS - 1)), trackerW))
        assertNull(LiveAim.place(ball.take(LiveAim.MIN_POINTS - 1), ball[3][0], ringSide, 4f, W, H))
        assertNotNull(LiveAim.place(ball.take(LiveAim.MIN_POINTS), ball[3][0], ringSide, 4f, W, H))
        // run far ahead off the right of the picture: clamped, and even
        val at = LiveAim.place(ball, ball.last()[0] + 200f, ringSide, 4f, W, H)!!
        assertEquals(W - ringSide, at[0])
        assertTrue(at[0] % 2 == 0 && at[1] % 2 == 0 && at[1] in 0..(H - ringSide))
    }

    @Test fun `the tracker publishes tracks as young as the ring follows`() {
        assertEquals(LiveAim.MIN_POINTS, ServeTracker(480, 270, 120).livePoints)
    }
}
