package com.monti.servecoach

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.monti.servecoach.core.Capture
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.RandomAccessFile
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Serve Coach Live. Put the phone beside the table (landscape, side-on, 1-1.5 m from the ball's
 * path) and press Start: it records slow motion all the time, spots each serve in the picture,
 * measures it with the spin meter's own engine and says how it went. Nothing to press between
 * serves. "Test with a video" runs a saved slow-motion clip through the same steps.
 */
class MainActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private val camThread = HandlerThread("camera").apply { start() }
    private val camHandler = Handler(camThread.looper)
    private val encThread = HandlerThread("encoder").apply { start() }
    private val encHandler = Handler(encThread.looper)

    private lateinit var camera: HighSpeedCamera
    private var cfg: HighSpeedCamera.Config? = null
    private var recorder: ClipRecorder? = null
    private var pipeline: LivePipeline? = null
    private var tap: GlTap? = null
    private var tts: TextToSpeech? = null
    private var mode = Mode.IDLE
    private enum class Mode { IDLE, LIVE, REPLAY }

    private var engineReady = false
    private var engineBusy = false
    private val waiting = ArrayDeque<Capture.Info>()       // serves spotted, waiting for the engine
    private val history = ArrayDeque<String>()
    private var unclear = 0
    private var measuring: Capture.Info? = null
    private var started = 0L

    private val report = JSONObject()
    private val events = JSONArray()
    private val results = JSONArray()
    private lateinit var capDir: File

    private lateinit var preview: SurfaceView
    private lateinit var web: WebView
    private lateinit var stateText: TextView
    private lateinit var bigText: TextView
    private lateinit var coachText: TextView
    private lateinit var historyText: TextView
    private lateinit var statsText: TextView
    private lateinit var startBtn: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        capDir = File(cacheDir, "captures").apply { deleteRecursively(); mkdirs() }
        camera = HighSpeedCamera(this)
        buildUi()
        setupEngine()
        tts = TextToSpeech(this) { if (it == TextToSpeech.SUCCESS) tts?.language = Locale.US }
        report.put("device", JSONObject().put("maker", Build.MANUFACTURER).put("model", Build.MODEL)
            .put("android", Build.VERSION.RELEASE).put("sdk", Build.VERSION.SDK_INT)).put("app", BuildConfig.VERSION_NAME)
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(Manifest.permission.CAMERA), 1)
        else checkCamera()
        main.post(object : Runnable { override fun run() { updateStats(); main.postDelayed(this, 400) } })
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) checkCamera()
        else state("The coach needs the camera")
    }

    // ------------------------------------------------------------------ screen
    private fun buildUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        preview = SurfaceView(this)
        root.addView(preview, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(215, 12, 18, 28)); setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        fun tv(size: Float, bold: Boolean = false, color: Int = Color.WHITE) = TextView(this).apply {
            textSize = size; setTextColor(color); if (bold) typeface = Typeface.DEFAULT_BOLD
        }
        stateText = tv(14f, true, Color.rgb(140, 200, 255)).apply { text = "Starting…" }
        bigText = tv(30f, true).apply { text = "" }
        coachText = tv(15f).apply { text = "Put the phone beside the table: landscape, side-on, 1–1.5 m from the ball's path. Then press Start and serve." }
        historyText = tv(13f, color = Color.rgb(200, 210, 225))
        statsText = tv(11f, color = Color.rgb(130, 145, 165))
        panel.addView(stateText); panel.addView(bigText); panel.addView(coachText)
        val scroll = ScrollView(this).apply {
            addView(LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL; addView(historyText); addView(statsText) })
        }
        panel.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(8) })
        startBtn = Button(this).apply {
            text = "Start"; isAllCaps = false; textSize = 20f; setTextColor(Color.WHITE)
            background = GradientDrawable().apply { setColor(Color.rgb(30, 120, 70)); cornerRadius = dp(10).toFloat() }
            setOnClickListener { if (mode == Mode.IDLE) startLive() else stopAll() }
        }
        panel.addView(startBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)).apply { topMargin = dp(8) })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun small(label: String, onTap: () -> Unit) = Button(this).apply { text = label; isAllCaps = false; textSize = 13f; setOnClickListener { onTap() } }
        row.addView(small("Test with a video") { pickVideo() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(small("Report") { copyReport() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        panel.addView(row)
        root.addView(panel, FrameLayout.LayoutParams(dp(340), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END))
        web = WebView(this)
        root.addView(web, FrameLayout.LayoutParams(2, 2, Gravity.BOTTOM or Gravity.START))
        setContentView(root)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()
    private fun state(s: String) { stateText.text = s }

    private fun setStartButton(running: Boolean) {
        startBtn.text = if (running) "Stop" else "Start"
        (startBtn.background as GradientDrawable).setColor(if (running) Color.rgb(160, 50, 40) else Color.rgb(30, 120, 70))
    }

    private fun updateStats() {
        val p = pipeline
        val sb = StringBuilder()
        cfg?.let { sb.append("Camera ${it.size.width}×${it.size.height} @ ${it.fps} fps\n") }
        if (p != null) {
            recorder?.let { sb.append("Recording ${it.recentFps().roundToInt()} frames/s\n") }
            sb.append("Watching: ${"%.1f".format(p.trackMsAvg)} ms a frame, ${p.lagMs} ms behind" + (if (p.dropped + p.skipped > 0) ", ${p.dropped + p.skipped} frames missed" else "") + "\n")
        }
        sb.append("Engine ${if (engineReady) "ready" else "loading"}" + (if (waiting.isNotEmpty()) " · ${waiting.size} waiting" else "") +
            (if (unclear > 0) " · $unclear movements weren't readable serves" else ""))
        statsText.text = sb.toString()
        if (mode != Mode.IDLE && measuring == null && waiting.isEmpty() && !stateText.text.startsWith("Watching") && !stateText.text.startsWith("Replaying"))
            state(if (mode == Mode.LIVE) "Watching for a serve…" else "Replaying…")
    }

    // ------------------------------------------------------------------ camera
    private fun checkCamera() {
        val probe = try { camera.probe() } catch (e: Exception) { JSONObject().put("error", e.message) }
        report.put("camera", probe)
        cfg = camera.choose()
        val c = cfg
        report.put("chosen", if (c == null) JSONObject.NULL else JSONObject().put("camera", c.cameraId).put("size", "${c.size.width}x${c.size.height}")
            .put("fps", c.fps).put("codec", c.mime).put("encoder", c.encoder))
        state(if (c == null) "This phone doesn't offer slow motion to apps" else "Ready")
    }

    private fun startLive() {
        val c = cfg ?: return
        val rec = ClipRecorder(c)
        try { rec.start(encHandler) { msg -> main.post { log("error", msg) } } } catch (e: Exception) { log("error", "encoder: ${e.message}"); return }
        recorder = rec
        val p = newPipeline(rec, blocking = false)
        p.start()
        // Watching: the camera's preview stream, shrunk by the graphics chip (GlTap); if that can't
        // be set up, the recording decoded again, as 0.4 did
        val t = try {
            GlTap(c.size.width, c.size.height, fps = c.fps,
                onFrame = { ts, no -> p.offerTapTime(ts, no) },
                onPicture = { ts, no, rgba -> p.offerTapPicture(ts, no, rgba, c.size.width / 4, c.size.height / 4) },
                onError = { msg -> main.post { log("error", msg) } }).also { it.start() }
        } catch (e: Exception) { log("error", "graphics tap: ${e.message}; decoding instead"); null }
        tap = t
        if (t == null) rec.onFrame = { p.offer(it) }
        report.put("watchingBy", if (t != null) "graphics chip" else "decoder")
        val holder = preview.holder
        holder.setFixedSize(if (t != null) c.size.width / 2 else c.size.width, if (t != null) c.size.height / 2 else c.size.height)
        fitPreview(c.size.width, c.size.height)
        camera.allToPreview = t != null
        val go = Runnable {
            camera.start(c, listOf(t?.surface ?: holder.surface, rec.inputSurface), camHandler,
                onError = { msg -> main.post { log("error", msg); state("Camera problem: $msg"); stopAll() } },
                onRunning = { main.post {
                    log("camera", "running" + (if (t != null) ", every frame to the preview: ${camera.everyFrameToPreview}" else "") +
                        camera.burstNote.let { if (it.isEmpty()) "" else " ($it)" })
                    if (t != null) calibrateSoon(p, 0)
                } })
            t?.showOn(holder.surface)
        }
        val (fw, fh) = if (t != null) c.size.width / 2 to c.size.height / 2 else c.size.width to c.size.height
        if (holder.surface.isValid && holder.surfaceFrame.width() == fw) go.run()
        else holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(h: SurfaceHolder) {}
            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hgt: Int) {
                if (w == fw && hgt == fh) { holder.removeCallback(this); go.run() }
            }
            override fun surfaceDestroyed(h: SurfaceHolder) { tap?.showOn(null) }
        })
        mode = Mode.LIVE
        setStartButton(true)
        state("Watching for a serve…")
        say("Ready. Serve when you like.")
    }

    /** which way round the tap's picture is, once a few frames have been recorded (tried for 5 s) */
    private fun calibrateSoon(p: LivePipeline, tries: Int) {
        main.postDelayed({
            if (pipeline !== p) return@postDelayed
            Thread {
                val f = try { p.calibrate() } catch (e: Exception) { main.post { log("error", "picture check: ${e.message}") }; -1 }
                main.post {
                    if (f >= 0) { tap?.setFlip(f); log("camera", "picture check: ${p.flipCheck}") }
                    else if (tries < 10) calibrateSoon(p, tries + 1)
                    else log("error", "picture check failed: ${p.flipCheck.ifEmpty { "no frame matched" }}")
                }
            }.start()
        }, 500)
    }

    private fun newPipeline(store: FrameStore, blocking: Boolean, fps: Int = 240): LivePipeline =
        LivePipeline(store, capDir, fps, blocking,
            clipDir = if (blocking) null else cacheDir,
            onCapture = { info, clip -> main.post { onCaptured(info, clip) } },
            onError = { msg -> main.post { log("error", msg) } }).also { pipeline = it }

    private fun fitPreview(w: Int, h: Int) {
        preview.post {
            val parent = preview.parent as View
            val ph = parent.height
            preview.layoutParams = FrameLayout.LayoutParams((ph * w / h.toFloat()).roundToInt(), ph, Gravity.START or Gravity.CENTER_VERTICAL)
        }
    }

    private fun stopAll() {
        pipeline?.let { p -> report.put("watching", JSONObject().put("encoded", recorder?.framesTotal ?: 0L).put("decoded", p.decoded).put("dropped", p.dropped)
            .put("skipped", p.skipped).put("pictureCheck", p.flipCheck).put("waitingForKey", p.waitingForKey).put("decoderFull", p.decoderFull).put("msPerFrame", p.trackMsAvg).put("shrinkMs", p.shrinkMsAvg).put("findMs", p.findMsAvg).put("linkMs", p.linkMsAvg).put("lagMs", p.lagMs).put("serves", p.servesFound)
            .put("rejected", JSONObject((p.tracker?.rejected ?: emptyMap<String, Int>()) as Map<*, *>))) }
        camera.stop()
        tap?.let { t -> report.put("tap", JSONObject().put("frames", t.frames).put("tapped", t.tapped).put("missed", t.missed).put("tapMs", t.tapMsAvg).put("readBack", t.readBack)) }
        tap?.stop(); tap = null
        recorder?.onFrame = null
        pipeline?.stop(); pipeline = null
        recorder?.stop(); recorder = null
        mode = Mode.IDLE
        setStartButton(false)
        state("Stopped")
    }

    override fun onPause() { super.onPause(); if (mode == Mode.LIVE) stopAll() }
    override fun onDestroy() { super.onDestroy(); stopAll(); tts?.shutdown(); camThread.quitSafely(); encThread.quitSafely() }

    // ------------------------------------------------------------------ replay a video (testing without a table)
    private fun pickVideo() {
        if (mode != Mode.IDLE) return
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "video/*" }, 7)
    }

    @Deprecated("plain Activity result")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        val uri = data?.data
        if (requestCode != 7 || resultCode != RESULT_OK || uri == null) return
        mode = Mode.REPLAY
        setStartButton(true)
        state("Replaying…")
        Thread {
            try {
                val ff = FileFrames(this, uri)
                main.post { log("replay", "${ff.frames.size} frames") }
                val p = newPipeline(ff, blocking = true, fps = ff.captureFps)
                p.prime(ff.frames)
                p.start()
                for (fr in ff.frames) { if (mode != Mode.REPLAY) break; p.offer(fr) }
                if (mode == Mode.REPLAY) p.finish()
                main.post {
                    if (mode == Mode.REPLAY) {
                        log("replay", "done: ${p.servesFound} serve(s) spotted, tracker ${"%.1f".format(p.trackMsAvg)} ms a frame")
                        stopAll()
                        state(if (p.servesFound == 0) "Replay done: no serve spotted" else "Replay done: ${p.servesFound} serve(s) spotted")
                    }
                }
            } catch (e: Exception) { main.post { log("error", "replay: ${e.message}"); stopAll() } }
        }.start()
    }

    // ------------------------------------------------------------------ a serve was spotted
    private fun onCaptured(info: Capture.Info, clip: File?) {
        log("capture", "#${info.id}: ${info.n} frames, ${info.frameBytes / 1024} KB each, written in ${info.writeMs} ms")
        waiting.addLast(info)
        while (waiting.size > (if (mode == Mode.LIVE) 3 else 30)) waiting.removeFirst().let { File(capDir, "cap_${it.id}.bin").delete() }
        if (mode == Mode.LIVE) { if (clip != null) saveClip(info, clip) else log("error", "#${info.id}: no clip saved (too few frames)") }
        next()
    }

    private fun next() {
        if (!engineReady || engineBusy) return
        val info = waiting.removeFirstOrNull() ?: return
        engineBusy = true
        measuring = info
        started = SystemClock.uptimeMillis()
        state("Measuring serve #${info.id}…")
        web.evaluateJavascript("analyseCapture('https://$host/capture/${info.id}', ${info.id})", null)
    }

    /** the serve's own clip in the phone's videos (Movies/ServeCoach), to look at or send */
    private fun saveClip(info: Capture.Info, tmp: File) {
        Thread {
            try {
                val name = "ServeCoach_${java.text.SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(java.util.Date())}_${info.id}.mp4"
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, name); put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/ServeCoach")
                }
                val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                    ?: throw IllegalStateException("the phone's video library refused a new file")
                contentResolver.openOutputStream(uri)?.use { out -> tmp.inputStream().use { it.copyTo(out) } }
                    ?: throw IllegalStateException("couldn't open the new video file")
                tmp.delete()
                main.post { log("saved", "#${info.id} Movies/ServeCoach/$name") }
            } catch (e: Exception) { main.post { log("error", "save: ${e.message}") } }
        }.start()
    }

    // ------------------------------------------------------------------ the engine
    private val host = "appassets.androidplatform.net"

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupEngine() {
        web.settings.javaScriptEnabled = true
        web.addJavascriptInterface(Bridge(), "Android")
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest): WebResourceResponse? {
                if (req.url.host != host) return null
                val path = req.url.path ?: return null
                return try {
                    if (path.startsWith("/capture/")) captureResponse(path.removePrefix("/capture/"))
                    else WebResourceResponse(when {
                        path.endsWith(".html") -> "text/html"; path.endsWith(".js") -> "application/javascript"; else -> "application/octet-stream"
                    }, "utf-8", assets.open(path.removePrefix("/")))
                } catch (e: Exception) { WebResourceResponse("text/plain", "utf-8", 404, "missing", null, null) }
            }
        }
        web.loadUrl("https://$host/engine.html")
    }

    /** /capture/<id>/head: the header; /capture/<id>/<k>: frame k's bytes */
    private fun captureResponse(rest: String): WebResourceResponse {
        val (id, what) = rest.split("/", limit = 2)
        if (what == "head") return WebResourceResponse("application/json", "utf-8", File(capDir, "cap_$id.json").inputStream())
        if (what == "all") return WebResourceResponse("application/octet-stream", null, File(capDir, "cap_$id.bin").inputStream().buffered(1 shl 20))
        val head = JSONObject(File(capDir, "cap_$id.json").readText())
        val rect = head.getJSONArray("rect")
        val per = rect.getInt(2) * rect.getInt(3) + 2 * (rect.getInt(2) / 2) * (rect.getInt(3) / 2)
        val bytes = ByteArray(per)
        RandomAccessFile(File(capDir, "cap_$id.bin"), "r").use { it.seek(what.toLong() * per); it.readFully(bytes) }
        return WebResourceResponse("application/octet-stream", null, ByteArrayInputStream(bytes))
    }

    inner class Bridge {
        @JavascriptInterface fun onReady(webCodecs: Boolean, mp4box: Boolean, engine: Boolean) = main.post {
            engineReady = engine
            report.put("engine", JSONObject().put("webCodecs", webCodecs).put("mp4box", mp4box).put("engine", engine))
            if (!engine) log("error", "the engine didn't load") else next()
        }
        @JavascriptInterface fun onProgress(id: Int, step: String, frac: Double) = main.post {
            state("Measuring serve #$id… ${(frac * 100).roundToInt()}%")
        }
        @JavascriptInterface fun onResult(id: Int, json: String) = main.post {
            engineBusy = false
            val info = measuring; measuring = null
            val r = try { JSONObject(json) } catch (e: Exception) { JSONObject().put("error", "bad result") }
            r.put("capture", id).put("wallMs", SystemClock.uptimeMillis() - started)
            info?.let { r.put("captureFrames", it.n).put("writeMs", it.writeMs) }
            results.put(r)
            File(capDir, "cap_$id.bin").delete(); File(capDir, "cap_$id.json").delete()
            show(r)
            next()
        }
    }

    // ------------------------------------------------------------------ what to say
    private val titles = mapOf(
        "thick" to ("You pushed the ball instead of brushing it" to "Open the face and brush along under the ball."),
        "good" to ("Strong backspin" to "Keep this contact."),
        "slip" to ("The ball slid on the rubber" to "Let the ball drop lower and brush slightly into it."),
        "slow" to ("The brush was too slow" to "Accelerate through the contact: snap the wrist."),
    )

    private fun show(r: JSONObject) {
        val sv = r.optJSONObject("serve")
        val secs = r.optLong("wallMs") / 1000.0
        if (sv == null || !sv.optBoolean("clear")) {
            // a movement that wasn't a readable serve (a toss, a catch, someone walking by): not announced
            unclear++
            log("unclear", "#${r.optInt("capture")}: ${r.optString("error").ifEmpty { sv?.optJSONArray("issues")?.optString(0) ?: "no clear reading" }}")
            return
        }
        // read only "after a bounce" and slowly turning: in the live tests that was other movement
        // (walking off to fetch the ball, 4.8 rev/s), not a serve; listed, not announced. (A real
        // serve can come out labelled "bounce" when its crop starts late: L1, 23 rev/s.)
        val phases = sv.optJSONArray("phases")
        val own = (0 until (phases?.length() ?: 0)).any { k -> phases!!.getJSONObject(k).let { it.optBoolean("clear") && it.optString("phase") != "bounce" } }
        if (!own && sv.optDouble("rps") < 10) {
            unclear++
            log("unclear", "#${r.optInt("capture")}: only read after a bounce (${"%.1f".format(sv.optDouble("rps"))} rev/s ${sv.optString("type")}): not counted as a serve")
            return
        }
        val rps = sv.optDouble("rps")
        val type = sv.optString("type")
        val coach = r.optJSONObject("coach")
        val kind = coach?.optString("kind") ?: ""
        val t = titles[kind]
        val tip = if (t != null) "${t.first}. ${t.second}" else when (kind) {
            "other" -> "Coaching covers backspin serves."
            "unknown" -> "Film side-on to judge the contact."
            else -> ""
        }
        val ballPx = sv.optDouble("ballPx", 0.0)
        val closer = if (ballPx in 1.0..24.0) "\nThe ball is only ${ballPx.roundToInt()} px across: move the phone closer." else ""
        val numbers = buildString {
            if (coach != null && coach.has("ratio")) append("spin ÷ speed ${"%.2f".format(coach.optDouble("ratio"))} · ")
            if (sv.has("speed")) append("ball ${"%.1f".format(sv.optDouble("speed"))} m/s · ")
            append("answer in ${"%.1f".format(secs)} s")
        }
        bigText.text = "${rps.roundToInt()} rev/s · ${(rps * 60).roundToInt()} RPM"
        coachText.text = "$type\n$tip\n$numbers$closer"
        history.addFirst("#${r.optInt("capture")}  ${rps.roundToInt()} rev/s $type" + (if (t != null) " · ${t.first}" else ""))
        while (history.size > 8) history.removeLast()
        historyText.text = history.joinToString("\n")
        say("${rps.roundToInt()} revs per second, $type. ${t?.first ?: ""}")
    }

    private fun say(text: String) { tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "r") }

    // ------------------------------------------------------------------ report
    private fun log(kind: String, text: String) {
        events.put(JSONObject().put("t", SystemClock.uptimeMillis()).put("kind", kind).put("text", text))
        if (kind == "error") Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    }

    private fun copyReport() {
        pipeline?.let { p -> report.put("watching", JSONObject().put("encoded", recorder?.framesTotal ?: 0L).put("decoded", p.decoded).put("dropped", p.dropped)
            .put("skipped", p.skipped).put("pictureCheck", p.flipCheck).put("waitingForKey", p.waitingForKey).put("decoderFull", p.decoderFull).put("msPerFrame", p.trackMsAvg).put("shrinkMs", p.shrinkMsAvg).put("findMs", p.findMsAvg).put("linkMs", p.linkMsAvg).put("lagMs", p.lagMs).put("serves", p.servesFound)) }
        tap?.let { t -> report.put("tap", JSONObject().put("frames", t.frames).put("tapped", t.tapped).put("missed", t.missed).put("tapMs", t.tapMsAvg).put("readBack", t.readBack)) }
        report.put("events", events).put("results", results)
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Serve Coach report", report.toString(1)))
        Toast.makeText(this, "Report copied: paste it into the chat", Toast.LENGTH_LONG).show()
    }
}
