package com.monti.servecoach

import android.hardware.HardwareBuffer

/** A camera frame's graphics buffer as an OpenGL texture, without a copy (cpp/hwtex.c; Java has no way to). */
object HwTex {
    val loaded = try { System.loadLibrary("hwtex"); true } catch (_: Throwable) { false }

    /** binds [buffer] to the external texture [tex] of the current context; the image to [release] afterwards, or 0 */
    @JvmStatic external fun bind(buffer: HardwareBuffer, tex: Int): Long
    @JvmStatic external fun release(image: Long)
}
