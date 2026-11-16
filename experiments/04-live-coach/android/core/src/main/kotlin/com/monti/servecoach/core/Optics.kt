package com.monti.servecoach.core

import kotlin.math.PI
import kotlin.math.atan

/**
 * What the engine needs to know about the camera that the pictures don't tell it: how wide the
 * lens sees, and how long the shutter is open.
 *
 * The field of view sets how far away a ball of a given size in pixels is, and with it how fast
 * the ball comes toward the camera or goes away from it. The engine used to assume 68 degrees on
 * every phone. Camera2 says what it actually is: the sensor's physical size, the lens's focal
 * length, and the part of the sensor the stream is cut from.
 */
object Optics {
    /** the engine's own assumption, when the camera says nothing usable */
    const val FALLBACK_HFOV = 68.0

    /**
     * The horizontal field of view, in degrees, across the width of a [streamW] x [streamH]
     * stream; null when the numbers don't make a believable one (30-120 degrees).
     *
     * [physW] is SENSOR_INFO_PHYSICAL_SIZE's width in mm, which spans the whole pixel array,
     * [pixelArrayW] pixels wide (SENSOR_INFO_PIXEL_ARRAY_SIZE). [cropW] x [cropH] is the part of
     * the sensor the camera reads for the stream, in the same pixels: SCALER_CROP_REGION from a
     * capture result, else the active array (which is what the crop is when nothing zooms).
     * A stream of a different shape from the crop is cut from its middle: a wider one (16:9 from a
     * 4:3 sensor) keeps the crop's full width and loses height, a narrower one loses width.
     */
    fun horizontalFov(physW: Double, pixelArrayW: Int, cropW: Int, cropH: Int, streamW: Int, streamH: Int, focalMm: Double): Double? {
        if (!(physW > 0) || pixelArrayW <= 0 || cropW <= 0 || cropH <= 0 || streamW <= 0 || streamH <= 0 || !(focalMm > 0)) return null
        val usedW = if (streamW.toDouble() / streamH >= cropW.toDouble() / cropH) cropW.toDouble()
                    else cropH * streamW.toDouble() / streamH
        val mm = usedW * physW / pixelArrayW
        val deg = 2 * atan(mm / (2 * focalMm)) * 180 / PI
        return if (deg in 30.0..120.0) deg else null
    }

    /**
     * The capture header's fields for these (appended to the JSON object, so each starts with a
     * comma): the field of view and where it came from, the shutter time when known, and the
     * numbers the field of view was worked out from, for the report.
     */
    fun headerFields(hfov: Double?, exposureNs: Long?, detail: String = ""): String {
        val sb = StringBuilder()
        if (hfov != null) sb.append(""","hfov":${"%.2f".format(java.util.Locale.ROOT, hfov)},"hfovFrom":"camera"""")
        else sb.append(""","hfovFrom":"assumed"""")
        if (exposureNs != null && exposureNs > 0) sb.append(""","exposureUs":${exposureNs / 1000}""")
        if (detail.isNotEmpty()) sb.append(""","optics":$detail""")
        return sb.toString()
    }
}
