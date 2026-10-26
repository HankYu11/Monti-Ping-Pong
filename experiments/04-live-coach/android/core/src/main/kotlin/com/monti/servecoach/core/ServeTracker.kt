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
                     val track: List<FloatArray> = emptyList(), val near: List<List<FloatArray>> = emptyList()) {
        /**
         * The tracks for the engine (the header's "track" and "near"): frames counted from the
         * capture's first ([f0], a whole frame; [step] whole frames per tracker frame), points in whole-picture pixels.
         */
        fun tracksJson(step: Int, scale: Float, f0: Long): String {
            fun one(t: List<FloatArray>) = t.joinToString(",", "[", "]") { p ->
                "[${(p[0] * step - f0).toLong()},${"%.1f".format(java.util.Locale.US, p[1] * scale)},${"%.1f".format(java.util.Locale.US, p[2] * scale)},${"%.2f".format(java.util.Locale.US, p[3] * scale)}]"
            }
            return ""","track":${one(track)},"near":${near.joinToString(",", "[", "]") { one(it) }}"""
        }
    }

    // the engine's numbers, at quarter size (it searches at half size)
    private val sampleEvery = 24                       // a background sample every 24 frames (0.1 s)
    private val samplesKept = 5                        // the background: the median of the last 5 (0.5 s)
    private val minLen = 12
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
        if (samples.size >= 3) bgNow = Background(bgY!!, bgU!!, bgV!!)
    }

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
            val gate = max(12.5f, 3 * l[3])
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
        for (tr in active) { if (i - tr.last()[0] <= 2) still.add(tr) else finish(tr, i) }
        for ((j, c) in cs.withIndex()) if (j !in used) still.add(Track().also { it.pts.add(floatArrayOf(i.toFloat(), c[0], c[1], c[2])) })
        active = still
    }

    private fun finish(tr: Track, now: Long) {
        if (tr.pts.size >= 6) { recent.addLast(tr); while (recent.size > 30) recent.removeFirst() }
        val why = judge(tr)
        if (tr.pts.size >= 8) onTrack?.invoke(tr.pts, why)
        if (why != null) { if (tr.pts.size >= 6) rejected[why] = (rejected[why] ?: 0) + 1; return }
        tr.score = tr.pts.size * median(tr.pts.map { it[3] })
        done.add(tr)
        // The bounce may follow as a track of its own, and a better track may come: wait 0.4 s. Only a
        // track at least half as good as the best so far waits longer (the server's arms and shirt
        // make short, small tracks all the time; T4: they kept a real serve from ever being reported).
        if (tr.score >= 0.5f * bestScore) groupDeadline = now + (0.4 * fps).toLong()
        if (tr.score > bestScore) bestScore = tr.score
    }

    /** The engine's flight tests (scaled to quarter size); null when it flies like a serve. */
    private fun judge(tr: Track): String? {
        val p = tr.pts
        if (p.size < minLen) return "short"
        val a = p[0]; val b = p[p.size - 1]
        val dx = b[1] - a[1]; val dy = b[2] - a[2]
        val disp = hypot(dx, dy)
        if (disp < 15f) return "barely moves"
        // live, stricter than the engine's search (which then picks the best of a whole clip): a
        // ball the meter can read (20 px across or more) that crosses a tenth of the picture
        if (median(p.map { it[3] }) < 2.5f) return "tiny"
        if (disp < 0.1f * w) return "short flight"
        if (disp / max(1f, b[0] - a[0]) < 0.5f) return "too slow"
        var path = 0f
        for (k in 1 until p.size) path += hypot(p[k][1] - p[k - 1][1], p[k][2] - p[k - 1][2])
        if (disp < 0.6f * path) return "zigzag"
        if (dy < 0 && -dy > abs(dx)) return "upward (toss)"
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

    private fun median(v: List<Float>): Float {
        if (v.isEmpty()) return 0f
        val s = v.sorted(); val n = s.size
        return if (n % 2 == 1) s[n / 2] else 0.5f * (s[n / 2 - 1] + s[n / 2])
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
        if (i - done.minOf { it.first()[0] } < 1.5 * fps && active.any { it.pts.size >= minLen && judge(it) == null }) return null
        // the best track is the serve; with it the tracks of nearly its quality close in time (the
        // flight after the bounce), and a toss track that ended just before it, near its start
        val best = done.maxByOrNull { it.score }!!
        val b0 = best.first()[0]; val b1 = best.last()[0]
        val keep = ArrayList<Track>()
        for (tr in done) if (tr === best || (tr.score >= 0.3f * best.score && tr.first()[0] >= b0 - 0.3f * fps && tr.first()[0] <= b1 + 0.4f * fps)) keep.add(tr)
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
            hypot(tr.last()[1] - f[1], tr.last()[2] - f[2]) < 12 * f[3]) { grow(tr); near.add(tr.pts) }
        val why = "best of ${done.size}: ${best.pts.size} frames, score ${best.score.toInt()}"
        val s = Serve(max(0, first - (0.25 * fps).toLong()), min(last + (0.05 * fps).toLong(), first + (1.5 * fps).toLong()), box, rMax, keep.size, why,
            best.pts.toList(), near)
        done.clear(); recent.clear(); groupDeadline = -1; bestScore = 0f
        return s
    }

    /** The serve's crop in the whole picture (size W x H): its box, padded by 4 radii + 40 px, all even. */
    fun cropFor(s: Serve, W: Int, H: Int): IntArray {
        val sx = W.toFloat() / w; val sy = H.toFloat() / h
        val pad = 4 * s.ballRadius * sx + 40
        val x0 = ((s.box[0] * sx - pad).toInt()).coerceAtLeast(0) and 1.inv()
        val y0 = ((s.box[1] * sy - pad).toInt()).coerceAtLeast(0) and 1.inv()
        val x1 = ((s.box[2] * sx + pad).toInt() + 1).coerceAtMost(W)
        val y1 = ((s.box[3] * sy + pad).toInt() + 1).coerceAtMost(H)
        return intArrayOf(x0, y0, (x1 - x0) and 1.inv(), (y1 - y0) and 1.inv())
    }
}
