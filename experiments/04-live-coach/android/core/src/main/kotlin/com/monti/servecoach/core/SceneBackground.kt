package com.monti.servecoach.core

/**
 * The scene's own background, built while nothing is happening.
 *
 * A windowed capture needs a background — the per-pixel median of a spread of frames — and until
 * now it got one by decoding fifteen frames out of the serve itself. That decode is what is left
 * of the wait once the window ring has taken the cutting away: about 0.9 s of a 1.3 s serve, and
 * up to 3 s on a wide crop.
 *
 * But a background is a property of the scene, not of the serve. The table, the floor, the wall
 * behind are the same between serves as during one, and the chip has every frame of them going
 * past anyway. So the picture is read back a horizontal strip at a time while no ball is in the
 * air, each strip keeps its last [samples] readings, and the median of those is the background.
 * A capture then takes the part of it under its own crop and decodes nothing at all.
 *
 * **Strips, and why the median survives them.** A whole 1920x1080 frame is 3.1 MB to read back,
 * which is four frames' worth of the chip's time in one go; a strip is a ninth of that. The
 * samples of one strip come from different frames than the samples of another, which does not
 * matter in the slightest: the median is per pixel, and a pixel only cares about its own
 * samples. What does matter is that a pixel's samples are spread in TIME, so that a player
 * standing still for a moment cannot become the background — hence one strip per call and a
 * caller that spaces them out.
 *
 * Everything is in the capture's own layout: brightness at full size, then the two colour planes
 * at half size each way.
 */
class SceneBackground(val frameW: Int, val frameH: Int, val strips: Int = 9, val samples: Int = 15) {
    val stripH = frameH / strips
    init {
        require(frameH % strips == 0) { "the picture must divide into whole strips: $frameH / $strips" }
        require(stripH % 2 == 0) { "a strip must be an even number of rows: $stripH" }
        require(frameW % 2 == 0) { "the picture must be an even number of columns: $frameW" }
    }
    /** bytes of one strip, in the capture's layout */
    val stripBytes = frameW * stripH + 2 * ((frameW / 2) * (stripH / 2))
    val frameBytes = frameW * frameH + 2 * ((frameW / 2) * (frameH / 2))

    private val ySize = frameW * frameH
    private val cSize = (frameW / 2) * (frameH / 2)
    private val held = Array(strips) { arrayOfNulls<ByteArray>(samples) }
    private val next = IntArray(strips)
    private val count = IntArray(strips)
    /** when each strip was last medianed into [out] */
    private val doneAt = LongArray(strips)
    private val dirty = BooleanArray(strips)
    private val out = ByteArray(frameBytes)
    private val tmp = IntArray(samples)

    /** strips that have been medianed at least once */
    @Volatile var ready = 0; private set
    /** readings taken in all */
    @Volatile var taken = 0L; private set
    /** the whole picture is covered */
    val complete get() = ready >= strips
    /** how long ago the stalest strip was medianed, or -1 when the picture is not covered yet */
    fun ageMs(now: Long): Long = if (!complete) -1 else now - doneAt.min()

    /** which strip to read next: the one with the fewest readings, then the stalest */
    fun wanted(): Int {
        var best = 0
        for (i in 1 until strips) {
            if (count[i] < count[best] || (count[i] == count[best] && doneAt[i] < doneAt[best])) best = i
        }
        return best
    }

    /** the picture rows one strip covers */
    fun rowsOf(strip: Int) = strip * stripH

    /**
     * One reading of one strip, in the capture's layout. Kept, and once there are enough the
     * strip's median is written into the background. The bytes are not held onto beyond this
     * class, but they are kept, so the caller must not reuse the array.
     */
    @Synchronized fun add(strip: Int, bytes: ByteArray, nowMs: Long) {
        if (strip !in 0 until strips || bytes.size != stripBytes) return
        held[strip][next[strip]] = bytes
        next[strip] = (next[strip] + 1) % samples
        if (count[strip] < samples) count[strip]++
        taken++
        if (count[strip] >= 5) {
            if (doneAt[strip] == 0L) ready++
            doneAt[strip] = nowMs
            dirty[strip] = true
        }
    }

    /**
     * A whole picture, sliced into its strips. The pictures come from the recording's own
     * keyframes, which decode on their own without the frames around them -- the same trick the
     * engine's search uses to learn a background without decoding a whole video.
     */
    fun addWhole(frame: ByteArray, nowMs: Long) {
        if (frame.size != frameBytes) return
        val cw = frameW / 2
        for (st in 0 until strips) {
            val y0 = st * stripH
            val b = ByteArray(stripBytes)
            var o = 0
            for (y in 0 until stripH) { System.arraycopy(frame, (y0 + y) * frameW, b, o, frameW); o += frameW }
            for (plane in 0..1) for (y in 0 until stripH / 2) {
                System.arraycopy(frame, ySize + plane * cSize + (y0 / 2 + y) * cw, b, o, cw); o += cw
            }
            add(st, b, nowMs)
        }
    }

