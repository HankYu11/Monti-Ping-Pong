package com.monti.servecoach

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.ScreenOrientation
import org.junit.Rule
import org.junit.Test

/** The live screen drawn on the desktop (no phone): a Pixel 8 in landscape, in its main states. */
class HudShot {
    @get:Rule val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5.copy(screenHeight = 1080, screenWidth = 2400, xdpi = 428, ydpi = 428, orientation = ScreenOrientation.LANDSCAPE))

    private fun screen(): HudScreen {
        val s = HudScreen(paparazzi.context, {}, {}, {})
        // the camera picture stands in as a dark blue-grey, like the club through the HUD
        s.preview.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.rgb(70, 74, 80), Color.rgb(28, 40, 70)))
        s.statsText.text = "App 0.9.0\nCamera 1920×1080 @ 240 fps\nEngine ready"
        return s
    }

    @Test fun idle() { val s = screen(); s.advice("looks good", "Looks good. Press Start when you're ready.", true); paparazzi.snapshot(s.root) }

    @Test fun setupAdvice() { val s = screen(); s.advice("tilt down", "Tilt the phone down a little: more of the table should show.", false); paparazzi.snapshot(s.root) }

    @Test fun aiCoach() {
        val s = screen(); s.aiState(true); s.advice("move closer", "Move closer: the table looks small. About 1.5 m from the ball's path.", false)
        s.aiText("Move the phone a step closer, "); s.aiText("and lower it to net height.")
        paparazzi.snapshot(s.root)
    }

    @Test fun videoTestNone() {
        val s = screen(); s.testStarted("ServeCoach_20261103_183540_skipped1_slow-a-body.mp4")
        s.testFoundNone("What it saw instead: slow movements (a body?): 2; balls going up (tosses): 1. The ball has to fly across the picture, over the table, from a phone that stays still.")
        s.state("Video test done: no serve found")
        paparazzi.snapshot(s.root)
    }

    @Test fun reading() {
        val s = screen(); s.running(true); s.state("Ready: serve")
        s.reading(3, 1910, "backspin", "Strong backspin. Keep this contact.\nball 4.1 m/s · answer in 3.2 s",
            Spin(lift = 29.9, curve = -3.1, gyro = 6.2, dots = true))
        s.noRead(4, "The ball turned only about 57% of a turn in this stretch, too little to tell the rate.")
        s.reading(5, 2770, "side-backspin (curves right)", "")
        s.reading(6, 2450, "backspin", "Strong backspin. Keep this contact.\nball 4.7 m/s · answer in 2.1 s",
            Spin(lift = 38.6, curve = 8.4, gyro = -11.0, dots = true))
        paparazzi.snapshot(s.root)
    }

    /** serve 3 off the real phone: 52.5 rev/s, and 46 of it gyro, which does nothing in the air */
    @Test fun spinGyro() {
        val s = screen(); s.running(true); s.state("Ready: serve")
        s.reading(3, 3150, "topspin (curves left) + gyro",
            "Mostly gyro: it will look like a nothing ball and skid at the bounce.\nball 2.8 m/s · answer in 2.4 s",
            Spin(lift = -21.3, curve = -12.2, gyro = 46.3, dots = true))
        paparazzi.snapshot(s.root)
    }

    /** the same serve off a half-marked ball: the ring stays, the chevrons go, and we say why */
    @Test fun spinNoDots() {
        val s = screen(); s.running(true); s.state("Ready: serve")
        s.reading(3, 3150, "topspin (curves left) + gyro",
            "Mostly gyro: it will look like a nothing ball and skid at the bounce.",
            Spin(lift = -21.3, curve = -12.2, gyro = 46.3, dots = false))
        paparazzi.snapshot(s.root)
    }

    /** a plain backspin serve: the axle lies across the table and the face rolls down */
    @Test fun spinBackspin() {
        val s = screen(); s.running(true); s.state("Ready: serve")
        s.reading(7, 2700, "backspin", "Strong backspin. Keep this contact.",
            Spin(lift = 45.0, curve = 0.0, gyro = 0.0, dots = true))
        paparazzi.snapshot(s.root)
    }

    @Test fun measuring() {
        val s = screen(); s.running(true); s.reading(6, 2450, "backspin", "ball 4.7 m/s"); s.tone(Hud.AMBER); s.state("Got it: measuring serve #7…")
        paparazzi.snapshot(s.root)
    }
}
