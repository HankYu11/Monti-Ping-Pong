@file:OptIn(kotlin.experimental.ExperimentalObjCRefinement::class, kotlinx.cinterop.ExperimentalForeignApi::class)
// java.nio.ByteBuffer, as much of it as `core`'s Plane and Capture use: bytes, and a position.
// The bytes are a Kotlin ByteArray, or (iOS) memory the app owns -- a locked CVPixelBuffer plane --
// read in place, so a 1080p frame is not copied into Kotlin before Downsample.rows or
// Capture.crop take the few rows they need. As on Android, such a buffer is valid only while
// the app holds the picture.
package java.nio

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.get
import kotlinx.cinterop.plus
import kotlinx.cinterop.usePinned
import platform.posix.memcpy

@kotlin.native.HiddenFromObjC
class ByteBuffer private constructor(private val a: ByteArray?, private val p: CPointer<ByteVar>?, private var pos: Int) {
    companion object {
        fun wrap(a: ByteArray) = ByteBuffer(a, null, 0)
        fun wrap(p: CPointer<ByteVar>) = ByteBuffer(null, p, 0)
    }
    fun get(i: Int): Byte = if (a != null) a[i] else p!![i]
    fun get(dst: ByteArray, off: Int, len: Int): ByteBuffer {
        if (len > 0) {
            if (a != null) a.copyInto(dst, off, pos, pos + len)
            else dst.usePinned { d -> memcpy(d.addressOf(off), p!! + pos, len.toULong()) }
        }
        pos += len; return this
    }
    fun duplicate() = ByteBuffer(a, p, pos)
    fun position(p: Int): ByteBuffer { pos = p; return this }
    fun position() = pos
}
