// java.util, as `core` calls it by its full name (java.util.Arrays.sort, java.util.Locale.US).
@file:OptIn(kotlin.experimental.ExperimentalObjCRefinement::class)
package java.util

@kotlin.native.HiddenFromObjC
object Arrays {
    fun sort(a: IntArray) = a.sort()
    fun sort(a: FloatArray) = a.sort()
    fun sort(a: DoubleArray) = a.sort()
    fun sort(a: IntArray, from: Int, to: Int) = a.sort(from, to)
    fun sort(a: FloatArray, from: Int, to: Int) = a.sort(from, to)
    fun sort(a: DoubleArray, from: Int, to: Int) = a.sort(from, to)
    fun fill(a: IntArray, v: Int) = a.fill(v)
    fun fill(a: FloatArray, v: Float) = a.fill(v)
    fun fill(a: ByteArray, v: Byte) = a.fill(v)
    fun fill(a: IntArray, from: Int, to: Int, v: Int) = a.fill(v, from, to)
    fun fill(a: ByteArray, from: Int, to: Int, v: Byte) = a.fill(v, from, to)
    fun fill(a: FloatArray, from: Int, to: Int, v: Float) = a.fill(v, from, to)
}

@kotlin.native.HiddenFromObjC
class Locale private constructor() {
    companion object { val US = Locale(); val ROOT = Locale() }
}
