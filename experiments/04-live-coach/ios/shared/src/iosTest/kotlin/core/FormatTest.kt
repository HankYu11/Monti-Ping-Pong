package com.monti.servecoach.core

import kotlin.test.Test
import kotlin.test.assertEquals

/** The iOS String.format against Java's own output (format-java.txt, made by java.util.Formatter). */
class FormatTest {
    @Test fun sameAsJava() {
        val lines = java.io.File(FORMAT_FILE).readText().lines().filter { it.isNotBlank() }
        var n = 0
        for (l in lines) {
            val p = l.split(' ')
            val x = Double.fromBits(p[0].toLong())
            assertEquals(p[2], p[1].format(java.util.Locale.US, x), "double ${x} ${p[1]}")
            if (p.size > 3) assertEquals(p[3], p[1].format(java.util.Locale.US, x.toFloat()), "float ${x.toFloat()} ${p[1]}")
            n++
        }
        assertEquals(lines.size, n)
    }
}
