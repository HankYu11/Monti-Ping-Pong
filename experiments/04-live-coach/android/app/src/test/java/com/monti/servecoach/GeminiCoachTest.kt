package com.monti.servecoach

import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The AI coach against a stand-in Live API server: what it sends, and what it does with the answers. */
class GeminiCoachTest {
    @Test fun session() {
        val server = MockWebServer()
        val got = CopyOnWriteArrayList<JSONObject>()
        val sawText = CountDownLatch(1)
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(s: WebSocket, text: String) {
                val m = JSONObject(text); got.add(m)
                if (m.has("setup")) s.send("""{"setupComplete":{}}""")
                if (m.has("realtimeInput") && m.getJSONObject("realtimeInput").has("text")) {
                    val pcm = Base64.getEncoder().encodeToString(ByteArray(480) { 1 })
                    // the answer as a binary frame, as the real server sends JSON
                    s.send(okio.ByteString.of(*"""{"serverContent":{"modelTurn":{"parts":[{"inlineData":{"mimeType":"audio/pcm;rate=24000","data":"$pcm"}}]}}}""".toByteArray()))
                    s.send("""{"serverContent":{"outputTranscription":{"text":"Tilt it down a little."}}}""")
                    s.send("""{"serverContent":{"turnComplete":true}}""")
                }
            }
            override fun onOpen(s: WebSocket, r: Response) {}
            override fun onClosing(s: WebSocket, code: Int, reason: String) { s.close(1000, null) }
        }))
        server.start()
        val texts = CopyOnWriteArrayList<String>(); val audio = CopyOnWriteArrayList<ByteArray>(); val status = CopyOnWriteArrayList<String>()
        val coach = GeminiCoach("KEY", onText = { texts.add(it); if (it.contains("Tilt")) sawText.countDown() }, onAudio = { audio.add(it) }, onStatus = { status.add(it) },
            endpoint = server.url("/ws").toString().replace("http", "ws"))
        coach.start()
        val t0 = System.currentTimeMillis(); while (!coach.ready && System.currentTimeMillis() - t0 < 5000) Thread.sleep(20)
        assertTrue("setup complete", coach.ready)
        coach.sendFrame(byteArrayOf(-1, -40, -1, -32))
        coach.ask("Tilt the phone down a little: more of the table should show.")
        assertTrue("answer heard", sawText.await(5, TimeUnit.SECONDS))
        Thread.sleep(200)
        coach.stop(); Thread.sleep(300)
        try { server.shutdown() } catch (_: java.io.IOException) {}

        val setup = got.first { it.has("setup") }.getJSONObject("setup")
        assertEquals("models/gemini-3.8-live", setup.getString("model"))
        assertEquals("AUDIO", setup.getJSONArray("responseModalities").getString(0))
        assertTrue(setup.getJSONObject("systemInstruction").getJSONArray("parts").getJSONObject(0).getString("text").contains("table tennis"))
        val video = got.first { it.optJSONObject("realtimeInput")?.has("video") == true }.getJSONObject("realtimeInput").getJSONObject("video")
        assertEquals("image/jpeg", video.getString("mimeType"))
        assertEquals("/9j/4A==", video.getString("data"))
        assertTrue(got.any { it.optJSONObject("realtimeInput")?.optString("text")?.contains("more of the table") == true })
        assertEquals(1, audio.size); assertEquals(480, audio[0].size)
        assertTrue(texts.any { it == "Tilt it down a little." })
        assertTrue(status.any { it.startsWith("AI coach on") })
        assertTrue("not speaking after the turn", !coach.speaking)
    }
}
