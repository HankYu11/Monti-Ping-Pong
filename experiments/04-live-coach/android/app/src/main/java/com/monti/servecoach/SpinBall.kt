package com.monti.servecoach

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A serve's spin split, in rev/s, exactly as the engine reports it: [lift] positive for backspin
 * and negative for topspin, [curve] positive for a ball that bends right, [gyro] signed, positive
 * when the spin points away down the table. [dots] is whether the colour dots were read.
 *
 * [afterBounce] matters more than it looks. The three parts are measured against the ball's
 * direction of travel, and the bounce turns that direction: the ball arrives going down and
 * leaves going up. So the same unchanged spin splits differently either side of a bounce, and
 * the gyro can genuinely swap sign without the ball doing anything new. Two club captures read
 * -13.7 and -7.1 off the bat and +11.4 and +19.5 after the bounce, and their spin axes in the
 * camera's own frame — which the bounce does not turn — agree to 13 and 16 degrees. Nothing
 * mirrored; the frame moved. So the drawing has to say which stretch it is showing.
 */
data class Spin(val lift: Double, val curve: Double, val gyro: Double, val dots: Boolean,
                val afterBounce: Boolean = false)

/**
 * The drawing's arithmetic, with no Android in it, so a test can check it against the ball it
 * draws instead of against the reasoning that produced it. That is not a style preference: on the
 * spin page the same arithmetic was worked out on paper, looked right, and had the ball turning
 * one way while the chevrons pointed the other. Tracking the painted dots through the renderer is
 * what caught it, and [sphere] is here so the same check can run against this port.
 *
 * The frame is what you see: x right, y **down**, z toward you, the ball flying away into the
 * screen. That is left handed, so a rotation built the usual way turns the mirror of what the
 * right hand says — which is exactly the trap. Nothing here reasons about the sign; the view asks
 * [chevronsClockwise] and [faceTravel], and the test asks whether the ball agrees with them.
 */
internal object SpinMath {
    val WHITE = doubleArrayOf(244.0, 244.0, 241.0)
    val INK = doubleArrayOf(20.0, 20.0, 20.0)
    val DOT_RGB = arrayOf(doubleArrayOf(208.0, 32.0, 42.0), doubleArrayOf(31.0, 71.0, 196.0), doubleArrayOf(30.0, 154.0, 60.0))

    // Three markers at thirds of the seam. They are placed like the painted ball's dots, but
    // drawn bigger than the real 8 mm ones: this is a diagram of a measured spin, not a replay of
    // the footage, so it should be read at arm's length on a phone rather than match the ball in
    // the player's hand. Three distinguishable colours is the part that has to stay -- you need
    // one feature you can follow to see which way the ball is going, and a single band or an
    // unlabelled pattern is symmetric enough to turn one way and read as the other.
    val DOT_DIR = doubleArrayOf(0.0, 120.0, 240.0).map { deg ->
        val th = Math.toRadians(61.4); val ph = Math.toRadians(deg)
        doubleArrayOf(cos(th), sin(th) * cos(ph), sin(th) * sin(ph))
    }.toTypedArray()
    val DOTCOS = cos(Math.toRadians(17.0))
    val LIGHT = unit(doubleArrayOf(-0.22, -0.3, 0.93))
    const val AMBIENT = 0.46
    /** the pose the ball starts in: the marked side turned toward you */
    val REST = rot(unit(doubleArrayOf(0.0, -0.978, -0.210)), 1.876)

