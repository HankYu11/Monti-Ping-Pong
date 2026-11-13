package com.monti.servecoach.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The crop's shape, which two different cutters have to agree on.
 *
 * The chip (CaptureGl) packs four pixels into a texel and reads a row back as width/4 texels, so
 * a crop 1870 wide reads 1868 pixels and every row after the first lands two bytes early -- the
 * whole plane skews, quietly. Colour is half width and packed the same way, so it needs a
 * multiple of 8. `ServeTracker.cropFor` promises that; `alignRect` then moves the left edge to a
 * multiple of the row sample's stride, and in 0.12.0 it added the shift to the width without
 * masking it again. Six of the seven crops of one session came out two pixels wide of a multiple
 * of four. The first one's colour fit read rms 40 against the decoder's bytes, where 0.3 is
 * normal, and the app turned the chip off for the rest of the session.
 */
class AlignRectTest {
    private val W = 1920; private val H = 1080

    @Test fun `the crop stays a multiple of eight wide and even tall`() {
        for (x0 in 0..64 step 2) for (w in 8..1904 step 8) for (y0 in listOf(0, 1, 86, 301)) {
            val r = intArrayOf(x0, y0, minOf(w, W - x0) and 7.inv(), 628)
            if (r[2] <= 0) continue
            val a = Windows.alignRect(r, W, H, 2)
            assertEquals("left on the stride", 0, a[0] % Windows.ROW_STRIDE)
            assertEquals("top on the row step", 0, a[1] % 2)
            assertEquals("width a multiple of 8 (${r.toList()} -> ${a.toList()})", 0, a[2] % 8)
            assertEquals("height even", 0, a[3] % 2)
            assertTrue("inside the picture", a[0] + a[2] <= W && a[1] + a[3] <= H)
            assertTrue("covers what it was given", a[0] <= r[0] && a[1] <= r[1])
        }
    }

    @Test fun `the crop that broke a session`() {
        // cropFor gave this; 0.12.0's alignRect turned it into 1870 wide
        val a = Windows.alignRect(intArrayOf(50, 86, 1864, 628), W, H, 2)
        assertEquals(48, a[0])
        assertEquals(86, a[1])
        assertEquals("1866 masked back to a multiple of 8", 1864, a[2])
        assertEquals(628, a[3])
    }

    @Test fun `an aligned crop is left alone`() {
        val r = intArrayOf(336, 180, 1424, 416)
        assertTrue(r.toList() == Windows.alignRect(r, W, H, 2).toList())
    }
}
