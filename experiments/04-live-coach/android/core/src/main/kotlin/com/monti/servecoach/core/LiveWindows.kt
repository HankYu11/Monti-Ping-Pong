package com.monti.servecoach.core

/**
 * Cutting the ball's window as the frame goes past, instead of decoding the frame again later.
 *
 * A capture costs what it costs because the full-resolution pixels are thrown away while the
 * serve happens and bought back from H.264 afterwards: on the measured sessions, 0.6 to 0.8 s of
 * decoding and 0.3 to 0.8 s of cutting, of a wait of about two seconds. The pixels were on the
 * graphics chip the whole time (GlTap), one frame at a time, and nobody kept them.
 *
 * Keeping whole frames is out of the question -- 1920x1080 in YUV is 3.1 MB and a serve is 170 of
 * them -- but the measurement only ever looks at a box round the ball. At 176 px square that is
 * 121 KB a frame as the chip reads it back, and a second of them is 29 MB.
 *
 * The catch is that the window has to be cut before anyone knows where the ball is. The tracker
 * finds it in a readback that is deliberately two frames late and taken every second frame, so
 * the window goes where the ball is *predicted* to be. [Predict] is that, and the margin the
 * window carries is what it misses by.
 */
/**
 * The size of the window cut live, for a ball of radius [r] (whole-picture pixels).
 *
 * The capture's own window is three radii round where the ball REALLY was (Windows.boxFor),
 * measured when the flight is over. This one goes where the ball is predicted to be, from a
 * track that is still growing, and has to be big enough that the capture's can be cut out of it
 * wherever the prediction missed by -- hence [margin], 72 px, against a worst measured miss of
 * 45 px two tracker frames back.
 *
 * Rounded to 32 so a ball that changes size a little does not make new render targets and a new
 * ring; clamped so a huge or tiny blob cannot ask for an absurd read-back.
 */
fun ringSide(r: Float, margin: Int = 72): Int =
    (Math.ceil(2.0 * (3 * r + margin) / 32.0).toInt() * 32).coerceIn(128, 384)

object Predict {
    /**
     * Where the ball will be at [frame], from track points no newer than [frame] - [lag].
     *
     * Straight-line from the last few points, not a curve. Measured over all 29 flights of the
     * four sessions recorded: fitting a quadratic is worse at every lag, because over the handful
     * of frames that are in reach it fits the tracker's own noise rather than gravity.
     *
     * At a lag of two frames the miss is 4 px in the middle and 41 px at worst; at six frames,
     * 56 px at worst. Those are what the window's margin has to cover.
     *
     * [track] is [frame, x, y, radius] in whatever units the caller uses, oldest first. Returns
     * x, y and the radius, or null when there is too little to go on.
     */
    fun at(track: List<FloatArray>, frame: Float, lag: Float = 2f, use: Int = 3, minPrior: Int = 4): FloatArray? {
        var end = -1
        for (i in track.indices) if (track[i][0] <= frame - lag) end = i else break
        // The size filter below needs a middle value it can trust, and at the start of a track
        // there is none: the hit's first points are the ball merged with the bat, so a median of
        // two or three of them IS the paddle, and the filter then throws the ball away and keeps
        // the paddle. Four points is where that stops: over the seventeen recorded flights the
        // worst miss falls from 90 px to 45 and goes no further however many more are required.
        // The cost is the first few frames of a track having no window, 11% of them, and the
        // engine skips the frames right after the hit anyway.
        if (end + 1 < minPrior) return null
        // A point the size of a paddle is not the ball. The tracker's first points often are one:
        // at the hit the ball merges with the bat and the blob comes out two or three times too
        // big, and a line drawn through those runs a long way off. Over the seventeen recorded
        // flights, leaving them in makes the worst miss 177 px where taking them out makes it 41,
        // which is the difference between a window that holds the ball and one that does not.
        // The engine throws the same points away for the same reason (dropMerged).
        // a plain array, sorted in place: this runs on the frames' thread for every frame of
        // every flight, and boxing a couple of hundred radii 240 times a second is work that
        // thread cannot spare
        val rs = FloatArray(end + 1)
        for (i in 0..end) rs[i] = track[i][3]
        java.util.Arrays.sort(rs)
        // the LOWER of the two middle values when there is an even number of them. What spoils a
        // young track is the ball merged with the bat, which makes blobs bigger, never smaller,
        // so leaning to the smaller middle leans toward the ball. Measured over the seventeen
        // flights: the lower median gives a worst miss of 45 px from four points on, the upper
        // one needs six to get there and places 5% fewer windows, and leaning lower still
        // (the 30th percentile) is worse again at 50.
        val rMed = rs[(rs.size - 1) / 2]
        val usable = ArrayList<FloatArray>(use)
        var i = end
        while (i >= 0 && usable.size < use) {
            val p = track[i]
            if (rMed <= 0f || (p[3] >= 0.5f * rMed && p[3] <= 1.5f * rMed)) usable.add(p)
            i--
        }
        // One point is not a prediction. The ball has moved since it was seen -- 25 px a frame on
        // a quick serve -- so a window placed on it misses by 50 to 100 px, which is worse than
        // no window at all: the engine reads a missing one as background and carries on, where a
        // window in the wrong place costs its bytes and shows findBall half a ball. This happens
        // at the very start of a track, which is also where the engine skips frames anyway.
        if (usable.size < 2) return null
        usable.reverse()
        val pts = usable
        val n = pts.size
        val last = n - 1
        var st = 0.0; var sx = 0.0; var sy = 0.0
        var stt = 0.0; var stx = 0.0; var sty = 0.0
        for (k in 0 until n) {
            val p = pts[k]; val t = p[0].toDouble()
            st += t; sx += p[1]; sy += p[2]
            stt += t * t; stx += t * p[1]; sty += t * p[2]
        }
        val den = n * stt - st * st
        if (den <= 1e-9) { val p = pts[last]; return floatArrayOf(p[1], p[2], p[3]) }
        fun line(s: Double, sts: Double): Double {
            val m = (n * sts - st * s) / den
            return (s - m * st) / n + m * frame
        }
        // the radius is held to what was last seen: a line through three noisy radii runs away
        return floatArrayOf(line(sx, stx).toFloat(), line(sy, sty).toFloat(), pts[last][3])
    }
}

