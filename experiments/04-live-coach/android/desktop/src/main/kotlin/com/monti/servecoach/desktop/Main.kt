package com.monti.servecoach.desktop

import com.monti.servecoach.core.Capture
import com.monti.servecoach.core.Downsample
import com.monti.servecoach.core.Plane
import com.monti.servecoach.core.ServeTracker
import com.monti.servecoach.core.Windows
import com.monti.servecoach.core.Yuv
import java.io.DataInputStream
import java.io.EOFException
import java.io.File

/**
 * The core on a video file, as the phone runs it: frames one by one through the tracker; for each
 * serve it reports, those frames decoded again and cropped into a capture file for the engine.
 *   desktop clip.mp4 outDir      -> outDir/<clip>_<n>.cap and a line of JSON per serve
 */
private val allDetectors = java.util.Collections.synchronizedList(ArrayList<com.monti.servecoach.core.Detector>())
private val benchFind = java.util.concurrent.atomic.AtomicLong()
private val benchAlpha = java.util.concurrent.atomic.AtomicLong()
private val benchFrames = java.util.concurrent.atomic.AtomicLong()

fun main(args: Array<String>) {
    // fit chip.bin decoded.yuv: ColourFit on two planes of the same size (tests)
    if (args[0] == "fit") {
        val a = File(args[1]).readBytes(); val b = File(args[2]).readBytes(); val n = args[3].toInt()
        val f = com.monti.servecoach.core.ColourFit.fit(a, b, 0, n)
        println("gain %.4f offset %.2f rms %.2f".format(f.gain, f.offset, f.rms)); return
    }
    // setup frame.yuv w h [tilt]: SetupCheck on a raw yuv444p picture (ffmpeg ... -pix_fmt yuv444p -f rawvideo)
    if (args[0] == "setup") {
        val w = args[2].toInt(); val h = args[3].toInt(); val b = File(args[1]).readBytes(); val n = w * h
        val (a, t) = com.monti.servecoach.core.SetupCheck.advise(b.copyOfRange(0, n), b.copyOfRange(n, 2 * n), b.copyOfRange(2 * n, 3 * n), w, h, args.getOrNull(4)?.toFloat() ?: 0f)
        println("${File(args[1]).name}: $a${t?.let { "  ($it)" } ?: ""}"); return
    }
    // selftest: the tracker on made-up candidates (0.8.0, K2: a serve followed by the ball rolling
    // back, slow and long, which out-scored the serve and took it down as "a body")
    if (args[0] == "selftest") {
        val tr = ServeTracker(480, 270, 120)
        val skipped = ArrayList<String>()
        tr.onSkipped = { _, _, why -> skipped.add(why) }
        var got: ServeTracker.Serve? = null
        for (i in 0L until 400L) {
            val c = ArrayList<FloatArray>()
            if (i in 20..55) c.add(floatArrayOf(100f + (i - 20) * 8.5f, 150f + (i - 20) * 0.5f, 3.2f))     // the serve
            if (i in 60..170) c.add(floatArrayOf(400f - (i - 60) * 2.3f, 180f, 3.0f))                    // rolling back
            tr.pushFound(i, c)?.let { got = it }
        }
        val s = got
        println(if (s != null && s.track.first()[0].toInt() == 20) "selftest: ok (the serve, ${s.why})" else "selftest: FAILED (serve ${s?.why}, skipped $skipped)")
        return
    }
    val clip = File(args[0]); val outDir = File(args[1]).apply { mkdirs() }
    val (W, H) = probe(clip)
    // as the app: the tracker sees every 2nd frame (120 a second), the capture all of them
    val step = (System.getenv("STEP") ?: "2").toInt()
    val tracker = ServeTracker(W / 4, H / 4, 240 / step)
    // MINPOINTS / MAXGAP: what the load guard sets on the phone when it is thinning. With
    // STEP=4 (every other tracker frame, as thinning does) these are how the fix is told from
    // the fault -- 6 and 4 keep a short flight, 12 and 2 throw it away as "short".
    System.getenv("MINPOINTS")?.let { tracker.minPoints = it.toInt() }
    System.getenv("MAXGAP")?.let { tracker.maxGap = it.toInt() }
    // warm the background up from frames spread over the clip (live, it is always warm)
    val total = countFrames(clip)
    val every = maxOf(1L, total / 6)
    val spread = ArrayList<com.monti.servecoach.core.Quarter>()
    var j = 0L
    frames(clip, W, H) { f -> if (j % every == 0L && spread.size < 5) spread.add((if (System.getenv("GPU") != null) Downsample.quarterMean(f) else Downsample.quarter(f))); j++ }
    // NOPRIME=1: as the phone. It has no future to warm a background from -- at the moment of
    // a serve its background is the median of what came BEFORE, and nothing else. Priming from
    // frames spread over the whole clip hands the harness a background the phone never has.
    if (System.getenv("NOPRIME") == null) tracker.prime(spread)
    if (System.getenv("DEBUG") != null) tracker.onTrack = { pts, why ->
        val a = pts.first(); val b = pts.last()
        // the radii, not just the ends: a blob's area is a LOWER bound on the ball (anything
        // that matches the background drops out of the mask), so the spread is what says
        // whether the ball was seen whole, and the median is what judge() rejects on.
        val rs = pts.map { it[3] * 4 }.sorted()
        fun pc(f: Double) = rs[((rs.size - 1) * f).toInt()]
        println("  track ${a[0].toInt()}-${b[0].toInt()} (${pts.size}) ${a[1].toInt() * 4},${a[2].toInt() * 4} -> ${b[1].toInt() * 4},${b[2].toInt() * 4}" +
            " r ${"%.1f".format(a[3] * 4)}-${"%.1f".format(b[3] * 4)}" +
            " (min ${"%.1f".format(rs.first())} p25 ${"%.1f".format(pc(0.25))} med ${"%.1f".format(pc(0.5))} p85 ${"%.1f".format(pc(0.85))} max ${"%.1f".format(rs.last())})" +
            ": ${why ?: "SERVE"}")
    }
    val serves = ArrayList<ServeTracker.Serve>()
    // RING=1: the live window ring reproduced here (RingSim), so the capture it would build can
    // be measured against the one the phone cuts. RINGLAG is how many tracker frames the live
    // track runs behind -- the number the phone could not give.
    val ringLag = (System.getenv("RINGLAG") ?: "2").toInt()
    val ringMargin = (System.getenv("RINGMARGIN") ?: "72").toInt()
    val ringRowStep = (System.getenv("RINGROWSTEP") ?: "2").toInt()
    val ring = if (System.getenv("RING") != null) RingSim(W, H, step, ringLag, ringMargin, ringRowStep, 600) else null
    var n = 0L
    val t0 = System.nanoTime()
    var dsNs = 0L; var pushNs = 0L; var pushed = 0
    // PAR=1: as the phone does it: blobs found on 3 threads with the background of the moment, linked in order up to 24 frames later
    val par = if (System.getenv("PAR") != null) Parallel(tracker) else null
    // THIN=1: the load guard's thinning, faithfully -- every OTHER tracker frame is dropped
    // while the tracker keeps the rate it was built with. STEP=4 is not the same thing: it also
    // halves the fps the tracker is told, which moves notABall's speed bar with it, so a serve
    // can fail there for a reason the phone would never have.
    val thin = System.getenv("THIN") != null
    // LOSE=n: n tracker frames gone out of every twelve, in a clump -- what a full finder queue
    // does, and what thinning was invented to replace. This is how a live failure gets
    // reproduced here instead of guessed at.
    val lose = (System.getenv("LOSE") ?: "0").toInt()
    frames(clip, W, H) { f ->
        if (n % step == 0L && !(thin && (n / step) % 2L == 1L) && !(lose > 0 && (n / step) % 12L < lose)) {
            val a = System.nanoTime()
            val q = (if (System.getenv("GPU") != null) Downsample.quarterMean(f) else Downsample.quarter(f))
            val b = System.nanoTime()
            val s = if (par == null) tracker.push(q, n / step) else par.push(q, n / step)
            dsNs += b - a; pushNs += System.nanoTime() - b; pushed++
            if (s != null) serves.add(s.copy(first = s.first * step, last = s.last * step + step - 1))
            ring?.seen(tracker, n / step)
        }
        ring?.cut(f, n)
        // the room, from frames the serve does not cover: one in sixteen, as the phone takes
        // keyframes from between serves
        if (ring != null && n % 16 == 0L) ring.seeRoom(f, n)
        n++
    }
    par?.drain()?.let { serves.addAll(it.map { s -> s.copy(first = s.first * step, last = s.last * step + step - 1) }) }
    // the clip ended: let a waiting serve out (live, the frames keep coming)
    val quiet = com.monti.servecoach.core.Quarter(W / 4, H / 4, FloatArray(W / 4 * H / 4), FloatArray(W / 4 * H / 4), FloatArray(W / 4 * H / 4))
    repeat(120) { tracker.push(quiet)?.let { serves.add(it.copy(first = it.first * step, last = it.last * step + step - 1)) } }
    val ms = (System.nanoTime() - t0) / 1e6
    // the second pass: the serves' frames, cropped (the phone decodes them again from its memory)
    // with the ring, the crop is nudged so the chip's row sample and the capture's are the same
    // columns and rows (Windows.alignRect); both captures then use it, so they can be compared
    val caps = serves.map { s ->
        val r = tracker.cropFor(s, W, H).let { if (ring != null) Windows.alignRect(it, W, H, ringRowStep) else it }
        s to Capture(W, H, r, false, 240)
    }
    var i = 0L
    frames(clip, W, H) { f ->
        for ((s, c) in caps) if (i in s.first..s.last) c.add(f)
        i++
    }
    for ((k, sc) in caps.withIndex()) {
        val (s, c) = sc
        val file = File(outDir, "${clip.nameWithoutExtension}_${k + 1}.cap")
        c.write(file, ""","first":${s.first},"last":${s.last}""" + s.tracksJson(step, W.toFloat() / (W / 4), s.first))
        // WINDOWS=3 also writes the capture as small windows round the ball: what the phone would
        // send once it stops cutting the whole box. The same file, a fiftieth of the bytes.
        System.getenv("WINDOWS")?.let { radii ->
            val sc = W.toFloat() / (W / 4)
            fun conv(t: List<FloatArray>) = t.map {
                floatArrayOf((it[0] * step - s.first).toFloat(), it[1] * sc, it[2] * sc, it[3] * sc)
            }
            val wf = File(outDir, "${clip.nameWithoutExtension}_${k + 1}.win.cap")
            val side = com.monti.servecoach.core.Windows.write(wf, c, listOf(conv(s.track)) + s.near.map { conv(it) },
                ""","first":${s.first},"last":${s.last}""" + s.tracksJson(step, sc, s.first), radii.toDouble())
            println("  windows ${side}x$side: ${wf.name} ${wf.length() / 1000} kB against ${file.length() / 1000} kB")
        }
        ring?.let { r ->
            val sc = W.toFloat() / (W / 4)
            fun conv(t: List<FloatArray>) = t.map {
                floatArrayOf((it[0] * step - s.first).toFloat(), it[1] * sc, it[2] * sc, it[3] * sc)
            }
            val tracks = (listOf(s.track) + s.near).map(::conv).filter { it.size >= 2 }
            val built = r.build(c.rect, tracks, c.frames, s.first)
            println("  ring (lag $ringLag, margin $ringMargin): ${built.note}")
            built.builder?.let { b ->
                val rf = File(outDir, "${clip.nameWithoutExtension}_${k + 1}.ring.cap")
                val head = c.header(b.headerExtra() + ""","first":${s.first},"last":${s.last}""" +
                    s.tracksJson(step, sc, s.first), c.frames.size).toByteArray()
                java.io.DataOutputStream(java.io.FileOutputStream(rf).buffered(1 shl 20)).use { o ->
                    o.writeInt(head.size); o.write(head); b.writeBody(o)
                }
                println("  ring: ${rf.name} ${rf.length() / 1000} kB against ${file.length() / 1000} kB")
            }
        }
        println("""{"clip":"${clip.name}","serve":${k + 1},"frames":"${s.first}-${s.last}","rect":[${c.rect.joinToString(",")}],"ballR":${"%.1f".format(s.ballRadius * 4)},"tracks":${s.tracks},"why":"${s.why}","file":"${file.path}"}""")
    }
    if (benchFrames.get() > 0) {
        val f = benchFrames.get()
        println("""{"maskbench":"$f frames","findMs":${"%.3f".format(benchFind.get() / 1e6 / f)},"fromChipMs":${"%.3f".format(benchAlpha.get() / 1e6 / f)}}""")
    }
    run {
        val ds = synchronized(allDetectors) { allDetectors.toList() }
        val parts = LongArray(3); var frames = 0L
        for (d in ds) { for (i in 0..2) parts[i] += d.nanos[i] }
        frames = pushed.toLong()
        val sat = ds.sumOf { it.saturatedFrames }
        val w = ds.maxByOrNull { it.worstSaturation[0] }?.worstSaturation
        val su = ds.maxByOrNull { kotlin.math.abs(it.shiftU) }?.shiftU ?: 0f
        val sv = ds.maxByOrNull { kotlin.math.abs(it.shiftV) }?.shiftV ?: 0f
        println("""{"drift":{"u":${"%.1f".format(su)},"v":${"%.1f".format(sv)}}}""")
        if (w != null) println("""{"saturated":$sat,"worst":${"%.3f".format(w[0])},"meanDy":${"%.1f".format(w[1])},"meanDu":${"%.1f".format(w[2])},"meanDv":${"%.1f".format(w[3])},"gain":"${"%.3f".format(w[4])}-${"%.3f".format(w[5])}"}""")
        if (frames > 0) println("""{"findParts":{"mask":${"%.2f".format(parts[0] / 1e6 / frames)},"open":${"%.2f".format(parts[1] / 1e6 / frames)},"blobs":${"%.2f".format(parts[2] / 1e6 / frames)}}}""")
    }
    println("""{"clip":"${clip.name}","framesSeen":$n,"serves":${serves.size},"msPerFrame":${"%.2f".format(ms / n)},"downsampleMs":${"%.2f".format(dsNs / 1e6 / pushed)},"trackMs":${"%.2f".format(pushNs / 1e6 / pushed)},"sections":"${tracker.nanos.joinToString(",") { "%.2f".format(it / 1e6 / pushed) }}","candidatesPerFrame":${"%.2f".format(tracker.candidatesSeen.toDouble() / n)},"rejected":"${tracker.rejected}","blobsRefused":"small=${tracker.refusedBlobs[0]} big=${tracker.refusedBlobs[1]} long=${tracker.refusedBlobs[2]} hollow=${tracker.refusedBlobs[3]}","worstAspect":${"%.2f".format(tracker.worstAspect)}}""")
}

