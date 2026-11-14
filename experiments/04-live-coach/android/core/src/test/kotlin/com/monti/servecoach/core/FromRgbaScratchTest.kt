package com.monti.servecoach.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The finder's scratch buffer, and that it changes nothing but the garbage.
 *
 * fromRgba allocated three FloatArrays of w*h every time -- 1.56 MB at 480x270, on every frame
 * the finders look at, 120 a second, 187 MB a second for the whole session. Handing a finder
 * its own buffer back costs nothing and allocates nothing; what it must not do is change a
 * single pixel, or quietly alias a buffer something else still holds.
 */
class FromRgbaScratchTest {
    private val W = 64; private val H = 36
    private val rnd = java.util.Random(4)
    private fun frame() = ByteArray(W * H * 4).also { rnd.nextBytes(it) }

    @Test fun `the same pixels, with a buffer and without`() {
        for (flip in 0..3) {
            val b = frame()
            val fresh = Downsample.fromRgba(b, W, H, flip)
            val into = Downsample.fromRgba(b, W, H, flip, Quarter(W, H, FloatArray(W * H), FloatArray(W * H), FloatArray(W * H)))
            assertArrayEquals("brightness, flip $flip", fresh.y, into.y, 0f)
            assertArrayEquals("u, flip $flip", fresh.u, into.u, 0f)
            assertArrayEquals("v, flip $flip", fresh.v, into.v, 0f)
        }
    }

    @Test fun `the buffer is reused, and a wrong-sized one is not`() {
        val mine = Quarter(W, H, FloatArray(W * H), FloatArray(W * H), FloatArray(W * H))
        assertSame("the same buffer comes back", mine, Downsample.fromRgba(frame(), W, H, 0, mine))
        val wrong = Quarter(8, 8, FloatArray(64), FloatArray(64), FloatArray(64))
        assertNotSame("a buffer of the wrong size is not written into", wrong, Downsample.fromRgba(frame(), W, H, 0, wrong))
    }

    @Test fun `a second frame into the same buffer leaves the first nowhere`() {
        // the point of the buffer, and the thing that makes it dangerous: it really is the same
        // memory, so anything that kept the last Quarter would now be holding this one
        val mine = Quarter(W, H, FloatArray(W * H), FloatArray(W * H), FloatArray(W * H))
        val a = frame(); val b = frame()
        Downsample.fromRgba(a, W, H, 0, mine)
        val first = mine.y.copyOf()
        Downsample.fromRgba(b, W, H, 0, mine)
        assertArrayEquals("and it matches a fresh read of the second frame",
            Downsample.fromRgba(b, W, H, 0).y, mine.y, 0f)
        assert(!first.contentEquals(mine.y)) { "the test frames were identical; it proves nothing" }
    }
}
