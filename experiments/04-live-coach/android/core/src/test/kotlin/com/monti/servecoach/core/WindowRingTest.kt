package com.monti.servecoach.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ring holds windows cut as the frames went past, until a serve asks for them. A serve asks
 * for a span by the camera's own frame numbers, and what it gets back has to line up with those
 * numbers exactly -- a window handed back against the wrong frame puts the ball where it is not,
 * which is the fault that made 0.10.5 search the crop on eight serves of ten.
 */
class WindowRingTest {
    private fun win(f: Long) = ByteArray(4) { (f + it).toByte() }
    private fun rows(f: Long) = ByteArray(2) { (f * 2 + it).toByte() }

    @Test fun `it hands back what it was given, against the right frames`() {
        val r = WindowRing(capacity = 16)
        for (f in 100L..110L) r.put(f, f.toInt(), f.toInt() * 2, 8, win(f), rows(f))
        val got = r.take(102, 5)
        assertEquals(5, got.count)
        assertEquals(5, got.have)
        for (k in 0 until 5) {
            val f = 102L + k
            assertEquals("frame $f's window", win(f).toList(), got.pixels[k]!!.toList())
            assertEquals("frame $f's place", listOf(f.toInt(), f.toInt() * 2), got.at[k]!!.toList())
        }
    }

    @Test fun `frames it never got come back empty, not wrong`() {
        val r = WindowRing(capacity = 16)
        for (f in 100L..110L) if (f % 3L != 0L) r.put(f, 1, 1, 8, win(f), rows(f))
        val got = r.take(100, 11)
        assertEquals(11, got.count)
        assertEquals(8, got.have)
        for (k in 0 until 11) {
            val f = 100L + k
            if (f % 3L == 0L) assertNull("frame $f was never put in", got.pixels[k])
            else assertEquals(win(f).toList(), got.pixels[k]!!.toList())
        }
        assertTrue("coverage reports what is missing", got.coverage > 0.7 && got.coverage < 0.8)
    }

    @Test fun `a frame overwritten by a later lap is not handed back as the old one`() {
        // the ring is small on purpose here: frame 100 and frame 108 share a slot
        val r = WindowRing(capacity = 8)
        r.put(100, 1, 1, 8, win(100), rows(100))
        r.put(108, 2, 2, 8, win(108), rows(108))
        assertNull("frame 100 is gone, and must not come back as 108's pixels", r.take(100, 1).pixels[0])
        assertEquals(win(108).toList(), r.take(108, 1).pixels[0]!!.toList())
        assertEquals("the overwrite is counted", 1L, r.dropped)
    }

    @Test fun `each window remembers how big it was`() {
        // the size follows the ball's apparent radius, so one flight's windows are not all the
        // same; a ring tied to one size threw the rest away (0.12.0: 6418 cut, 376 held)
        val r = WindowRing(capacity = 16)
        r.put(100, 1, 1, 128, win(100), rows(100))
        r.put(101, 1, 1, 160, win(101), rows(101))
        val got = r.take(100, 3)
        assertEquals(128, got.sides[0])
        assertEquals(160, got.sides[1])
        assertEquals("a frame it never had has no size", 0, got.sides[2])
        assertEquals(160, r.lastSide)
    }

    @Test fun `asking for a span it never saw gives nothing`() {
        val r = WindowRing(capacity = 16)
        for (f in 100L..110L) r.put(f, 1, 1, 8, win(f), rows(f))
        val got = r.take(500, 4)
        assertEquals(0, got.have)
        assertEquals(0.0, got.coverage, 1e-9)
    }
}
