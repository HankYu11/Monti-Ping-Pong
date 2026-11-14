package com.monti.servecoach.core

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Spots serves as the frames arrive. The spin meter's flight search (engine.js findFlights), at a
 * quarter of the picture's size each way and frame by frame: moving pixels against a rolling
 * background, ball-sized round blobs, linked into tracks; a track that flies like a serve (not a
 * toss, a drop, a flicker or a jittery spot) is a serve. When a serve's flight ends (and the
 * bounce had its chance to follow), it reports which frames and which part of the picture to
 * hand to the engine.
 *
 * Feed it every frame in order with [push]; it returns a [Serve] when one is complete.
 */
class ServeTracker(val w: Int, val h: Int, private val fps: Int = 240) {
    /** A finished serve: frames [first, last] (as pushed) and the box [x0, y0, x1, y1] in quarter-size pixels. */
    data class Serve(val first: Long, val last: Long, val box: FloatArray, val ballRadius: Float, val tracks: Int, val why: String,
                     /** the flight's track, [frame, x, y, r] at the tracker's rate and size, and the tracks near it (the bounce, the toss) */
                     val track: List<FloatArray> = emptyList(), val near: List<List<FloatArray>> = emptyList(),
                     /** the share of the picture moving while the ball flew: median and largest */
                     val moving: FloatArray = floatArrayOf(0f, 0f)) {
        /**
         * The tracks for the engine (the header's "track" and "near"): frames counted from the
         * capture's first ([f0], a whole frame; [step] whole frames per tracker frame), points in whole-picture pixels.
         *
         * Points outside the capture's [n] frames are left out. The engine drops them anyway --
         * it cannot look at a frame it does not have -- and leaving them in makes the header
         * disagree with the capture it describes, which is how a shifted track went unnoticed
         * through 0.10.4 and 0.10.5. The start is trimmed to the tracks and then trimmed again
         * if the frames would not fit in 300 MB, and that second trim counts from the main
         * track alone, so a near track can begin before the capture does. Clipping here means
         * the header is consistent however the span was arrived at, rather than every trim
         * having to remember to keep it so.
         */
        fun tracksJson(step: Int, scale: Float, f0: Long, n: Int = Int.MAX_VALUE): String {
            fun one(t: List<FloatArray>) = t.mapNotNull { p ->
                val f = (p[0] * step - f0).toLong()
                if (f < 0 || f >= n) null
                else "[$f,${"%.1f".format(java.util.Locale.US, p[1] * scale)},${"%.1f".format(java.util.Locale.US, p[2] * scale)},${"%.2f".format(java.util.Locale.US, p[3] * scale)}]"
            }.joinToString(",", "[", "]")
            return ""","track":${one(track)},"near":${near.joinToString(",", "[", "]") { one(it) }}"""
        }
    }

    // the engine's numbers, at quarter size (it searches at half size)
    private val sampleEvery = 24                       // a background sample every 24 frames (0.1 s)
    private val samplesKept = 5                        // the background: the median of the last 5 (0.5 s)
    /**
     * Points a track needs before it is judged at all.
     *
     * A COUNT, not a duration -- which is fine while every tracker frame is looked at, and wrong
     * the moment they are not. Under the load guard only every other one is, so the same flight
     * gives half the points and this bar quietly asks for twice the flight: 100 ms of ball in
     * the air becomes 200 ms, and a fast serve crosses the picture in about 200. The first
     * session with thinning working caught one serve and threw away 1,102 tracks as "short".
     * The caller halves it while it is thinning, which keeps the bar where it was in milliseconds.
     */
    @Volatile var minPoints = 12
    private val chromaGain = 1.5f

    private class Sample(val y: FloatArray, val u: FloatArray, val v: FloatArray)
    private val samples = ArrayDeque<Sample>()
    private var bgY: FloatArray? = null
    private var bgU: FloatArray? = null
    private var bgV: FloatArray? = null
    private var frameNo = 0L

    /** [frame, x, y, r] */
    private class Track {
        val pts = ArrayList<FloatArray>()
        fun last() = pts[pts.size - 1]
        fun first() = pts[0]
        /** as the engine ranks serves: frames times the ball's size */
        var score = 0f
    }
    private var active = ArrayList<Track>()
    private val done = ArrayList<Track>()                 // finished good tracks waiting to be grouped into a serve
    private val recent = ArrayDeque<Track>()              // finished tracks of any kind, for the toss before a flight
    private var groupDeadline = -1L
    private var bestScore = 0f

