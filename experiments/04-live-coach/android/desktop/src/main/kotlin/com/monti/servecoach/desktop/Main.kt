package com.monti.servecoach.desktop

import com.monti.servecoach.core.Capture
import com.monti.servecoach.core.Downsample
import com.monti.servecoach.core.Plane
import com.monti.servecoach.core.ServeTracker
import com.monti.servecoach.core.Yuv
import java.io.DataInputStream
import java.io.EOFException
import java.io.File

/**
 * The core on a video file, as the phone runs it: frames one by one through the tracker; for each
 * serve it reports, those frames decoded again and cropped into a capture file for the engine.
 *   desktop clip.mp4 outDir      -> outDir/<clip>_<n>.cap and a line of JSON per serve
 */
fun main(args: Array<String>) {
    val clip = File(args[0]); val outDir = File(args[1]).apply { mkdirs() }
    val (W, H) = probe(clip)
    // as the app: the tracker sees every 2nd frame (120 a second), the capture all of them
    val step = (System.getenv("STEP") ?: "2").toInt()
    val tracker = ServeTracker(W / 4, H / 4, 240 / step)
    // warm the background up from frames spread over the clip (live, it is always warm)
    val total = countFrames(clip)
    val every = maxOf(1L, total / 6)
    val spread = ArrayList<com.monti.servecoach.core.Quarter>()
    var j = 0L
    frames(clip, W, H) { f -> if (j % every == 0L && spread.size < 5) spread.add(Downsample.quarter(f)); j++ }
    tracker.prime(spread)
    if (System.getenv("DEBUG") != null) tracker.onTrack = { pts, why ->
        val a = pts.first(); val b = pts.last()
        println("  track ${a[0].toInt()}-${b[0].toInt()} (${pts.size}) ${a[1].toInt() * 4},${a[2].toInt() * 4} -> ${b[1].toInt() * 4},${b[2].toInt() * 4} r ${"%.1f".format(a[3] * 4)}-${"%.1f".format(b[3] * 4)}: ${why ?: "SERVE"}")
    }
    val serves = ArrayList<ServeTracker.Serve>()
    var n = 0L
    val t0 = System.nanoTime()
    frames(clip, W, H) { f ->
        if (n % step == 0L) {
            val s = tracker.push(Downsample.quarter(f), n / step)
            if (s != null) serves.add(s.copy(first = s.first * step, last = s.last * step + step - 1))
        }
        n++
    }
    // the clip ended: let a waiting serve out (live, the frames keep coming)
    val quiet = com.monti.servecoach.core.Quarter(W / 4, H / 4, FloatArray(W / 4 * H / 4), FloatArray(W / 4 * H / 4), FloatArray(W / 4 * H / 4))
    repeat(120) { tracker.push(quiet)?.let { serves.add(it.copy(first = it.first * step, last = it.last * step + step - 1)) } }
    val ms = (System.nanoTime() - t0) / 1e6
    // the second pass: the serves' frames, cropped (the phone decodes them again from its memory)
    val caps = serves.map { s -> s to Capture(W, H, tracker.cropFor(s, W, H), false, 240) }
    var i = 0L
    frames(clip, W, H) { f ->
        for ((s, c) in caps) if (i in s.first..s.last) c.add(f)
        i++
    }
    for ((k, sc) in caps.withIndex()) {
        val (s, c) = sc
        val file = File(outDir, "${clip.nameWithoutExtension}_${k + 1}.cap")
        c.write(file, ""","first":${s.first},"last":${s.last}""")
        println("""{"clip":"${clip.name}","serve":${k + 1},"frames":"${s.first}-${s.last}","rect":[${c.rect.joinToString(",")}],"ballR":${"%.1f".format(s.ballRadius * 4)},"tracks":${s.tracks},"why":"${s.why}","file":"${file.path}"}""")
    }
    println("""{"clip":"${clip.name}","framesSeen":$n,"serves":${serves.size},"msPerFrame":${"%.2f".format(ms / n)},"candidatesPerFrame":${"%.2f".format(tracker.candidatesSeen.toDouble() / n)},"rejected":"${tracker.rejected}"}""")
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