/**
 * The windows cut live, held until a serve asks for them.
 *
 * Keyed by the camera's own frame number, so a capture can ask for exactly the span it covers.
 * Frames the chip was too busy to hand over are simply missing; the engine reads a missing window
 * as background, which is what it does for a frame no track covers.
 *
 * Each window carries its own size. The size is chosen from how big the ball looks, which drifts
 * as the ball flies, and a ring tied to one size has to be thrown away whenever it changes --
 * which in the first session on a phone was most of the flight: 6418 windows cut, 376 still held.
 * The capture can cut a smaller window out of a bigger one, so a mixed ring costs it nothing.
 */
class WindowRing(capacity: Int) {
    private val n = capacity
    private val frame = LongArray(n) { -1L }
    private val at = arrayOfNulls<IntArray>(n)
    private val side = IntArray(n)
    private val pixels = arrayOfNulls<ByteArray>(n)
    private val rows = arrayOfNulls<ByteArray>(n)
    @Volatile var kept = 0L; private set
    @Volatile var dropped = 0L; private set
    /** the size of the last window handed in, for the report */
    @Volatile var lastSide = 0; private set

    /** one frame's window, [s] square at [x], [y] in the whole picture, with its row samples */
    @Synchronized fun put(f: Long, x: Int, y: Int, s: Int, win: ByteArray, rowSample: ByteArray) {
        val i = (f % n).toInt()
        if (frame[i] >= 0 && frame[i] != f) dropped++
        frame[i] = f; at[i] = intArrayOf(x, y); side[i] = s; pixels[i] = win; rows[i] = rowSample
        kept++; lastSide = s
    }

    /** what is held for frames [first, first + count), with nulls for the ones that are not */
    @Synchronized fun take(first: Long, count: Int): Taken {
        val a = arrayOfNulls<IntArray>(count)
        val sd = IntArray(count)
        val p = arrayOfNulls<ByteArray>(count)
        val r = arrayOfNulls<ByteArray>(count)
        var have = 0
        for (k in 0 until count) {
            val f = first + k
            val i = (f % n).toInt()
            if (frame[i] != f) continue
            a[k] = at[i]; sd[k] = side[i]; p[k] = pixels[i]; r[k] = rows[i]; have++
        }
        return Taken(a, sd, p, r, have)
    }

    class Taken(val at: Array<IntArray?>, val sides: IntArray, val pixels: Array<ByteArray?>,
                val rows: Array<ByteArray?>, val have: Int) {
        val count get() = at.size
        /** the share of the asked-for frames that were still held */
        val coverage get() = if (count == 0) 0.0 else have.toDouble() / count
    }
}