    /** time spent (ns) in: background, mask, opening, blobs, (mask+opening+blobs), linking */
    val nanos = LongArray(6)
    private fun lap(k: Int, t: Long): Long { val n = System.nanoTime(); nanos[k] += n - t; return n }

    // counters for the report
    var framesSeen = 0L; private set
    var candidatesSeen = 0L; private set
    val rejected = HashMap<String, Int>()

    /** a group of good tracks that the last checks turned down: its frames [first, last] (as pushed) and why */
    var onSkipped: ((Long, Long, String) -> Unit)? = null

    /** for tests: every finished track of 8+ frames and why it isn't a serve (null: it is) */
    var onTrack: ((List<FloatArray>, String?) -> Unit)? = null

    /**
     * The next frame. [index]: its number at the tracker's rate (frames skipped when the phone falls
     * behind leave gaps; tracks bridge up to 2).
     */
    fun push(q: Quarter, index: Long = frameNo): Serve? {
        require(q.w == w && q.h == h)
        val i = index
        frameNo = index + 1
        framesSeen++
        var t = System.nanoTime()
        if (i % sampleEvery == 0L) addSample(q)
        t = lap(0, t)
        val cands = if (bgY != null && samples.size >= 3) candidates(q) else emptyList()
        candidatesSeen += cands.size
        (cands as? Found)?.let { movingAt[(i % movingAt.size).toInt()] = it.moving }
        t = System.nanoTime()
        link(i, cands)
        val s = maybeServe(i)
        lap(5, t)
        return s
    }

    // ------------------------------------------------------------ background
    private fun addSample(q: Quarter) {
        samples.addLast(Sample(q.y.copyOf(), q.u.copyOf(), q.v.copyOf()))
        while (samples.size > samplesKept) samples.removeFirst()
        val ys = samples.map { it.y }; val us = samples.map { it.u }; val vs = samples.map { it.v }
        bgY = medianOf(ys); bgU = medianOf(us); bgV = medianOf(vs)
        if (samples.size >= 3) { bgNow = Background(bgY!!, bgU!!, bgV!!); bgSamples = samples.size; bgVersion++ }
    }

    /**
     * Which background [background] is on, and how many frames it is the median of.
     *
     * The graphics chip can cut the mask itself, but only against a background it has been
     * handed, and only one that is whole: the median of five integral samples is itself an
     * integer and survives the trip through a byte texture, the median of four is a half and
     * does not. The version is what says whether a mask the chip cut was cut against the same
     * background a finder would have used -- which is the only fair way to compare the two.
     */
    @Volatile var bgVersion = 0; private set
    @Volatile var bgSamples = 0; private set

    private fun medianOf(frames: List<FloatArray>): FloatArray {
        val n = frames.size
        val out = FloatArray(w * h)
        if (n == 5) {                                    // the usual case: a fixed little sorting network
            val a = frames[0]; val b = frames[1]; val c = frames[2]; val d = frames[3]; val e = frames[4]
            for (p in 0 until w * h) out[p] = median5(a[p], b[p], c[p], d[p], e[p])
            return out
        }
        val buf = FloatArray(n)
        for (p in 0 until w * h) {
            for (k in 0 until n) buf[k] = frames[k][p]
            buf.sort(0, n)
            out[p] = if (n % 2 == 1) buf[n / 2] else 0.5f * (buf[n / 2 - 1] + buf[n / 2])
        }
        return out
    }

    private val five = FloatArray(5)
    private fun median5(a: Float, b: Float, c: Float, d: Float, e: Float): Float {
        val t = five
        t[0] = a; t[1] = b; t[2] = c; t[3] = d; t[4] = e
        for (i in 1 until 5) { val x = t[i]; var j = i - 1; while (j >= 0 && t[j] > x) { t[j + 1] = t[j]; j-- }; t[j + 1] = x }
        return t[2]
    }

    /** Warm the background up from frames spread over a clip (tests; live, it is always warm). */
    fun prime(spread: List<Quarter>) { spread.takeLast(samplesKept).forEach { addSample(it) } }

