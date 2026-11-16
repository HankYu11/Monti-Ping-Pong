package com.monti.servecoach.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Movements that are not serves, on the phone's own tracks.
 *
 * The 15 and 16 Nov sessions sent four movements to the engine as serves that had no ball in
 * flight: a person walking through, the player walking in at the edge, the player raising the
 * racket, and text moving on a laptop screen. Each came back "Couldn't read that one". Here every track
 * the phone sent that day goes through the tracker again, as the candidates it was built from:
 * the four must give no serve, and the nineteen real serves must still give theirs.
 */
class BodyTrackTest {
    private class Recorded(val name: String, val body: Boolean, val pts: List<FloatArray>)

    /** whole-picture pixels and capture frames -> the tracker's small picture and its frames */
    private val recorded: List<Recorded> by lazy {
        val text = javaClass.getResource("/phone-tracks-2026-11-15.txt")!!.readText()
        text.lines().filter { it.isNotBlank() && !it.startsWith("#") }.map { line ->
            val (name, kind, pts) = line.split(" ")
            Recorded(name, kind == "none", pts.split(";").map { p ->
                val v = p.split(",").map { it.toFloat() }
                floatArrayOf(v[0] / 2, v[1] / 4, v[2] / 4, v[3] / 4)
            })
        }
    }

    /** the track's points fed to a fresh tracker (480 x 270, 120 a second), then quiet until it decides */
    private fun replay(pts: List<FloatArray>): Pair<ServeTracker.Serve?, List<String>> {
        val tr = ServeTracker(480, 270, 120)
        val skipped = ArrayList<String>()
        tr.onSkipped = { _, _, why -> skipped.add(why) }
        val at = pts.associateBy { it[0].toLong() }
        val start = pts.first()[0].toLong()
        var got: ServeTracker.Serve? = null
        for (i in 0L until start + 400L) {
            val p = at[i]
            tr.pushFound(i, if (p == null) emptyList() else listOf(floatArrayOf(p[1], p[2], p[3])))?.let { got = it }
        }
        return got to skipped
    }

    @Test fun `the fixture has the four bodies and the nineteen serves`() {
        assertEquals(4, recorded.count { it.body })
        assertEquals(19, recorded.count { !it.body })
    }

    @Test fun `a body moving is not a serve`() {
        for (r in recorded.filter { it.body }) {
            val (serve, skipped) = replay(r.pts)
            assertNull("${r.name} gave a serve: ${serve?.why}", serve)
            assertEquals("${r.name} turned down as $skipped", listOf("wandering (a body?)"), skipped)
        }
    }

    @Test fun `every real serve is still a serve`() {
        for (r in recorded.filter { !it.body }) {
            val (serve, skipped) = replay(r.pts)
            assertNotNull("${r.name} lost (turned down as $skipped)", serve)
        }
    }

    @Test fun `a serve with a body moving just before it is still the serve`() {
        // the person walking through, then a serve half a second later: the group is the serve
        val body = recorded.first { it.name == "214545/serve02" }.pts
        val serve = recorded.first { it.name == "214434/serve02" }.pts
        val b0 = body.first()[0]; val s0 = serve.first()[0]
        val both = body.map { floatArrayOf(it[0] - b0 + 10, it[1], it[2], it[3]) } +
            serve.map { floatArrayOf(it[0] - s0 + 10 + body.size + 30, it[1], it[2], it[3]) }
        val tr = ServeTracker(480, 270, 120)
        val at = both.groupBy { it[0].toLong() }
        var got: ServeTracker.Serve? = null
        for (i in 0L until 600L) tr.pushFound(i, at[i]?.map { floatArrayOf(it[1], it[2], it[3]) } ?: emptyList())?.let { got = it }
        assertNotNull(got)
        assertTrue("the serve's track, not the body's: ${got!!.why}", kotlin.math.abs(got!!.track.size - serve.size) <= 3)
    }
}
