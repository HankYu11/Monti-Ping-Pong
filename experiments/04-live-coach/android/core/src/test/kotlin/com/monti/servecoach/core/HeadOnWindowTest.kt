package com.monti.servecoach.core

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

/**
 * A ball coming at the camera, and whether the window keeps up with it.
 *
 * Every session recorded since the ring was built has been side-on: the flight runs across the
 * picture at 2 to 34 degrees toward the camera, and the ball's size barely changes. The club
 * footage is the other case — the camera at the far end of the table, the ball flying into it at
 * 45 to 62 degrees — and that footage no longer exists; it was deleted with the old unpainted
 * balls, and the session that held it has been reset. What survives is its measurements, from
 * the session that made them:
 *
 *   paddle stretches, head-on:  the radius grew x1.07 to x1.26 over 12 to 22 frames
 *   bounce stretches:           x0.78 to x1.52, M14's bounce 15 -> 23 px over 60 frames
 *
 * That growth is the thing the ring could fail on and nothing else tests, because the window's
 * size comes from the median radius of the track SO FAR. On a growing ball that median is always
 * behind: halfway through a flight that ends 1.5x bigger, the median is the size the ball was a
 * quarter of the way in. If the window falls behind far enough, the capture's own window — three
 * radii round where the ball really was — stops fitting inside it, and the serve is cut the old
 * way.
 *
 * So this walks the geometry: a track whose radius grows at the measured rates, the window sized
 * and placed exactly as the phone sizes and places it, and the capture's window cut out of it
 * exactly as the capture cuts it. It proves nothing about pixels. It proves whether the sizes
 * and the places can still meet.
 */
class HeadOnWindowTest {
    private val frameW = 1920; private val frameH = 1080
    private val step = 2                      // the tracker sees every 2nd frame
    private val scale = step * 2f             // its pixels are a quarter of the picture's
    private val grace = 10                    // frames after the hit the capture does not count

    /**
     * A flight at [deg] toward the camera: the ball crosses less of the picture the more of its
     * motion is into it, and grows by [growth] over [frames] camera frames. Points are the
     * tracker's own — quarter size, every [step]th frame — as ServeTracker publishes them.
     */
    private fun flight(frames: Int, r0: Float, growth: Float, deg: Double): List<FloatArray> {
        val across = Math.cos(Math.toRadians(deg)).toFloat()
        val out = ArrayList<FloatArray>()
        // The tracker's points are a blob's middle, not a ball's: they wobble. A pixel and a
        // half at quarter size is what the recorded tracks carry, and without it the prediction
        // is perfect and the test measures nothing it is meant to.
        val rnd = java.util.Random(7)
        fun jitter() = (rnd.nextGaussian() * 1.5).toFloat()
        var i = 0
        while (i < frames) {
            val u = i.toFloat() / (frames - 1)
            val r = r0 * (1 + (growth - 1) * u)                  // full-picture radius
            // 25 px a frame side-on, scaled by how much of the flight is across the picture
            val x = 200f + across * 25f * i
            val y = 300f + 40f * u + 90f * u * u                 // a little drop
            out.add(floatArrayOf((i / step).toFloat(), x / scale + jitter(), y / scale + jitter(),
                r / scale * (1 + 0.06f * rnd.nextGaussian().toFloat())))
            i += step
        }
        return out
    }

    /** the window the phone would have cut at each frame, with the size rule's own hysteresis */
    private fun ringRun(track: List<FloatArray>, frames: Int): Pair<Array<IntArray?>, IntArray> {
        val at = arrayOfNulls<IntArray>(frames)
        val sides = IntArray(frames)
        var planSide = 0
        for (no in 0 until frames) {
            // the track as the tracker had published it: points up to this frame
            val live = track.filter { it[0] <= no.toFloat() / step }
            if (live.size < 4) continue
            val rs = live.map { it[3] }.sorted()
            val want = ringSide(rs[(rs.size - 1) / 2] * scale)
            val side = if (planSide > 0 && want <= planSide && want > planSide * 6 / 10) planSide else want
            planSide = side
            val p = Predict.at(live, no.toFloat() / step, lag = 0f) ?: continue
            at[no] = intArrayOf(
                Math.round(p[0] * scale - side / 2f).coerceIn(0, frameW - side) and 1.inv(),
                Math.round(p[1] * scale - side / 2f).coerceIn(0, frameH - side) and 1.inv())
            sides[no] = side
        }
        return at to sides
    }