    // ------------------------------------------------------------ candidates in one frame
    private val detector = Detector(w, h)
    /**
     * Tracker frames a track may go unseen before it is finished.
     *
     * Two, normally: the finder misses the ball now and then and a track should survive it. But
     * when the app cannot keep up it is fed every OTHER tracker frame on purpose (LivePipeline's
     * load guard), and then consecutive points are two apart by design, right on the limit --
     * one further miss and every track dies. The caller raises it while it is thinning.
     */
    @Volatile var maxGap = 2
    /** blobs the finder turned away, by rule: too small, too big, too long, too hollow */
    val refusedBlobs: LongArray get() = detector.refused
    /** the longest/shortest ratio of the worst blob turned away for being too long */
    val worstAspect: Float get() = detector.worstAspect
    private val movingAt = FloatArray(1024)            // the share of the picture moving, by frame

    /** The background as it is now, for finding candidates on other threads ([Detector.find]). */
    fun background(): Background? = bgNow
    @Volatile private var bgNow: Background? = null

    private fun candidates(q: Quarter): List<FloatArray> {
        val t = System.nanoTime()
        val out = detector.find(q, background()!!)
        nanos[1] += detector.nanos[0]; nanos[2] += detector.nanos[1]; nanos[3] += detector.nanos[2]
        detector.nanos.fill(0)
        lap(4, t)
        return out
    }

    /**
     * For finding candidates on other threads: call with every frame, in order, before taking its
     * [background] (it learns the background from every 24th), then [pushFound] its candidates.
     */
    fun sample(q: Quarter, index: Long) { if (needsFrame(index)) addSample(q) }

    /** whether [sample] learns from this frame (else it needn't be shrunk before its blobs are found) */
    fun needsFrame(index: Long) = index % sampleEvery == 0L

    /** The next frame, its candidates found elsewhere (see [sample]). */
    fun pushFound(index: Long, cands: List<FloatArray>): Serve? {
        val i = index
        frameNo = index + 1
        framesSeen++
        candidatesSeen += cands.size
        (cands as? Found)?.let { movingAt[(i % movingAt.size).toInt()] = it.moving }
        link(i, cands)
        return maybeServe(i)
    }

    // ------------------------------------------------------------ tracks
    /** As the engine: best matches first (near the predicted spot, similar size); a track ends after 2 missed frames. */
    private fun link(i: Long, cs: List<FloatArray>) {
        val pairs = ArrayList<Triple<Float, Int, Int>>()
        for ((ti, tr) in active.withIndex()) {
            val l = tr.last()
            var px = l[1]; var py = l[2]
            if (tr.pts.size > 1) {
                val p0 = tr.pts[tr.pts.size - 2]
                val df = max(1f, l[0] - p0[0])
                px = l[1] + (l[1] - p0[1]) / df * (i - l[0]); py = l[2] + (l[2] - p0[2]) / df * (i - l[0])
            }
            // How far the ball may have gone. After the first link there is a velocity to
            // predict from, so the gate only has to cover the ERROR in that prediction -- one
            // step of gravity is 0.1 of these pixels, and the rest is the finder's own wobble,
            // so three radii is already generous.
            //
            // On the FIRST link there is no velocity, [px] is simply where the ball was, and
            // the gate has to cover its whole displacement. Three radii then means a ceiling on
            // how fast a serve may be and still be seen at all: a ball crosses 0.417 x v of its
            // own radii between tracker frames (v in m/s), at ANY camera distance -- the ball
            // shrinks and the displacement shrinks together -- so the old floor of 12.5, which
            // is 4.2 radii at 2.5 m, stopped tracking anything past 10.1 m/s. Rendered serves
            // agreed exactly: 10 m/s tracked, 12 m/s produced no track at all.
            //
            // Eight radii is 19 m/s, past the point where the flight is too short to measure
            // anyway (about 17 m/s at 2.5 m, needing 40 frames). Widening only the blind link
            // leaves the steady-state gate where it was, and a wrong first link does not
            // survive: the next prediction misses, and the track dies short.
            val gate = if (tr.pts.size > 1) max(12.5f, 3 * l[3]) else max(12.5f, 8 * l[3])
            for ((j, c) in cs.withIndex()) {
                val d = hypot(c[0] - px, c[1] - py)
                if (d < gate) pairs.add(Triple(d + 8 * abs(ln(c[2] / l[3])), ti, j))
            }
        }
        pairs.sortBy { it.first }
        val took = HashSet<Int>(); val used = HashSet<Int>()
        for ((_, ti, j) in pairs) {
            if (ti in took || j in used) continue
            took.add(ti); used.add(j)
            val c = cs[j]
            active[ti].pts.add(floatArrayOf(i.toFloat(), c[0], c[1], c[2]))
        }
        val still = ArrayList<Track>()
        for (tr in active) { if (i - tr.last()[0] <= maxGap) still.add(tr) else finish(tr, i) }
        for ((j, c) in cs.withIndex()) if (j !in used) still.add(Track().also { it.pts.add(floatArrayOf(i.toFloat(), c[0], c[1], c[2])) })
        active = still
        liveTracks = active.mapNotNull { if (it.pts.size >= 4) ArrayList<FloatArray>(it.pts) else null }
    }

