package com.monti.servecoach

import android.os.SystemClock
import com.monti.servecoach.core.Capture
import com.monti.servecoach.core.ColourFit
import com.monti.servecoach.core.Detector
import com.monti.servecoach.core.Downsample
import com.monti.servecoach.core.Quarter
import com.monti.servecoach.core.Predict
import com.monti.servecoach.core.ServeTracker
import com.monti.servecoach.core.WindowRing
import com.monti.servecoach.core.Windows
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Watches the stream for serves. Encoded frames come in (from the camera's encoder, or a video
 * being replayed); a decoder turns them back into pictures; every 2nd one (120 a second) is
 * shrunk to a quarter of its size, its ball-like blobs are found on a few threads at once, and
 * one thread links them into tracks in order (the serve tracker). When the tracker reports a
 * serve, its frames are decoded again from the store, all of them, cropped to where the ball
 * flew, and written to a capture for the engine.
 */
class LivePipeline(
    private val store: FrameStore,
    private val captureDir: File,
    private val captureFps: Int = 240,
    private val blocking: Boolean = false,              // replay: wait for room rather than drop frames
    private val clipDir: File? = null,                  // live: each serve's own clip (MP4) written here too
    /** live: a clip of a movement turned down as not a serve (its number, why, the file) */
    private val onSkippedClip: ((Int, String, File) -> Unit)? = null,
    private val onCapture: (Capture.Info, File?) -> Unit,
    /** a capture's frames have started to be written (its first header: the frame count estimated, n): it can be measured now */
    private val onCaptureStarted: ((Capture.Info) -> Unit)? = null,
    private val onError: (String) -> Unit,
    /** the player's pose, to tell walking from serving (null: not used) */
    private val pose: PoseWatcher? = null,
    private val onNote: (String) -> Unit = {},
    /** live: one serve at a time; called when one is spotted (its number), then nothing until [resume] */
    private val onSpotted: ((Int) -> Unit)? = null,
    /** the app's assets (the capture shaders), to cut captures on the graphics chip */
    private val assets: android.content.res.AssetManager? = null,
) {
    /** measuring a serve: others are ignored until [resume] (turn by turn, so each answer is clearly about one serve) */
    @Volatile var paused = false; private set
    @Volatile var ignoredWhileMeasuring = 0; private set
    fun resume() { paused = false }
    private val queue = LinkedBlockingQueue<EncodedFrame>(if (blocking) 64 else 720)
    @Volatile private var running = true
    private val thread = Thread({ run() }, "decoder")
    private val linker = Thread({ link() }, "serve-tracker")
    private val step = 2                                   // the tracker sees every 2nd frame

    // finding blobs, on a few threads; their results are linked in order
    private val finders = Executors.newFixedThreadPool(3) { r -> Thread(r, "finder").apply { priority = Thread.NORM_PRIORITY + 1 } }
    private val detectors = ThreadLocal<Detector>()
    private class Job(val index: Long, val found: Future<List<FloatArray>>?, val end: Boolean = false)
    private val jobs = LinkedBlockingQueue<Job>(if (blocking) 16 else 24)   // 0.2 s of frames waiting (1.5 MB each)

    // for the screen and the report
    @Volatile var decoded = 0L; private set              // frames decoded (or, from the tap, seen)
    @Volatile var dropped = 0L; private set              // encoded frames dropped (the decoder fell behind)
    @Volatile var skipped = 0L; private set
    @Volatile var decoderFull = 0L; private set          // times the decoder had no room for the next frame (5 ms waits)
    @Volatile var waitingForKey = 0L; private set        // frames passed over after a drop, until the next keyframe              // frames the tracker skipped (finding blobs fell behind)
    @Volatile var trackMsAvg = 0.0; private set         // per tracker frame: shrinking + finding + linking (on several threads)
    @Volatile var shrinkMsAvg = 0.0; private set         // on the decoder's thread: copying the rows to shrink
    @Volatile var findMsAvg = 0.0; private set           // on a finder: shrinking + finding blobs
    @Volatile var linkMsAvg = 0.0; private set
    @Volatile var lagMs = 0L; private set
    @Volatile var servesFound = 0; private set
    @Volatile var trimmed = 0; private set                // captures cut down to 300 MB
    @Volatile var tracker: ServeTracker? = null; private set
    private var captures = 0

    fun start() { thread.start(); linker.start() }
    fun stop() {
        running = false
        thread.interrupt(); linker.interrupt()
        thread.join(1000); linker.join(1000)
        finders.shutdownNow()
    }

    /** replay: the video ended; decode what's left and let a serve still being watched out. Blocks until done. */
    fun finish() { queue.put(END); thread.join(); linker.join(); finders.shutdown() }

    /**
     * Replay: learn the background from keyframes spread over the video before it plays (live, the
     * camera has been running a while; a video may start with the serve). Call before [start].
     */
    fun prime(frames: List<EncodedFrame>, count: Int = 5) {
        val fmt = store.outputFormat ?: return
        val keys = frames.filter { it.key }
        if (keys.isEmpty()) return
        val pick = (0 until count).map { keys[it * (keys.size - 1) / maxOf(1, count - 1)] }.distinct()
        val qs = ArrayList<Quarter>()
        for (k in pick) {
            val dec = FrameDecoder(fmt)
            try {
                var fed = false; var eos = false; var tries = 0
                while (!dec.ended && tries++ < 400) {
                    if (!fed) fed = dec.feed(k, 5_000) else if (!eos) eos = dec.endOfStream()
                    dec.drain(5_000) { _, yuv -> if (qs.size < count) qs.add(Downsample.quarter(yuv)) }
                }
            } finally { dec.release() }
        }
        if (qs.isEmpty()) return
        val q = qs[0]
        tracker = (tracker ?: ServeTracker(q.w, q.h, captureFps / step).also { watchSkips(it) }).also { it.prime(qs) }
    }

    /** from the encoder (or the replay): never blocks the camera; frames are dropped when we fall behind */
    fun offer(fr: EncodedFrame) {
        if (blocking) { queue.put(fr); return }
        if (!queue.offer(fr)) dropped++
    }

    // the time of each decoded frame, by its order: the tracker counts frames, the store keeps times
    private val ptsRing = LongArray(8192)

    companion object {
        private val END = EncodedFrame(-1, false, ByteArray(0))
        /** the captures being written or measured, by number (the engine's reads wait on them) */
        val writing = java.util.concurrent.ConcurrentHashMap<Int, Writing>()
    }

    private fun run() {
        var dec: FrameDecoder? = null
        var waitKey = true
        var newest = 0L
        var seenDropped = 0L
        try {
            while (running) {
                val fr = queue.poll(20, TimeUnit.MILLISECONDS)
                if (fr === END) { flush(dec); break }
                if (fr != null) {
                    newest = fr.ptsUs
                    if (dec == null) {
                        val fmt = store.outputFormat ?: continue
                        dec = FrameDecoder(fmt)
                    }
                    if (waitKey && !fr.key) { waitingForKey++; continue }   // after a drop: start again at a keyframe
                    waitKey = false
                    while (running && !dec.feed(fr, 5_000)) { decoderFull++; drainOnce(dec) }
                }
                if (dec != null) drainOnce(dec)
                if (dropped != seenDropped) { seenDropped = dropped; waitKey = true }   // frames lost: resume at a keyframe
                if (newest > 0) lagMs = (newest - lastPts) / 1000
            }
        } catch (_: InterruptedException) {
        } catch (e: Exception) { onError("decoder: ${e.message}") }
        dec?.release()
    }

    @Volatile private var lastPts = 0L
    @Volatile private var frameW = 0
    @Volatile private var frameH = 0

    /** the end of a replayed video: the decoder's last pictures, then quiet frames so a serve still being watched is let out */
    private fun flush(dec: FrameDecoder?) {
        if (dec != null) {
            var tries = 0
            while (!dec.endOfStream() && tries++ < 100) drainOnce(dec)
            tries = 0
            while (!dec.ended && tries++ < 500) dec.drain(10_000) { pts, yuv -> onPicture(pts, yuv) }
        }
        jobs.put(Job(decoded / step, null, end = true))
    }

    private fun drainOnce(dec: FrameDecoder) { dec.drain(0) { pts, yuv -> onPicture(pts, yuv) } }

    private fun avg(old: Double, x: Double, n: Long) = old + (x - old) / minOf(n, 240L).coerceAtLeast(1L)
    private var shrinkN = 0L

    /** on the decoder's thread: note the frame's time; every 2nd frame shrunk and sent to the finders */
    private fun onPicture(pts: Long, yuv: com.monti.servecoach.core.Yuv) {
        val n = decoded
        ptsRing[(n % ptsRing.size).toInt()] = pts
        decoded = n + 1
        lastPts = pts
        if (n % step != 0L) return
        val index = n / step
        if (!blocking && jobs.remainingCapacity() == 0) { skipped++; return }      // the finders are behind: skip it
        frameW = yuv.width; frameH = yuv.height
        // here only the rows the small picture is made from are copied (the decoder wants its
        // picture back); the finders make the small picture
        val t0 = System.nanoTime()
        val rows = Downsample.rows(yuv)
        shrinkMsAvg = avg(shrinkMsAvg, (System.nanoTime() - t0) / 1e6, ++shrinkN)
        val tr = tracker ?: ServeTracker(rows.w, rows.h, captureFps / step).also { watchSkips(it); tracker = it }
        // the background learns from every 24th frame, before that frame's blobs are found
        val q0 = if (tr.needsFrame(index)) Downsample.quarter(rows).also { tr.sample(it, index) } else null
        val bg = tr.background()
        val found = if (bg == null) null else finders.submit(Callable<List<FloatArray>> {
            val d = detectors.get() ?: Detector(rows.w, rows.h).also { detectors.set(it) }
            val t = System.nanoTime()
            val q = q0 ?: Downsample.quarter(rows)
            if (index % 8 == 0L) pose?.offer(q, index * step)
            val c = d.find(q, bg)
            findMsAvg = avg(findMsAvg, (System.nanoTime() - t) / 1e6, index + 1)
            c
        })
        jobs.put(Job(index, found))
    }

    // ------------------------------------------------------------ live, from the graphics chip (GlTap)
    @Volatile var tapped = false; private set
    @Volatile var calibrated = false; private set
    @Volatile var flip = 0; private set
    @Volatile var flipCheck = ""; private set
    @Volatile var pictureStats = ""; private set
    @Volatile var flipBest = 0.0; private set             // the mean difference the picture check found the right way round
    private var lastNo = -1L
    private class Tapped(val pts: Long, val rgba: ByteArray, val w: Int, val h: Int)
    private val recentTaps = ArrayDeque<Tapped>()

    /**
     * The preview stream's times are on the clock that counts sleep (the camera's), the recording's
     * on the one that doesn't (0.5.1: 762,430 s apart): tap time minus this is recording time.
     * Estimated from the two clocks, then set exactly by [calibrate] from a matched frame.
     */
    @Volatile var clockOffsetUs = (SystemClock.elapsedRealtimeNanos() - System.nanoTime()) / 1000; private set

    /** Every camera frame's time (ns, the camera's clock) and number (240 a second, by its time), from the graphics chip (GlTap). Quick. */
    fun offerTapTime(tsNs: Long, frameNo: Long) {
        tapped = true
        val pts = tsNs / 1000 - clockOffsetUs
        // frame times by number (frames that never arrived get the time they would have had)
        if (lastNo >= 0) for (k in lastNo + 1 until frameNo) ptsRing[(k % ptsRing.size).toInt()] = pts - (frameNo - k) * 1_000_000L / captureFps
        ptsRing[(frameNo % ptsRing.size).toInt()] = pts
        lastNo = frameNo
        decoded = frameNo + 1
        lastPts = pts
    }

    /**
     * Every 2nd frame's small picture from the graphics chip (a little after its time): to the
     * finders, as decoded frames went before (a Pixel 8 couldn't decode 240 frames a second for
     * watching and keep up).
     */
    fun offerTapPicture(tsNs: Long, frameNo: Long, rgba: ByteArray, w: Int, h: Int) {
        frameW = w * 4; frameH = h * 4
        if (!calibrated) {                     // until the picture's way round is known: kept for that
            synchronized(recentTaps) { recentTaps.addLast(Tapped(tsNs / 1000, rgba, w, h)); while (recentTaps.size > 48) recentTaps.removeFirst() }
            return
        }
        val index = frameNo / step
        if (jobs.remainingCapacity() == 0) { skipped++; return }
        val tr = tracker ?: ServeTracker(w, h, captureFps / step).also { watchSkips(it); tracker = it }
        val f = flip
        val q0 = if (tr.needsFrame(index)) Downsample.fromRgba(rgba, w, h, f).also { tr.sample(it, index) } else null
        val bg = tr.background()
        val found = if (bg == null) null else finders.submit(Callable<List<FloatArray>> {
            val d = detectors.get() ?: Detector(w, h).also { detectors.set(it) }
            val t = System.nanoTime()
            val q = q0 ?: Downsample.fromRgba(rgba, w, h, f)
            if (index % 8 == 0L) pose?.offer(q, index * step)
            val c = d.find(q, bg)
            findMsAvg = avg(findMsAvg, (System.nanoTime() - t) / 1e6, index + 1)
            c
        })
        jobs.offer(Job(index, found))
    }

    /**
     * Which way the chip's small picture is turned against the recorded frames: one of the
     * recent ones next to the same frame decoded from the recording, compared all 4 ways round.
     * Returns the flip (bit 0: x, bit 1: y), or -1 when no frame could be matched yet.
     */
    fun calibrate(): Int {
        val fmt = store.outputFormat ?: return -1
        val taps = synchronized(recentTaps) { recentTaps.toList() }
        if (taps.isEmpty()) return -1
        // one of them, decoded from the recording (from the keyframe before it to it): the recorded
        // frame nearest its time by the clocks' estimated offset, within half a frame
        var q: Quarter? = null
        var t: Tapped? = null
        var matched = 0L
        // (through the recording stream the encoder gets the camera's own times: no offset)
        for (cand in taps.asReversed()) {
            var near: EncodedFrame? = null
            for (off in listOf(clockOffsetUs, 0L)) {
                val target = cand.pts - off
                val n = store.between(target - 5_000, target + 5_000).minByOrNull { kotlin.math.abs(it.ptsUs - target) } ?: continue
                if (kotlin.math.abs(n.ptsUs - target) <= 1_000_000 / captureFps / 2) { near = n; break }
            }
            if (near == null) continue
            val frames = store.between(near.ptsUs, near.ptsUs)
            val dec = FrameDecoder(fmt)
            try {
                var k = 0; var tries = 0
                while (!dec.ended && tries++ < 2000 && q == null) {
                    if (k < frames.size) { if (dec.feed(frames[k], 5_000)) k++ } else if (k == frames.size) { if (dec.endOfStream()) k++ }
                    dec.drain(if (k > frames.size) 10_000 else 0) { pts, yuv -> if (q == null && pts == near.ptsUs) q = Downsample.quarter(yuv) }
                }
            } finally { dec.release() }
            if (q != null) { t = cand; matched = near.ptsUs; break }
        }
        if (t == null) { flipCheck = "no recorded frame matched the ${taps.size} tapped ones (times ${taps.first().pts}-${taps.last().pts} less $clockOffsetUs, recording ${store.between(0, Long.MAX_VALUE).let { if (it.isEmpty()) "empty" else "${it.first().ptsUs}-${it.last().ptsUs}" }})"; return -1 }
        val ref = q ?: return -1
        if (ref.w != t.w || ref.h != t.h) { flipCheck = "sizes differ: ${ref.w}x${ref.h} / ${t.w}x${t.h}"; return -1 }
        val diffs = (0 until 4).map { f ->
            val g = Downsample.fromRgba(t.rgba, t.w, t.h, f)
            var s = 0.0
            for (i in g.y.indices) s += kotlin.math.abs(g.y[i] - ref.y[i])
            s / g.y.size
        }
        val best = diffs.indices.minByOrNull { diffs[it] }!!
        flipBest = diffs[best]
        // Through the recording stream the recording is drawn from the very buffer the chip reads, so
        // agreeing with it proves little: the picture must also look like a picture (not black,
        // white or flat), and its rows must change smoothly (not noise from a buffer read wrongly).
        run {
            val g = Downsample.fromRgba(t.rgba, t.w, t.h, best).y
            val mean = g.average()
            val sd = kotlin.math.sqrt(g.sumOf { (it - mean) * (it - mean) } / g.size)
            var rough = 0.0; var n = 0
            for (y in 1 until t.h) for (x in 0 until t.w step 4) { rough += kotlin.math.abs(g[y * t.w + x] - g[(y - 1) * t.w + x]); n++ }
            rough /= maxOf(1, n)
            pictureStats = "brightness ${"%.0f".format(mean)}, contrast ${"%.0f".format(sd)}, row-to-row ${"%.1f".format(rough)}"
            if (mean < 8 || mean > 247 || sd < 4 || rough > 0.5 * sd + 10) flipBest = 99.0
        }
        flip = best
        val estimate = clockOffsetUs
        clockOffsetUs = t.pts - matched
        flipCheck = "$pictureStats; flip $best, mean differences ${diffs.joinToString(" / ") { "%.1f".format(it) }}; clocks ${clockOffsetUs} us apart (estimated ${clockOffsetUs - estimate} us off)"
        calibrated = true
        synchronized(recentTaps) { recentTaps.clear() }
        return best
    }

    /** The check kept failing: watch anyway, the picture taken as it comes and the clocks' estimate (0.5.1 watched nothing). */
    fun assumeDefault() {
        if (calibrated) return
        flip = 0
        flipCheck = "not checked, assumed as it comes: $flipCheck"
        calibrated = true
        synchronized(recentTaps) { recentTaps.clear() }
    }

    /** the tracker's own thread: blobs linked into tracks, frame by frame in order */
    private fun link() {
        var n = 0L
        try {
            while (true) {
                val job = jobs.poll(50, TimeUnit.MILLISECONDS) ?: if (running) continue else break
                val tr = tracker ?: continue
                if (job.end) {
                    var i = job.index
                    repeat(120) { tr.pushFound(i++, emptyList())?.let { onServe(tr, it) } }
                    break
                }
                val cands = job.found?.get() ?: emptyList()
                val t = System.nanoTime()
                val serve = tr.pushFound(job.index, cands)
                val ms = (System.nanoTime() - t) / 1e6
                n++
                linkMsAvg = avg(linkMsAvg, ms, n)
                trackMsAvg = shrinkMsAvg + findMsAvg + linkMsAvg
                planFromTracks(tr)
                if (serve != null) onServe(tr, serve)
            }
        } catch (_: InterruptedException) {
        } catch (e: Exception) { onError("tracker: ${e.message}") }
    }

    /**
     * Live: a clip of each movement the last checks turned down ("slow (a body?)", "toss only"...),
     * up to 30 a session, so a real serve wrongly skipped can be seen (0.7.9: "some serves were skipped").
     */
    private fun watchSkips(tr: ServeTracker) {
        if (clipDir == null || onSkippedClip == null) return
        tr.onSkipped = { f, l, why ->
            val first = f * step; val last = l * step + step - 1
            val dec = decoded
            if (!paused && skippedClips < 30 && dec - first <= ptsRing.size && first < dec) {
                val n = ++skippedClips
                val fromUs = ptsRing[(first % ptsRing.size).toInt()] - 1
                val toUs = ptsRing[(minOf(last, dec - 1) % ptsRing.size).toInt()] + 1
                Thread({
                    try {
                        val fmt = store.outputFormat ?: return@Thread
                        val file = File(clipDir, "skipped_$n.mp4")
                        if (writeMp4(store.between(fromUs - 500_000, toUs + 300_000), fmt, toUs + 300_000, file) > 0) onSkippedClip.invoke(n, why, file)
                    } catch (e: Exception) { onError("skipped clip $n: ${e.message}") }
                }, "skipped-$n").apply { priority = Thread.MIN_PRIORITY }.start()
            }
        }
    }
    @Volatile var skippedClips = 0; private set

    // ------------------------------------------------------------ windows cut on the chip (LiveWindows)

    /**
     * Cut the ball's window on the chip as the frame goes past, instead of decoding the serve
     * again to cut it. Off: this is new, and the capture the old way is what every measured
     * session was read from.
     */
    @Volatile var windowRing = false
    /** where a new plan goes (GlTap's), set by the owner; null tears the last one down */
    @Volatile var planWindows: ((GlTap.WindowPlan?) -> Unit)? = null
    /** the windows cut so far, waiting for a serve to ask for them */
    @Volatile var windows: WindowRing? = null; private set
    /** the window's side for the flight in the air, in whole-picture pixels */
    @Volatile var ringSide = 0; private set
    @Volatile var ringPlans = 0L; private set
    @Volatile var ringNote = ""; private set

    /** frames the ring holds: a flight is about a second, and the capture is written right after */
    private val ringFrames = captureFps + captureFps / 2
    /**
     * Every other row of the whole picture (see assets/shaders/crop_rows.frag).
     *
     * The rows between are interpolated, and how much that costs was measured against reading
     * every row: a step of 2 moves the row brightness by 0.002 on average and 0.017 at worst,
     * a step of 4 by 0.004 and 0.021, a step of 8 by 0.012 and 0.043. Row-to-row noise on the
     * captures recorded is 0.0066, so a step of 2 is below it and the rest are not -- and the
     * row brightness is the one number here that has gone wrong before, by a few percent,
     * which moved a serve's flight angle by 23 degrees. It is 65 KB a frame against the
     * window's 65, which the chip has the time for.
     */
    private val ringRowStep = 2
    private var planSide = 0
    private var planning = false
    private var planFit: CaptureGl.Calib? = null
    private class Aim { @Volatile var track: List<FloatArray> = emptyList() }
    private val aim = Aim()
    /** no colour fit yet: the chip's numbers as they come */
    private val ONE = floatArrayOf(1f, 0f)

    /**
     * The window's side for a ball of radius [r] (whole-picture pixels).
     *
     * The capture's own window is three radii (Windows.boxFor) round where the ball REALLY was,
     * which is not known until the flight is over; this one goes where the ball is predicted to
     * be, and has to be big enough that the capture's can be cut out of it afterwards wherever
     * the prediction missed by. Over the seventeen recorded flights the worst miss two tracker
     * frames back is 45 px and four back is 97, and how far behind the live track really runs
     * is the one thing that could not be measured without a phone. So the margin is 72, well
     * past the first and short of the second, and the capture reports how far off centre the
     * ball actually came out ("worst px off centre"), which says what it should be. Too big
     * only costs read-back; too small and every window misses and the serve is cut the old way.
     *
     * The side is rounded to 32 so that a ball that changes size a little does not make new
     * targets and a new ring.
     */
    private fun sideFor(r: Float): Int {
        val half = 3 * r + 72
        val side = ((Math.ceil(2 * half / 32.0).toInt()) * 32)
        return side.coerceIn(128, 384)
    }

    /**
     * The tracker's thread, every frame: what the chip should cut next.
     *
     * The track most likely to be the ball is the longest one that is going somewhere, which is
     * what the grouping picks later too (its score is length times size). Getting it wrong costs
     * bytes, not a reading: the capture checks every window it takes against the serve's real
     * track and falls back to decoding if they disagree.
     */
    private fun planFromTracks(tr: ServeTracker) {
        val put = planWindows
        if (!windowRing || put == null || frameW == 0) {
            if (planning) { planning = false; put?.invoke(null) }
            return
        }
        var best: List<FloatArray>? = null; var bestScore = 0f
        for (t in tr.liveTracks) {
            val a = t[0]; val b = t[t.size - 1]
            val disp = kotlin.math.hypot(b[1] - a[1], b[2] - a[2])
            if (disp < 8f) continue                      // an arm, a shirt: it has to be going somewhere
            val s = t.size * disp
            if (s > bestScore) { bestScore = s; best = t }
        }
        if (best == null) {
            // the plan goes, but not the size it settled on: a flight breaks into several
            // tracks, and starting the size again each time is what made it drift
            if (planning) { planning = false; put(null) }
            return
        }
        aim.track = best
        val scale = step * 2f                            // the tracker's pixels are a quarter the picture's
        val rs = best.map { it[3] }.sorted()
        val want = sideFor(rs[(rs.size - 1) / 2] * scale)
        // Hold the size steady. It follows the ball's apparent radius, which drifts frame by
        // frame as the ball flies and as the track grows, so taking it literally changes the
        // window -- and with it the chip's targets -- most of the flight. A window that is a
        // little too big costs read-back and nothing else, so it only grows, and only shrinks
        // when the ball is much smaller than it was.
        val side = if (planSide > 0 && want <= planSide && want > planSide * 6 / 10) planSide else want
        val fit = CaptureGl.ringCalib
        // the fit arrives from the first serve measured, and the plan carries it into the
        // shader: without this the windows would go on being cut in the chip's own numbers
        // for as long as the size happened not to change
        if (side == planSide && planning && fit === planFit) return
        planSide = side
        planning = true
        planFit = fit
        ringSide = side
        if (windows == null) windows = WindowRing(ringFrames)
        ringPlans++
        put(GlTap.WindowPlan(intArrayOf(0, 0, frameW, frameH), side, ringRowStep, Windows.ROW_STRIDE,
            fit?.bt601 ?: (CaptureGl.calib?.bt601 ?: false),
            fit?.y?.let { floatArrayOf(it.gain.toFloat(), it.offset.toFloat()) } ?: ONE,
            fit?.u?.let { floatArrayOf(it.gain.toFloat(), it.offset.toFloat()) } ?: ONE,
            fit?.v?.let { floatArrayOf(it.gain.toFloat(), it.offset.toFloat()) } ?: ONE) { no -> aimAt(side, no) })
    }

    /** the size the last plan asked for, kept across a flight's gaps so it does not drift */
    /** where frame [no]'s window goes, from the live track: even, and inside the picture */
    private fun aimAt(side: Int, no: Long): IntArray? {
        val t = aim.track
        if (t.isEmpty()) return null
        // no lag: the track handed over is already as new as the tracker has, which is itself
        // a few frames behind. Predict's own lag is for measuring the miss against a finished
        // track; asking for it here would discard the newest points and double the delay.
        val p = Predict.at(t, no.toFloat() / step, lag = 0f) ?: return null
        val scale = step * 2f
        val x = Math.round(p[0] * scale - side / 2f).coerceIn(0, maxOf(0, frameW - side)) and 1.inv()
        val y = Math.round(p[1] * scale - side / 2f).coerceIn(0, maxOf(0, frameH - side)) and 1.inv()
        return intArrayOf(x, y)
    }

    /** one frame's window off the chip: the window's planes, then the picture's row sample */
    fun offerWindow(frameNo: Long, at: IntArray, bytes: ByteArray) {
        val r = windows ?: return
        // The row sample is the same size whatever the window is, so what is left over is the
        // window, and a window is one and a half times its side squared. That is how a frame
        // still in flight when the plan changed is read with the size it was really cut at,
        // rather than dropped or split by the wrong one.
        val rowBytes = ((((frameW + Windows.ROW_STRIDE - 1) / Windows.ROW_STRIDE) + 3) / 4) * 4 *
            ((frameH + ringRowStep - 1) / ringRowStep)
        val win = bytes.size - rowBytes
        if (win <= 0) return
        val side = Math.round(Math.sqrt(win / 1.5)).toInt()
        if (side % 8 != 0 || side * side + 2 * (side / 2) * (side / 2) != win) return
        r.put(frameNo, at[0], at[1], side, bytes.copyOfRange(0, win), bytes.copyOfRange(win, bytes.size))
    }

    private fun onServe(tr: ServeTracker, serve: ServeTracker.Serve) {
        servesFound++
        var first = serve.first * step; var last = serve.last * step + step - 1
        // a ball moving while the player walks (fetching it) isn't a serve
        pose?.veto(first, last)?.let { why -> onNote("not a serve: $why"); return }
        if (onSpotted != null) {
            if (paused) { ignoredWhileMeasuring++; onNote("a serve while the last one was being measured: ignored"); return }
            paused = true
        }
        // Only the frames some track covers are worth cutting. Measuring along the live tracker's
        // tracks, the engine builds its stretches out of those tracks and cannot look anywhere
        // else; and when it refuses them and searches the crop instead, the flight it found was
        // inside the same span every time it has been measured (four occasions over two
        // sessions, the furthest 5 frames past the track's own last point). Everything else is
        // decoded, cut, written, served and parsed for nothing: over session 20261112_194529
        // that was 326 of 639 frames, 51% of every capture.
        //
        // 0.05 s either side, which is ten times the furthest the engine has been seen to reach.
        run {
            val fs = (listOf(serve.track) + serve.near).flatten().map { it[0].toLong() * step }
            if (fs.isNotEmpty()) {
                val margin = (captureFps / 20).toLong()
                first = maxOf(first, fs.min() - margin)
                last = minOf(last, fs.max() + margin)
            }
        }
        // at most 300 MB of frames (the engine takes up to 600 MB at once; over that, a frame at a
        // time: 29 s for one serve in 0.6.8): the run-up before the flight trimmed to 0.1 s first,
        // then the end
        run {
            val r = tr.cropFor(serve, frameW, frameH)
            val per = r[2].toLong() * r[3] + 2L * (r[2] / 2) * (r[3] / 2)
            val maxN = (300_000_000L / maxOf(1L, per)).coerceAtLeast(60L)
            if (last - first + 1 > maxN) {
                val flightStart = (serve.track.firstOrNull()?.get(0)?.toLong() ?: serve.first) * step
                first = maxOf(first, flightStart - captureFps / 10)
                if (last - first + 1 > maxN) last = first + maxN - 1
                trimmed++
            }
        }
        val dec = decoded
        if (dec - first > ptsRing.size || first >= dec) return          // too long ago to find again
        val fromUs = ptsRing[(first % ptsRing.size).toInt()] - 1
        val toUs = ptsRing[(minOf(last, dec - 1) % ptsRing.size).toInt()] + 1
        val w = frameW; val h = frameH
        val rect = tr.cropFor(serve, w, h)
        val id = ++captures
        onSpotted?.invoke(id)
        // the capture is written on its own thread, so the tracker keeps up
        Thread({ writeCapture(id, serve, first, fromUs, toUs, rect, w, h) }, "capture-$id").apply { priority = Thread.MIN_PRIORITY }.start()
    }

    /**
     * Captures written as windows round the ball rather than the whole crop. Off falls back to
     * the whole crop, which is what every version up to 0.10.7 wrote.
     */
    @Volatile var windowCaptures = true
    /** captures cut on the graphics chip (off after two failures) */
    @Volatile var gpuCaptures = true
    @Volatile var gpuFailures = 0; private set
    @Volatile var lastCaptureBy = ""; private set

    /** "Test with a video": how far the chip's captures are from the old way's (mean and largest difference per plane) */
    @Volatile var chipCheck = ""; private set

    private fun compareWithCopied(bin: File, frames: List<EncodedFrame>, fmt: android.media.MediaFormat, fromUs: Long, toUs: Long, rect: IntArray, w: Int, h: Int, count: Int): String {
        val per = rect[2] * rect[3] + 2 * (rect[2] / 2) * (rect[3] / 2)
        val ny = rect[2] * rect[3]; val nc = (rect[2] / 2) * (rect[3] / 2)
        val sums = DoubleArray(3); val maxs = IntArray(3); var compared = 0
        val chip = ByteArray(per)
        java.io.RandomAccessFile(bin, "r").use { f ->
            val dec = FrameDecoder(fmt)
            try {
                var k = 0
                while (!dec.ended && compared < count) {
                    if (k < frames.size) { if (dec.feed(frames[k], 5_000)) k++ } else if (k == frames.size) { if (dec.endOfStream()) k++ }
                    dec.drain(if (k > frames.size) 10_000 else 0) { pts, yuv ->
                        if (pts < fromUs || pts > toUs || compared >= count) return@drain
                        val old = Capture(w, h, rect, dec.fullRange, captureFps).crop(yuv)
                        f.seek(compared.toLong() * per); f.readFully(chip)
                        for (i in 0 until per) {
                            val plane = if (i < ny) 0 else if (i < ny + nc) 1 else 2
                            val d = kotlin.math.abs((old[i].toInt() and 0xff) - (chip[i].toInt() and 0xff))
                            sums[plane] += d.toDouble(); if (d > maxs[plane]) maxs[plane] = d
                        }
                        compared++
                    }
                }
            } finally { dec.release() }
        }
        if (compared == 0) return "nothing compared"
        val names = listOf("Y", "U", "V"); val sizes = listOf(ny, nc, nc)
        return "$compared frames: " + (0..2).joinToString(", ") { "${names[it]} mean ${"%.2f".format(sums[it] / (sizes[it].toDouble() * compared))} max ${maxs[it]}" }
    }

    /** the first frame in [fromUs, toUs] decoded the old way: its time, cropped planes, and range (for CaptureGl's fit) */
    private fun firstDecoded(frames: List<EncodedFrame>, fmt: android.media.MediaFormat, fromUs: Long, toUs: Long, rect: IntArray, w: Int, h: Int): Triple<Long, ByteArray, Boolean>? {
        val dec = FrameDecoder(fmt)
        var got: Triple<Long, ByteArray, Boolean>? = null
        try {
            var k = 0
            while (!dec.ended && got == null) {
                if (k < frames.size) { if (dec.feed(frames[k], 5_000)) k++ } else if (k == frames.size) { if (dec.endOfStream()) k++ }
                dec.drain(if (k > frames.size) 10_000 else 0) { pts, yuv ->
                    if (got == null && pts >= fromUs && pts <= toUs) got = Triple(pts, Capture(w, h, rect, dec.fullRange, captureFps).crop(yuv), dec.fullRange)
                }
            }
        } finally { dec.release() }
        return got
    }

    /** the serve's frames, all of them, decoded again and cropped: a header (JSON) and the frames (one file) */
    /**
     * [firstFrame] is the whole frame the capture starts at, which is NOT always
     * serve.first * step: the span is trimmed to the tracks, and again if the crop is big
     * enough that the frames would not fit in 300 MB. The track in the header counts its
     * frames from the capture's first, so it has to be told which frame that is.
     *
     * 0.10.5 passed serve.first here and so shifted every track by however far the trimming
     * had moved the start. The engine then looked for the ball up to 48 frames from where it
     * was, measured what it found there, got nothing, and searched the crop instead: in
     * session 20261112_215446 that happened on 8 of 10 serves and cost about 9 s each. The
     * 300 MB trim had carried the same fault since it was written, but it fires rarely;
     * trimming to the tracks fires on every serve, which is what brought it out.
     */
    /** a capture built from the chip's windows: the builder fed, and what it cost */
    private class RingCut(val builder: Windows.Builder, val n: Int, val fullRange: Boolean, val note: String)

    /**
     * The capture built from the windows the chip cut while the serve was in the air, with only
     * the handful of frames the background is medianed from decoded and cut.
     *
     * Null when the ring cannot serve this one: it was off, it has no colour map onto the
     * decoder's bytes yet (the first serve of a session makes one), it was too busy to keep
     * enough of the flight, or its windows are not where the serve's real track says the ball
     * was. The caller then cuts the capture the way it always has, which is also what makes
     * the fit: there is always a reading, whatever the chip did.
     */
    private fun cutFromRing(id: Int, frames: List<EncodedFrame>, fmt: android.media.MediaFormat,
                            fromUs: Long, toUs: Long, rect: IntArray, w: Int, h: Int,
                            firstFrame: Long, nGuess: Int, tracks: List<List<FloatArray>>): RingCut? {
        fun no(why: String): RingCut? { ringNote = why; return null }
        if (!windowRing) return null
        val ring = windows ?: return no("nothing was cut on the chip")
        val assets = assets ?: return no("no assets")
        if (!gpuCaptures) return no("the chip's captures are off")
        if (tracks.isEmpty()) return no("no track to put a window on")
        if (CaptureGl.calib == null) return no("the chip has no colour fit yet")
        // without a map onto the decoder's bytes the ring's windows are the chip's own numbers,
        // which the background would not match: the serve is cut the old way and makes the fit
        if (CaptureGl.ringCalib == null) return no("the ring has no colour fit yet: ${CaptureGl.ringNote}")
        val t0 = SystemClock.elapsedRealtime()
        // which frames the background wants: asked of a builder of the guessed length, because
        // the real one is only known once the decoder has been through the span
        val b0 = Windows.Builder(rect, nGuess + captureFps / 4, tracks)
        val want = b0.backgroundFrames
        val wanted = want.toHashSet()
        // Does the ring even hold this serve? Asked before anything is decoded, because a
        // decode it then throws away is the whole cost of the old way paid twice.
        run {
            val pre = ring.take(firstFrame, nGuess)
            val s0 = b0.side
            var w0 = 0; var h0 = 0
            for (i in 0 until nGuess) {
                val place = b0.placeOf(i) ?: continue
                w0++
                val at = pre.at[i] ?: continue
                // where the ball really was against where it was predicted to be: a window the
                // capture's cannot be cut out of is one the prediction missed, and a ring full
                // of them was following something else
                val dx = rect[0] + place[0] - at[0]; val dy = rect[1] + place[1] - at[1]
                if (dx >= 0 && dy >= 0 && dx + s0 <= pre.sides[i] && dy + s0 <= pre.sides[i]) h0++
            }
            if (w0 == 0 || h0 < w0 * 3 / 4) return no("the chip's windows fitted $h0 of $w0 frames")
        }
        val crops = HashMap<Int, ByteArray>()
        var j = 0
        val r = CaptureGl(assets).write(frames, fmt, fromUs, toUs, rect, w, h,
            { b -> if (j < want.size) crops[want[j++]] = b }, null, { i -> i in wanted })
        val n = r.seen
        if (n <= 0 || crops.isEmpty()) return no("decoded $n frames, kept ${crops.size}")
        val taken = ring.take(firstFrame, n)
        val b = Windows.Builder(rect, n, tracks)
        val side = b.side
        // the live window went where the ball was PREDICTED to be and the capture's goes where
        // it really was, so every one is cut again; how often that fails is how far the
        // prediction missed, and too often means the ring was following something else
        var need = 0; var got = 0; var missed = 0; var worst = 0
        val rowStep = ringRowStep
        for (i in 0 until n) {
            val place = b.placeOf(i)
            val at = taken.at[i]
            var win: ByteArray? = null
            if (place != null) {
                need++
                if (at != null) {
                    val from = taken.sides[i]
                    val dx = rect[0] + place[0] - at[0]; val dy = rect[1] + place[1] - at[1]
                    val d = maxOf(kotlin.math.abs(dx - (from - side) / 2), kotlin.math.abs(dy - (from - side) / 2))
                    if (d > worst) worst = d
                    win = taken.pixels[i]?.let { Windows.reCut(it, from, dx, dy, side) }
                }
                if (win == null) missed++ else got++
            }
            b.addParts(taken.rows[i]?.let { Windows.ringRows(it, rect, w, h, rowStep) }, win, crops[i])
        }
        if (need == 0 || got < need * 3 / 4) return no("the chip's windows covered $got of $need frames (decoded already)")
        ringNote = "$got of $need windows, missed $missed, worst ${worst} px off centre; " +
            "decoded ${r.decodeMs} ms (cutting ${crops.size} frames, ${r.cutMs} ms)"
        return RingCut(b, n, r.fullRange, "chip windows: $ringNote, ${SystemClock.elapsedRealtime() - t0} ms")
    }

    /**
     * How the ring's bytes map onto the decoder's, from a background frame the ring also holds.
     *
     * The chip sees the camera's own frame, before it is encoded; the background is decoded from
     * the recording. The two are the same picture through different paths, so the map is a gain
     * and an offset per plane (ColourFit, as CaptureGl fits the chip against the decoder). The
     * first serve of a session is cut the old way and makes this fit; later ones carry it in the
     * plan, and refitting then would compose it with itself, so it is only ever fitted once.
     * The rms is recorded either way, which is how a fit that has gone stale shows up.
     */
    private fun fitRing(frameNo: Long, crop: ByteArray, rect: IntArray) {
        val ring = windows ?: return
        val taken = ring.take(frameNo, 1)
        val at = taken.at[0]
        val chip = taken.pixels[0]
        if (at == null || chip == null) { CaptureGl.ringNote = "no window held for frame $frameNo"; return }
        val side = taken.sides[0]
        // The overlap, not the whole window. A live window is round the ball and the crop is
        // round the whole flight, so most of the time part of the window hangs over the crop's
        // edge -- on the first session, of four serves none had one that fitted, and the fit
        // never happened. Only the pixels both of them hold are compared.
        val w = rect[2]; val h = rect[3]
        val x0 = maxOf(0, at[0] - rect[0]); val y0 = maxOf(0, at[1] - rect[1])
        val x1 = minOf(w, at[0] - rect[0] + side); val y1 = minOf(h, at[1] - rect[1] + side)
        val ow = (x1 - x0) and 1.inv(); val oh = (y1 - y0) and 1.inv()
        if (ow < 64 || oh < 64) { CaptureGl.ringNote = "the window and the crop overlap by only ${ow}x$oh"; return }
        val cSide = side / 2; val cw = w / 2
        val a = ByteArray(ow * oh + 2 * (ow / 2) * (oh / 2))     // the chip's
        val d = ByteArray(a.size)                                 // the decoder's
        var o = 0
        for (y in 0 until oh) {
            val wy = y0 + rect[1] - at[1] + y                     // the row inside the window
            System.arraycopy(chip, wy * side + (x0 + rect[0] - at[0]), a, o, ow)
            System.arraycopy(crop, (y0 + y) * w + x0, d, o, ow)
            o += ow
        }
        val ow2 = ow / 2; val oh2 = oh / 2
        val wc0 = side * side; val cc0 = w * h
        val cSize = cSide * cSide; val cropC = cw * (h / 2)
        for (plane in 0..1) for (y in 0 until oh2) {
            val wy = (y0 + rect[1] - at[1]) / 2 + y
            System.arraycopy(chip, wc0 + plane * cSize + wy * cSide + (x0 + rect[0] - at[0]) / 2, a, o, ow2)
            System.arraycopy(crop, cc0 + plane * cropC + ((y0 / 2) + y) * cw + x0 / 2, d, o, ow2)
            o += ow2
        }
        val ny = ow * oh; val nc = ow2 * oh2
        val y = ColourFit.fit(a, d, 0, ny)
        val u = ColourFit.fit(a, d, ny, nc)
        val v = ColourFit.fit(a, d, ny + nc, nc)
        val fit = CaptureGl.Calib(CaptureGl.calib?.bt601 ?: false, y, u, v)
        CaptureGl.ringNote = "$fit; over ${ow}x$oh of a ${side}px window"
        if (CaptureGl.ringCalib == null && y.rms <= 6.0) CaptureGl.ringCalib = fit
    }

    /**
     * The crop nudged so its row sample's columns are the ones the chip sampled, and its first
     * row one the chip sampled too (see assets/shaders/crop_rows.frag). Only when the windows
     * are cut on the chip: otherwise the crop is what it always was.
     */
    private fun alignRect(r: IntArray, w: Int, h: Int): IntArray {
        if (!windowRing) return r
        val dx = r[0] % Windows.ROW_STRIDE
        val dy = r[1] % ringRowStep
        return intArrayOf(r[0] - dx, r[1] - dy, minOf(w - (r[0] - dx), r[2] + dx), minOf(h - (r[1] - dy), r[3] + dy))
    }

    private fun writeCapture(id: Int, serve: ServeTracker.Serve, firstFrame: Long, fromUs: Long, toUs: Long, rect0: IntArray, w: Int, h: Int) {
        val rect = alignRect(rect0, w, h)
        try {
            val t0 = SystemClock.elapsedRealtime()
            val frames = store.between(fromUs, toUs)
            val fmt = store.outputFormat ?: return
            if (frames.isEmpty()) { onError("capture #$id: its frames were no longer in memory"); return }
            // the clip's frames, now: the store keeps only the last few seconds (0.3 cut it after the
            // capture was written, by when they were gone). Written after the capture (0.8.2), so the
            // engine can start on the capture sooner; the list is a copy, its frames don't change.
            val clipFrames = if (clipDir != null) store.between(fromUs - 500_000, toUs + 300_000) else null
            val bin = File(captureDir, "cap_$id.bin")
            // 0.8.2: the engine reads the frames while they are cut (it waited for all of them, then
            // fetched them: 0.5-1.9 s more a serve). A first header now, with the count estimated;
            // the last one, with the decoder's colour range, when all are written.
            val per = rect[2] * rect[3] + 2 * (rect[2] / 2) * (rect[3] / 2)
            val prog = Writing(per).also { writing[id] = it }
            val nGuess = frames.count { it.ptsUs in fromUs..toUs }
            // The crop is mostly empty table. A windowed capture keeps a window round the
            // ball, one background frame and how bright each row was, which on these serves
            // is a fiftieth of the bytes -- and cutting, writing, serving and parsing the
            // crop was measured at about 25 ms a megabyte, seven of the nine seconds a
            // 287 MB serve took. The tracks say where the windows go, so without one there
            // is nothing to window and the whole crop is written as before.
            val scale = w.toFloat() / (w / 4)
            fun conv(t: List<FloatArray>) = t.map {
                floatArrayOf((it[0] * step - firstFrame).toFloat(), it[1] * scale, it[2] * scale, it[3] * scale)
            }
            val tracks = (listOf(serve.track) + serve.near).map(::conv).filter { it.size >= 2 }
            val windowed = windowCaptures && tracks.isNotEmpty()
            writeHead(id, Capture(w, h, rect, false, captureFps).header(
                ""","id":$id,"partial":true""" + (if (windowed) ""","windowed":true""" else ""), nGuess))
            onCaptureStarted?.invoke(Capture.Info(id, nGuess, per, "", 0, fromUs, toUs))
            var cap: Capture? = null
            var n = 0
            // the windows the chip cut while the serve happened, when there are enough of them:
            // then only the frames the background needs are cut, not all of them
            val fromRing = if (!windowed) null else try {
                cutFromRing(id, frames, fmt, fromUs, toUs, rect, w, h, firstFrame, nGuess, tracks)
            } catch (e: Throwable) { onError("capture #$id from the chip's windows: ${e.message}"); null }
            var builder = fromRing?.builder ?: if (windowed) Windows.Builder(rect, nGuess + captureFps / 4, tracks) else null
            if (fromRing != null) {
                cap = Capture(w, h, rect, fromRing.fullRange, captureFps)
                n = fromRing.n
                lastCaptureBy = fromRing.note
                onNote("capture #$id: $lastCaptureBy")
            }
            // where each crop goes: reduced into the windowed capture, or straight into the file
            val out = if (builder == null) FrameCountingOut(bin, prog) else null
            // one crop kept back, when the chip's windows still need a map onto the decoder's
            // bytes: a serve cut the old way goes past every frame anyway, so the fit is free
            val fitAt = if (windowRing && CaptureGl.ringCalib == null && windows != null) nGuess / 2 else -1
            var fitCrop: ByteArray? = null
            var seenAt = 0
            val sink: (ByteArray) -> Unit = { b ->
                if (seenAt == fitAt) fitCrop = b
                seenAt++
                if (builder != null) builder!!.add(b) else out!!.write(b)
            }
            try {
            // on the graphics chip (CaptureGl), else the frames copied out by the processor
            if (cap == null && assets != null && gpuCaptures) try {
                val t1 = SystemClock.elapsedRealtime()
                val reference = if (CaptureGl.calib == null) firstDecoded(frames, fmt, fromUs, toUs, rect, w, h) else null
                val refMs = SystemClock.elapsedRealtime() - t1
                val r = CaptureGl(assets).write(frames, fmt, fromUs, toUs, rect, w, h, sink, reference?.let { it.first to it.second })
                if (r.frames > 0) {
                    cap = Capture(w, h, rect, reference?.third ?: r.fullRange, captureFps)
                    n = r.frames
                    // replaying a video (testing): the first 20 frames cut the old way too, compared
                    if (blocking && builder == null) { chipCheck = compareWithCopied(bin, frames, fmt, fromUs, toUs, rect, w, h, 20); onNote("capture #$id, chip against copied: $chipCheck") }
                    lastCaptureBy = "chip: ${r.frames} frames, decode + cut ${r.decodeMs} ms (cutting ${r.cutMs} ms)" + (if (reference != null) ", fit ${refMs} ms: ${CaptureGl.calibNote}" else "")
                    onNote("capture #$id: $lastCaptureBy")
                }
            } catch (e: Throwable) {
                gpuFailures++
                onError("capture #$id on the chip: ${e.message}; copying it out instead")
                if (gpuFailures >= 2 || e.message?.contains("don't match") == true) gpuCaptures = false
            }
            if (cap == null) {
                val dec = FrameDecoder(fmt)
                // (a failure on the chip part way: the engine may have read its first frames already;
                // the processor's copy of the rest matches them to within the fit, rms under 3)
                var k = 0
                while (!dec.ended) {
                    if (k < frames.size) { if (dec.feed(frames[k], 5_000)) k++ } else if (k == frames.size) { if (dec.endOfStream()) k++ }
                    dec.drain(if (k > frames.size) 10_000 else 0) { pts, yuv ->
                        if (pts < fromUs || pts > toUs) return@drain
                        val c = cap ?: Capture(w, h, rect, dec.fullRange, captureFps).also { cap = it }
                        sink(c.crop(yuv)); n++
                    }
                }
                dec.release()
                lastCaptureBy = "copied out"
            }
            } finally { out?.close() }
            fitCrop?.let { try { fitRing(firstFrame + fitAt, it, rect) } catch (e: Exception) { CaptureGl.ringNote = "fit: ${e.message}" } }
            val c = cap ?: return
            // the windowed capture is written in one go at the end: the background is a
            // median of the frames, so it cannot be written before the last of them
            val bld = builder
            if (bld != null) {
                n = bld.count
                DataOutputStream(FileOutputStream(bin).buffered(1 shl 20)).use { o -> bld.writeBody(o) }
                lastCaptureBy += ", ${bld.side}x${bld.side} windows"
            }
            val head = c.header(""","id":$id,"fromUs":$fromUs,"toUs":$toUs,"why":"${serve.why}"""" +
                (bld?.headerExtra() ?: "") + serve.tracksJson(step, w.toFloat() / (w / 4), firstFrame, n), n)
            writeHead(id, head)
            prog.finish()
            val writeMs = SystemClock.elapsedRealtime() - t0
            var clip: File? = null
            if (clipFrames != null) try {
                val f = File(clipDir, "clip_$id.mp4")
                // with half a second more before (the toss) and a little after, for replaying it
                if (writeMp4(clipFrames, fmt, toUs + 300_000, f) > 0) clip = f
            } catch (e: Exception) { onError("clip #$id: ${e.message}") }
            onCapture(Capture.Info(id, n, c.frameBytes, head, writeMs, fromUs, toUs), clip)
        } catch (e: Exception) { onError("capture: ${e.message}") }
        finally { writing[id]?.finish() }
    }

    /** the header, swapped in whole (the engine may be reading the old one) */
    private fun writeHead(id: Int, head: String) {
        val tmp = File(captureDir, "cap_$id.json.tmp")
        tmp.writeText(head)
        tmp.renameTo(File(captureDir, "cap_$id.json"))
    }

    /** A capture being written: how many whole frames are on disk, and whether it is done. */
    class Writing(val per: Int) {
        private val lock = Object()
        @Volatile var frames = 0; private set
        @Volatile var done = false; private set
        fun wrote(n: Int) = synchronized(lock) { if (n > frames) { frames = n; lock.notifyAll() } }
        fun finish() = synchronized(lock) { done = true; lock.notifyAll() }
        /** until [upto] frames are written or the capture is done (or [timeoutMs] passes) */
        fun await(upto: Int, timeoutMs: Long) = synchronized(lock) {
            val end = SystemClock.elapsedRealtime() + timeoutMs
            while (!done && frames < upto) { val left = end - SystemClock.elapsedRealtime(); if (left <= 0) break; lock.wait(left) }
        }
        fun awaitDone(timeoutMs: Long) = await(Int.MAX_VALUE, timeoutMs)
    }

    /** writes a capture's frames, each one flushed to disk as it is complete, and counts them */
    private class FrameCountingOut(file: File, private val w: Writing) : java.io.OutputStream() {
        private val out = FileOutputStream(file).buffered(1 shl 20)
        private var bytes = 0L
        override fun write(b: Int) { out.write(b); bytes++; check() }
        override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); bytes += len; check() }
        private fun check() { val f = (bytes / w.per).toInt(); if (f > w.frames) { out.flush(); w.wrote(f) } }
        override fun flush() = out.flush()
        override fun close() = out.close()
    }

}
