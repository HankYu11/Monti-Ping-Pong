package com.monti.servecoach

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaRecorder
import kotlin.math.max

/**
 * Hears the hit: the paddle striking the ball is a short, sharp click. Every 5 ms of sound is
 * compared with the room's recent background (high frequencies only, so voices and footsteps
 * count less); a jump well above it is a click. After a click, the next 2.5 s are ignored (the
 * bounces, catching the ball; 0.1 took one 1.6 s after a hit). Reports the click's time on the System.nanoTime() clock.
 */
class ClickDetector(private val onClick: (nanoTime: Long, level: Double) -> Unit) {
    private val rate = 48000
    private val block = 240                         // 5 ms
    @Volatile private var running = false
    private var thread: Thread? = null
    @Volatile var sensitivity = 10.0                 // how many times the background a click must be
    @Volatile var lastLevel = 0.0; private set       // the latest block's level over the background, for the screen
    @Volatile var source = ""; private set

    @SuppressLint("MissingPermission")
    fun start(onError: (String) -> Unit) {
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        var rec: AudioRecord? = null
        for ((name, src) in listOf("unprocessed" to MediaRecorder.AudioSource.UNPROCESSED,
                                   "voice recognition" to MediaRecorder.AudioSource.VOICE_RECOGNITION,
                                   "mic" to MediaRecorder.AudioSource.MIC)) {
            try {
                val r = AudioRecord(src, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(minBuf, rate / 5))
                if (r.state == AudioRecord.STATE_INITIALIZED) { rec = r; source = name; break } else r.release()
            } catch (_: Exception) {}
        }
        val r = rec ?: run { onError("no microphone"); return }
        running = true
        thread = Thread {
            val buf = ShortArray(block)
            val ts = AudioTimestamp()
            var framesRead = 0L
            val history = DoubleArray(200)                 // the last second of block levels
            var hi = 0
            var filled = 0
            var quietUntil = 0L
            var prev = 0
            r.startRecording()
            while (running) {
                val n = r.read(buf, 0, block)
                if (n <= 0) continue
                // high-passed energy (first difference) and where in the block it peaks
                var e = 0.0
                var peakAt = 0
                var peak = 0
                for (i in 0 until n) {
                    val d = buf[i] - prev
                    prev = buf[i].toInt()
                    e += d.toDouble() * d
                    val a = if (d < 0) -d else d
                    if (a > peak) { peak = a; peakAt = i }
                }
                e /= n
                val sorted = history.copyOf(max(filled, 1)).also { it.sort() }
                val bg = max(sorted[sorted.size / 2], 50.0)
                lastLevel = e / bg
                val sampleIndex = framesRead + peakAt
                framesRead += n
                history[hi] = e; hi = (hi + 1) % history.size; if (filled < history.size) filled++
                if (filled < 40) continue                                  // learn the room first
                if (e > sensitivity * bg && e > 2.0e4) {
                    var t = System.nanoTime()
                    if (r.getTimestamp(ts, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS)
                        t = ts.nanoTime + (sampleIndex - ts.framePosition) * 1_000_000_000L / rate
                    if (t > quietUntil) {
                        quietUntil = t + 2_500_000_000L
                        onClick(t, e / bg)
                    }
                }
            }
            r.stop(); r.release()
        }.apply { name = "click-detector"; start() }
    }

    fun stop() { running = false; thread?.join(500); thread = null }
}
