@file:OptIn(kotlin.experimental.ExperimentalObjCRefinement::class)
package java.lang

@kotlin.native.HiddenFromObjC
object Float {
    fun floatToIntBits(f: kotlin.Float): Int = f.toBits()
    fun intBitsToFloat(bits: Int): kotlin.Float = kotlin.Float.fromBits(bits)
}
