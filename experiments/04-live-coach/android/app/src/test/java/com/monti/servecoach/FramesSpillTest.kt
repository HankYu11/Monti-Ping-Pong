package com.monti.servecoach

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * A replayed video's frames wait on disk (SpilledFrame) instead of in memory: 0.13.x died of
 * OutOfMemoryError on a 178 MB slow-motion video. That fix is only safe if every frame comes back
 * exactly as it went in, from any thread, in any order, as often as asked. This writes frames
 * through FrameSpill, FileFrames' own writing half, and reads them back.
 */
class FramesSpillTest {
    private fun bytes(seed: Int, n: Int) = ByteArray(n) { ((it * 31 + seed * 17) xor (it ushr 8)).toByte() }

    /** a spill file opened, then deleted from its folder, as FileFrames does */
    private fun spillChannel(): FileChannel {
        val f = File.createTempFile("frames", ".bin")
        val ch = RandomAccessFile(f, "rw").channel
        f.delete()
        return ch
    }

    /** one direct buffer reused for every frame, like FileFrames' 8 MB one */
    private fun spillAll(ch: FileChannel, payloads: List<ByteArray>, keyEvery: Int = 8): Pair<FrameSpill, List<SpilledFrame>> {
        val spill = FrameSpill(ch)
        val buf = ByteBuffer.allocateDirect(payloads.maxOf { it.size } + 16)
        val frames = payloads.mapIndexed { i, p ->
            buf.clear(); buf.put(p); buf.position(0); buf.limit(p.size)
            spill.add(i * 4_167L, i % keyEvery == 0, buf)
        }
        return spill to frames
    }

    @Test fun framesComeBackExactly() {
        val sizes = listOf(1, 7, 4096, 65_537, 300_000, 12, 0, 1_000_003, 5)
        val payloads = sizes.mapIndexed { i, n -> bytes(i, n) }
        spillChannel().use { ch ->
            val (spill, frames) = spillAll(ch, payloads)
            assertEquals(sizes.sum().toLong(), spill.bytes)
            assertEquals(spill.bytes, ch.size())
            frames.forEachIndexed { i, f ->
                assertArrayEquals("frame $i", payloads[i], f.data)
                assertEquals(i * 4_167L, f.ptsUs)
                assertEquals(i % 8 == 0, f.key)
            }
            // read again, backwards: each read goes to disk afresh and nothing is consumed
            for (i in frames.indices.reversed()) assertArrayEquals("frame $i again", payloads[i], frames[i].data)
        }
    }

    @Test fun manyThreadsAtOnce() {
        val payloads = (0 until 200).map { bytes(it, 2_000 + 37 * it) }
        spillChannel().use { ch ->
            val (_, frames) = spillAll(ch, payloads)
            val pool = Executors.newFixedThreadPool(8)
            val bad = java.util.concurrent.atomic.AtomicInteger()
            repeat(8) { w ->
                pool.execute { for (k in 0 until 400) { val i = (k * 7 + w * 13) % frames.size; if (!frames[i].data.contentEquals(payloads[i])) bad.incrementAndGet() } }
            }
            pool.shutdown()
            assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS))
            assertEquals(0, bad.get())
        }
    }

    @Test fun betweenOverSpilledFrames() {
        val payloads = (0 until 40).map { bytes(it, 100 + it) }
        spillChannel().use { ch ->
            val (_, frames) = spillAll(ch, payloads, keyEvery = 10)
            // from the keyframe at or before fromUs to the first keyframe after toUs
            val got = framesBetween(frames, 15 * 4_167L, 22 * 4_167L)
            assertEquals(10, got.first().ptsUs / 4_167L)
            assertEquals(29, got.last().ptsUs / 4_167L)
            got.forEach { assertArrayEquals(payloads[(it.ptsUs / 4_167L).toInt()], it.data) }
        }
    }

    @Test(expected = java.io.EOFException::class)
    fun aShortFileSaysSo() {
        spillChannel().use { ch ->
            val (_, frames) = spillAll(ch, listOf(bytes(1, 1000), bytes(2, 1000)))
            ch.truncate(1500)
            frames[1].data
        }
    }

    @Test fun captureFpsFromTheTimes() {
        assertEquals(240, captureFpsOf((0 until 50).map { it * 4_167L }))            // real-rate 240
        assertEquals(120, captureFpsOf((0 until 50).map { it * 8_333L }))            // 120 fps
        assertEquals(240, captureFpsOf((0 until 50).map { it * 33_333L }))           // stretched for playback
        assertEquals(120, captureFpsOf((0 until 50).map { it * 8_333L }.shuffled(java.util.Random(3))))  // decoding order
        assertEquals(240, captureFpsOf(emptyList()))
        assertEquals(240, captureFpsOf(listOf(5L, 5L, 5L)))
    }
}
