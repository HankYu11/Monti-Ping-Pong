package com.monti.servecoach

import android.os.SystemClock
import com.monti.servecoach.core.Capture
import com.monti.servecoach.core.Detector
import com.monti.servecoach.core.Downsample
import com.monti.servecoach.core.Quarter
import com.monti.servecoach.core.ServeTracker
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
    private val onCapture: (Capture.Info, File?) -> Unit,
    private val onError: (String) -> Unit,
    /** the player's pose, to tell walking from serving (null: not used) */
    private val pose: PoseWatcher? = null,
    private val onNote: (String) -> Unit = {},
    /** live: one serve at a time; called when one is spotted (its number), then nothing until [resume] */
    private val onSpotted: ((Int) -> Unit)? = null,
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
        tracker = (tracker ?: ServeTracker(q.w, q.h, captureFps / step)).also { it.prime(qs) }
    }

    /** from the encoder (or the replay): never blocks the camera; frames are dropped when we fall behind */
    fun offer(fr: EncodedFrame) {
        if (blocking) { queue.put(fr); return }
        if (!queue.offer(fr)) dropped++
    }

    // the time of each decoded frame, by its order: the tracker counts frames, the store keeps times
    private val ptsRing = LongArray(8192)

    private companion object { val END = EncodedFrame(-1, false, ByteArray(0)) }

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
        val tr = tracker ?: ServeTracker(rows.w, rows.h, captureFps / step).also { tracker = it }
        // the background learns from every 24th frame, before that frame's blobs are found
        val q0 = if (tr.needsFrame(index)) Downsample.quarter(rows).also { tr.sample(it, index) } else null
        val bg = tr.background()
        val found = if (bg == null) null else finders.submit(Callable {
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
        val tr = tracker ?: ServeTracker(w, h, captureFps / step).also { tracker = it }
        val f = flip
        val q0 = if (tr.needsFrame(index)) Downsample.fromRgba(rgba, w, h, f).also { tr.sample(it, index) } else null
        val bg = tr.background()
        val found = if (bg == null) null else finders.submit(Callable {
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
                if (serve != null) onServe(tr, serve)
            }
        } catch (_: InterruptedException) {
        } catch (e: Exception) { onError("tracker: ${e.message}") }
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
        Thread({ writeCapture(id, serve, fromUs, toUs, rect, w, h) }, "capture-$id").apply { priority = Thread.MIN_PRIORITY }.start()
    }

    /** the serve's frames, all of them, decoded again and cropped: a header (JSON) and the frames (one file) */
    private fun writeCapture(id: Int, serve: ServeTracker.Serve, fromUs: Long, toUs: Long, rect: IntArray, w: Int, h: Int) {
        try {
            val t0 = SystemClock.elapsedRealtime()
            val frames = store.between(fromUs, toUs)
            val fmt = store.outputFormat ?: return
            if (frames.isEmpty()) { onError("capture #$id: its frames were no longer in memory"); return }
            // the clip from these same frames, now: the store keeps only the last few seconds
            // (0.3 cut it after the capture was written, by when they were gone)
            var clip: File? = null
            if (clipDir != null) try {
                val f = File(clipDir, "clip_$id.mp4")
                // with half a second more before (the toss) and a little after, for replaying it
                if (writeMp4(store.between(fromUs - 500_000, toUs + 300_000), fmt, toUs + 300_000, f) > 0) clip = f
            } catch (e: Exception) { onError("clip #$id: ${e.message}") }
            val dec = FrameDecoder(fmt)
            val bin = File(captureDir, "cap_$id.bin")
            var cap: Capture? = null
            var n = 0
            FileOutputStream(bin).buffered(1 shl 20).use { out ->
                var k = 0
                while (!dec.ended) {
                    if (k < frames.size) { if (dec.feed(frames[k], 5_000)) k++ } else if (k == frames.size) { if (dec.endOfStream()) k++ }
                    dec.drain(if (k > frames.size) 10_000 else 0) { pts, yuv ->
                        if (pts < fromUs || pts > toUs) return@drain
                        val c = cap ?: Capture(w, h, rect, dec.fullRange, captureFps).also { cap = it }
                        out.write(c.crop(yuv)); n++
                    }
                }
            }
            dec.release()
            val c = cap ?: return
            val head = c.header(""","id":$id,"fromUs":$fromUs,"toUs":$toUs,"why":"${serve.why}"""" + serve.tracksJson(step, w.toFloat() / (w / 4), serve.first * step), n)
            File(captureDir, "cap_$id.json").writeText(head)
            onCapture(Capture.Info(id, n, c.frameBytes, head, SystemClock.elapsedRealtime() - t0, fromUs, toUs), clip)
        } catch (e: Exception) { onError("capture: ${e.message}") }
    }
}