    /**
     * One strip's background: the median of its readings, each with its own row brightness
     * divided out first.
     *
     * Not the median of the readings as they came. The engine compares a frame against a
     * background it has already corrected for the light's flicker, so the background has to be
     * the median of CORRECTED frames -- which is what Windows.Builder does for a capture cut
     * the old way. The ratio is a chicken and egg, measured against the background it defines,
     * and is broken the same way the Builder breaks it: median raw once, measure each reading's
     * rows against that, correct, median again.
     *
     * Colour is the median of the readings as they came, since the engine never corrects it.
     *
     * Done on demand rather than on every reading: a strip is read fifteen times for one
     * background, and medianing it fifteen times over is fifteen times the work for one answer.
     */
    private fun medianInto(strip: Int) {
        val m = count[strip]
        val set = Array(m) { held[strip][it]!! }
        val y0 = strip * stripH
        val yRun = frameW * stripH
        val raw = ByteArray(yRun)
        for (p in 0 until yRun) {
            for (k in 0 until m) tmp[k] = set[k][p].toInt() and 255
            raw[p] = Windows.medianOf(tmp, m).toByte()
        }
        val stride = Windows.ROW_STRIDE
        val cols = (frameW + stride - 1) / stride
        val ratios = Array(m) { FloatArray(stripH) }
        val buf = DoubleArray(cols)
        for (k in 0 until m) for (y in 0 until stripH) {
            val r0 = y * frameW
            var c = 0; var x = 0
            while (x < frameW) {
                val bb = (raw[r0 + x].toInt() and 255).toDouble()
                buf[c++] = (set[k][r0 + x].toInt() and 255) / (if (bb < 1.0) 1.0 else bb)
                x += stride
            }
            ratios[k][y] = Windows.medianOf(buf, c).toFloat()
        }
        var o = y0 * frameW
        for (y in 0 until stripH) {
            val src = y * frameW
            for (x in 0 until frameW) {
                for (k in 0 until m) {
                    val rr = ratios[k][y]
                    val v = ((set[k][src + x].toInt() and 255) / (if (rr < 1e-3f) 1f else rr)).toInt()
                    tmp[k] = if (v > 255) 255 else if (v < 0) 0 else v
                }
                out[o + x] = Windows.medianOf(tmp, m).toByte()
            }
            o += frameW
        }
        // the two colour planes, half size each way
        val cw = frameW / 2; val cRun = cw * (stripH / 2)
        for (plane in 0..1) {
            var q = yRun + plane * cRun
            var d = ySize + plane * cSize + (y0 / 2) * cw
            var i = 0
            while (i < cRun) {
                for (k in 0 until m) tmp[k] = set[k][q].toInt() and 255
                out[d] = Windows.medianOf(tmp, m).toByte()
                q++; d++; i++
            }
        }
        dirty[strip] = false
    }

    /**
     * The background under [rect], in the capture's layout — what a windowed capture wants in
     * place of medianing frames out of the serve. Null until the whole picture is covered.
     */
    @Synchronized fun crop(rect: IntArray): ByteArray? {
        if (!complete) return null
        for (i in 0 until strips) if (dirty[i]) medianInto(i)
        val x0 = rect[0]; val y0 = rect[1]; val w = rect[2]; val h = rect[3]
        if (x0 < 0 || y0 < 0 || x0 + w > frameW || y0 + h > frameH) return null
        val cw = frameW / 2; val w2 = w / 2; val h2 = h / 2
        val res = ByteArray(w * h + 2 * (w2 * h2))
        var o = 0
        for (y in 0 until h) { System.arraycopy(out, (y0 + y) * frameW + x0, res, o, w); o += w }
        for (plane in 0..1) {
            for (y in 0 until h2) {
                System.arraycopy(out, ySize + plane * cSize + (y0 / 2 + y) * cw + x0 / 2, res, o, w2); o += w2
            }
        }
        return res
    }

    companion object {
        /** a number of strips that divides a picture into whole strips with an even number of rows */
        fun stripsFor(frameH: Int) =
            listOf(9, 12, 8, 10, 6, 5, 4, 3, 2, 1).firstOrNull { frameH % it == 0 && (frameH / it) % 2 == 0 } ?: 1
    }

    /** the picture moved, or the light changed: everything held is about a scene that is gone */
    @Synchronized fun reset() {
        for (i in 0 until strips) { count[i] = 0; next[i] = 0; doneAt[i] = 0L; dirty[i] = false; held[i].fill(null) }
        ready = 0
    }
}
