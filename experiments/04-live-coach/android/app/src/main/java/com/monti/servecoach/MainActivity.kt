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
import android.media.MediaCodec
import android.media.MediaFormat
import android.provider.MediaStore
import java.util.concurrent.TimeUnit
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
    private var poseWatcher: PoseWatcher? = null
    private var queueMode = 0                              // see GlTap's queueMode

    /** the chip couldn't read the queue's buffers, or read nonsense: start again with the next mark */
    private fun nextQueueMode() {
        if (queueMode >= 1 || mode != Mode.LIVE) return
        queueMode++
        log("camera", "starting again with the queue marked ${if (queueMode == 1) "sampled by the chip" else "?"}")
        stopAll(); startLive()
    }
    private var tts: TextToSpeech? = null
    private var mode = Mode.IDLE
    @Volatile private var previewing = false        // the camera on with only the screen's picture, before Start
    private var resumed = false
    private enum class Mode { IDLE, LIVE, REPLAY }

    private var engineReady = false
    private var engineBusy = false
    private val waiting = ArrayDeque<Capture.Info>()       // serves spotted, waiting for the engine
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
    // 0.9: the cockpit HUD (HudScreen), built in its own class so a test can draw it without a phone
    private lateinit var screen: HudScreen

    private fun buildUi() {
        screen = HudScreen(this, onStart = { if (mode == Mode.IDLE) startLive() else stopAll() }, onTest = { pickVideo() }, onReport = { copyReport() }, onAi = { toggleAi() }, onNext = { forceReady() },
            onWindows = { windowRingOn = !windowRingOn; screen.windowsState(windowRingOn); pipeline?.windowRing = windowRingOn
                log("camera", "windows cut on the chip: " + (if (windowRingOn) "on" else "off")) })
        preview = screen.preview; stateText = screen.stateText; bigText = screen.bigText; coachText = screen.coachText
        statsText = screen.statsText; startBtn = screen.startBtn
        web = WebView(this)
        screen.root.addView(web, FrameLayout.LayoutParams(2, 2, Gravity.BOTTOM or Gravity.START))
        setContentView(screen.root)
        screen.aiState(aiOn)
        screen.windowsState(windowRingOn)
    }

    private fun tone(c: Int) = screen.tone(c)

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()
    private fun state(s: String) = screen.state(s)

    private fun setStartButton(running: Boolean) = screen.running(running)

    private fun updateStats() {
        val p = pipeline
        val sb = StringBuilder()
        sb.append("App ${BuildConfig.VERSION_NAME}\n")
        cfg?.let { sb.append("Camera ${it.size.width}×${it.size.height} @ ${it.fps} fps\n") }
        if (p != null && p.tapped) sb.append(if (p.calibrated) "Picture check: done\n" else "Picture check: waiting (not watching yet)\n")
        if (p != null) {
            recorder?.let { sb.append("Recording ${it.recentFps().roundToInt()} frames/s\n") }
            sb.append("Watching: ${"%.1f".format(p.trackMsAvg)} ms a frame, ${p.lagMs} ms behind" + (if (p.dropped + p.skipped > 0) ", ${p.dropped + p.skipped} frames missed" else "") + "\n")
        }
        sb.append("Engine ${if (engineReady) "ready" else "loading"}" + (if (waiting.isNotEmpty()) " · ${waiting.size} waiting" else "") +
            (if (unclear > 0) " · $unclear movements weren't readable serves" else ""))
        statsText.text = sb.toString()
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
        startPreview()
    }

    /**
     * While nothing runs, the picture the recording will have (the same slow-motion mode, only the
     * screen fed: 30 frames a second), to set the phone up by before pressing Start.
     */
    private fun startPreview() {
        val c = cfg ?: return
        if (mode != Mode.IDLE || previewing || !resumed || checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        previewing = true
        val holder = preview.holder
        holder.setFixedSize(c.size.width, c.size.height)
        fitPreview(c.size.width, c.size.height)
        val go = Runnable {
            if (!previewing || mode != Mode.IDLE) return@Runnable
            // on the camera's thread: after any close still waiting there
            // the setup check's small copy of the picture, unless it failed once (then the preview alone)
            // (reading from the start: a full reader would hold the camera's requests back, the screen's too)
            val g = if (guideFailed) null else try { newGuide(c).also { it.start() } } catch (e: Exception) { log("error", "setup check: ${e.message}"); null }
            guide = g
            camHandler.post { if (previewing) camera.startPreview(c, holder.surface, camHandler,
                onError = { msg -> main.post { if (previewing) {
                    log("camera", "preview: $msg")
                    if (g != null && !guideFailed) { guideFailed = true; stopPreview { startPreview() } } else stopPreview()
                } } },
                onRunning = { main.post { log("camera", "preview running" + (if (g != null) ", setup check ${g.size.width}x${g.size.height}" else "")); if (aiOn) startCoach() } },
                analysis = g?.surface) }
        }
        if (holder.surface.isValid && holder.surfaceFrame.width() == c.size.width) go.run()
        else holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(h: SurfaceHolder) {}
            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hgt: Int) {
                if (w == c.size.width && hgt == c.size.height) { holder.removeCallback(this); go.run() }
            }
            override fun surfaceDestroyed(h: SurfaceHolder) {}
        })
    }

    // ------------------------------------------------------------------ setting up (0.9.1)
    private var guide: SetupGuide? = null
    private var guideFailed = false
    private var lastAdviceSaid = 0L

    private fun newGuide(c: HighSpeedCamera.Config) = SetupGuide(this, c.cameraId, camHandler,
        rotation = { @Suppress("DEPRECATION") windowManager.defaultDisplay.rotation },
        onAdvice = { a -> main.post { showAdvice(a) } },
        onJpeg = { j -> coach?.sendFrame(j) })

    private fun showAdvice(a: com.monti.servecoach.core.SetupCheck.Advice) {
        if (mode != Mode.IDLE || !previewing) return
        val short = when (a.kind) {
            com.monti.servecoach.core.SetupCheck.Kind.GOOD -> "looks good"
            com.monti.servecoach.core.SetupCheck.Kind.NO_TABLE -> "no table in view"
            com.monti.servecoach.core.SetupCheck.Kind.MOVING -> "hold still"
            com.monti.servecoach.core.SetupCheck.Kind.DARK -> "too dark"
            com.monti.servecoach.core.SetupCheck.Kind.TILTED -> "level the phone"
            com.monti.servecoach.core.SetupCheck.Kind.FAR -> "move closer"
            com.monti.servecoach.core.SetupCheck.Kind.NEAR -> "move back"
            com.monti.servecoach.core.SetupCheck.Kind.TABLE_HIGH -> "tilt up"
            com.monti.servecoach.core.SetupCheck.Kind.TABLE_LOW -> "tilt down"
            com.monti.servecoach.core.SetupCheck.Kind.END_ON -> "face the side"
        }
        screen.advice(short, a.text, a.ok)
        log("setup", a.toString())
        // spoken: by the AI coach when it's on (asked with this advice), else the phone's own voice,
        // not "hold still" (it's said while the phone is being moved), and not more than every 4 s
        val c = coach
        if (c != null && c.ready) c.ask(a.text)
        else if (a.kind != com.monti.servecoach.core.SetupCheck.Kind.MOVING && SystemClock.uptimeMillis() - lastAdviceSaid > 4000) {
            lastAdviceSaid = SystemClock.uptimeMillis(); say(a.text)
        }
    }

    // ------------------------------------------------------------------ the AI setup coach (Gemini Live, optional)
    private val prefs by lazy { getSharedPreferences("coach", MODE_PRIVATE) }
    private var aiOn: Boolean
        get() = prefs.getBoolean("ai", false)
        set(v) { prefs.edit().putBoolean("ai", v).apply() }
    private var coach: GeminiCoach? = null
    private var aiAudio: android.media.AudioTrack? = null

    private fun toggleAi() {
        if (aiOn) { aiOn = false; stopCoach(); screen.aiState(false); return }
        val key = prefs.getString("key", null)
        if (key.isNullOrBlank()) { askKey(); return }
        aiOn = true; screen.aiState(true)
        if (previewing && mode == Mode.IDLE) startCoach()
    }

    private fun askKey() {
        val input = android.widget.EditText(this).apply { hint = "Gemini API key"; isSingleLine = true }
        android.app.AlertDialog.Builder(this)
            .setTitle("AI setup coach")
            .setMessage("While you set the phone up, it sends what the camera sees, about one picture a second, to Google's Gemini and plays its spoken advice. Nothing is sent once you press Start. It uses your own Gemini API key (aistudio.google.com/apikey), kept on this phone only.")
            .setView(input)
            .setPositiveButton("Turn on") { _, _ ->
                val k = input.text.toString().trim()
                if (k.isNotEmpty()) { prefs.edit().putString("key", k).apply(); toggleAi() }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startCoach() {
        if (coach != null) return
        val key = prefs.getString("key", null) ?: return
        aiAudio = android.media.AudioTrack.Builder()
            .setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_ASSISTANT).setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(android.media.AudioFormat.Builder().setSampleRate(24000).setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT).setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(android.media.AudioTrack.MODE_STREAM).build().also { it.play() }
        tts?.stop()
        coach = GeminiCoach(key,
            onText = { t -> main.post { screen.aiText(t) } },
            onAudio = { pcm -> aiAudio?.write(pcm, 0, pcm.size) },
            onStatus = { msg -> main.post {
                log("ai", msg)
                if (msg.contains("refused")) { prefs.edit().remove("key").apply(); aiOn = false; screen.aiState(false); stopCoach(); Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
            } }).also { it.start() }
    }

    private fun stopCoach() {
        coach?.stop(); coach = null
        aiAudio?.let { try { it.stop(); it.release() } catch (_: Exception) {} }; aiAudio = null
    }

    private fun stopPreview(then: (() -> Unit)? = null) {
        if (!previewing) { then?.invoke(); return }
        previewing = false
        guide?.stop(); guide = null
        stopCoach()
        camera.stopOn(camHandler) { if (then != null) main.post(then) }
    }

    private var starting = false

    private fun startLive() {
        val c = cfg ?: return
        // the setup preview closes first, off this thread; then the slow-motion session opens
        if (previewing) {
            if (starting) return
            starting = true
            state("Starting…")
            stopPreview { starting = false; if (mode == Mode.IDLE && resumed) startLive() }
            return
        }
        if (starting) return
        val rec = ClipRecorder(c)
        try { rec.start(encHandler) { msg -> main.post { log("error", msg) } } } catch (e: Exception) { log("error", "encoder: ${e.message}"); return }
        recorder = rec
        val p = newPipeline(rec, blocking = false)
        p.start()
        // Watching: the camera's recording stream through the graphics chip, which shrinks each
        // frame for the tracker and passes it on to the encoder (GlTap with an encoder); if that
        // can't be set up, the preview stream (0.5.4: lost frames), else the recording decoded
        val onFrame = { ts: Long, no: Long -> p.offerTapTime(ts, no) }
        val onPicture = { ts: Long, no: Long, rgba: ByteArray -> p.offerTapPicture(ts, no, rgba, c.size.width / 4, c.size.height / 4) }
        val onTapError = { msg: String -> main.post { log("error", msg) }; Unit }
        val onWindow = { no: Long, at: IntArray, b: ByteArray -> p.offerWindow(no, at, b) }
        // the shaders that cut the ball's window on the chip; without them the window pass is
        // simply never set up and the capture is decoded again as before
        val ws = try {
            GlTap.WindowShaders(
                assets.open("shaders/quad.vert").bufferedReader().readText(),
                assets.open("shaders/crop_y.frag").bufferedReader().readText(),
                assets.open("shaders/crop_c.frag").bufferedReader().readText(),
                assets.open("shaders/crop_rows.frag").bufferedReader().readText())
        } catch (e: Exception) { log("error", "window shaders: ${e.message}"); null }
        var t: GlTap? = try {
            GlTap(c.size.width, c.size.height, fps = c.fps, onFrame = onFrame, onPicture = onPicture, onError = onTapError, encoder = rec.inputSurface,
                queueMode = queueMode, onBindFailed = { main.post { nextQueueMode() } }, windowShaders = ws, onWindow = onWindow).also { it.start() }
        } catch (e: Exception) { log("error", "recording stream through the graphics chip: ${e.message}; trying the preview stream"); null }
        if (t == null) t = try {
            GlTap(c.size.width, c.size.height, fps = c.fps, onFrame = onFrame, onPicture = onPicture, onError = onTapError,
                windowShaders = ws, onWindow = onWindow).also { it.start() }
        } catch (e: Exception) { log("error", "graphics tap: ${e.message}; decoding instead"); null }
        tap = t
        t?.let { tp -> p.planWindows = { plan -> tp.windowPlan = plan } }
        if (t == null) rec.onFrame = { p.offer(it) }
        report.put("watchingBy", when { t == null -> "decoder"; t.throughRecording -> "graphics chip, recording stream"; else -> "graphics chip, preview stream" })
        val viaRecording = t?.throughRecording == true
        val previewOwn = t == null || viaRecording                 // the camera draws the preview itself
        val holder = preview.holder
        holder.setFixedSize(if (previewOwn) c.size.width else c.size.width / 2, if (previewOwn) c.size.height else c.size.height / 2)
        fitPreview(c.size.width, c.size.height)
        camera.allToPreview = t != null && !viaRecording
        val go = Runnable {
            val outs = when {
                t == null -> listOf(holder.surface, rec.inputSurface)
                viaRecording -> listOf(holder.surface, t.surface)
                else -> listOf(t.surface, rec.inputSurface)
            }
            camera.start(c, outs, camHandler,
                onError = { msg -> main.post { log("error", msg); state("Camera problem: $msg"); stopAll() } },
                onRunning = { main.post {
                    log("camera", "running" + (if (t != null && !viaRecording) ", every frame to the preview: ${camera.everyFrameToPreview}" else "") +
                        camera.burstNote.let { if (it.isEmpty()) "" else " ($it)" })
                    if (t != null) calibrateSoon(p, 0)
                    if (viaRecording) main.postDelayed({
                        if (tap === t && t.queued == 0L) { log("error", "no frames from the camera's recording stream after 3 s; stop and send the report"); state("No frames from the camera: stop and send the report"); tone(Hud.RED) }
                    }, 3000)
                } })
            if (t != null && !viaRecording) t.showOn(holder.surface)
        }
        val (fw, fh) = if (previewOwn) c.size.width to c.size.height else c.size.width / 2 to c.size.height / 2
        if (holder.surface.isValid && holder.surfaceFrame.width() == fw) go.run()
        else holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(h: SurfaceHolder) {}
            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hgt: Int) {
                if (w == fw && hgt == fh) { holder.removeCallback(this); go.run() }
            }
            override fun surfaceDestroyed(h: SurfaceHolder) { tap?.showOn(null) }
        })
        newSession()
        latestSpotted = 0
        screen.source = "SERVE"
        mode = Mode.LIVE
        setStartButton(true)
        tone(Hud.CYAN)
        state("Starting: wait for Ready…")
        // nothing spoken here: the camera takes a moment to open and the picture check a moment
        // more, and an instruction said over that is in the way. "Ready." is the whole cue.
    }

    /** which way round the tap's picture is, once a few frames have been recorded (tried for 5 s) */
    private fun calibrateSoon(p: LivePipeline, tries: Int) {
        main.postDelayed({
            if (pipeline !== p) return@postDelayed
            Thread {
                val f = try { p.calibrate() } catch (e: Exception) { main.post { log("error", "picture check: ${e.message}") }; -1 }
                main.post {
                    if (f >= 0 && p.flipBest > 15 && tap?.throughRecording == true) {
                        // the chip's picture isn't the recording's: it read the buffers wrongly
                        log("error", "picture check: the chip's picture doesn't match the recording (${p.flipCheck})")
                        nextQueueMode()
                    } else if (f >= 0) { tap?.setFlip(f); log("camera", "picture check: ${p.flipCheck}"); if (mode == Mode.LIVE) setReady("Ready.") }
                    else if (tries < 10) calibrateSoon(p, tries + 1)
                    else {
                        log("error", "picture check failed: ${p.flipCheck.ifEmpty { "no frame matched" }}; watching anyway")
                        p.assumeDefault()
                        say("Picture check failed. Watching anyway; send the report.")
                        if (mode == Mode.LIVE) setReady("Ready.")
                    }
                }
            }.start()
        }, 500)
    }

    private fun newPipeline(store: FrameStore, blocking: Boolean, fps: Int = 240): LivePipeline =
        LivePipeline(store, capDir, fps, blocking,
            pose = PoseWatcher(this, fps) { msg -> main.post { log("error", msg) } }.also { poseWatcher = it },
            onNote = { msg -> main.post { log("skipped", msg) } },
            onSpotted = if (blocking) null else { id -> main.post { onSpotted(id) } },
            assets = assets,
            clipDir = if (blocking) null else cacheDir,
            onSkippedClip = if (blocking) null else { n, why, f -> main.post { log("skipped", "not a serve ($why): clip kept"); saveClip("skipped${n}_${why.replace(Regex("[^a-z]+"), "-").trim('-')}", f) } },
            onCapture = { info, clip -> main.post { onCaptured(info, clip) } },
            onCaptureStarted = { info -> main.post { onCaptureStarted(info) } },
            onError = { msg -> main.post { log("error", msg) } }).also { pipeline = it; it.windowRing = windowRingOn
                // Both colour fits belong to the session that made them. The chip's maps its own
                // numbers onto a decoder's, and the ring's maps the CAMERA's frames onto that
                // decoder's -- so a fit made live is wrong for a replay, where both sides are
                // the same decode, and a fit made replaying is wrong for the camera. They cost
                // one serve to make, so each session makes its own.
                CaptureGl.calib = null; CaptureGl.ringCalib = null }

    /**
     * Cutting the ball's window on the chip as the serve happens (LiveWindows), instead of
     * decoding the whole span again to cut the capture. Off: it is new, and every session
     * measured so far was read from a capture cut the old way. Turned on with the version
     * number tapped five times, which is where the other experiments live.
     */
    private var windowRingOn
        get() = prefs.getBoolean("windowRing", false)
        set(v) { prefs.edit().putBoolean("windowRing", v).apply() }

    private fun fitPreview(w: Int, h: Int) {
        preview.post {
            val parent = preview.parent as View
            val ph = parent.height
            preview.layoutParams = FrameLayout.LayoutParams((ph * w / h.toFloat()).roundToInt(), ph, Gravity.CENTER)
        }
    }

    private fun stopAll() {
        val wasLive = mode == Mode.LIVE
        pipeline?.let { p -> report.put("watching", JSONObject().put("encoded", recorder?.framesTotal ?: 0L).put("decoded", p.decoded).put("dropped", p.dropped)
            .put("skipped", p.skipped).put("pictureCheck", p.flipCheck).put("waitingForKey", p.waitingForKey).put("decoderFull", p.decoderFull).put("msPerFrame", p.trackMsAvg).put("shrinkMs", p.shrinkMsAvg).put("findMs", p.findMsAvg).put("linkMs", p.linkMsAvg).put("lagMs", p.lagMs).put("serves", p.servesFound).put("trimmed", p.trimmed).put("ignoredWhileMeasuring", p.ignoredWhileMeasuring).put("captureBy", p.lastCaptureBy).put("chipCheck", p.chipCheck).put("chipCaptureFailures", p.gpuFailures)
            .put("rejected", JSONObject((p.tracker?.rejected ?: emptyMap<String, Int>()) as Map<*, *>))) }
        camera.stop()
        tap?.let { t -> report.put("tap", JSONObject().put("frames", t.frames).put("tapped", t.tapped).put("missed", t.missed).put("tapMs", t.tapMsAvg).put("latchMs", t.latchMsAvg).put("showMs", t.showMsAvg)
            .put("readMs", t.readMsAvg).put("busySkips", t.busySkips).put("readBack", t.readBack).put("queue", t.queueKind)
            .put("queued", t.queued).put("encodeMs", t.encodeMsAvg).put("bindFailures", t.bindFailures).put("fenceWaitMs", t.fenceWaitMsAvg)
            .put("swapMs", t.swapMsAvg).put("busy", "%.2f".format(t.busyShare)).put("framesPerWake", if (t.drains > 0) "%.1f".format(t.queued.toDouble() / t.drains) else "-").put("maxBurst", t.maxBurst)
            .put("windows", JSONObject().put("on", pipeline?.windowRing == true).put("cut", t.windowsCut).put("skips", t.winSkips).put("errors", t.winErrors)
                .put("ms", t.winMsAvg).put("readMs", t.winReadMsAvg).put("side", pipeline?.ringSide ?: 0).put("plans", pipeline?.ringPlans ?: 0L)
                .put("kept", pipeline?.windows?.kept ?: 0L).put("dropped", pipeline?.windows?.dropped ?: 0L)
                .put("note", pipeline?.ringNote ?: "").put("fit", CaptureGl.ringNote))) }
        tap?.stop(); tap = null
        recorder?.onFrame = null
        poseWatcher?.let { w -> report.put("pose", JSONObject().put("ready", w.ready).put("looked", w.looked).put("found", w.found).put("ms", w.msAvg).put("notServes", w.rejected)) }
        pipeline?.stop(); pipeline = null
        poseWatcher?.stop(); poseWatcher = null
        recorder?.stop(); recorder = null
        mode = Mode.IDLE
        turnId = 0
        setStartButton(false)
        tone(Hud.CYAN)
        state("Stopped")
        if (wasLive) finishSession()
        main.post { startPreview() }          // unless something started meanwhile (a restart)
    }

    override fun onResume() { super.onResume(); resumed = true; startPreview() }
    override fun onPause() { super.onPause(); resumed = false; stopPreview(); if (mode == Mode.LIVE) stopAll() }
    override fun onDestroy() { super.onDestroy(); stopAll(); tts?.shutdown(); camThread.quitSafely(); encThread.quitSafely() }

    // ------------------------------------------------------------------ replay a video (testing without a table)
    private fun pickVideo() {
        if (mode != Mode.IDLE) return
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "video/*" }, 7)
    }

    /**
     * A video replayed through the graphics chip, as the camera's own frames go.
     *
     * "Test with a video" has always fed the file to the pipeline's own decoder, which measures
     * the tracker, the capture and the engine but never touches GlTap -- so the window pass and
     * the ring, which only exist on the camera's path, went untested by it. Here the clip is
     * decoded into the tap's surface instead: every frame reaches the chip, the small picture
     * comes back for the tracker, the window is cut, and a capture is built from the ring,
     * exactly as live. What it cannot test is the camera itself -- the ring's colour fit comes
     * out near identity because both sides of it are the same decode.
     *
     * Frames are fed one at a time and each waits for the tap to report it. A surface hands its
     * buffers over through a queue that drops when the consumer falls behind, and replaying
     * faster than the chip can cut would silently lose frames -- which is the one thing this is
     * meant to measure. Slower than real time is fine; it is a test.
     *
     * Returns false if the chip cannot be set up, and the caller falls back to decoding.
     */
    private fun replayThroughChip(ff: FileFrames, p: LivePipeline): Boolean {
        val fmt = ff.outputFormat ?: return false
        val w = fmt.getInteger(MediaFormat.KEY_WIDTH); val h = fmt.getInteger(MediaFormat.KEY_HEIGHT)
        val ws = try {
            GlTap.WindowShaders(
                assets.open("shaders/quad.vert").bufferedReader().readText(),
                assets.open("shaders/crop_y.frag").bufferedReader().readText(),
                assets.open("shaders/crop_c.frag").bufferedReader().readText(),
                assets.open("shaders/crop_rows.frag").bufferedReader().readText())
        } catch (e: Exception) { null } ?: return false
        val paced = java.util.concurrent.Semaphore(0)
        val t = GlTap(w, h, fps = ff.captureFps,
            onFrame = { ts, no -> p.offerTapTime(ts, no); paced.release() },
            onPicture = { ts, no, rgba -> p.offerTapPicture(ts, no, rgba, w / 4, h / 4) },
            onError = { msg -> main.post { log("error", msg) } },
            windowShaders = ws, onWindow = { no, at, b -> p.offerWindow(no, at, b) })
        tap = t
        p.planWindows = { plan -> t.windowPlan = plan }
        val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
        var stalls = 0
        try {
            t.start()
            codec.configure(fmt, t.surface, null, 0)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var k = 0; var eosIn = false; var done = false; var shown = 0; var checked = false
            while (!done && mode == Mode.REPLAY) {
                if (!eosIn) {
                    val i = codec.dequeueInputBuffer(2_000)
                    if (i >= 0) {
                        if (k < ff.frames.size) {
                            val fr = ff.frames[k++]
                            codec.getInputBuffer(i)!!.apply { clear(); put(fr.data) }
                            codec.queueInputBuffer(i, 0, fr.data.size, fr.ptsUs, if (fr.key) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                        } else { codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); eosIn = true }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 5_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED || o == MediaCodec.INFO_TRY_AGAIN_LATER) continue
                if (o < 0) continue
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) done = true
                val render = info.size > 0
                // the clip's own time, so the tap numbers the frames as the recording does and
                // the picture check lines the two up with no clock offset at all
                codec.releaseOutputBuffer(o, if (render) info.presentationTimeUs * 1000 else 0L)
                if (!render) continue
                if (!paced.tryAcquire(1, 200, TimeUnit.MILLISECONDS)) stalls++
                shown++
                // the picture check as soon as there are taps to check. Early on purpose: the
                // frames before it are only buffered for the check itself, and a serve clip
                // carries half a second of lead-in that the tracker wants for its background.
                if (!checked && shown > 30) { checked = true; val f = p.calibrate(); main.post { log("replay", "picture check: ${p.flipCheck}") }; if (f < 0) checked = false }
                if (shown % 60 == 0) { val pct = 100 * shown / maxOf(1, ff.frames.size); main.post { if (mode == Mode.REPLAY) state("Video test on the chip: $pct%") } }
            }
        } finally {
            try { codec.stop() } catch (_: Exception) {}
            codec.release()
            main.post { log("replay", "chip: ${t.frames} frames, ${t.tapped} tapped, ${t.missed} missed, ${t.windowsCut} windows (${t.winSkips} skipped)" +
                (if (stalls > 0) ", $stalls frames the chip never reported" else "")) }
            t.stop(); if (tap === t) tap = null
            p.planWindows = null
        }
        return true
    }

    private var testSpotted = 0                          // a video test: serves found, and answers so far
    private var testRead = 0
    private var testGood = 0
    /** once every serve the video test found has its answer: say how it went */
    private fun finishTest() {
        if (mode == Mode.REPLAY || testSpotted == 0 || testRead < testSpotted) return
        tone(Hud.CYAN)
        state("Video test done: $testGood of $testSpotted serve(s) read")
        screen.source = "SERVE"
    }

    /** what the tracker saw in a video with no serve, in words (its counts of turned-down movements) */
    private fun whyNoServe(rejected: Map<String, Int>): String {
        val words = mapOf("slow (a body?)" to "slow movements (a body?)", "too big (a body?)" to "large moving shapes (a body?)",
            "picture moving" to "moments with the picture moving", "toss only" to "tosses with no flight after",
            "upward (toss)" to "balls going up (tosses)", "straight drop" to "balls dropping straight down", "short flight" to "flights too short to measure")
        val seen = rejected.filterKeys { it in words }.entries.sortedByDescending { it.value }.take(3).map { "${words[it.key]}: ${it.value}" }
        return (if (seen.isEmpty()) "Nothing moved like a ball in flight." else "What it saw instead: " + seen.joinToString("; ") + ".") +
            " The ball has to fly across the picture, over the table, from a phone that stays still."
    }

    @Deprecated("plain Activity result")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        val uri = data?.data
        if (requestCode != 7 || resultCode != RESULT_OK || uri == null) return
        stopPreview()
        mode = Mode.REPLAY
        setStartButton(true); screen.nextBtn.visibility = android.view.View.GONE
        val name = uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':') ?: "the video"
        screen.testStarted(name)
        tone(Hud.AMBER)
        state("Video test: reading the video…")
        testSpotted = 0; testRead = 0; testGood = 0
        Thread {
            try {
                val ff = FileFrames(this, uri)
                main.post { log("replay", "${ff.frames.size} frames") }
                val p = newPipeline(ff, blocking = true, fps = ff.captureFps)
                p.prime(ff.frames)
                p.start()
                // Through the chip when it can be: the clip's frames are decoded into the tap's
                // own surface, so the small picture, the window pass and the ring all run the
                // way they do off the camera. Otherwise the pipeline's own decoder, as before.
                val byChip = if (!windowRingOn) false else try { replayThroughChip(ff, p) }
                    catch (e: Exception) { main.post { log("error", "replay on the chip: ${e.message}; decoding instead") }; false }
                if (!byChip) {
                    var k = 0
                    for (fr in ff.frames) {
                        if (mode != Mode.REPLAY) break
                        p.offer(fr)
                        if (++k % 60 == 0) { val pct = 100 * k / ff.frames.size; main.post { if (mode == Mode.REPLAY) state("Video test: looking for serves… $pct%") } }
                    }
                }
                if (mode == Mode.REPLAY) p.finish()
                main.post {
                    if (mode == Mode.REPLAY) {
                        log("replay", "done: ${p.servesFound} serve(s) spotted, tracker ${"%.1f".format(p.trackMsAvg)} ms a frame")
                        val why = whyNoServe(p.tracker?.rejected ?: emptyMap())
                        stopAll()
                        testSpotted = p.servesFound
                        if (p.servesFound == 0) {
                            screen.testFoundNone(why); screen.source = "SERVE"
                            tone(Hud.CYAN); state("Video test done: no serve found")
                            say("No serve found in this video.")
                        } else { tone(Hud.AMBER); state("Video test: measuring ${p.servesFound} serve(s)…"); finishTest() }
                    }
                }
            } catch (e: Exception) { main.post { log("error", "replay: ${e.message}"); stopAll(); tone(Hud.RED); state("Video test failed: ${e.message}") } }
        }.start()
    }

    // ------------------------------------------------------------------ a serve was spotted
    /** a capture started being written: measured from now on, its frames read as they are cut (0.8.2) */
    private fun onCaptureStarted(info: Capture.Info) {
        waiting.addLast(info)
        // serves waiting to be measured: up to 12 (0.5.4 kept 3 and lost 3 of 15 to slow readings), and
        // 1.5 GB of frames (a capture is up to ~350 MB); the oldest go first, their clips are saved
        fun bytes() = waiting.sumOf { it.n.toLong() * it.frameBytes }
        while (waiting.size > (if (mode == Mode.LIVE) 12 else 30) || (waiting.size > 1 && bytes() > 1_500_000_000L))
            waiting.removeFirst().let { File(capDir, "cap_${it.id}.bin").delete(); log("skipped", "#${it.id}: too many serves waiting to be measured") }
        next()
    }

    private val writeMsOf = HashMap<Int, Long>()
    private val spottedAt = HashMap<Int, Long>()

    private fun onCaptured(info: Capture.Info, clip: File?) {
        log("capture", "#${info.id}: ${info.n} frames, ${info.frameBytes / 1024} KB each, written in ${info.writeMs} ms")
        writeMsOf[info.id] = info.writeMs
        if (mode == Mode.LIVE) { if (clip != null) saveClip("serve%02d".format(info.id), clip) else log("error", "#${info.id}: no clip saved (too few frames)") }
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
    // ------------------------------------------------------------------ the session's files (0.8.3)
    // Download/ServeCoach/<session>/: each serve's clip and data, the skipped movements' clips, the
    // report; at Stop, all of them can go to Drive in one share (training data for the spin model).
    private var session = ""
    private var sessionFrom = 0                         // results before this session
    private val sessionFiles = ArrayList<android.net.Uri>()
    private var sessionBytes = 0L

    private fun newSession() {
        session = "Session_" + java.text.SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(java.util.Date())
        sessionFrom = results.length()
        synchronized(sessionFiles) { sessionFiles.clear(); sessionBytes = 0 }
    }

    /** a file into this session's folder (on its own thread), then logged */
    private fun saveToSession(name: String, mime: String, write: (java.io.OutputStream) -> Unit) {
        val folder = session
        Thread {
            try {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name); put(MediaStore.Downloads.MIME_TYPE, mime)
                    put(MediaStore.Downloads.RELATIVE_PATH, "Download/ServeCoach/$folder")
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw IllegalStateException("the phone's storage refused a new file")
                var n = 0L
                contentResolver.openOutputStream(uri)?.use { out ->
                    val counting = object : java.io.FilterOutputStream(out) {
                        override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); n += len }
                        override fun write(b: Int) { out.write(b); n++ }
                    }
                    write(counting); counting.flush()
                } ?: throw IllegalStateException("couldn't open the new file")
                synchronized(sessionFiles) { sessionFiles.add(uri); sessionBytes += n }
                if (mime == "video/mp4") main.post { log("saved", "Download/ServeCoach/$folder/$name") }
            } catch (e: Exception) { main.post { log("error", "save $name: ${e.message}") } }
        }.start()
    }

    private fun saveClip(label: String, tmp: File) =
        saveToSession("$label.mp4", "video/mp4") { out -> tmp.inputStream().use { it.copyTo(out) }; tmp.delete() }

    /** a serve's data: the reading with each measured stretch (the ball's path, the flight), and the tracker's header */
    private fun saveServeData(id: Int, r: JSONObject, data: Any?) {
        val head = try { JSONObject(File(capDir, "cap_$id.json").readText()) } catch (e: Exception) { null }
        val rec = JSONObject().put("app", BuildConfig.VERSION_NAME).put("session", session).put("serve", id)
            .put("clip", "serve%02d.mp4".format(id)).put("time", System.currentTimeMillis())
            .put("capture", head ?: JSONObject.NULL).put("result", r).put("data", data ?: JSONObject.NULL)
        saveToSession("serve%02d.json".format(id), "application/json") { it.write(rec.toString().toByteArray()) }
    }

    /** at Stop: the report into the session's folder, then offer to send the whole folder on */
    private fun finishSession(tries: Int = 0) {
        if (session.isEmpty()) return
        // a serve still being measured: its answer (and data) first, up to 30 s
        if ((measuring != null || waiting.isNotEmpty()) && tries < 60) { main.postDelayed({ finishSession(tries + 1) }, 500); return }
        report.put("events", events).put("results", JSONArray().also { a -> for (k in sessionFrom until results.length()) a.put(results.get(k)) })
        val text = report.toString(1)
        saveToSession("report.json", "application/json") { it.write(text.toByteArray()) }
        val name = session
        // once the clips still being written are in (they go on their own threads)
        main.postDelayed({
            val (uris, bytes) = synchronized(sessionFiles) { ArrayList(sessionFiles) to sessionBytes }
            if (uris.size <= 1 || isFinishing) return@postDelayed
            android.app.AlertDialog.Builder(this)
                .setTitle("Send this session?")
                .setMessage("${uris.size} files (${bytes / 1_000_000} MB) in Download/ServeCoach/$name. Send them to Drive, into the shared folder, to train the spin model.")
                .setPositiveButton("Send") { _, _ -> shareSession(uris) }
                .setNegativeButton("Not now", null)
                .show()
        }, 1500)
    }

    private fun shareSession(uris: ArrayList<android.net.Uri>) {
        val send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            clipData = ClipData.newRawUri(session, uris[0]).also { c -> uris.drop(1).forEach { c.addItem(ClipData.Item(it)) } }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, "Send the session"))
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
        // a capture still being written (0.8.2): a read waits for its frames, up to 20 s
        val w = id.toIntOrNull()?.let { LivePipeline.writing[it] }
        if (what == "head") return WebResourceResponse("application/json", "utf-8", File(capDir, "cap_$id.json").inputStream())
        if (what == "head/final") { w?.awaitDone(20_000); return WebResourceResponse("application/json", "utf-8", File(capDir, "cap_$id.json").inputStream()) }
        if (what == "all") w?.awaitDone(20_000)
        if (what == "all") return WebResourceResponse("application/octet-stream", null, File(capDir, "cap_$id.bin").inputStream().buffered(1 shl 20))
        if (what.startsWith("frames/")) {                   // frames/<from>/<count>: a chunk of them
            val (from, count) = what.removePrefix("frames/").split("/").map { it.toLong() }
            w?.await((from + count).toInt(), 20_000)
            val head = JSONObject(File(capDir, "cap_$id.json").readText())
            val r = head.getJSONArray("rect")
            val per = r.getInt(2).toLong() * r.getInt(3) + 2L * (r.getInt(2) / 2) * (r.getInt(3) / 2)
            val f = RandomAccessFile(File(capDir, "cap_$id.bin"), "r")
            val n = minOf(count, maxOf(0L, f.length() / per - from))
            val bytes = ByteArray((n * per).toInt())
            f.use { it.seek(from * per); it.readFully(bytes) }
            return WebResourceResponse("application/octet-stream", null, ByteArrayInputStream(bytes))
        }
        w?.await(what.toInt() + 1, 20_000)
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
            val data = r.remove("data")                     // the serve's data file only, not the report
            r.put("capture", id).put("wallMs", SystemClock.uptimeMillis() - started)
            info?.let { r.put("captureFrames", it.n) }
            writeMsOf.remove(id)?.let { r.put("writeMs", it) }
            // from "Got it" to the answer: the wait the player has
            spottedAt.remove(id)?.let { r.put("answerMs", SystemClock.uptimeMillis() - it) }
            results.put(r)
            if (session.isNotEmpty() && mode != Mode.REPLAY) saveServeData(id, r, data)
            LivePipeline.writing.remove(id)
            File(capDir, "cap_$id.bin").delete(); File(capDir, "cap_$id.json").delete()
            show(r)
            if (screen.source != "SERVE") {                 // a video test: its answers counted, then the summary
                testRead++; if (r.optJSONObject("serve")?.optBoolean("clear") == true) testGood++
                finishTest()
            }
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

    // ------------------------------------------------------------------ turn by turn (live)
    // Ready -> a serve spotted ("Got it", others ignored) -> its answer spoken -> Ready. Answers
    // take seconds, longer than between serves: without turns it wasn't clear which serve an
    // answer was about.
    private var turnId = 0
    private var latestSpotted = 0                       // the newest serve spotted this session
    private fun setReady(spoken: String?) {
        pipeline?.resume()
        turnId = 0
        tone(Hud.CYAN)
        state("Ready: serve")
        if (spoken != null) say(spoken, queue = true)
    }
    private fun onSpotted(id: Int) {
        if (mode != Mode.LIVE) return
        turnId = id; latestSpotted = id
        spottedAt[id] = SystemClock.uptimeMillis()
        tone(Hud.AMBER)
        state("Got it: measuring serve #$id…")
        screen.busy(true)
        say("Got it.")
        // never stuck waiting: if no answer comes in 30 s (a capture that failed), ready again
        main.postDelayed({ if (mode == Mode.LIVE && turnId == id) { log("error", "#$id: no answer in 30 s"); setReady("Ready.") } }, 30_000)
    }
    /** after an answer (or none) in live mode: the next turn */
    private fun turnDone(spoken: String, id: Int) {
        screen.busy(false)
        // its own turn: then ready for the next. A turn already ended by NEXT SERVE: readiness left as
        // it is, and its answer said only if no serve came after it (only the latest serve is spoken)
        if (mode == Mode.LIVE && id == turnId) setReady("$spoken Ready.")
        else if (mode != Mode.LIVE || id >= latestSpotted) say(spoken, queue = true)
    }

    /** NEXT SERVE (0.9.2): a helper ends the turn now; a serve being measured still gets its answer */
    private fun forceReady() {
        if (mode != Mode.LIVE || turnId == 0) return
        log("turn", "#$turnId: ready for the next serve (button)")
        setReady("Ready.")
    }

    private fun show(r: JSONObject) {
        val sv = r.optJSONObject("serve")
        val secs = r.optLong("wallMs") / 1000.0
        // a serve that hit the net: the ball stopped and dropped without bouncing. The engine used
        // to measure that drop and give it as the serve's spin (10 Nov session, serve 6: 40.9
        // rev/s, clear). It is the player's answer, so it is said rather than passed over.
        if (r.optBoolean("net")) {
            unclear++
            log("unclear", "#${r.optInt("capture")}: into the net")
            screen.noRead(r.optInt("capture"), "Into the net")
            if (mode == Mode.LIVE) turnDone("Into the net.", r.optInt("capture"))
            return
        }
        if (sv == null || !sv.optBoolean("clear")) {
            // a movement that wasn't a readable serve (a toss, a catch, someone walking by): not announced
            unclear++
            // a JSON null reads as "null" through optString
            val why = (if (r.isNull("error")) "" else r.optString("error")).ifEmpty { sv?.optJSONArray("issues")?.optString(0) ?: "no clear reading" }
            log("unclear", "#${r.optInt("capture")}: $why")
            screen.noRead(r.optInt("capture"), why)
            if (mode == Mode.LIVE) turnDone("Couldn't read that one.", r.optInt("capture"))
            return
        }
        // read only "after a bounce" and slowly turning: in the live tests that was other movement
        // (walking off to fetch the ball, 4.8 rev/s), not a serve; listed, not announced. (A real
        // serve can come out labelled "bounce" when its crop starts late: L1, 23 rev/s.)
        val phases = sv.optJSONArray("phases")
        val own = (0 until (phases?.length() ?: 0)).any { k -> phases!!.getJSONObject(k).let { it.optBoolean("clear") && it.optString("phase") != "bounce" } }
        if (!own && sv.optDouble("rps") < 10) {
            unclear++
            log("unclear", "#${r.optInt("capture")}: only read after a bounce (${(sv.optDouble("rps") * 60).roundToInt()} RPM ${sv.optString("type")}): not counted as a serve")
            if (mode == Mode.LIVE) turnDone("Couldn't read that one.", r.optInt("capture"))
            return
        }
        val rps = sv.optDouble("rps")
        val type = sv.optString("type")
        val coach = r.optJSONObject("coach")
        val kind = coach?.optString("kind") ?: ""
        val t = titles[kind]
        val ballPx = sv.optDouble("ballPx", 0.0)
        // the only line the screen still carries on a good reading: something the player can act on.
        // The coaching prose and the timing numbers both went into the report, where they can be
        // read at leisure instead of competing with the serve.
        val closer = if (ballPx in 1.0..24.0) "The ball is only ${ballPx.roundToInt()} px across: move the phone closer." else ""
        // one number (0.7.9 showed rev/s and RPM side by side: "kinda confusing"): RPM, to the nearest 10
        val rpm = (rps * 6).roundToInt() * 10
        // the spin split, for the ball the HUD draws. gyro keeps its sign; its direction is only
        // worth drawing when the colour dots were read (engine.js, mirrored()).
        // afterBounce is not cosmetic: the split is measured against the direction of travel, and
        // the bounce turns it, so the gyro's sign belongs to one stretch and not to the other.
        // gyroDirKnown is the engine's own verdict on whether the drill direction survives a
        // front-to-back mirror; it is true for a side-on reading whether or not the dots were read.
        val spin = if (sv.has("lift")) Spin(sv.optDouble("lift", 0.0), sv.optDouble("curve", 0.0),
            sv.optDouble("gyro", 0.0), sv.optBoolean("gyroDirKnown"),
            afterBounce = sv.optString("phase") == "bounce") else null
        screen.reading(r.optInt("capture"), rpm, type, closer, spin)
        turnDone("$rpm RPM, ${SpinLevel.spoken(rpm)} spin, $type. ${t?.first ?: ""}", r.optInt("capture"))
    }

    private fun say(text: String, queue: Boolean = false) { tts?.speak(text, if (queue) TextToSpeech.QUEUE_ADD else TextToSpeech.QUEUE_FLUSH, null, "r") }

    // ------------------------------------------------------------------ report
    private fun log(kind: String, text: String) {
        events.put(JSONObject().put("t", SystemClock.uptimeMillis()).put("kind", kind).put("text", text))
        if (kind == "error") Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    }

    private fun copyReport() {
        pipeline?.let { p -> report.put("watching", JSONObject().put("encoded", recorder?.framesTotal ?: 0L).put("decoded", p.decoded).put("dropped", p.dropped)
            .put("skipped", p.skipped).put("pictureCheck", p.flipCheck).put("waitingForKey", p.waitingForKey).put("decoderFull", p.decoderFull).put("msPerFrame", p.trackMsAvg).put("shrinkMs", p.shrinkMsAvg).put("findMs", p.findMsAvg).put("linkMs", p.linkMsAvg).put("lagMs", p.lagMs).put("serves", p.servesFound).put("trimmed", p.trimmed).put("ignoredWhileMeasuring", p.ignoredWhileMeasuring).put("captureBy", p.lastCaptureBy).put("chipCheck", p.chipCheck).put("chipCaptureFailures", p.gpuFailures)) }
        tap?.let { t -> report.put("tap", JSONObject().put("frames", t.frames).put("tapped", t.tapped).put("missed", t.missed).put("tapMs", t.tapMsAvg).put("latchMs", t.latchMsAvg).put("showMs", t.showMsAvg)
            .put("readMs", t.readMsAvg).put("busySkips", t.busySkips).put("readBack", t.readBack).put("queue", t.queueKind)
            .put("queued", t.queued).put("encodeMs", t.encodeMsAvg).put("bindFailures", t.bindFailures).put("fenceWaitMs", t.fenceWaitMsAvg)
            .put("swapMs", t.swapMsAvg).put("busy", "%.2f".format(t.busyShare)).put("framesPerWake", if (t.drains > 0) "%.1f".format(t.queued.toDouble() / t.drains) else "-").put("maxBurst", t.maxBurst)
            .put("windows", JSONObject().put("on", pipeline?.windowRing == true).put("cut", t.windowsCut).put("skips", t.winSkips).put("errors", t.winErrors)
                .put("ms", t.winMsAvg).put("readMs", t.winReadMsAvg).put("side", pipeline?.ringSide ?: 0).put("plans", pipeline?.ringPlans ?: 0L)
                .put("kept", pipeline?.windows?.kept ?: 0L).put("dropped", pipeline?.windows?.dropped ?: 0L)
                .put("note", pipeline?.ringNote ?: "").put("fit", CaptureGl.ringNote))) }
        poseWatcher?.let { w -> report.put("pose", JSONObject().put("ready", w.ready).put("looked", w.looked).put("found", w.found).put("ms", w.msAvg).put("notServes", w.rejected)) }
        report.put("events", events).put("results", results)
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Serve Coach report", report.toString(1)))
        Toast.makeText(this, "Report copied: paste it into the chat", Toast.LENGTH_LONG).show()
    }
}