    fun unit(v: DoubleArray): DoubleArray {
        val m = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]).let { if (it == 0.0) 1.0 else it }
        return doubleArrayOf(v[0] / m, v[1] / m, v[2] / m)
    }

    /** Rodrigues about [a] by [t], row major */
    fun rot(a: DoubleArray, t: Double): DoubleArray {
        val c = cos(t); val s = sin(t); val k = 1 - c
        val x = a[0]; val y = a[1]; val z = a[2]
        return doubleArrayOf(
            c + x * x * k, x * y * k - z * s, x * z * k + y * s,
            y * x * k + z * s, c + y * y * k, y * z * k - x * s,
            z * x * k - y * s, z * y * k + x * s, c + z * z * k)
    }

    fun matmul(a: DoubleArray, b: DoubleArray): DoubleArray {
        val o = DoubleArray(9)
        for (r in 0 until 3) for (c in 0 until 3)
            o[r * 3 + c] = a[r * 3] * b[c] + a[r * 3 + 1] * b[3 + c] + a[r * 3 + 2] * b[6 + c]
        return o
    }

    fun apply(m: DoubleArray, p: DoubleArray) = doubleArrayOf(
        m[0] * p[0] + m[1] * p[1] + m[2] * p[2],
        m[3] * p[0] + m[4] * p[1] + m[5] * p[2],
        m[6] * p[0] + m[7] * p[1] + m[8] * p[2])

    /** the spin as the drawing holds it, or null when there is no spin worth drawing */
    fun axisOf(s: Spin): DoubleArray? {
        val v = doubleArrayOf(-s.lift, -s.curve, s.gyro)
        val m = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
        return if (m < 1e-6) null else doubleArrayOf(v[0] / m, v[1] / m, v[2] / m)
    }

    fun rateOf(s: Spin) = sqrt(s.lift * s.lift + s.curve * s.curve + s.gyro * s.gyro)

    /** where a point sitting on the ball's surface has moved to after turning by [t] */
    fun turn(a: DoubleArray, t: Double, p: DoubleArray) = apply(rot(a, t), p)

    /** the way the face you can see is travelling, on screen (y down) */
    fun faceTravel(a: DoubleArray) = doubleArrayOf(a[1], -a[0])

    /** which way the gyro ring goes, as you watch it */
    fun chevronsClockwise(a: DoubleArray) = a[2] > 0

    /**
     * The ball as a square of packed ARGB, [n] across, filling it: one half inked to the seam,
     * three markers at thirds of it, lit from in front. Outside the ball is clear.
     */
    fun sphere(a: DoubleArray, t: Double, n: Int, into: IntArray) {
        val m = matmul(rot(a, t), REST)
        val half = n / 2.0
        val rad = n * 0.5
        var i = 0
        for (py in 0 until n) {
            val dy = (py + 0.5 - half) / rad
            for (px in 0 until n) {
                val dx = (px + 0.5 - half) / rad
                val rr = dx * dx + dy * dy
                if (rr >= 1.0) { into[i++] = 0; continue }
                val nz = sqrt(1 - rr)
                val bx = m[0] * dx + m[3] * dy + m[6] * nz
                val by = m[1] * dx + m[4] * dy + m[7] * nz
                val bz = m[2] * dx + m[5] * dy + m[8] * nz
                var col = if (bx < 0) INK else WHITE
                if (bx >= 0) {
                    for (k in 0 until 3) {
                        val d = DOT_DIR[k]
                        if (bx * d[0] + by * d[1] + bz * d[2] > DOTCOS) { col = DOT_RGB[k]; break }
                    }
                }
                val lit = dx * LIGHT[0] + dy * LIGHT[1] + nz * LIGHT[2]
                val sh = AMBIENT + (1 - AMBIENT) * (if (lit > 0) lit else 0.0)
                into[i++] = (255 shl 24) or ((col[0] * sh).toInt() shl 16) or
                    ((col[1] * sh).toInt() shl 8) or (col[2] * sh).toInt()
            }
        }
    }
}

/**
 * The spin, drawn. A marked ball turning the way the serve really turned, seen from behind the
 * server with the ball flying away down the table, slowed about fifty times so the eye can follow.
 *
 * Three marks, and only one of them is an arrow:
 *  - the solid arrow is the way the face you can see is travelling, which is what the bat did;
 *  - the dashed line is the axis it turns about, drawn through the ball like an axle. It is a line
 *    in depth, so it shortens as the spin tips away down the table and vanishes on a pure gyro;
 *  - the dashed ring is the gyro, the part turning about the line of flight. It moves the ball
 *    nowhere in the air and everything at the bounce. Its chevrons say which way round it goes.
 *
 * The chevrons only appear when the reading came from the colour dots. A half-marked ball cannot
 * tell front from back (engine.js, mirrored()), and with the front-to-back sign goes the answer to
 * which way the ball will skid, so it is better to say nothing than to point the wrong way.
 */
class SpinBall(ctx: Context) : View(ctx) {
    private val cyan = Hud.CYAN
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val blit = Paint(Paint.FILTER_BITMAP_FLAG)
    private val path = Path()
    private val src = Rect()
    private val dst = RectF()

    private var axis: DoubleArray? = null
    private var rps = 0.0
    private var gyroKnown = true
    private var angle = 0.0
    private var lastNanos = 0L
    private var tex: Bitmap? = null
    private var texPx = IntArray(0)
    private var texN = 0

    /** how much slower than life the ball turns here: a 45 rev/s serve becomes about 0.8 a second */
    private val slow = 55.0

    fun show(lift: Double, curve: Double, gyro: Double, dots: Boolean) = show(Spin(lift, curve, gyro, dots))

    fun show(spin: Spin) {
        val a = SpinMath.axisOf(spin)
        if (a == null) { clear(); return }
        axis = a
        rps = SpinMath.rateOf(spin)
        gyroKnown = spin.dots
        lastNanos = 0L
        visibility = VISIBLE
        invalidate()
    }

