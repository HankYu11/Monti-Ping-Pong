// kotlin.math as the JVM (and Android's ART) computes it, for the functions where Kotlin/Native
// differs. On the JVM a Float hypot, ln or atan is worked out in double precision and then
// rounded to Float; Kotlin/Native calls the Float C functions (hypotf, logf, atanf), which can
// round the last bit differently. The tracker compares such numbers against thresholds and ranks
// candidate pairs by them, so one different bit can join a blob to a different track, and from
// there a serve is judged differently. roundToInt is java.lang.Math.round's floor(x + 1/2), worked
// out exactly (Kotlin/Native adds the half in the argument's own precision).
//
// The build copies the Android core's sources with `import kotlin.math.hypot` (ln, atan,
// roundToInt) pointing here instead (build.gradle.kts, task androidCore); the files themselves
// are untouched.
package com.monti.servecoach.jvmmath

import kotlin.math.floor

fun hypot(x: Float, y: Float): Float = kotlin.math.hypot(x.toDouble(), y.toDouble()).toFloat()
fun hypot(x: Double, y: Double): Double = kotlin.math.hypot(x, y)
fun ln(x: Float): Float = kotlin.math.ln(x.toDouble()).toFloat()
fun ln(x: Double): Double = kotlin.math.ln(x)
fun atan(x: Float): Float = kotlin.math.atan(x.toDouble()).toFloat()
fun atan(x: Double): Double = kotlin.math.atan(x)

fun Float.roundToInt(): Int {
    if (isNaN()) throw IllegalArgumentException("Cannot round NaN value.")
    val d = toDouble(); val f = floor(d)
    val r = if (d - f >= 0.5) f + 1 else f
    return if (r >= Int.MAX_VALUE) Int.MAX_VALUE else if (r <= Int.MIN_VALUE) Int.MIN_VALUE else r.toInt()
}
fun Double.roundToInt(): Int {
    if (isNaN()) throw IllegalArgumentException("Cannot round NaN value.")
    val f = floor(this)
    val r = if (this - f >= 0.5) f + 1 else f
    return if (r >= Int.MAX_VALUE) Int.MAX_VALUE else if (r <= Int.MIN_VALUE) Int.MIN_VALUE else r.toInt()
}
