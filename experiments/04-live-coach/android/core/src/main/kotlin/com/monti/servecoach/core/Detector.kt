package com.monti.servecoach.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** The background a frame is compared with: per pixel brightness and colour, quarter size. */
class Background(val y: FloatArray, val u: FloatArray, val v: FloatArray)

/** A frame's candidates, and the share of the picture that moved (a body near the camera moves a lot of it). */
class Found(val moving: Float) : ArrayList<FloatArray>()

/**
 * Ball-like blobs in one frame, as the engine's scanFrame finds them: brightness evened out for
 * light flicker (a ratio per row against the background), then a pixel moves when its brightness
 * or colour differs by more than 30; blobs of 12-625 px, not long (1.8), filling half their box.
 * Each one keeps its own buffers: one per thread.
 */
class Detector(val w: Int, val h: Int) {
    private val chromaGain = 1.5f
    private val mask = ByteArray(w * h)
    private val eroded = ByteArray(w * h)
    private val stack = IntArray(w * h)
    private val raw = FloatArray(h)
    private val rows = FloatArray(h)
    /** time (ns) in: mask, opening, blobs */
    val nanos = LongArray(3)

    /** [x, y, r] of each candidate */
    fun find(q: Quarter, bg: Background): Found {
        var t = System.nanoTime()
        makeMask(q, bg)
        var n = System.nanoTime(); nanos[0] += n - t; t = n
        open2()
        n = System.nanoTime(); nanos[1] += n - t; t = n
        var moved = 0
        for (b in mask) if (b.toInt() != 0) moved++
        val out = Found(moved.toFloat() / mask.size)
        components { area, bw, bh, cx, cy ->
            // a ball the meter can read is 20 px across or more: 12 px of area here and up
            // (the engine's search takes 3 and up, then its rules drop the small ones)
            if (area < 12 || area > 625) return@components
            if (max(bw, bh).toFloat() / max(1, min(bw, bh)) > 1.8f || area.toFloat() / (bw * bh) < 0.5f) return@components
            out.add(floatArrayOf(cx, cy, sqrt(area / Math.PI).toFloat()))
        }
        nanos[2] += System.nanoTime() - t
        return out
    }

    private fun makeMask(q: Quarter, bg: Background) {
        rowRatios(q.y, bg.y)
        val qy = q.y; val qu = q.u; val qv = q.v; val by = bg.y; val bu = bg.u; val bv = bg.v
        val m = mask
        val cLim = 30f / chromaGain
        for (y in 0 until h) {
            val inv = 1f / rows[y]
            var p = y * w
            val end = p + w
            while (p < end) {
                var gv = (qy[p] * inv).toInt()
                if (gv < 0) gv = 0 else if (gv > 255) gv = 255
                m[p] = if (abs(gv - by[p]) > 30f || abs(qu[p] - bu[p]) > cLim || abs(qv[p] - bv[p]) > cLim) 1 else 0
                p++
            }
        }
    }

    /** per-row brightness ratio to the background (every 4th pixel, over a lit background), smoothed over 5 rows */
    private fun rowRatios(g: FloatArray, bg: FloatArray) {
        for (y in 0 until h) {
            var sg = 0f; var sb = 0f
            var p = y * w
            val end = p + w
            while (p < end) { val b = bg[p]; if (b > 20f) { sg += g[p]; sb += b }; p += 4 }
            raw[y] = if (sb > 0f) sg / sb else 1f
        }
        for (y in 0 until h) {
            var s = 0f; var c = 0
            for (k in max(0, y - 2)..min(h - 1, y + 2)) { s += raw[k]; c++ }
            rows[y] = (s / c).coerceIn(0.5f, 2f)
        }
    }

    /**
     * Opening with a 2x2 square, as the engine does at half size: single pixels and one-pixel lines
     * (compression noise, edges shimmering) go, the ball stays.
     */
    private fun open2() {
        val m = mask; val e = eroded
        // erode: a pixel stays when its 2x2 block (it and right, down, down-right) is all set
        for (y in 0 until h - 1) {
            var p = y * w
            val end = p + w - 1
            while (p < end) { e[p] = (m[p].toInt() and m[p + 1].toInt() and m[p + w].toInt() and m[p + w + 1].toInt()).toByte(); p++ }
            e[end] = 0
        }
        java.util.Arrays.fill(e, (h - 1) * w, h * w, 0)
        // dilate back: set where any of it, left, up, up-left was kept
        for (x in 0 until w) m[x] = (e[x].toInt() or (if (x > 0) e[x - 1].toInt() else 0)).toByte()
        for (y in 1 until h) {
            var p = y * w
            m[p] = (e[p].toInt() or e[p - w].toInt()).toByte()
            p++
            val end = y * w + w
            while (p < end) { m[p] = (e[p].toInt() or e[p - 1].toInt() or e[p - w].toInt() or e[p - w - 1].toInt()).toByte(); p++ }
        }
    }

    /** 8-connected blobs of the mask (visited pixels are marked 2): area, box size, centre */
    private inline fun components(onBlob: (Int, Int, Int, Float, Float) -> Unit) {
        val m = mask; val st = stack
        for (start in 0 until w * h) {
            if (m[start].toInt() != 1) continue
            var sp = 0
            st[sp++] = start; m[start] = 2
            var area = 0; var sx = 0L; var sy = 0L
            var x0 = w; var y0 = h; var x1 = -1; var y1 = -1
            while (sp > 0) {
                val p = st[--sp]
                val y = p / w; val x = p - y * w
                area++; sx += x; sy += y
                if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y
                val l = x > 0; val r = x < w - 1
                if (y > 0) {
                    val u = p - w
                    if (l && m[u - 1].toInt() == 1) { m[u - 1] = 2; st[sp++] = u - 1 }
                    if (m[u].toInt() == 1) { m[u] = 2; st[sp++] = u }
                    if (r && m[u + 1].toInt() == 1) { m[u + 1] = 2; st[sp++] = u + 1 }
                }
                if (l && m[p - 1].toInt() == 1) { m[p - 1] = 2; st[sp++] = p - 1 }
                if (r && m[p + 1].toInt() == 1) { m[p + 1] = 2; st[sp++] = p + 1 }
                if (y < h - 1) {
                    val d = p + w
                    if (l && m[d - 1].toInt() == 1) { m[d - 1] = 2; st[sp++] = d - 1 }
                    if (m[d].toInt() == 1) { m[d] = 2; st[sp++] = d }
                    if (r && m[d + 1].toInt() == 1) { m[d + 1] = 2; st[sp++] = d + 1 }
                }
                if (area > 4000) break                       // a person or the paddle: not a ball, stop counting
            }
            onBlob(area, x1 - x0 + 1, y1 - y0 + 1, sx.toFloat() / area, sy.toFloat() / area)
        }
    }
}
