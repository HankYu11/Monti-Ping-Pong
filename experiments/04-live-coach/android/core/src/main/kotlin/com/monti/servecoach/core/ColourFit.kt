package com.monti.servecoach.core

/**
 * How the graphics chip's picture maps back to the decoder's own bytes. The chip samples a frame
 * as colour (it converts with a matrix and a range of its own choosing), and the capture must
 * hold what the decoder gives, as the engine was checked with (its range taken as the decoder
 * reports it: the phone's videos are full range but were read as limited, and a paddle stretch
 * read 32 rev/s instead of 44 when that changed). One frame decoded both ways, at the start:
 * bytes = gain x chip + offset, by least squares; the matrix that fits better is kept.
 */
object ColourFit {
    class Fit(val gain: Double, val offset: Double, val rms: Double)

    /** a straight line from [chip] to [decoded] (same pixels, bytes), and how far off it is */
    fun fit(chip: ByteArray, decoded: ByteArray, from: Int = 0, n: Int = minOf(chip.size, decoded.size)): Fit {
        var sx = 0.0; var sy = 0.0; var sxx = 0.0; var sxy = 0.0
        for (i in from until from + n) {
            val x = (chip[i].toInt() and 0xff).toDouble(); val y = (decoded[i].toInt() and 0xff).toDouble()
            sx += x; sy += y; sxx += x * x; sxy += x * y
        }
        val d = n * sxx - sx * sx
        val gain = if (d > 0) (n * sxy - sx * sy) / d else 1.0
        val offset = (sy - gain * sx) / n
        var se = 0.0
        for (i in from until from + n) {
            val x = (chip[i].toInt() and 0xff).toDouble(); val y = (decoded[i].toInt() and 0xff).toDouble()
            val e = y - (gain * x + offset); se += e * e
        }
        return Fit(gain, offset, Math.sqrt(se / n))
    }
}
