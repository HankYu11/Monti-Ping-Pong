package com.monti.servecoach

import org.junit.Assert.assertEquals
import org.junit.Test

/** The headline and the spoken outcome: "Strong backspin", "Almost no spin" (SpinLevel.outcome). */
class SpinLevelTest {
    @Test fun bands() {
        assertEquals(0, SpinLevel.of(0)); assertEquals(0, SpinLevel.of(599))
        assertEquals(1, SpinLevel.of(600)); assertEquals(1, SpinLevel.of(1499))
        assertEquals(2, SpinLevel.of(1500)); assertEquals(2, SpinLevel.of(2399))
        assertEquals(3, SpinLevel.of(2400)); assertEquals(3, SpinLevel.of(9000))
        assertEquals("strong", SpinLevel.spoken(2710)); assertEquals("almost no", SpinLevel.spoken(300))
    }

    @Test fun outcomes() {
        assertEquals("Strong backspin", SpinLevel.outcome(2710, "backspin"))
        assertEquals("Strong backspin", SpinLevel.outcome(3750, "Backspin"))
        assertEquals("Medium side-backspin", SpinLevel.outcome(1800, "side-backspin"))
        assertEquals("Light topspin", SpinLevel.outcome(900, "topspin"))
        assertEquals("Almost no spin", SpinLevel.outcome(420, "backspin"))
        assertEquals("Almost no spin", SpinLevel.outcome(2710, "no spin"))
        assertEquals("Almost no spin", SpinLevel.outcome(2710, ""))
    }

    @Test fun qualifiersAreDropped() {
        // the kind without what goes on the line under it
        assertEquals("Strong sidespin", SpinLevel.outcome(2500, "sidespin (curves right)"))
        assertEquals("Medium backspin", SpinLevel.outcome(2000, "backspin + gyro"))
        assertEquals("Medium side-topspin", SpinLevel.outcome(1500, "Side-topspin + gyro (curves left)"))
        assertEquals("Almost no spin", SpinLevel.outcome(2500, " (curves left)"))
    }

    @Test fun edges() {
        assertEquals("Almost no spin", SpinLevel.outcome(599, "backspin"))
        assertEquals("Light backspin", SpinLevel.outcome(600, "backspin"))
        assertEquals("Medium backspin", SpinLevel.outcome(1500, "backspin"))
        assertEquals("Strong backspin", SpinLevel.outcome(2400, "backspin"))
    }
}