    /**
     * The tracks as they stand this frame, those long enough to predict from, newest point last.
     *
     * For cutting the ball's window as the frame goes past (LiveWindows): the window has to be
     * placed while the serve is still in the air, so there is nothing to go on but the tracker's
     * state mid-flight -- not the serve it will report when the flight is over. Copied as it is
     * published, because the graphics chip's thread reads it while this one goes on; the copy
     * can be shallow, since a point never changes once it is added.
     */
    @Volatile var liveTracks: List<List<FloatArray>> = emptyList(); private set

    private fun finish(tr: Track, now: Long) {
        if (tr.pts.size >= 6) { recent.addLast(tr); while (recent.size > 30) recent.removeFirst() }
        val why = judge(tr)
        if (tr.pts.size >= 8) onTrack?.invoke(tr.pts, why)
        if (why != null) {
            if (tr.pts.size >= 6) rejected[why] = (rejected[why] ?: 0) + 1
            // a toss, or a serve's flight climbing steeply (a phone low or at an angle): seen in a clip
            if (why == "upward (toss)") onSkipped?.invoke(tr.first()[0].toLong(), tr.last()[0].toLong(), why)
            return
        }
        tr.score = tr.pts.size * median(tr.pts.map { it[3] })
        done.add(tr)
        // The bounce may follow as a track of its own, and a better track may come: wait 0.4 s. Only a
        // track at least half as good as the best so far waits longer (the server's arms and shirt
        // make short, small tracks all the time; T4: they kept a real serve from ever being reported).
        if (tr.score >= 0.5f * bestScore) groupDeadline = now + (0.4 * fps).toLong()
        if (tr.score > bestScore) bestScore = tr.score
    }

    /**
     * The longest track this session judged: how many points it had, how big the ball looked,
     * and what was said about it.
     *
     * The rejection tally says what was turned away but not what was there to turn away. A
     * session that reports forty "short" tracks is a different fault depending on whether the
     * longest of them was four points or eleven: four means the ball is barely being found at
     * all, eleven means it is found the whole way and thrown out one point under the bar. The
     * tally cannot tell those apart. This can.
     */
    @Volatile var longest = 0; private set
    @Volatile var longestR = 0f; private set
    @Volatile var longestWhy = ""; private set

    /** The engine's flight tests (scaled to quarter size); null when it flies like a serve. */
    private fun judge(tr: Track): String? {
        val said = judged(tr)
        if (tr.pts.size > longest) {
            longest = tr.pts.size
            longestR = median(tr.pts.map { it[3] })
            longestWhy = said ?: "flies like a serve"
        }
        return said
    }

