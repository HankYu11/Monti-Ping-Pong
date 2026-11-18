// java.util.Random, bit for bit (the same 48-bit generator), so a seeded test makes the same data on iOS.
package java.util

import kotlin.math.ln
import kotlin.math.sqrt

class Random(seed: Long) {
    private var s = (seed xor 0x5DEECE66DL) and ((1L shl 48) - 1)
    private var nextNextGaussian = 0.0
    private var haveNextNextGaussian = false
    private fun next(bits: Int): Int { s = (s * 0x5DEECE66DL + 0xBL) and ((1L shl 48) - 1); return (s ushr (48 - bits)).toInt() }
    fun nextInt(): Int = next(32)
    fun nextBytes(bytes: ByteArray) {
        var i = 0
        while (i < bytes.size) { var rnd = nextInt(); var n = minOf(bytes.size - i, 4); while (n-- > 0) { bytes[i++] = rnd.toByte(); rnd = rnd shr 8 } }
    }
    fun nextDouble(): Double = ((next(26).toLong() shl 27) + next(27)) * (1.0 / (1L shl 53))
    fun nextGaussian(): Double {
        if (haveNextNextGaussian) { haveNextNextGaussian = false; return nextNextGaussian }
        var v1: Double; var v2: Double; var q: Double
        do { v1 = 2 * nextDouble() - 1; v2 = 2 * nextDouble() - 1; q = v1 * v1 + v2 * v2 } while (q >= 1 || q == 0.0)
        val m = sqrt(-2 * ln(q) / q)
        nextNextGaussian = v2 * m; haveNextNextGaussian = true
        return v1 * m
    }
}
