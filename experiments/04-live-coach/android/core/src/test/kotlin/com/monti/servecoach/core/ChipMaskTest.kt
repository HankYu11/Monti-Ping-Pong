package com.monti.servecoach.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The mask the graphics chip cuts, where it meets this code.
 *
 * The arithmetic itself is checked off a phone, against the app's own shader files on llvmpipe
 * (experiments/04-live-coach/tools/maskcheck.py: 0 of 129,600 pixels differ over six adversarial
 * byte cases). These are the other half: the handover. The chip works in the picture's own way
 * round and the finders work in the flipped one, so a mask that is arithmetically perfect still
 * comes out upside down if the turn is dropped -- and a mask upside down is not a loud failure,
 * it is a session that quietly finds nothing.
 */
class ChipMaskTest {
    private val w = 64; private val h = 48

    /** alpha = 1 over a disc at (cx, cy) in the CHIP's way round, which is unflipped */
    private fun alphaDisc(cx: Int, cy: Int, r: Int): ByteArray {
        val b = ByteArray(w * h * 4)
        for (y in 0 until h) for (x in 0 until w) {
            val i = (y * w + x) * 4
            b[i] = 100; b[i + 1] = -128; b[i + 2] = -128
            val dx = x - cx; val dy = y - cy
            b[i + 3] = if (dx * dx + dy * dy <= r * r) 1 else 0
        }
        return b
    }

    @Test fun `the chip's mask is turned the way the finders' picture is turned`() {
        val r = 4
        val cx = 12; val cy = 9                                    // chip coordinates
        val rgba = alphaDisc(cx, cy, r)
        val d = Detector(w, h)
        for (flip in 0..3) {
            val want = Pair(
                if (flip and 1 != 0) w - 1 - cx else cx,
                if (flip and 2 != 0) h - 1 - cy else cy)
            val got = d.findFromAlpha(rgba, flip)
            assertEquals("one blob, flip $flip", 1, got.size)
            val p = got[0]
            assertTrue("x at flip $flip: wanted ${want.first}, got ${p[0]}", abs(p[0] - want.first) < 1.5f)
            assertTrue("y at flip $flip: wanted ${want.second}, got ${p[1]}", abs(p[1] - want.second) < 1.5f)
        }
    }

    /** a scene that moves in colour only, so the row gain is 1 and every pixel is "moved" */
    private fun colourShift(): Triple<Quarter, Background, ByteArray> {
        val y = FloatArray(w * h) { 120f }
        val bgU = FloatArray(w * h) { 0f }
        val frameU = FloatArray(w * h) { 40f }                     // 40 > the colour bar of 20
        val zero = FloatArray(w * h) { 0f }
        val q = Quarter(w, h, y, frameU, FloatArray(w * h) { 0f })
        val bg = Background(y.copyOf(), bgU, zero)
        val rgba = ByteArray(w * h * 4)
        for (p in 0 until w * h) { rgba[p * 4] = 120; rgba[p * 4 + 1] = (40 - 128).toByte(); rgba[p * 4 + 2] = -128; rgba[p * 4 + 3] = 1 }
        return Triple(q, bg, rgba)
    }

    @Test fun `agreeing masks are counted as agreeing`() {
        val (q, bg, rgba) = colourShift()
        val bad = IntArray(1)
        Detector(w, h).findChecked(q, bg, rgba, 0, bad)
        assertEquals("every pixel moved, and the chip said so", 0, bad[0])
    }

    @Test fun `every pixel the two differ on is counted`() {
        val (q, bg, rgba) = colourShift()
        for (p in 0 until 37) rgba[p * 4 + 3] = 0                  // the chip loses 37 of them
        val bad = IntArray(1)
        Detector(w, h).findChecked(q, bg, rgba, 0, bad)
        assertEquals("the count is the disagreement, not a sample of it", 37, bad[0])
    }

    @Test fun `a mask handed over the wrong way round is counted as wrong`() {
        // the failure this is all for: right arithmetic, wrong turn. The count has to see it,
        // because nothing downstream will -- the blobs are simply in the wrong place.
        val rgba = alphaDisc(12, 9, 4)
        val y = FloatArray(w * h) { 120f }
        val u = FloatArray(w * h)
        for (py in 0 until h) for (px in 0 until w) {
            val dx = px - 12; val dy = py - 9
            u[py * w + px] = if (dx * dx + dy * dy <= 16) 40f else 0f
        }
        val q = Downsample.fromRgba(ByteArray(w * h * 4).also { b ->
            for (p in 0 until w * h) { b[p * 4] = 120; b[p * 4 + 1] = (u[p].toInt() - 128).toByte(); b[p * 4 + 2] = -128 }
        }, w, h, 3)                                                 // the finders' picture, flipped
        val bg = Background(y, FloatArray(w * h), FloatArray(w * h))
        val bad = IntArray(1)
        Detector(w, h).findChecked(q, bg, rgba, 3, bad)
        assertEquals("flipped on both sides, the two agree", 0, bad[0])
        Detector(w, h).findChecked(q, bg, rgba, 0, bad)
        assertTrue("read the wrong way round, the count says so (${bad[0]})", bad[0] > 40)
    }
}