fun probe(clip: File): Pair<Int, Int> {
    val p = ProcessBuilder("ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries", "stream=width,height", "-of", "csv=p=0", clip.path).start()
    val (w, h) = p.inputStream.bufferedReader().readText().trim().split(",").map { it.toInt() }
    return w to h
}

fun countFrames(clip: File): Long {
    val p = ProcessBuilder("ffprobe", "-v", "error", "-select_streams", "v:0", "-count_packets", "-show_entries", "stream=nb_read_packets", "-of", "csv=p=0", clip.path).start()
    return p.inputStream.bufferedReader().readText().trim().toLong()
}

fun frames(clip: File, W: Int, H: Int, each: (Yuv) -> Unit) {
    val p = ProcessBuilder("ffmpeg", "-v", "error", "-i", clip.path, "-f", "rawvideo", "-pix_fmt", "yuv420p", "-").start()
    val ins = DataInputStream(p.inputStream.buffered(1 shl 22))
    val size = W * H * 3 / 2
    val buf = ByteArray(size)
    try {
        while (true) {
            ins.readFully(buf)
            val f = Yuv(W, H, Plane(buf, 0, W, 1), Plane(buf, W * H, W / 2, 1), Plane(buf, W * H + W * H / 4, W / 2, 1), false)
            each(f)
        }
    } catch (_: EOFException) {}
    p.destroy()
}