    fun clear() { axis = null; rps = 0.0; invalidate() }

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val w = MeasureSpec.getSize(wSpec)
        setMeasuredDimension(w, w)                      // square: the ball and its ring
    }

    override fun onDraw(c: Canvas) {
        val a = axis ?: return
        val w = width.toFloat()
        if (w < 8f) return
        val now = System.nanoTime()
        if (lastNanos != 0L) {
            val dt = ((now - lastNanos) / 1e9).coerceAtMost(0.05)
            angle += 2 * Math.PI * (rps / slow) * dt
        }
        lastNanos = now

        val cxy = w / 2f
        val r = w * 0.345f
        ball(c, a, angle, cxy, r)
        marks(c, a, w, r, cxy)
        // about 30 a second. The ball turns slowly enough that this looks the same as 60, and
        // the camera, the tracker and the engine all want the CPU more than this does.
        postInvalidateDelayed(32)
    }

    /** drawn small and scaled up: the sphere is smooth, and this keeps the per-pixel pass cheap */
    private fun ball(c: Canvas, a: DoubleArray, t: Double, cxy: Float, r: Float) {
        val n = ((2 * r).toInt().coerceIn(96, 220) / 2) * 2
        if (texN != n) {
            texN = n; texPx = IntArray(n * n)
            tex = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
        }
        val bmp = tex ?: return
        SpinMath.sphere(a, t, n, texPx)
        bmp.setPixels(texPx, 0, n, 0, 0, n, n)
        src.set(0, 0, n, n)
        dst.set(cxy - r, cxy - r, cxy + r, cxy + r)
        c.drawBitmap(bmp, src, dst, blit)
    }

    private fun dim(alpha: Int) = Color.argb(alpha, Color.red(cyan), Color.green(cyan), Color.blue(cyan))

    private fun marks(c: Canvas, a: DoubleArray, w: Float, r: Float, cxy: Float) {
        c.save()
        c.translate(cxy, cxy)

        // the axle. The spin on screen is (-a[0], -a[1]); the more it points down the table the
        // shorter it draws, until a pure gyro leaves nothing but the ring.
        val ax = -a[0]; val ay = -a[1]
        if (hypot(ax, ay) > 0.06) {
            line.color = dim(110)
            line.strokeWidth = w * 0.013f
            line.pathEffect = DashPathEffect(floatArrayOf(w * 0.028f, w * 0.022f), 0f)
            val l = r * 1.42f
            c.drawLine((-ax * l).toFloat(), (-ay * l).toFloat(), (ax * l).toFloat(), (ay * l).toFloat(), line)
            line.pathEffect = null
        }

        // the gyro: a ring, not an arrow, because it moves the ball nowhere in the air
        if (abs(a[2]) > 0.1) {
            val ring = r * 1.2f
            line.color = dim(118)
            line.strokeWidth = w * 0.014f
            line.pathEffect = DashPathEffect(floatArrayOf(w * 0.032f, w * 0.028f), 0f)
            c.drawCircle(0f, 0f, ring, line)
            line.pathEffect = null
            if (gyroKnown) {
                val tw = if (SpinMath.chevronsClockwise(a)) 1.0 else -1.0
                line.color = dim(205)
                line.strokeWidth = w * 0.017f
                val k = w * 0.046f
                for (j in 0 until 3) {
                    val th = -Math.PI / 2 + j * 2 * Math.PI / 3
                    val qx = (ring * cos(th)).toFloat(); val qy = (ring * sin(th)).toFloat()
                    val at = atan2(tw * cos(th), -tw * sin(th))         // along the ring
                    path.reset()
                    path.moveTo(qx + k * cos(at + Math.PI - 0.62).toFloat(), qy + k * sin(at + Math.PI - 0.62).toFloat())
                    path.lineTo(qx, qy)
                    path.lineTo(qx + k * cos(at + Math.PI + 0.62).toFloat(), qy + k * sin(at + Math.PI + 0.62).toFloat())
                    c.drawPath(path, line)
                }
            }
        }

        // the one arrow: where the face you can see is travelling
        val f = SpinMath.faceTravel(a)
        val mm = hypot(f[0], f[1])
        if (mm > 0.1) {
            val ux = f[0] / mm; val uy = f[1] / mm
            val l = r * 0.66f * (if (mm + 0.25 < 1.0) (mm + 0.25).toFloat() else 1f)
            val hw = w * 0.062f
            line.color = cyan
            line.strokeWidth = w * 0.022f
            c.drawLine((-ux * l).toFloat(), (-uy * l).toFloat(),
                (ux * (l - hw * 0.7f)).toFloat(), (uy * (l - hw * 0.7f)).toFloat(), line)
            val ah = atan2(uy, ux)
            fill.color = cyan
            path.reset()
            path.moveTo((ux * (l + hw * 0.45f)).toFloat(), (uy * (l + hw * 0.45f)).toFloat())
            path.lineTo((ux * l - hw * cos(ah - 0.5)).toFloat(), (uy * l - hw * sin(ah - 0.5)).toFloat())
            path.lineTo((ux * l - hw * cos(ah + 0.5)).toFloat(), (uy * l - hw * sin(ah + 0.5)).toFloat())
            path.close()
            c.drawPath(path, fill)
        }
        c.restore()
    }
}
