package com.monti.servecoach

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * The AI setup coach (0.9.1, optional, the player's own Gemini API key): a Gemini Live session over
 * its WebSocket while the phone is being set up. It gets a JPEG of the camera's view about once a
 * second and, when the on-device check's advice changes, a short question with that advice; it
 * answers by voice (24 kHz PCM, [onAudio]) with a transcript ([onText]). Only during setup: closed
 * at Start. Protocol as in Google's "Get started with Live API using WebSockets".
 */
class GeminiCoach(
    private val apiKey: String,
    private val onText: (String) -> Unit,
    private val onAudio: (ByteArray) -> Unit,
    private val onStatus: (String) -> Unit,
    private val endpoint: String = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent",
    private val models: List<String> = listOf("gemini-3.8-live", "gemini-3.1-flash-live-preview"),
) {
    private val client = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).pingInterval(20, TimeUnit.SECONDS).build()
    @Volatile private var ws: WebSocket? = null
    @Volatile var ready = false; private set
    @Volatile private var closed = false
    private var modelIndex = 0
    private var transcripts = true
    private var lastAsk = 0L
    @Volatile var speaking = false; private set

    fun start() { closed = false; connect() }
    fun stop() { closed = true; ready = false; ws?.close(1000, "setup done"); ws = null }

    private fun connect() {
        ready = false
        val req = Request.Builder().url("$endpoint?key=$apiKey").build()
        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(s: WebSocket, r: Response) { s.send(setupMessage().toString()) }
            override fun onMessage(s: WebSocket, text: String) = handle(text)
            override fun onMessage(s: WebSocket, bytes: ByteString) = handle(bytes.utf8())
            override fun onClosing(s: WebSocket, code: Int, reason: String) { s.close(1000, null); onClosed(code, reason) }
            override fun onFailure(s: WebSocket, t: Throwable, r: Response?) { onClosed(r?.code ?: -1, t.message ?: "connection failed") }
        })
    }

    private fun onClosed(code: Int, reason: String) {
        val wasReady = ready; ready = false; ws = null
        if (closed) return
        // refused at setup: the other model name, then without transcripts, once each
        if (!wasReady) {
            if (reason.contains("model", true) && modelIndex + 1 < models.size) { modelIndex++; onStatus("AI coach: trying ${models[modelIndex]}"); connect(); return }
            if (transcripts && (reason.contains("transcription", true) || reason.contains("unknown", true) || reason.contains("invalid", true))) { transcripts = false; connect(); return }
        }
        onStatus(if (code == 1008 || reason.contains("API key", true)) "AI coach: the API key was refused" else "AI coach stopped: $reason")
    }

    fun setupMessage(): JSONObject {
        val setup = JSONObject()
            .put("model", "models/${models[modelIndex]}")
            .put("responseModalities", JSONArray().put("AUDIO"))
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", INSTRUCTIONS))))
        if (transcripts) setup.put("outputAudioTranscription", JSONObject())
        return JSONObject().put("setup", setup)
    }

    /** the camera's view (JPEG), about once a second */
    fun sendFrame(jpeg: ByteArray) {
        if (!ready) return
        ws?.send(JSONObject().put("realtimeInput", JSONObject().put("video",
            JSONObject().put("data", Base64.getEncoder().encodeToString(jpeg)).put("mimeType", "image/jpeg"))).toString())
    }

    /** ask for the next instruction, with what the phone's own check sees; not while it's still speaking, nor more than every 5 s */
    fun ask(onDevice: String, now: Long = System.currentTimeMillis()) {
        if (!ready || speaking || now - lastAsk < 5000) return
        lastAsk = now
        ws?.send(JSONObject().put("realtimeInput", JSONObject().put("text",
            "The phone's own check says: \"$onDevice\". Look at the latest picture: what one change should I make now?")).toString())
    }

    fun handle(text: String) {
        val m = try { JSONObject(text) } catch (e: Exception) { return }
        if (m.has("setupComplete")) { ready = true; onStatus("AI coach on (${models[modelIndex]})"); return }
        m.optJSONObject("goAway")?.let { onStatus("AI coach: the session is ending") }
        val sc = m.optJSONObject("serverContent") ?: return
        sc.optJSONObject("modelTurn")?.optJSONArray("parts")?.let { parts ->
            for (k in 0 until parts.length()) {
                val p = parts.getJSONObject(k)
                p.optJSONObject("inlineData")?.optString("data")?.takeIf { it.isNotEmpty() }?.let { speaking = true; onAudio(Base64.getDecoder().decode(it)) }
                p.optString("text").takeIf { it.isNotEmpty() }?.let { onText(it) }
            }
        }
        sc.optJSONObject("outputTranscription")?.optString("text")?.takeIf { it.isNotEmpty() }?.let { onText(it) }
        if (sc.optBoolean("turnComplete") || sc.optBoolean("interrupted")) { speaking = false; onText("\n") }
    }

    companion object {
        const val INSTRUCTIONS = """You help a table tennis player set up a phone that films their serves at 240 frames a second, to measure the ball's spin. You see what the phone's camera sees, about once a second.
A good setup: the phone in landscape, still (a tripod), level, beside the table, facing its side, roughly level with the net, 1 to 1.5 metres from where the ball flies. The server's half of the table low in the picture, with room above it for the toss; the ball must fly across the picture, not toward the camera. Even light; nobody walking between the phone and the table; a plain background behind the ball's path helps.
Speak in English, calmly, one short instruction at a time (at most 12 words), the most important first: for example "Move the phone back a little" or "Tilt it down so the whole table shows". When the setup is good, say "Looks good, press Start." Talk about nothing else."""
    }
}