    private fun judged(tr: Track): String? {
        val p = tr.pts
        if (p.size < minPoints) return "short"
        val a = p[0]; val b = p[p.size - 1]
        val dx = b[1] - a[1]; val dy = b[2] - a[2]
        val disp = hypot(dx, dy)
        if (disp < 15f) return "barely moves"
        // live, stricter than the engine's search (which then picks the best of a whole clip): a
        // ball the meter can read (20 px across or more) that crosses a tenth of the picture.
        // Measured at the TOP of what was seen, not the middle of it -- see [ballSize].
        if (ballSize(p) < 2.5f) return "tiny"
        if (disp < 0.1f * w) return "short flight"
        if (disp / max(1f, b[0] - a[0]) < 0.5f) return "too slow"
        var path = 0f
        for (k in 1 until p.size) path += hypot(p[k][1] - p[k - 1][1], p[k][2] - p[k - 1][2])
        if (disp < 0.6f * path) return "zigzag"
        // rising steeply: a toss (Q3, phone low by the net: up and away at 40 degrees); a serve's
        // flight, even rising off its bounce, climbs at under 15 degrees in every labelled clip
        if (dy < 0 && -dy > 0.5f * abs(dx)) return "upward (toss)"
        val grow = median(p.takeLast(4).map { it[3] }) / max(1e-6f, median(p.take(4).map { it[3] }))
        val n = p.size
        fun part(a0: Double, b0: Double): Float {
            val s = (a0 * n).toInt(); val e = max(s + 1, (b0 * n).toInt())
            return median(p.subList(s, min(e, n)).map { it[3] })
        }
        val growMid = part(0.625, 0.875) / max(1e-6f, part(0.125, 0.375))
        fun steady(g: Float, lim: Float) = g < lim && g > 1 / lim
        if (dy > 2 * abs(dx) && (steady(grow, 1.3f) || steady(growMid, 1.15f))) return "straight drop"
        return null
    }

    /**
     * A serve's best track that flies like a ball but isn't one (0.7.4, a player walking past a phone
     * low on the table: 30 of 34 "serves" were bodies). Measured on the labelled clips at 120 tracker
     * frames a second on a 480-wide picture, every real serve: fastest 5.1+ px a frame, median 2.0+,
     * ball 6.1 px or less, under a tenth of the picture moving. Walking, arms and a handled camera:
     * slower, bigger, or much of the picture moving. Scaled to the picture's width and the rate.
     */
    private fun notABall(sp: FloatArray, r: Float, moving: FloatArray): String? {
        val perFrame = w / 480f * 120f / fps
        if (sp[1] < 4.5f * perFrame || sp[0] < 1.5f * perFrame) return "slow (a body?)"
        if (r > 7f * w / 480f) return "too big (a body?)"
        if (moving[1] > 0.2f) return "picture moving"
        return null
    }

    private fun falls(tr: Track): Boolean {
        val a = tr.first(); val b = tr.last()
        return b[2] - a[2] > 2 * abs(b[1] - a[1])
    }

    /**
     * A track's speed, small-picture pixels per tracker frame: median and fastest.
     *
     * Over four-frame steps where there are four to step over, and over whatever there is where
     * there is not. It used to need five points and return ZERO speed below that -- and zero
     * speed is not "no reading", it is the fastest possible way to fail notABall's first test,
     * so every track of four points or fewer was reported as "slow (a body?)". A ball crossing
     * the picture and a hand resting on the table came back as the same thing, from no evidence
     * either way, and the sessions that could not keep up made short tracks by the hundred.
     *
     * A two-point track gives one reading over one step, which is noisy; it is still a
     * measurement, and the rules downstream are written against measurements.
     */
    fun speeds(p: List<FloatArray>): FloatArray {
        val span = minOf(4, p.size - 1)
        if (span < 1) return floatArrayOf(0f, 0f)
        val v = ArrayList<Float>()
        for (k in span until p.size) { val a = p[k - span]; val b = p[k]; v.add(hypot(b[1] - a[1], b[2] - a[2]) / max(1f, b[0] - a[0])) }
        v.sort(); return floatArrayOf(v[v.size / 2], v.last())
    }

    private fun median(v: List<Float>): Float {
        if (v.isEmpty()) return 0f
        val s = v.sorted(); val n = s.size
        return if (n % 2 == 1) s[n / 2] else 0.5f * (s[n / 2 - 1] + s[n / 2])
    }

