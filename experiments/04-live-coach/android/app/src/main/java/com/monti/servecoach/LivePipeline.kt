package com.monti.servecoach

import android.os.SystemClock
import com.monti.servecoach.core.Capture
import com.monti.servecoach.core.Downsample
import com.monti.servecoach.core.ServeTracker
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Watches the stream for serves. Encoded frames come in (from the camera's encoder, or a video
 * being replayed); a decoder turns them back into pictures; every 2nd one (120 a second) goes to
 * the serve tracker at a quarter of its size. When the tracker reports a serve, its frames are
 * decoded again from the store, all of them, cropped to where the ball flew, and written to a
 * capture for the engine.
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
    private val thread = Thread({ run() }, "serve-tracker")
    private val step = 2                                   // the tracker sees every 2nd frame

    // for the screen and the report
    @Volatile var decoded = 0L; private set
    @Volatile var dropped = 0L; private set
    @Volatile var trackMsAvg = 0.0; private set
    @Volatile var lagMs = 0L; private set
    @Volatile var servesFound = 0; private set
    var tracker: ServeTracker? = null; private set
    private var captures = 0

    fun start() = thread.start()
    fun stop() { running = false; thread.interrupt(); thread.join(1000) }

    /** replay: the video ended; decode what's left and let a serve still being watched out. Blocks until done. */
    fun finish() { queue.put(END); thread.join() }

    /**
     * Replay: learn the background from keyframes spread over the video before it plays (live, the
     * camera has been running a while; a video may start with the serve). Call before [start].
     */
    fun prime(frames: List<EncodedFrame>, count: Int = 5) {
        val fmt = store.outputFormat ?: return
        val keys = frames.filter { it.key }
        if (keys.isEmpty()) return
        val pick = (0 until count).map { keys[it * (keys.size - 1) / maxOf(1, count - 1)] }.distinct()
        val qs = ArrayList<com.monti.servecoach.core.Quarter>()
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
        } catch (e: Exception) { onError("tracker: ${e.message}") }
        dec?.release()
    }

    private var lastPts = 0L
    private var avgN = 0
    private var frameW = 0
    private var frameH = 0

    /** the end of a replayed video: the decoder's last pictures, then quiet frames so a serve still being watched is let out */
    private fun flush(dec: FrameDecoder?) {
        if (dec != null) {
            var tries = 0
            while (!dec.endOfStream() && tries++ < 100) drainOnce(dec)
            tries = 0
            while (!dec.ended && tries++ < 500) dec.drain(10_000) { pts, yuv -> onPicture(pts, yuv) }
        }
        val tr = tracker ?: return
        val quiet = com.monti.servecoach.core.Quarter(tr.w, tr.h, FloatArray(tr.w * tr.h), FloatArray(tr.w * tr.h), FloatArray(tr.w * tr.h))
        repeat(120) { tr.push(quiet)?.let { onServe(tr, it) } }
    }

    private fun drainOnce(dec: FrameDecoder) { dec.drain(0) { pts, yuv -> onPicture(pts, yuv) } }

    private fun onPicture(pts: Long, yuv: com.monti.servecoach.core.Yuv) {
        val n = decoded++
        ptsRing[(n % ptsRing.size).toInt()] = pts
        lastPts = pts
        if (n % step != 0L) return
        frameW = yuv.width; frameH = yuv.height
        val t0 = SystemClock.elapsedRealtimeNanos()
        val tr = tracker ?: ServeTracker(yuv.width / 4, yuv.height / 4, captureFps / step).also { tracker = it }
        val serve = tr.push(Downsample.quarter(yuv), n / step)
        val ms = (SystemClock.elapsedRealtimeNanos() - t0) / 1e6
        avgN++; trackMsAvg += (ms - trackMsAvg) / minOf(avgN, 240)
        if (serve != null) onServe(tr, serve)
    }

    private fun onServe(tr: ServeTracker, serve: ServeTracker.Serve) {
        servesFound++
        val first = serve.first * step; val last = serve.last * step + step - 1
        if (decoded - first > ptsRing.size || first >= decoded) return          // too long ago to find again
        val fromUs = ptsRing[(first % ptsRing.size).toInt()] - 1
        val toUs = ptsRing[(minOf(last, decoded - 1) % ptsRing.size).toInt()] + 1
        val w = frameW; val h = frameH
        val rect = tr.cropFor(serve, w, h)
        val id = ++captures
        // the capture is written on its own thread, so the tracker keeps up
        Thread({ writeCapture(id, serve, fromUs, toUs, rect, w, h) }, "capture-$id").start()
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
            val head = c.header(""","id":$id,"fromUs":$fromUs,"toUs":$toUs,"why":"${serve.why}"""", n)
            File(captureDir, "cap_$id.json").writeText(head)
            onCapture(Capture.Info(id, n, c.frameBytes, head, SystemClock.elapsedRealtime() - t0, fromUs, toUs))
        } catch (e: Exception) { onError("capture: ${e.message}") }
    }
}
