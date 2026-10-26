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
    private val onCapture: (Capture.Info) -> Unit,
    private val onError: (String) -> Unit,
) {
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
    @Volatile var decoded = 0L; private set
    @Volatile var dropped = 0L; private set              // encoded frames dropped (the decoder fell behind)
    @Volatile var skipped = 0L; private set              // frames the tracker skipped (finding blobs fell behind)
    @Volatile var trackMsAvg = 0.0; private set         // per tracker frame: shrinking + finding + linking (on several threads)
    @Volatile var shrinkMsAvg = 0.0; private set
    @Volatile var findMsAvg = 0.0; private set
    @Volatile var linkMsAvg = 0.0; private set
    @Volatile var lagMs = 0L; private set
    @Volatile var servesFound = 0; private set
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
                    if (waitKey && !fr.key) continue                  // after a drop: start again at a keyframe
                    waitKey = false
                    while (running && !dec.feed(fr, 5_000)) drainOnce(dec)
                }
                if (dec != null) drainOnce(dec)
                if (dropped != seenDropped) { seenDropped = dropped; waitKey = true }   // frames lost: resume at a keyframe
                lagMs = (newest - lastPts) / 1000
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
        val t0 = System.nanoTime()
        val q = Downsample.quarter(yuv)
        shrinkMsAvg = avg(shrinkMsAvg, (System.nanoTime() - t0) / 1e6, ++shrinkN)
        val tr = tracker ?: ServeTracker(q.w, q.h, captureFps / step).also { tracker = it }
        tr.sample(q, index)                                    // the background, from every 24th frame
        val bg = tr.background()
        val found = if (bg == null) null else finders.submit(Callable {
            val d = detectors.get() ?: Detector(q.w, q.h).also { detectors.set(it) }
            val t = System.nanoTime()
            val c = d.find(q, bg)
            findMsAvg = avg(findMsAvg, (System.nanoTime() - t) / 1e6, index + 1)
            c
        })
        jobs.put(Job(index, found))
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
        val first = serve.first * step; val last = serve.last * step + step - 1
        val dec = decoded
        if (dec - first > ptsRing.size || first >= dec) return          // too long ago to find again
        val fromUs = ptsRing[(first % ptsRing.size).toInt()] - 1
        val toUs = ptsRing[(minOf(last, dec - 1) % ptsRing.size).toInt()] + 1
        val w = frameW; val h = frameH
        val rect = tr.cropFor(serve, w, h)
        val id = ++captures
        // the capture is written on its own thread, so the tracker keeps up
        Thread({ writeCapture(id, serve, fromUs, toUs, rect, w, h) }, "capture-$id").apply { priority = Thread.MIN_PRIORITY }.start()
    }

    /** the serve's frames, all of them, decoded again and cropped: a header (JSON) and the frames (one file) */
    private fun writeCapture(id: Int, serve: ServeTracker.Serve, fromUs: Long, toUs: Long, rect: IntArray, w: Int, h: Int) {
        try {
            val t0 = SystemClock.elapsedRealtime()
            val frames = store.between(fromUs, toUs)
            val fmt = store.outputFormat ?: return
            if (frames.isEmpty()) return
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
            onCapture(Capture.Info(id, n, c.frameBytes, head, SystemClock.elapsedRealtime() - t0, fromUs, toUs))
        } catch (e: Exception) { onError("capture: ${e.message}") }
    }
}
