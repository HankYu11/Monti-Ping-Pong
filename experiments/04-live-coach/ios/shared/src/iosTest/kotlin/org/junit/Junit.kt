// JUnit 4 as the Android `core` tests use it, over kotlin.test, so the same test files run on iOS.
package org.junit

import kotlin.math.abs

typealias Test = kotlin.test.Test

object Assert {
    private fun fail(msg: String?, what: String): Nothing = throw AssertionError((if (msg != null) "$msg " else "") + what)
    private fun eq(a: Any?, b: Any?) = a == b || (a is FloatArray && b is FloatArray && a.contentEquals(b))

    fun assertEquals(expected: Any?, actual: Any?) = assertEquals(null, expected, actual)
    fun assertEquals(message: String?, expected: Any?, actual: Any?) { if (!eq(expected, actual)) fail(message, "expected:<$expected> but was:<$actual>") }
    fun assertEquals(expected: Long, actual: Long) = assertEquals(null, expected, actual)
    fun assertEquals(message: String?, expected: Long, actual: Long) { if (expected != actual) fail(message, "expected:<$expected> but was:<$actual>") }
    fun assertEquals(expected: Double, actual: Double, delta: Double) = assertEquals(null, expected, actual, delta)
    fun assertEquals(message: String?, expected: Double, actual: Double, delta: Double) {
        if (expected.compareTo(actual) != 0 && !(abs(expected - actual) <= delta)) fail(message, "expected:<$expected> but was:<$actual>")
    }
    fun assertEquals(expected: Float, actual: Float, delta: Float) = assertEquals(null, expected, actual, delta)
    fun assertEquals(message: String?, expected: Float, actual: Float, delta: Float) {
        if (expected.compareTo(actual) != 0 && !(abs(expected - actual) <= delta)) fail(message, "expected:<$expected> but was:<$actual>")
    }
    fun assertTrue(condition: Boolean) = assertTrue(null, condition)
    fun assertTrue(message: String?, condition: Boolean) { if (!condition) fail(message, "") }
    fun assertFalse(condition: Boolean) = assertTrue(null, !condition)
    fun assertFalse(message: String?, condition: Boolean) = assertTrue(message, !condition)
    fun assertNull(o: Any?) = assertNull(null, o)
    fun assertNull(message: String?, o: Any?) { if (o != null) fail(message, "expected null, but was:<$o>") }
    fun assertNotNull(o: Any?) = assertNotNull(null, o)
    fun assertNotNull(message: String?, o: Any?) { if (o == null) fail(message, "") }
    fun assertSame(expected: Any?, actual: Any?) = assertSame(null, expected, actual)
    fun assertSame(message: String?, expected: Any?, actual: Any?) { if (expected !== actual) fail(message, "expected same:<$expected> was not:<$actual>") }
    fun assertNotSame(expected: Any?, actual: Any?) = assertNotSame(null, expected, actual)
    fun assertNotSame(message: String?, expected: Any?, actual: Any?) { if (expected === actual) fail(message, "expected not same") }
    fun assertArrayEquals(expected: ByteArray, actual: ByteArray) = assertArrayEquals(null, expected, actual)
    fun assertArrayEquals(message: String?, expected: ByteArray, actual: ByteArray) { if (!expected.contentEquals(actual)) fail(message, "arrays differ") }
    fun assertArrayEquals(expected: IntArray, actual: IntArray) = assertArrayEquals(null, expected, actual)
    fun assertArrayEquals(message: String?, expected: IntArray, actual: IntArray) { if (!expected.contentEquals(actual)) fail(message, "arrays differ") }
    fun assertArrayEquals(expected: FloatArray, actual: FloatArray, delta: Float) = assertArrayEquals(null, expected, actual, delta)
    fun assertArrayEquals(message: String?, expected: FloatArray, actual: FloatArray, delta: Float) {
        if (expected.size != actual.size) fail(message, "array lengths differed, expected.length=${expected.size} actual.length=${actual.size}")
        for (i in expected.indices) assertEquals("$message: arrays first differed at element [$i];", expected[i], actual[i], delta)
    }
}
