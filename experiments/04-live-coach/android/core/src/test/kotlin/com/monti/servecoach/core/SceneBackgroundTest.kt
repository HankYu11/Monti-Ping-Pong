package com.monti.servecoach.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The background built from the scene, against the one a capture builds from the serve.
 *
 * The second is what the engine has always been given: the per-pixel median of fifteen frames
 * decoded out of the serve. The first is what replaces it, so that a capture need decode
 * nothing. They are medians of different frames of the same room, so they do not have to be the
 * same bytes — but they have to be the same background, or the ball stops separating from it.
 */
class SceneBackgroundTest {
    private val W = 320; private val H = 180            // small, for a test; the shape is what matters
    private val cw = W / 2; private val ch = H / 2

    /** the room: a table, a wall, a little texture, and a person who moves about */
    private fun frame(t: Int, personAt: Int): ByteArray {
        val out = ByteArray(W * H + 2 * (cw * ch))
        for (y in 0 until H) for (x in 0 until W) {
            var v = 90 + 25 * Math.sin(x * 0.07) + 12 * Math.sin(y * 0.13)
            if (y > H * 2 / 3) v = 55.0 + 8 * Math.sin(x * 0.2)          // the table
            // mains flicker: a band across the rows that moves between frames
            v *= 1 + 0.06 * Math.sin(2 * Math.PI * (0.4 * y / H + 0.4167 * t))
            if (x >= personAt && x < personAt + 36) v = 30.0              // the person
            out[y * W + x] = v.roundToInt().coerceIn(0, 255).toByte()
        }
        for (y in 0 until ch) for (x in 0 until cw) {
            val person = 2 * x >= personAt && 2 * x < personAt + 36
            out[W * H + y * cw + x] = (if (person) 140 else 124).toByte()
            out[W * H + cw * ch + y * cw + x] = (if (person) 110 else 130).toByte()
        }
        return out
    }

    private fun stripOf(f: ByteArray, strip: Int, stripH: Int): ByteArray {
        val y0 = strip * stripH
        val out = ByteArray(W * stripH + 2 * (cw * (stripH / 2)))
        var o = 0
        for (y in 0 until stripH) { System.arraycopy(f, (y0 + y) * W + x0y(0), out, o, W); o += W }
        for (plane in 0..1) for (y in 0 until stripH / 2) {
            System.arraycopy(f, W * H + plane * cw * ch + (y0 / 2 + y) * cw, out, o, cw); o += cw
        }
        return out
    }
    private fun x0y(x: Int) = x

    @Test fun `the scene's background is the room, not whoever walked through it`() {
        val sb = SceneBackground(W, H, strips = 9, samples = 15)
        assertEquals(20, sb.stripH)
        assertNull("nothing until the whole picture is covered", sb.crop(intArrayOf(0, 0, 32, 20)))
        // 15 readings of every strip, the person moving across the room the whole time
        var t = 0
        while (sb.ready < sb.strips || sb.taken < 9L * 15) {
            val s = sb.wanted()
            sb.add(s, stripOf(frame(t, 10 + (t * 7) % (W - 40)), s, sb.stripH), t.toLong())
            t++
            if (t > 4000) break
        }
        assertTrue("every strip covered", sb.complete)

        // the capture's own background, the way it has always been made: the median of a spread
        // of frames of the same room, with the same person moving through them
        val frames = (0 until 15).map { frame(it * 11, 10 + (it * 77) % (W - 40)) }
        val theirs = Windows.background(frames, W * H + 2 * (cw * ch), 15)

        val mine = sb.crop(intArrayOf(0, 0, W, H))!!
        var worst = 0; var sum = 0.0; var personPixels = 0
        for (y in 0 until H) for (x in 0 until W) {
            val d = abs((mine[y * W + x].toInt() and 255) - (theirs[y * W + x].toInt() and 255))
            if (d > worst) worst = d
            sum += d
            if ((mine[y * W + x].toInt() and 255) < 40) personPixels++
        }
        println("scene against capture: mean %.2f, worst %d levels; %d pixels still dark".format(sum / (W * H), worst, personPixels))
        // The two median different frames under moving flicker, so they are not the same bytes.
        // What matters is that neither has the person in it and both read the same room.
        assertTrue("the backgrounds differ by $worst levels at worst", worst <= 12)
        assertTrue("they differ by ${sum / (W * H)} on average", sum / (W * H) <= 3.0)
        assertEquals("the person is in neither", 0, personPixels)
    }

    @Test fun `a whole picture slices into the same strips`() {
        val a = SceneBackground(W, H, strips = 9, samples = 15)
        val b = SceneBackground(W, H, strips = 9, samples = 15)
        for (t in 0 until 15) {
            val f = frame(t * 11, 10 + (t * 77) % (W - 40))
            a.addWhole(f, t.toLong())
            for (s in 0 until a.strips) b.add(s, stripOf(f, s, b.stripH), t.toLong())
        }
        assertTrue(a.complete && b.complete)
        assertTrue("slicing a picture gives what reading its strips gives",
            a.crop(intArrayOf(0, 0, W, H))!!.contentEquals(b.crop(intArrayOf(0, 0, W, H))!!))
    }

    @Test fun `a crop takes the right part of the picture`() {
        val sb = SceneBackground(W, H, strips = 9, samples = 15)
        var t = 0
        while (!sb.complete && t < 4000) { val s = sb.wanted(); sb.add(s, stripOf(frame(t, 400), s, sb.stripH), t.toLong()); t++ }
        val rect = intArrayOf(32, 20, 64, 40)
        val got = sb.crop(rect)!!
        val whole = sb.crop(intArrayOf(0, 0, W, H))!!
        assertEquals(64 * 40 + 2 * (32 * 20), got.size)
        for (y in 0 until 40) for (x in 0 until 64) {
            assertEquals("row $y column $x", whole[(20 + y) * W + 32 + x], got[y * 64 + x])
        }
        // and the colour planes, which are half size and must not be read at the brightness's offsets
        for (y in 0 until 20) for (x in 0 until 32) {
            assertEquals("U row $y column $x", whole[W * H + (10 + y) * cw + 16 + x], got[64 * 40 + y * 32 + x])
        }
    }

    @Test fun `every strip keeps being refreshed, so none goes stale`() {
        val sb = SceneBackground(W, H, strips = 9, samples = 15)
        var t = 0
        while (!sb.complete && t < 4000) { val s = sb.wanted(); sb.add(s, stripOf(frame(t, 400), s, sb.stripH), t.toLong()); t++ }
        val first = sb.ageMs(t.toLong())
        repeat(200) { val s = sb.wanted(); sb.add(s, stripOf(frame(t, 400), s, sb.stripH), t.toLong()); t++ }
        assertTrue("the stalest strip is younger than it was", sb.ageMs(t.toLong()) <= first)
        assertTrue("and it is not old", sb.ageMs(t.toLong()) <= 9L * 2)
    }

    @Test fun `a reset forgets a room that is gone`() {
        val sb = SceneBackground(W, H, strips = 9, samples = 15)
        var t = 0
        while (!sb.complete && t < 4000) { val s = sb.wanted(); sb.add(s, stripOf(frame(t, 400), s, sb.stripH), t.toLong()); t++ }
        assertTrue(sb.complete)
        sb.reset()
        assertTrue("nothing is held", !sb.complete)
        assertNull(sb.crop(intArrayOf(0, 0, W, H)))
    }
}
