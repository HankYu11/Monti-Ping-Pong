package com.monti.servecoach

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
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
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Serve Coach Live. Set the phone up beside the table (landscape, side-on, as in the spin meter's
 * "How to record"), press Start and serve: the phone records slow motion all the time, hears each
 * hit, cuts out the serve, measures it with the spin meter's own engine and says how it went.
 *
 * This first version is also the feasibility check: Report collects what the phone offers and
 * how fast each step was, to copy and send back.
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
    private var clicks: ClickDetector? = null
    private var tts: TextToSpeech? = null
    private var running = false
    private var engineReady = false
    private var engineBusy = false
    private var pending: Pair<Int, File>? = null
    private var clipNo = 0

    private val report = JSONObject()
    private val events = JSONArray()
    private val results = JSONArray()

    private lateinit var preview: SurfaceView
    private lateinit var web: WebView
    private lateinit var bigText: TextView
    private lateinit var coachText: TextView
    private lateinit var statusText: TextView
    private lateinit var startBtn: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        camera = HighSpeedCamera(this)
        buildUi()
        setupEngine()
        tts = TextToSpeech(this) { if (it == TextToSpeech.SUCCESS) tts?.language = Locale.US }
        report.put("device", JSONObject().put("maker", Build.MANUFACTURER).put("model", Build.MODEL)
            .put("android", Build.VERSION.RELEASE).put("sdk", Build.VERSION.SDK_INT))
        if (!hasPermissions()) requestPermissions(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO), 1)
        else checkCamera()
        main.post(object : Runnable { override fun run() { updateStatus(); main.postDelayed(this, 300) } })
    }

    private fun hasPermissions() = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (hasPermissions()) checkCamera() else say("The coach needs the camera and the microphone.", speak = false)
    }

    // ------------------------------------------------------------------ screen
    private fun buildUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        preview = SurfaceView(this)
        root.addView(preview, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(200, 12, 18, 28)); setPadding(24, 20, 24, 20)
        }
        fun tv(size: Float, bold: Boolean = false, color: Int = Color.WHITE) = TextView(this).apply {
            textSize = size; setTextColor(color); if (bold) typeface = Typeface.DEFAULT_BOLD
        }
        panel.addView(tv(13f, true, Color.rgb(140, 170, 210)).apply { text = "SERVE COACH LIVE" })
        bigText = tv(30f, true).apply { text = "–" }
        coachText = tv(15f).apply { text = "Set the phone beside the table, side-on, landscape. Press Start, then serve." }
        statusText = tv(12f, color = Color.rgb(160, 175, 195))
        panel.addView(bigText); panel.addView(coachText)
        val scroll = ScrollView(this).apply { addView(statusText) }
        panel.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun btn(label: String, onTap: () -> Unit) = Button(this).apply { text = label; isAllCaps = false; setOnClickListener { onTap() } }
        startBtn = btn("Start") { if (running) stopPractice() else startPractice() }
        row.addView(startBtn)
        row.addView(btn("Serve now") { manualServe() })
        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row2.addView(btn("Ear −") { clicks?.let { it.sensitivity = (it.sensitivity * 1.4).coerceAtMost(60.0) } })
        row2.addView(btn("Ear +") { clicks?.let { it.sensitivity = (it.sensitivity / 1.4).coerceAtLeast(3.0) } })
        row2.addView(btn("Report") { copyReport() })
        panel.addView(row); panel.addView(row2)
        root.addView(panel, FrameLayout.LayoutParams(dp(330), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END))
        web = WebView(this)
        root.addView(web, FrameLayout.LayoutParams(2, 2, Gravity.BOTTOM or Gravity.START))
        setContentView(root)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    private fun say(text: String, speak: Boolean = true, coach: String? = null) {
        bigText.text = text
        if (coach != null) coachText.text = coach
        if (speak) tts?.speak(text + (if (coach != null) ". $coach" else ""), TextToSpeech.QUEUE_FLUSH, null, "r")
    }

    private fun updateStatus() {
        val c = cfg
        val r = recorder
        val sb = StringBuilder()
        if (c != null) sb.append("Camera ${c.size.width}×${c.size.height} @ ${c.fps} fps, ${c.mime.substringAfter('/')} (${c.encoder})\n")
        if (r != null && running) sb.append("Recording: ${r.recentFps().roundToInt()} frames in the last second\n")
        clicks?.let { sb.append("Ear: ${it.source}, level ${"%.1f".format(it.lastLevel)}× background, click at ${"%.0f".format(it.sensitivity)}×\n") }
        sb.append("Engine: ${if (engineReady) (if (engineBusy) "measuring…" else "ready") else "loading"} · serves measured ${results.length()}\n")
        statusText.text = sb.toString()
    }

    // ------------------------------------------------------------------ camera
    private fun checkCamera() {
        val probe = try { camera.probe() } catch (e: Exception) { JSONObject().put("error", e.message) }
        report.put("camera", probe)
        cfg = camera.choose()
        val c = cfg
        report.put("chosen", if (c == null) JSONObject.NULL else JSONObject().put("camera", c.cameraId).put("size", "${c.size.width}x${c.size.height}")
            .put("fps", c.fps).put("codec", c.mime).put("encoder", c.encoder).put("timestamps", if (c.timestampRealtime) "realtime" else "unknown"))
        if (c == null) say("This phone doesn't offer slow motion to apps", speak = false,
            coach = "No high-speed (120/240 fps) camera mode is open to apps. Press Report and send it.")
        else say("Ready: ${c.fps} fps", speak = false)
    }

    private fun startPractice() {
        val c = cfg ?: return
        if (!hasPermissions()) return
        val rec = ClipRecorder(c)
        try { rec.start(encHandler) { msg -> main.post { log("error", msg) } } } catch (e: Exception) { log("error", "encoder: ${e.message}"); return }
        recorder = rec
        val holder = preview.holder
        holder.setFixedSize(c.size.width, c.size.height)
        fitPreview(c)
        val go = Runnable {
            camera.start(c, listOf(holder.surface, rec.inputSurface), camHandler,
                onError = { msg -> main.post { log("error", msg); say("Camera problem", coach = msg, speak = false); stopPractice() } },
                onRunning = { main.post { log("camera", "running") } })
        }
        if (holder.surface.isValid && holder.surfaceFrame.width() == c.size.width) go.run()
        else holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(h: SurfaceHolder) {}
            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hgt: Int) {
                if (w == c.size.width && hgt == c.size.height) { holder.removeCallback(this); go.run() }
            }
            override fun surfaceDestroyed(h: SurfaceHolder) {}
        })
        clicks = ClickDetector { t, level -> main.post { onHit(t, level, "sound") } }.also {
            it.start { msg -> main.post { log("error", msg) } }
        }
        running = true
        startBtn.text = "Stop"
        say("Recording. Serve when ready.", speak = true)
        // the frame rate the phone really delivers, for the report
        main.postDelayed({ recorder?.let { report.put("measuredFps", it.recentFps()); log("fps", "${it.recentFps()}") } }, 4000)
    }

    private fun fitPreview(c: HighSpeedCamera.Config) {
        preview.post {
            val parent = preview.parent as View
            val h = parent.height
            val w = (h * c.size.width / c.size.height.toFloat()).roundToInt()
            preview.layoutParams = FrameLayout.LayoutParams(w, h, Gravity.START or Gravity.CENTER_VERTICAL)
        }
    }

    private fun stopPractice() {
        running = false
        startBtn.text = "Start"
        clicks?.stop(); clicks = null
        camera.stop()
        recorder?.stop(); recorder = null
    }

    override fun onPause() { super.onPause(); if (running) stopPractice() }
    override fun onDestroy() { super.onDestroy(); stopPractice(); tts?.shutdown(); camThread.quitSafely(); encThread.quitSafely() }

    // ------------------------------------------------------------------ a serve
    /**
     * A hit heard at t (System.nanoTime). The frames carry the camera's clock: boot time when the
     * camera says so, else assumed the same as nanoTime; checked against when frames arrive.
     */
    private fun onHit(tNano: Long, level: Double, how: String) {
        val rec = recorder ?: return
        val c = cfg ?: return
        val offsetExpected = if (c.timestampRealtime) SystemClock.elapsedRealtimeNanos() - System.nanoTime() else 0L
        val offsetArrival = -rec.arrivalMinusPtsNs                  // frame time minus nanoTime, plus the encoder's delay
        val offset = if (abs(offsetExpected - offsetArrival) < 400_000_000L) offsetExpected else offsetArrival - 40_000_000L
        val hitUs = (tNano + offset) / 1000
        log("hit", "$how level ${"%.1f".format(level)} at ${hitUs / 1000} ms (clock check ${(offsetExpected - offsetArrival) / 1_000_000} ms)")
        bigText.text = "Hit heard…"
        cutWhenReady(hitUs - 600_000, hitUs + 1_200_000, SystemClock.uptimeMillis())
    }

    private fun manualServe() {
        val rec = recorder ?: return
        val newest = rec.newestPtsUs()
        if (newest < 0) return
        log("hit", "button")
        cutWhenReady(newest - 2_000_000, newest, SystemClock.uptimeMillis())
    }

    private fun cutWhenReady(fromUs: Long, toUs: Long, askedAt: Long) {
        val rec = recorder ?: return
        if (rec.newestPtsUs() < toUs && SystemClock.uptimeMillis() - askedAt < 3000) {
            main.postDelayed({ cutWhenReady(fromUs, toUs, askedAt) }, 80); return
        }
        val n = ++clipNo
        val file = File(cacheDir, "serve_$n.mp4")
        Thread {
            val t0 = SystemClock.uptimeMillis()
            val frames = try { rec.cut(fromUs, toUs, file) } catch (e: Exception) { main.post { log("error", "cut: ${e.message}") }; 0 }
            main.post {
                log("clip", "#$n $frames frames, cut in ${SystemClock.uptimeMillis() - t0} ms")
                if (frames > 0) analyse(n, file) else say("Missed that one", speak = false, coach = "No frames around the hit were kept.")
            }
        }.start()
    }

    // ------------------------------------------------------------------ the engine
    private val host = "appassets.androidplatform.net"
    private var started = 0L

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupEngine() {
        web.settings.javaScriptEnabled = true
        web.settings.mediaPlaybackRequiresUserGesture = false
        web.addJavascriptInterface(Bridge(), "Android")
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest): WebResourceResponse? {
                if (req.url.host != host) return null
                val path = req.url.path ?: return null
                return try {
                    if (path.startsWith("/clip/")) WebResourceResponse("video/mp4", null, FileInputStream(File(cacheDir, path.removePrefix("/clip/"))))
                    else WebResourceResponse(when {
                        path.endsWith(".html") -> "text/html"; path.endsWith(".js") -> "application/javascript"; else -> "application/octet-stream"
                    }, "utf-8", assets.open(path.removePrefix("/")))
                } catch (e: Exception) { WebResourceResponse("text/plain", "utf-8", 404, "missing", null, null) }
            }
        }
        web.loadUrl("https://$host/engine.html")
    }

    private fun analyse(n: Int, file: File) {
        if (!engineReady || engineBusy) { pending = n to file; return }
        engineBusy = true
        started = SystemClock.uptimeMillis()
        bigText.text = "Measuring…"
        web.evaluateJavascript("analyse('https://$host/clip/${file.name}', $n)", null)
    }

    inner class Bridge {
        @JavascriptInterface fun onReady(webCodecs: Boolean, mp4box: Boolean, engine: Boolean) = main.post {
            engineReady = webCodecs && mp4box && engine
            report.put("engine", JSONObject().put("webCodecs", webCodecs).put("mp4box", mp4box).put("engine", engine))
            if (!engineReady) log("error", "engine can't run here (WebCodecs $webCodecs, mp4box $mp4box, engine $engine)")
        }
        @JavascriptInterface fun onProgress(id: Int, step: String, frac: Double) = main.post {
            bigText.text = "Measuring… ${(frac * 100).roundToInt()}%"
        }
        @JavascriptInterface fun onResult(id: Int, json: String) = main.post {
            engineBusy = false
            val wall = SystemClock.uptimeMillis() - started
            val r = try { JSONObject(json) } catch (e: Exception) { JSONObject().put("error", "bad result") }
            r.put("clip", id).put("wallMs", wall)
            results.put(r)
            show(r)
            pending?.let { (pn, pf) -> pending = null; analyse(pn, pf) }
        }
    }

    // ------------------------------------------------------------------ what to say
    private val titles = mapOf(
        "thick" to ("You pushed the ball instead of brushing it" to "Open the face and brush along under the ball."),
        "good" to ("Strong backspin" to "Keep this contact."),
        "slip" to ("The ball slid on the rubber" to "Let the ball drop lower and brush slightly into it."),
        "slow" to ("The brush was too slow" to "Accelerate through the contact, snap the wrist."),
    )

    private fun show(r: JSONObject) {
        val sv = r.optJSONObject("serve")
        val coach = r.optJSONObject("coach")
        val secs = r.optLong("wallMs") / 1000.0
        if (sv == null || !sv.optBoolean("clear")) {
            val why = r.optString("error").ifEmpty { sv?.optJSONArray("issues")?.optString(0) ?: "no clear reading" }
            say("No clear reading", coach = "$why  (${"%.1f".format(secs)} s)")
            return
        }
        val rps = sv.optDouble("rps")
        val type = sv.optString("type")
        val kind = coach?.optString("kind") ?: ""
        val t = titles[kind]
        val spoken = "${rps.roundToInt()} revs per second, $type"
        val tip = if (t != null) "${t.first}. ${t.second}" else when (kind) {
            "other" -> "Coaching covers backspin serves."
            "unknown" -> "Film side-on to judge the contact."
            else -> ""
        }
        val numbers = buildString {
            if (coach != null && coach.has("ratio")) append("spin÷speed ${"%.2f".format(coach.optDouble("ratio"))} · ")
            if (sv.has("speed")) append("ball ${"%.1f".format(sv.optDouble("speed"))} m/s · ")
            append("measured in ${"%.1f".format(secs)} s")
        }
        bigText.text = "${rps.roundToInt()} rev/s · ${(rps * 60).roundToInt()} RPM"
        coachText.text = "$type\n$tip\n$numbers"
        tts?.speak("$spoken. ${t?.first ?: ""}", TextToSpeech.QUEUE_FLUSH, null, "r")
    }

    // ------------------------------------------------------------------ report
    private fun log(kind: String, text: String) {
        events.put(JSONObject().put("t", SystemClock.uptimeMillis()).put("kind", kind).put("text", text))
        if (kind == "error") Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    }

    private fun copyReport() {
        report.put("events", events).put("results", results).put("app", "0.1")
        clicks?.let { report.put("ear", JSONObject().put("source", it.source).put("sensitivity", it.sensitivity)) }
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Serve Coach report", report.toString(1)))
        Toast.makeText(this, "Report copied: paste it into the chat", Toast.LENGTH_LONG).show()
    }
}
