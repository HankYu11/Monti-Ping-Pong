package java.lang

object Float {
    fun floatToIntBits(f: kotlin.Float): Int = f.toBits()
    fun intBitsToFloat(bits: Int): kotlin.Float = kotlin.Float.fromBits(bits)
}
