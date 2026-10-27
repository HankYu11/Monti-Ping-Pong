package com.monti.servecoach

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.monti.servecoach.core.Quarter
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Where the player is, about 15 times a second (MediaPipe's pose model on the tracker's small
 * picture), so a ball moving while they walk isn't taken for a serve: in the 0.6.8 session 8 of
 * 17 captures were the player walking to fetch the ball.
 *
 * The rule, from those clips and every serve recorded so far: a serve is played standing. The
 * hips' sideways travel within any 1 s, in torso lengths, was 0.04-0.68 in all 21 serves and
 * 1.37-3.32 when walking: over 1.0 is not a serve. No one seen: no say.
 */
class PoseWatcher(context: Context, private val fps: Int, private val onError: (String) -> Unit) {
    private val thread = HandlerThread("pose").apply { start() }
    private val handler = Handler(thread.looper)
    private var landmarker: PoseLandmarker? = null
    @Volatile private var busy = false
    private var lastNo = -1L

    /** frame number (whole frames), hip centre x and torso length in small-picture pixels */
    private class Seen(val no: Long, val hipX: Float, val torso: Float)
    private val seen = ArrayDeque<Seen>()

    // for the report
    @Volatile var looked = 0L; private set
    @Volatile var found = 0L; private set
    @Volatile var msAvg = 0.0; private set
    @Volatile var rejected = 0; private set
    @Volatile var ready = false; private set

    init {
        handler.post {
            try {
                val opts = PoseLandmarker.PoseLandmarkerOptions.builder()
                    .setBaseOptions(BaseOptions.builder().setModelAssetPath("pose_landmarker_lite.task").build())
                    .setRunningMode(RunningMode.VIDEO).setNumPoses(1).build()
                landmarker = PoseLandmarker.createFromOptions(context, opts)
                ready = true
            } catch (e: Throwable) { onError("pose model: ${e.message}") }
        }
    }

    fun stop() { handler.post { try { landmarker?.close() } catch (_: Throwable) {}; landmarker = null }; thread.quitSafely() }

    /** a small picture (frame [no], whole frames): looked at if the model is free, else skipped */
    fun offer(q: Quarter, no: Long) {
        if (busy || !ready || no <= lastNo) return
        busy = true
        lastNo = no
        val bmp = toBitmap(q)
        handler.post {
            try {
                val t0 = SystemClock.elapsedRealtimeNanos()
                val r = landmarker?.detectForVideo(BitmapImageBuilder(bmp).build(), no * 1000 / fps)
                looked++
                msAvg += ((SystemClock.elapsedRealtimeNanos() - t0) / 1e6 - msAvg) / minOf(looked, 60L)
                val l = r?.landmarks()?.firstOrNull()
                if (l != null && l.size > 24) {
                    val w = q.w.toFloat(); val h = q.h.toFloat()
                    val hx = (l[23].x() + l[24].x()) / 2 * w; val hy = (l[23].y() + l[24].y()) / 2 * h
                    val sx = (l[11].x() + l[12].x()) / 2 * w; val sy = (l[11].y() + l[12].y()) / 2 * h
                    found++
                    synchronized(seen) {
                        seen.addLast(Seen(no, hx, hypot(sx - hx, sy - hy)))
                        while (seen.size > 600) seen.removeFirst()
                    }
                }
            } catch (e: Throwable) { onError("pose: ${e.message}") }
            finally { bmp.recycle(); busy = false }
        }
    }

    /**
     * Why the ball movement in frames [from, to] (whole frames) isn't a serve, or null: the
     * player walking. Looked at from half a second before.
     */
    fun veto(from: Long, to: Long): String? {
        val pts = synchronized(seen) { seen.filter { it.no in (from - fps / 2)..to } }
        if (pts.size < 3) return null
        val torso = pts.map { it.torso }.sorted()[pts.size / 2]
        if (torso < 5f) return null
        var travel = 0f
        for (i in pts.indices) for (j in i + 1 until pts.size) {
            if (pts[j].no - pts[i].no > fps) break
            travel = maxOf(travel, abs(pts[j].hipX - pts[i].hipX))
        }
        val t = travel / torso
        if (t <= 1.0f) return null
        rejected++
        return "the player was walking (hips moved ${"%.1f".format(t)} torso lengths in a second)"
    }

    /** brightness and colour (the tracker's units) to RGB, BT.709 */
    private fun toBitmap(q: Quarter): Bitmap {
        val px = IntArray(q.w * q.h)
        for (i in px.indices) {
            val y = q.y[i]; val u = q.u[i]; val v = q.v[i]
            val r = (y + 1.5748f * v).toInt().coerceIn(0, 255)
            val g = (y - 0.1873f * u - 0.4681f * v).toInt().coerceIn(0, 255)
            val b = (y + 1.8556f * u).toInt().coerceIn(0, 255)
            px[i] = (0xff shl 24) or (r shl 16) or (g shl 8) or b
        }
        return Bitmap.createBitmap(px, q.w, q.h, Bitmap.Config.ARGB_8888)
    }
}
