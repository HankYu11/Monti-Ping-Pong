// The few JVM calls the Android `core` sources make, for iOS. The sources themselves are the
// Android module's, unchanged: these declarations sit in the same package, so an unqualified
// `System`, `Math`, `Volatile`, `Synchronized`, `ArrayList` or `String.format` there resolves here
// on iOS and to the JVM's own on Android. Each one behaves as the JVM's does for the way `core`
// uses it, rounding and number formatting included, so both phones give the same numbers.
//
// One difference: `@Synchronized` is a no-op on Kotlin/Native. `LiveWindows.put/take` and
// `SceneBackground.add/crop/reset` are called by the iOS app under its own lock (CoreLock.kt).
@file:OptIn(kotlin.experimental.ExperimentalObjCRefinement::class)
package com.monti.servecoach.core

import kotlin.math.floor
import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.CLOCK_MONOTONIC_RAW
import platform.posix.clock_gettime_nsec_np

typealias Volatile = kotlin.concurrent.Volatile
/** a marker only on iOS: the app calls these methods under its own lock (see the note above) */
@Target(AnnotationTarget.FUNCTION) annotation class Synchronized

/** kotlin.io's JVM extensions `core` uses: stdio already buffers, so the size is ignored */
fun java.io.OutputStream.buffered(@Suppress("UNUSED_PARAMETER") size: Int = 8192): java.io.OutputStream = this
fun String.toByteArray(): ByteArray = encodeToByteArray()
fun <K, V> MutableMap<K, V>.putIfAbsent(key: K, value: V): V? = get(key) ?: run { put(key, value); null }

/** kotlin.collections.ArrayList is final on Kotlin/Native; `class Found : ArrayList<FloatArray>()` needs an open one */
@OptIn(kotlin.experimental.ExperimentalObjCName::class)
@kotlin.native.ObjCName("OpenArrayList")
open class ArrayList<E> private constructor(private val list: kotlin.collections.ArrayList<E>) : MutableList<E> by list, RandomAccess {
    constructor() : this(kotlin.collections.ArrayList<E>())
    constructor(initialCapacity: Int) : this(kotlin.collections.ArrayList<E>(initialCapacity))
    constructor(elements: Collection<E>) : this(kotlin.collections.ArrayList<E>(elements))
    override fun equals(other: Any?) = list == other
    override fun hashCode() = list.hashCode()
    override fun toString() = list.toString()
}

@kotlin.native.HiddenFromObjC
object System {
    @OptIn(ExperimentalForeignApi::class)
    fun nanoTime(): Long = clock_gettime_nsec_np(CLOCK_MONOTONIC_RAW.toUInt()).toLong()
    fun arraycopy(src: ByteArray, srcPos: Int, dest: ByteArray, destPos: Int, length: Int) { src.copyInto(dest, destPos, srcPos, srcPos + length) }
    fun arraycopy(src: FloatArray, srcPos: Int, dest: FloatArray, destPos: Int, length: Int) { src.copyInto(dest, destPos, srcPos, srcPos + length) }
    fun arraycopy(src: IntArray, srcPos: Int, dest: IntArray, destPos: Int, length: Int) { src.copyInto(dest, destPos, srcPos, srcPos + length) }
}

@kotlin.native.HiddenFromObjC
object Math {
    const val PI = kotlin.math.PI
    fun ceil(a: Double) = kotlin.math.ceil(a)
    fun sqrt(a: Double) = kotlin.math.sqrt(a)
    fun sin(a: Double) = kotlin.math.sin(a)
    fun cos(a: Double) = kotlin.math.cos(a)
    fun hypot(a: Double, b: Double) = kotlin.math.hypot(a, b)
    fun abs(a: Double) = kotlin.math.abs(a)
    fun abs(a: Float) = kotlin.math.abs(a)
    fun abs(a: Int) = kotlin.math.abs(a)
    fun toRadians(deg: Double) = deg / 180.0 * PI
    fun toDegrees(rad: Double) = rad * 180.0 / PI
    /** java.lang.Math.round: floor(a + 1/2), NaN to 0, clamped to the type's range */
    fun round(a: Float): Int = if (a.isNaN()) 0 else floor(a.toDouble() + 0.5).coerceIn(Int.MIN_VALUE.toDouble(), Int.MAX_VALUE.toDouble()).toInt()
    fun round(a: Double): Long = if (a.isNaN()) 0L else floor(a + 0.5).let { if (it >= Long.MAX_VALUE.toDouble()) Long.MAX_VALUE else if (it <= Long.MIN_VALUE.toDouble()) Long.MIN_VALUE else it.toLong() }
}