class Parallel(private val tracker: ServeTracker) {
    private val pool = java.util.concurrent.Executors.newFixedThreadPool(3)
    private val detectors = ThreadLocal<com.monti.servecoach.core.Detector>()
    private val waiting = ArrayDeque<Pair<Long, java.util.concurrent.Future<List<FloatArray>>?>>()

    fun push(q: com.monti.servecoach.core.Quarter, index: Long): ServeTracker.Serve? {
        tracker.sample(q, index)
        val bg = tracker.background()
        val f = if (bg == null) null else pool.submit(java.util.concurrent.Callable<List<FloatArray>> {
            val d = detectors.get() ?: com.monti.servecoach.core.Detector(q.w, q.h).also { detectors.set(it); allDetectors.add(it) }
            // MASKBENCH: the same frame found twice -- the mask cut here, then taken as the
            // graphics chip hands it over. The chip's own two passes are not in this (they run
            // while the chip is already holding the picture); what is in it is everything the
            // processor stops doing: makeMask, and the small picture fromRgba built for it.
            if (System.getenv("MASKBENCH") != null) {
                val rgba = ByteArray(q.w * q.h * 4)
                var t0 = System.nanoTime(); d.find(q, bg); benchFind.addAndGet(System.nanoTime() - t0)
                // the chip's mask, as it would arrive: this frame's own, before open2 erodes it
                d.maskInto(q, bg, rgba, 3)
                t0 = System.nanoTime(); d.findFromAlpha(rgba, 0); benchAlpha.addAndGet(System.nanoTime() - t0)
                benchFrames.incrementAndGet()
            }
            d.find(q, bg)
        })
        waiting.addLast(index to f)
        return if (waiting.size > 24) one() else null
    }

    private fun one(): ServeTracker.Serve? {
        val (i, f) = waiting.removeFirst()
        return tracker.pushFound(i, f?.get() ?: emptyList())
    }

    fun drain(): List<ServeTracker.Serve> {
        val out = ArrayList<ServeTracker.Serve>()
        while (waiting.isNotEmpty()) one()?.let { out.add(it) }
        pool.shutdown()
        return out
    }
}
