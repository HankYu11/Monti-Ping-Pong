package com.monti.servecoach.core

import com.monti.servecoach.core.NoRead.Cause
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class NoReadTest {
    // The unclear serves of the 15 Nov 2026 sessions (live-clips/Session_20261115_*/serveNN.json,
    // `result.serve`): marking, toward, issues as the engine gave them.
    @Test fun halfBallShowingOneSide() {      // 21:44 #1: the black side kept to the camera, filmed side-on
        val r = NoRead.of("half", -2.46, listOf("The ball showed the same side all the way (its marking hardly changed), so there is nothing to read the spin from."))
        assertEquals(Cause.MARKING, r?.cause)
    }
    @Test fun flyingAtTheCamera() {           // 23:04 #3: dots, 85 degrees toward the camera, lost in places
        val r = NoRead.of("dots", 85.44, listOf("The ball was found in only 38 of 60 frames."))
        assertEquals(Cause.TOWARD, r?.cause)
        assertEquals("it flew toward the camera", r?.short)
    }
    @Test fun windowLagSaysNothing() {        // 21:44 #4: the capture window lagged the ball; no setup fix
        assertNull(NoRead.of("half", 51.79, listOf("The ball was found in only 25 of 37 frames.")))
    }
    @Test fun lostAndShortWithoutTheMarkingCause() {   // 23:04 #4: away from the camera at 57, found in 11 of 21
        val r = NoRead.of("half", -57.0, listOf("The ball was found in only 11 of 21 frames.", "The ball turned only a little in this stretch, too little to tell the rate."))
        assertEquals(Cause.LOST, r?.cause)
    }
    @Test fun markingBeforeTheAngle() {       // 21:45 #2: all three issues and 65 degrees; the marking is what hid the spin
        val r = NoRead.of("half", 65.0, listOf("The ball was found in only 10 of 52 frames.", "The ball turned only about 58% of a turn in this stretch, too little to tell the rate.",
            "The ball showed the same side all the way (its marking hardly changed), so there is nothing to read the spin from."))
        assertEquals(Cause.MARKING, r?.cause)
    }

    @Test fun awayFromTheCamera() = assertEquals("it flew away from the camera", NoRead.of("half", -70.0, emptyList())?.short)
    @Test fun blurAndFaintAreLight() {
        assertEquals(Cause.LIGHT, NoRead.of("half", 10.0, listOf("The ball is smeared by motion blur (stretched 1.6× along its path)."))?.cause)
        assertEquals(Cause.LIGHT, NoRead.of("half", 10.0, listOf("The black half is faint (contrast 22%)."))?.cause)
    }
    @Test fun small() = assertEquals(Cause.SMALL, NoRead.of("half", 0.0, listOf("The ball is only 14 px across."))?.cause)
    @Test fun foundOftenEnoughIsNotLost() {   // clear 15 Nov readings had the ball in 63-68% of frames
        assertNull(NoRead.of("dots", 17.0, listOf("The ball was found in only 40 of 60 frames.")))
    }
    @Test fun nothingToGoOn() {
        assertNull(NoRead.of(null, Double.NaN, emptyList()))
        assertNull(NoRead.of("dots", 10.0, listOf("The spin rate is ambiguous: a quite different rate fits the marking about as well.")))
        // a dot-marked ball can't show "the same side" in the engine's sense; never told to use the dot ball
        assertNull(NoRead.of("dots", 0.0, listOf("The ball showed the same side all the way (its marking hardly changed).")))
        // 23:04 #6: no serve flight found at all
        assertNull(NoRead.of(null, Double.NaN, emptyList(), "No serve flight found. Keep the phone still, with the ball's flight over the table in view."))
    }

    @Test fun fixOncePerCauseEveryFewServes() {
        val a = NoRead.Advisor(every = 4)
        val m = NoRead.of("half", 0.0, listOf(NoRead.SAME_SIDE + "."))
        assertEquals("Couldn't read that one: the marking stayed facing the camera. Use the dot-marked ball." to
            "Couldn't read it: the marking stayed facing the camera. Use the dot-marked ball.", a.next(m))
        assertEquals("Couldn't read that one: the marking stayed facing the camera.", a.next(m).first)   // serve 2
        // another cause has its own turn
        assertTrue(a.next(NoRead.of("half", 0.0, listOf("The ball is only 14 px across.")))!!.first.endsWith("Move the phone closer."))  // 3
        a.read()                                                                                            // 4
        assertTrue(a.next(m).first.endsWith("Use the dot-marked ball."))                                    // 5: four serves on
        // the screen always carries the fix; no cause: the old words
        assertTrue(a.next(m).second.endsWith("Use the dot-marked ball."))
        assertEquals("Couldn't read that one." to "Couldn't read it.", a.next(null))
        a.reset()
        assertTrue(a.next(m).first.endsWith("Use the dot-marked ball."))
    }

    /** the engine's texts matched here still exist in the engine (it is edited elsewhere) */
    @Test fun engineStillSaysThem() {
        var d: File? = File("").absoluteFile
        while (d != null && !File(d, "experiments/02-spin-meter/web/engine.js").exists()) d = d.parentFile
        val js = File(d ?: return, "experiments/02-spin-meter/web/engine.js").readText()
        for (s in listOf(NoRead.SAME_SIDE, NoRead.BLUR, NoRead.FAINT, NoRead.SMALL_PX, "The ball was found in only \${"))
            assertTrue("engine.js no longer says \"$s\"", js.contains(s))
    }
}
