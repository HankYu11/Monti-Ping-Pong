package com.monti.servecoach.desktop

import com.monti.servecoach.core.Capture
import com.monti.servecoach.core.Windows
import java.io.DataInputStream
import java.io.File

/**
 * Turn a whole-crop `.cap` into the windowed form, through the app's own [Windows.write].
 *
 * The point is to measure a REAL serve both ways without a phone. `test/capfromclip.py` rebuilds
 * the capture a session measured, out of the clip it saved beside it; this cuts that capture down
 * to windows exactly as the phone would. The two files then go through `test/cap.mjs --pair`, and
 * any drift between them is the format's, not a simulation of it.
 *
 * Rendered clips cannot stand in for this: spinsim renders no mains flicker, and the flicker
 * correction is the part of the windowed format most likely to be wrong.
 *
 * Run it with the desktop module's lib directory on the classpath (Kotlin nests block comments,
 * so the glob cannot be written here):
 *
 *   java -cp "desktop/build/install/desktop/lib/[star]" \
 *        com.monti.servecoach.desktop.WinCapKt serve01.cap serve01.win.cap [radii]
 */
fun main(argv: Array<String>) {
    // --bg FIRST LAST builds the background from that span of frames only, instead of a spread of
    // the whole capture. It is how the span was shown to matter: a capture holds the swing as
    // well as the flight, and a background medianed across the swing has the arm in it wherever
    // the arm went -- which is exactly where the ball goes next.
    var bgFrom = -1; var bgTo = -1; var stream = false
    val args = ArrayList<String>()
    var i = 0
    while (i < argv.size) {
        if (argv[i] == "--bg") { bgFrom = argv[i + 1].toInt(); bgTo = argv[i + 2].toInt(); i += 3 }
        else if (argv[i] == "--stream") { stream = true; i++ }
        else args.add(argv[i++])
    }
    if (args.size < 2) { System.err.println("usage: WinCapKt [--bg first last] in.cap out.win.cap [radii]"); return }
    val radii = args.getOrNull(2)?.toDouble() ?: Windows.DEFAULT_RADII
    val src = File(args[0])

    val head: String
    val frames = ArrayList<ByteArray>()
    DataInputStream(src.inputStream().buffered(1 shl 20)).use { inp ->
        val hb = ByteArray(inp.readInt()); inp.readFully(hb); head = String(hb)
        val rect = ints(field(head, "rect")!!)
        val n = num(head, "n")!!.toInt()
        val fb = rect[2] * rect[3] + 2 * (rect[2] / 2) * (rect[3] / 2)
        repeat(n) { val f = ByteArray(fb); inp.readFully(f); frames.add(f) }
    }
    val rect = ints(field(head, "rect")!!)
    val cap = Capture(num(head, "W")!!.toInt(), num(head, "H")!!.toInt(), rect.toIntArray(),
        Regex("\"full\"\\s*:\\s*true").containsMatchIn(head), num(head, "fps")?.toInt() ?: 240)
    cap.frames.addAll(frames)

    val tracks = ArrayList<List<FloatArray>>()
    field(head, "track")?.let { tracks.add(points(it)) }
    field(head, "near")?.let { near -> rows(near).forEach { t -> points(t).let { if (it.isNotEmpty()) tracks.add(it) } } }
    if (tracks.isEmpty() || tracks[0].isEmpty()) { System.err.println("no track in ${src.name}: nothing to put a window round"); return }

    // The track has to travel with the windows. Without it the engine falls back to searching
    // the crop for the flight, which is slower and is not the path the phone takes.
    val extra = StringBuilder()
    field(head, "track")?.let { extra.append(""","track":""").append(it) }
    field(head, "near")?.let { extra.append(""","near":""").append(it) }
    // --stream builds the same file through Windows.Builder, one crop at a time and letting each
    // go, which is how the phone has to do it. The two should come out byte for byte the same.
    if (stream) {
        val b = Windows.Builder(cap.rect, cap.frames.size, tracks, radii)
        for (f in cap.frames) b.add(f)
        val head = cap.header(b.headerExtra() + extra.toString(), b.count).toByteArray()
        java.io.DataOutputStream(File(args[1]).outputStream().buffered(1 shl 20)).use { o ->
            o.writeInt(head.size); o.write(head); b.writeBody(o)
        }
        val f = File(args[1])
        println("${f.name}: ${b.side}x${b.side} windows (streamed), ${f.length() / 1000} kB against ${src.length() / 1000} kB")
        return
    }
    val out = File(args[1])
    val bg = if (bgFrom >= 0) Windows.background(
        cap.frames.subList(bgFrom.coerceIn(0, cap.frames.size), (bgTo + 1).coerceIn(0, cap.frames.size)),
        cap.frameBytes) else null
    val side = Windows.write(out, cap, tracks, extra.toString(), radii, bg)
    println("${out.name}: ${side}x$side windows, ${out.length() / 1000} kB against ${src.length() / 1000} kB " +
        "(${src.length() / maxOf(1, out.length())}x), ${frames.size} frames")
}

// --- just enough JSON for a header this project writes itself -------------------------------
private fun field(s: String, name: String): String? {
    val i = Regex("\"$name\"\\s*:").find(s)?.range?.first ?: return null
    val j = s.indexOf('[', i); if (j < 0) return null
    var depth = 0
    for (k in j until s.length) {
        if (s[k] == '[') depth++
        if (s[k] == ']' && --depth == 0) return s.substring(j, k + 1)
    }
    return null
}
private fun num(s: String, name: String): Double? =
    Regex("\"$name\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?)").find(s)?.groupValues?.get(1)?.toDouble()
private val NUM = Regex("-?\\d+(?:\\.\\d+)?(?:[eE][-+]?\\d+)?")
private fun ints(s: String) = NUM.findAll(s).map { it.value.toDouble().toInt() }.toList()
private fun rows(s: String): List<String> {       // the top-level [...] groups inside an array
    val out = ArrayList<String>(); var depth = 0; var start = -1
    for (k in s.indices) {
        if (s[k] == '[') { if (depth++ == 1) start = k }
        if (s[k] == ']' && --depth == 1 && start >= 0) { out.add(s.substring(start, k + 1)); start = -1 }
    }
    return out
}
private fun points(s: String): List<FloatArray> = rows(s).mapNotNull {
    val v = NUM.findAll(it).map { m -> m.value.toFloat() }.toList()
    if (v.size >= 4) floatArrayOf(v[0], v[1], v[2], v[3]) else null
}
