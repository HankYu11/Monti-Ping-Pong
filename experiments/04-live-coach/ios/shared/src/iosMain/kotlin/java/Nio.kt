// java.nio.ByteBuffer, as much of it as `core`'s Plane and Capture use: a byte array, a start
// and a position. The iOS app wraps the camera's plane bytes (copied out of the CVPixelBuffer).
package java.nio

class ByteBuffer private constructor(private val a: ByteArray, private var pos: Int) {
    companion object { fun wrap(a: ByteArray) = ByteBuffer(a, 0) }
    fun get(i: Int): Byte = a[i]
    fun get(dst: ByteArray, off: Int, len: Int): ByteBuffer { a.copyInto(dst, off, pos, pos + len); pos += len; return this }
    fun duplicate() = ByteBuffer(a, pos)
    fun position(p: Int): ByteBuffer { pos = p; return this }
    fun position() = pos
    fun array() = a
}
