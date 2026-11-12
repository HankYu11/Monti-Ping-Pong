package com.monti.servecoach

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.ScreenOrientation
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * The live screen at the phone's own size, drawn by the app's own views, and the spin ball turning
 * a full revolution. Not a mock-up: HudScreen, SpinBall.onDraw and the real fonts, laid out and
 * drawn exactly as on the phone. The only stand-in is the camera picture behind the HUD, which on
 * a desktop is a gradient.
 *
 * It writes raw RGB rather than Paparazzi snapshots so the frames are full size (a snapshot is
 * scaled down) and so a film's worth of them never lands in the committed snapshot folder. There
 * is no java.awt on the Android unit-test classpath, so ffmpeg turns the raw frames into pictures:
 *
 *     ffmpeg -f rawvideo -pix_fmt rgb24 -s 2400x1080 -i turning.rgb -r 25 turning.mp4
 */
class HudFilm {
    @get:Rule val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5.copy(
        screenHeight = 1080, screenWidth = 2400, xdpi = 428, ydpi = 428, orientation = ScreenOrientation.LANDSCAPE))

    private val W = 2400
    private val H = 1080
    private val out = File(System.getProperty("hudFilmDir") ?: "build/hudfilm").apply { mkdirs() }

    private fun screen(): HudScreen {
        val s = HudScreen(paparazzi.context, {}, {}, {})
        s.preview.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.rgb(70, 74, 80), Color.rgb(28, 40, 70)))
        s.statsText.text = "App 0.9.10\nCamera 1920×1080 @ 240 fps\nEngine ready"
        return s
    }

    private val px = IntArray(W * H)
    private val rgb = ByteArray(W * H * 3)

    private fun write(root: View, file: File, append: Boolean) {
        root.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY),
                     View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, W, H)
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))
        bmp.getPixels(px, 0, W, 0, 0, W, H)
        for (i in px.indices) {
            val v = px[i]
            rgb[i * 3] = ((v shr 16) and 255).toByte()
            rgb[i * 3 + 1] = ((v shr 8) and 255).toByte()
            rgb[i * 3 + 2] = (v and 255).toByte()
        }
        java.io.FileOutputStream(file, append).use { it.write(rgb) }
    }

    /** a measured serve, held still, at the phone's own resolution */
    @Test fun still() {
        val s = screen(); s.running(true); s.state("Ready: serve")
        s.reading(3, 3150, "topspin (curves left) + gyro",
            "Mostly gyro: it will look like a nothing ball and skid at the bounce.\nball 2.8 m/s · answer in 2.4 s",
            Spin(lift = -21.3, curve = -12.2, gyro = 46.3, dirKnown = true))
        s.spinBall.poseTurns(0.0)
        write(s.root, File(out, "still.rgb"), false)
    }

    /** one whole turn of the ball, as the screen really draws it */
    @Test fun turning() {
        val s = screen(); s.running(true); s.state("Ready: serve")
        s.reading(3, 3150, "topspin (curves left) + gyro",
            "Mostly gyro: it will look like a nothing ball and skid at the bounce.\nball 2.8 m/s · answer in 2.4 s",
            Spin(lift = -21.3, curve = -12.2, gyro = 46.3, dirKnown = true))
        val n = 36
        val film = File(out, "turning.rgb")
        if (film.exists()) film.delete()
        for (k in 0 until n) {
            s.spinBall.poseTurns(k.toDouble() / n)
            write(s.root, film, true)
        }
    }
}
