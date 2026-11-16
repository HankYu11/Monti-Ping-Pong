package com.monti.servecoach

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** One coaching cue every 3rd readable serve, about the commonest fault since the last one. */
class TipPickerTest {
    private val thick = TipPicker.TITLES.getValue("thick")
    private val slip = TipPicker.TITLES.getValue("slip")
    private val slow = TipPicker.TITLES.getValue("slow")

    @Test fun everyThirdServe() {
        val p = TipPicker()
        assertNull(p.next("thick")); assertNull(p.next("thick"))
        assertEquals(thick, p.next("thick"))
        assertNull(p.next("slip")); assertNull(p.next("slip"))
        assertEquals(slip, p.next("slip"))
    }

    @Test fun theCommonestFaultWins() {
        val p = TipPicker()
        p.next("slow"); p.next("slip")
        assertEquals(slip, p.next("slip"))
        p.next("thick"); p.next("slow")
        assertEquals(slow, p.next("slow"))
    }

    @Test fun countsStartAfreshAfterATip() {
        val p = TipPicker()
        p.next("thick"); p.next("thick"); assertEquals(thick, p.next("thick"))
        // three thicks before do not outweigh one slip now
        p.next("good"); p.next("good")
        assertEquals(slip, p.next("slip"))
    }

    @Test fun allGoodSaysNothing() {
        val p = TipPicker()
        repeat(9) { assertNull(p.next("good")) }
        // unknown or empty verdicts are not faults, but they still count as serves
        repeat(3) { assertNull(p.next("")) }
        p.next("wobbly"); p.next("")
        assertNull(p.next("good"))
    }

    @Test fun oneFaultAmongGoodServesIsStillTipped() {
        val p = TipPicker()
        p.next("good"); p.next("slow")
        assertEquals(slow, p.next("good"))
    }

    @Test fun resetForANewSession() {
        val p = TipPicker()
        p.next("thick"); p.next("thick")
        p.reset()
        assertNull(p.next("slip")); assertNull(p.next("slip"))
        assertEquals(slip, p.next("slip"))
    }

    @Test fun theTipsAreTheOnesSpoken() {
        assertEquals("Open the face and brush along under the ball.", thick.second)
        assertEquals("Let the ball drop lower and brush slightly into it.", slip.second)
        assertEquals("Accelerate through the contact: snap the wrist.", slow.second)
    }
}
