package com.monti.servecoach.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.atan

/**
 * The field of view the speed is worked out with, from what Camera2 says about the sensor and
 * the lens. The engine assumed 68 degrees on every phone; this is the phone's own.
 */
class OpticsTest {
    private fun deg(halfWidthMm: Double, focalMm: Double) = 2 * atan(halfWidthMm / focalMm) * 180 / PI

    // a 4:3 sensor 6.4 x 4.8 mm, 4000 x 3000 pixels, all of them active, behind a 4.8 mm lens
    private val physW = 6.4; private val px = 4000; private val f = 4.8

    @Test fun wideStreamKeepsTheSensorsWidth() {
        // 1920 x 1080 is wider than 4:3: cut from the middle by height, every column kept
        val fov = Optics.horizontalFov(physW, px, 4000, 3000, 1920, 1080, f)!!
        assertEquals(deg(3.2, f), fov, 1e-9)
        assertEquals(67.38, fov, 0.01)
    }

    @Test fun narrowStreamLosesWidth() {
        // a square stream from a 4:3 crop keeps 3000 of the 4000 columns
        assertEquals(deg(2.4, f), Optics.horizontalFov(physW, px, 4000, 3000, 1080, 1080, f)!!, 1e-9)
    }

    @Test fun aCropOfTheSensorNarrowsIt() {
        // a camera that reads only the middle half of the sensor for a high-speed mode (the result's
        // SCALER_CROP_REGION says so) sees half as wide
        assertEquals(deg(1.6, f), Optics.horizontalFov(physW, px, 2000, 1500, 1920, 1080, f)!!, 1e-9)
    }

    @Test fun pixelArrayLargerThanTheActiveArea() {
        // the physical size spans the whole pixel array; the crop is counted in those pixels
        assertEquals(deg(3.0, f), Optics.horizontalFov(physW, 4000, 3750, 2812, 1920, 1080, f)!!, 1e-9)
    }

    @Test fun nonsenseGivesNothing() {
        assertNull(Optics.horizontalFov(0.0, px, 4000, 3000, 1920, 1080, f))
        assertNull(Optics.horizontalFov(physW, px, 4000, 3000, 1920, 1080, 0.0))
        assertNull(Optics.horizontalFov(physW, 0, 4000, 3000, 1920, 1080, f))
        assertNull(Optics.horizontalFov(Double.NaN, px, 4000, 3000, 1920, 1080, f))
        // a focal length in the wrong units (micrometres) makes a pinhole: not believed
        assertNull(Optics.horizontalFov(physW, px, 4000, 3000, 1920, 1080, 4800.0))
        // a fisheye beyond what the engine's flat-picture geometry handles
        assertNull(Optics.horizontalFov(physW, px, 4000, 3000, 1920, 1080, 1.0))
    }

    @Test fun headerFields() {
        val h = Optics.headerFields(70.234, 4_100_000L, """{"focalMm":6.9}""")
        assertEquals(""","hfov":70.23,"hfovFrom":"camera","exposureUs":4100,"optics":{"focalMm":6.9}""", h)
        // nothing known: the engine is told it is assuming, and keeps its 68
        assertEquals(""","hfovFrom":"assumed"""", Optics.headerFields(null, null))
        assertTrue(Optics.headerFields(null, 0L).indexOf("exposureUs") < 0)
    }
}