    /**
     * How big the ball is, from a track's radii: the upper end of what was seen, not the middle.
     *
     * A candidate's radius comes from the AREA of the moving mask (Detector: sqrt(area/PI)), and
     * that area is a lower bound on the ball. Any part of the ball that happens to match the
     * background drops out of the mask -- the shaded side of the white half against a pale wall,
     * the inked half against a dark one -- so a frame can measure the ball small, and no frame
     * can ever measure it large. Taking the middle of that therefore measures the ball small
     * every time, and by an amount that depends on what is behind it.
     *
     * How much: on rendered serves whose ball is 2.96 of these pixels across the radius, the
     * median of a track's radii came out 2.46 to 2.59 -- 13 to 17% under -- while percentile 85
     * came out 2.71 to 3.09, within 8% either way. The bar below is 2.5, so the median had a
     * real 20 px ball failing or passing on which way its marking happened to be facing: one
     * rendered serve passed by 0.08 and two identical ones failed by 0.04.
     *
     * Which is the same reading engine.js arrived at from real clips for its millimetres per
     * pixel: "Not the median: where the white half's shaded side matches the wall it drops out
     * and the ball looks smaller... the ball turns, so some frames show all of it" -- and it
     * uses percentile 85 of the narrow width. This is that, for the live tracker.
     *
     * Only for "how big is the ball". The comparisons elsewhere -- does it grow, is this track
     * the same ball as that one -- put a median against a median and are not affected.
     */
    private fun ballSize(p: List<FloatArray>): Float {
        if (p.isEmpty()) return 0f
        val s = p.map { it[3] }.sorted()
        return s[((s.size - 1) * 0.85f).toInt().coerceIn(0, s.size - 1)]
    }

    // ------------------------------------------------------------ the serve
    /**
     * Once the last good track's wait is over: the serve's frames run from 0.25 s before its first
     * good track (the toss and the hit) to 0.05 s after its last; its box holds those tracks and a
     * toss track that ended just before.
     */
    private fun maybeServe(i: Long): Serve? {
        if (done.isEmpty() || i < groupDeadline) return null
        // a flight still going on (V1: the toss ended and its wait ran out while the serve itself
        // was in the air, so the toss came out as a serve of its own): wait for it, up to 1.5 s
        if (i - done.minOf { it.first()[0] } < 1.5 * fps && active.any { it.pts.size >= minPoints && judge(it) == null }) return null
        // the best track is the serve; with it the tracks of nearly its quality close in time (the
        // flight after the bounce), and a toss track that ended just before it, near its start
        // the best track, unless it doesn't fly like a ball (0.8.0, K2: the ball rolling back along
        // the table, 140 frames, slow, and the serve went with it as "a body"); then the best of
        // those that do and cross the picture (P31: not a short drop beside a walking body)
        fun ballLike(tr: Track) = notABall(speeds(tr.pts), median(tr.pts.map { it[3] }), floatArrayOf(0f, 0f)) == null
        val top = done.maxByOrNull { it.score }!!
        val best = if (ballLike(top)) top else done.filter { ballLike(it) && !falls(it) }.maxByOrNull { it.score } ?: top
        // only falling tracks (Q2: the toss coming down, its flight unseen): no serve. A real serve
        // has a flight across the picture too, though its falling toss may be the best track (N4)
        if (done.all { falls(it) }) {
            rejected["toss only"] = (rejected["toss only"] ?: 0) + 1
            onSkipped?.invoke(done.minOf { it.first()[0] }.toLong(), done.maxOf { it.last()[0] }.toLong(), "toss only")
            done.clear(); recent.clear(); groupDeadline = -1; bestScore = 0f
            return null
        }
        val b0 = best.first()[0]; val b1 = best.last()[0]
        // A track kept beside the serve is meant to be the same ball at another moment -- the
        // toss, or the flight on from the bounce -- and the engine uses it to place the serve's
        // own stretch. One that is half the size is not that ball: a toss happens where the
        // flight begins, at the same distance from the camera, so it is the same size there.
        //
        // Keeping it costs the capture twice over, because the crop is stretched to reach it and
        // the capture starts a quarter second before it began. Session 20261112_194529's serve 4
        // carried two such blobs, 0.55 and 0.46 of the ball, 200 px below the flight and 180 px
        // to its left; between them they doubled a 217 MB capture, which the phone then decoded,
        // cut, wrote, served and parsed.
        val rBest = median(best.pts.map { it[3] })
        fun sameBall(tr: Track): Boolean {
            val r = median(tr.pts.map { it[3] })
            return r >= 0.6f * rBest && r <= 1.7f * rBest
        }
        val keep = ArrayList<Track>()
        for (tr in done) if (tr === best || (tr.score >= 0.3f * best.score && tr.first()[0] >= b0 - 0.3f * fps && tr.first()[0] <= b1 + 0.4f * fps && sameBall(tr))) keep.add(tr)
        val first = keep.minOf { it.first()[0] }.toLong()
        val last = keep.maxOf { it.last()[0] }.toLong()
        val box = FloatArray(4).also { it[0] = Float.MAX_VALUE; it[1] = Float.MAX_VALUE; it[2] = -1f; it[3] = -1f }
        var rMax = 0f
        fun grow(tr: Track) { for (p in tr.pts) { box[0] = min(box[0], p[1]); box[1] = min(box[1], p[2]); box[2] = max(box[2], p[1]); box[3] = max(box[3], p[2]); rMax = max(rMax, p[3]) } }
        keep.forEach { grow(it) }
        val f = best.first()
        val near = ArrayList<List<FloatArray>>()
        for (tr in keep) if (tr !== best) near.add(tr.pts)
        for (tr in recent) if (tr !in keep && tr.last()[0] >= b0 - 0.3f * fps && tr.last()[0] <= b0 + 3 &&
            hypot(tr.last()[1] - f[1], tr.last()[2] - f[2]) < 12 * f[3] && sameBall(tr)) { grow(tr); near.add(tr.pts) }
        val mv = (b0.toLong()..b1.toLong()).map { movingAt[(it % movingAt.size).toInt()] }.sorted()
        val moving = if (mv.isEmpty()) floatArrayOf(0f, 0f) else floatArrayOf(mv[mv.size / 2], mv.last())
        val sp = speeds(best.pts)
        val r = median(best.pts.map { it[3] })
        val no = notABall(sp, r, moving)
        if (no != null) {
            rejected[no] = (rejected[no] ?: 0) + 1
            onSkipped?.invoke(first, last, no)
            done.clear(); recent.clear(); groupDeadline = -1; bestScore = 0f
            return null
        }
        val why = "best of ${done.size}: ${best.pts.size} frames, score ${best.score.toInt()}, moving ${"%.3f".format(moving[0])}/${"%.3f".format(moving[1])}, speed ${"%.1f".format(sp[0])}/${"%.1f".format(sp[1])} r ${"%.1f".format(r)}"
        val s = Serve(max(0, first - (0.25 * fps).toLong()), min(last + (0.05 * fps).toLong(), first + (1.5 * fps).toLong()), box, rMax, keep.size, why,
            best.pts.toList(), near, moving)
        done.clear(); recent.clear(); groupDeadline = -1; bestScore = 0f
        return s
    }