    private fun run(name: String, frames: Int, r0: Float, growth: Float, deg: Double): Double {
        val track = flight(frames, r0, growth, deg)
        val full = track.map { floatArrayOf(it[0] * step, it[1] * scale, it[2] * scale, it[3] * scale) }
        val rect = intArrayOf(0, 0, frameW, frameH)
        val b = Windows.Builder(rect, frames, listOf(full))
        val cap = b.side                                          // boxFor: three radii, median
        val (at, sides) = ringRun(track, frames)
        val lo = full.minOf { it[0] }.toInt() + grace
        val hi = full.maxOf { it[0] }.toInt()
        var need = 0; var got = 0; var worst = 0
        for (i in lo..hi) {
            val place = b.placeOf(i) ?: continue
            need++
            val a = at[i] ?: continue
            val from = sides[i]
            if (cap > from) continue
            val dx = place[0] - a[0]; val dy = place[1] - a[1]
            val d = maxOf(Math.abs(dx - (from - cap) / 2), Math.abs(dy - (from - cap) / 2))
            if (dx >= 0 && dy >= 0 && dx + cap <= from && dy + cap <= from) { got++; if (d > worst) worst = d }
        }
        val share = if (need == 0) 0.0 else got.toDouble() / need
        // The slack is what the whole thing turns on: how far off centre the ball may come out
        // and still have the capture's window cut from the live one. Both windows scale with
        // the same radius, so what is left over is the margin -- and the question this test
        // exists to answer is whether a growing ball eats it.
        val slack = (sides.max() - cap) / 2
        println("%-26s %3d frames r %4.1f->%4.1f (x%.2f) at %2.0f deg: ring %d, capture %d, slack %d px, %d of %d (%.0f%%), worst %d off centre"
            .format(name, frames, r0, r0 * growth, growth, deg, sides.max(), cap, slack, got, need, 100 * share, worst))
        slacks.add(slack)
        return share
    }

    private val slacks = ArrayList<Int>()

    @Test fun `the window keeps up with a ball coming at the camera`() {
        // the two paddle-stretch extremes the club footage measured, at its two extreme angles
        val cases = listOf(
            Triple("paddle, slowest growth", Triple(22, 1.07f, 45.0), 18f),
            Triple("paddle, fastest growth", Triple(12, 1.26f, 62.0), 18f),
            Triple("paddle, long and fast", Triple(40, 1.26f, 62.0), 18f),
            // M14's bounce: 15 -> 23 px over 60 frames, the largest growth of any stretch held
            Triple("M14 bounce", Triple(60, 1.52f, 55.0), 15f),
            // and the same growth on a smaller ball, where the window has less room to spare
            Triple("M14 bounce, small ball", Triple(60, 1.52f, 55.0), 9f),
            // side-on, for the contrast: this is every session since
            Triple("side-on, barely grows", Triple(60, 1.05f, 10.0), 20f))
        var worstShare = 1.0
        for ((name, g, r0) in cases) {
            val (frames, growth, deg) = g
            worstShare = minOf(worstShare, run(name, frames, r0, growth, deg))
        }
        // the capture's own bar: three quarters of the measured flight must have a window it can
        // cut its own out of, or the serve falls back to being cut the old way
        assertTrue("the worst case covered only ${"%.0f".format(100 * worstShare)}% of the flight", worstShare >= 0.75)
        // The real answer. A ball growing by half over a flight does not eat the slack, because
        // the capture's window grows with it and the live one grows with it too -- what is left
        // over is the fixed margin either way. If this ever drops, the margin is being spent
        // somewhere and the head-on case is the first that will fail.
        println("slack across every case: ${slacks.sorted()}")
        assertTrue("the head-on cases left only ${slacks.min()} px of slack", slacks.min() >= 64)
    }

    @Test fun `the size rule grows with the ball and never shrinks on noise`() {
        // it must grow: a bigger ball always gets at least as big a window
        var last = 0
        for (r in 6..60) {
            val s = ringSide(r.toFloat())
            assertTrue("side shrank as the ball grew: $r px -> $s", s >= last)
            assertTrue("the window must hold three radii and the 72 px margin", s >= 2 * (3 * r + 72) || s == 384)
            last = s
        }
        // and it must be steady: a radius wobbling by a pixel must not change it every frame
        val changes = (10..40).count { ringSide(it.toFloat()) != ringSide(it + 1f) }
        assertTrue("the size changes on $changes of 31 one-pixel steps", changes <= 8)
    }
}