fun String.format(vararg args: Any?): String = jvmFormat(this, args)
fun String.format(@Suppress("UNUSED_PARAMETER") locale: java.util.Locale, vararg args: Any?): String = jvmFormat(this, args)

/**
 * java.util.Formatter for the patterns `core` uses: %d, %s, %%, and %.Nf. A float is formatted
 * the way Java does it: the shortest decimal that reads back as the same double, rounded half up
 * (so 0.15 gives "0.2", where C's printf gives "0.1").
 */
internal fun jvmFormat(fmt: String, args: Array<out Any?>): String {
    val sb = StringBuilder(); var i = 0; var a = 0
    while (i < fmt.length) {
        val c = fmt[i]
        if (c != '%') { sb.append(c); i++; continue }
        var j = i + 1; var left = false; var zero = false; var plus = false
        while (fmt[j] == '-' || fmt[j] == '0' || fmt[j] == '+') { when (fmt[j]) { '-' -> left = true; '0' -> zero = true; else -> plus = true }; j++ }
        var width = 0; while (fmt[j].isDigit()) { width = width * 10 + (fmt[j] - '0'); j++ }
        var prec = -1
        if (fmt[j] == '.') { j++; var p = 0; while (fmt[j].isDigit()) { p = p * 10 + (fmt[j] - '0'); j++ }; prec = p }
        var t = when (fmt[j]) {
            '%' -> "%"
            'n' -> "\n"
            'd' -> (args[a++] as Number).toLong().let { if (plus && it >= 0) "+$it" else "$it" }
            's' -> args[a++].toString().let { if (prec >= 0 && it.length > prec) it.substring(0, prec) else it }
            'f' -> fixed((args[a++] as Number).toDouble(), if (prec < 0) 6 else prec).let { if (plus && !it.startsWith("-")) "+$it" else it }
            else -> error("format: %${fmt[j]} not supported")
        }
        if (t.length < width) t = when {
            left -> t.padEnd(width)
            zero && fmt[j] != 's' -> { val neg = t.startsWith("-") || t.startsWith("+"); if (neg) t[0] + t.substring(1).padStart(width - 1, '0') else t.padStart(width, '0') }
            else -> t.padStart(width)
        }
        sb.append(t)
        i = j + 1
    }
    return sb.toString()
}

internal fun fixed(x: Double, prec: Int): String {
    if (x.isNaN()) return "NaN"
    if (x.isInfinite()) return if (x > 0) "Infinity" else "-Infinity"
    val neg = x < 0 || (x == 0.0 && 1.0 / x < 0)
    // the shortest round-trip digits and the decimal point's place
    var s = kotlin.math.abs(x).toString()
    var exp = 0
    val e = s.indexOfFirst { it == 'E' || it == 'e' }
    if (e >= 0) { exp = s.substring(e + 1).toInt(); s = s.substring(0, e) }
    val dot = s.indexOf('.')
    var digits = if (dot < 0) s else s.substring(0, dot) + s.substring(dot + 1)
    var point = (if (dot < 0) s.length else dot) + exp
    val lead = digits.indexOfFirst { it != '0' }
    if (lead < 0) { digits = "0"; point = 1 } else { digits = digits.substring(lead); point -= lead }
    // digits are d1 d2 ... with value 0.d1d2... x 10^point; keep `point + prec` of them, half up
    val keep = point + prec
    val ds = IntArray(maxOf(keep, 0) + 1)  // ds[0] is a carry slot
    for (k in 0 until maxOf(keep, 0)) ds[k + 1] = if (k < digits.length) digits[k] - '0' else 0
    val next = if (keep >= 0 && keep < digits.length) digits[keep] - '0' else if (keep < 0) 0 else 0
    if (keep >= 0 && next >= 5) { var k = keep; while (true) { ds[k]++; if (ds[k] < 10 || k == 0) break; ds[k] = 0; k-- } }
    // ds[0..keep] now holds the rounded digits with one extra leading place
    val all = ds.joinToString("") { it.toString() }
    val intLen = point + 1  // including the carry slot
    val intPart = (if (intLen > 0) all.substring(0, minOf(intLen, all.length)).padEnd(intLen, '0') else "0").trimStart('0').ifEmpty { "0" }
    val frac = if (prec == 0) "" else {
        val f = if (intLen >= 0) all.substring(minOf(intLen, all.length)) else "0".repeat(-intLen) + all
        "." + f.padEnd(prec, '0').substring(0, prec)
    }
    val out = intPart + frac
    return if (neg && out.any { it in '1'..'9' }) "-$out" else if (neg) "-$out" else out
}