    /** The serve's crop in the whole picture (size W x H): its box, padded by 4 radii + 40 px, all even. */
    /**
     * The crop a capture is cut to: the serve's own box and enough round it for the engine.
     *
     * The engine measures a box round the ball of 3r + 20 either way and reads nothing outside it
     * (engine.js, measureSegment). The 4r + 40 this used to take was sized when it read the whole
     * crop every frame and needed room for a background; that is 1.4x the pixels, on every stage
     * that scales with them -- the cut, the write, the WebView's parse and the measuring itself.
     *
     * r is the largest the ball gets along the track, not its typical size: a ball coming toward
     * the camera can end half as big again as it started, and the box grows with it.
     */
    fun cropFor(s: Serve, W: Int, H: Int): IntArray {
        val sx = W.toFloat() / w; val sy = H.toFloat() / h
        val rMax = maxOf(s.ballRadius, s.track.maxOfOrNull { it[3] } ?: 0f)
        val pad = 3 * rMax * sx + 24
        val x0 = ((s.box[0] * sx - pad).toInt()).coerceAtLeast(0) and 1.inv()
        val y0 = ((s.box[1] * sy - pad).toInt()).coerceAtLeast(0) and 1.inv()
        val x1 = ((s.box[2] * sx + pad).toInt() + 1).coerceAtMost(W)
        val y1 = ((s.box[3] * sy + pad).toInt() + 1).coerceAtMost(H)
        // width a multiple of 8: the graphics chip packs 4 pixels a texel, and colour is half width
        return intArrayOf(x0, y0, (x1 - x0) and 7.inv(), (y1 - y0) and 1.inv())
    }
}
