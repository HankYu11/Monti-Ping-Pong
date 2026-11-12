package com.monti.servecoach

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The live screen's look (0.9): a cockpit HUD drawn over the camera view. Cyan line art, the
 * reading in light wide numerals (Oxanium), labels and lists in a monospace (IBM Plex Mono).
 */
object Hud {
    val CYAN = Color.rgb(95, 242, 255)
    val AMBER = Color.rgb(255, 211, 106)
    val RED = Color.rgb(255, 95, 109)
    val WHITE = Color.rgb(240, 248, 250)
    val DIM = Color.argb(150, 95, 242, 255)

    private var oxanium: Typeface? = null
    private var mono: Typeface? = null
    private var monoMedium: Typeface? = null

    /** Oxanium at a weight (300 light .. 800 extra bold): one variable font */
    fun display(ctx: Context, weight: Int): Typeface {
        val base = oxanium ?: Typeface.createFromAsset(ctx.assets, "fonts/Oxanium.ttf").also { oxanium = it }
        return try {
            Typeface.Builder(ctx.assets, "fonts/Oxanium.ttf").setFontVariationSettings("'wght' $weight").build() ?: base
        } catch (e: Exception) { base }
    }
    fun mono(ctx: Context, medium: Boolean = false): Typeface =
        if (medium) monoMedium ?: Typeface.createFromAsset(ctx.assets, "fonts/IBMPlexMono-Medium.ttf").also { monoMedium = it }
        else mono ?: Typeface.createFromAsset(ctx.assets, "fonts/IBMPlexMono-Regular.ttf").also { mono = it }
}

/**
 * How strong a serve's spin is. From the 2009 World Championships quarterfinals (Tamaki et al.):
 * men's serves averaged 46 rev/s (about 2,760 RPM), women's 39, the fastest 62.5 (3,750 RPM).
 */
object SpinLevel {
    const val WORLD_AVG_RPM = 2760
    const val WORLD_MAX_RPM = 3750
    /** the bands' upper ends, RPM: none, weak, medium; strong is the rest */
    val EDGES = intArrayOf(600, 1500, 2400)
    val NAMES = arrayOf("NO SPIN", "WEAK", "MEDIUM", "STRONG")
    fun of(rpm: Int): Int = EDGES.indexOfFirst { rpm < it }.let { if (it < 0) 3 else it }
    /** for the voice: "strong", "medium", "weak" or "almost no" spin */
    fun spoken(rpm: Int) = arrayOf("almost no", "weak", "medium", "strong")[of(rpm)]
}

/** The shading over the camera view: a dark fall-off on the right, behind the readout. (0.9.0 drew
 * camera-style corner brackets too: "the phone is already a camera".) */
class HudFrame(ctx: Context) : View(ctx) {
    private val shade = Paint()
    var color: Int = Hud.CYAN
    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        shade.shader = LinearGradient(w * 0.38f, 0f, w.toFloat(), 0f, Color.TRANSPARENT, Color.argb(200, 0, 8, 14), Shader.TileMode.CLAMP)
    }
    override fun onDraw(c: Canvas) { c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), shade) }
}

/**
 * The reference for a reading: four bands (no spin, weak, medium, strong) from 0 to 4,000 RPM,
 * the world-class average and fastest marked, and this serve's reading as a marker on it.
 */
class SpinScale(ctx: Context) : View(ctx) {
    private val maxRpm = 4000f
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Hud.mono(ctx) }
    /** this serve's RPM, or null for none */
    var rpm: Int? = null
        set(v) { field = v; invalidate() }
    private val d = resources.displayMetrics.density
    override fun onMeasure(wSpec: Int, hSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(wSpec), (24 * d).toInt())
    }
    private fun x(r: Float) = width * min(r, maxRpm) / maxRpm
    override fun onDraw(c: Canvas) {
        val top = 2 * d; val barH = 5 * d
        bar.color = Color.argb(38, 95, 242, 255)
        c.drawRect(0f, top, width.toFloat(), top + barH, bar)
        rpm?.let { bar.color = Hud.CYAN; c.drawRect(0f, top, x(it.toFloat()), top + barH, bar) }
        // one comparison, not four bands: what a world-class serve averages. The bands were naming
        // the same thing the bar's own length already says.
        val px = x(SpinLevel.WORLD_AVG_RPM.toFloat())
        bar.color = Hud.AMBER
        c.drawRect(px - 0.75f * d, top - 2.5f * d, px + 0.75f * d, top + barH + 2.5f * d, bar)
        text.textSize = 8.5f * d
        text.color = Hud.DIM
        c.drawText("NO SPIN", 0f, top + barH + 10.5f * d, text)
        text.color = Hud.AMBER
        val lbl = "PRO AVG"; val w = text.measureText(lbl)
        c.drawText(lbl, (px - w / 2).coerceIn(0f, width - w), top + barH + 10.5f * d, text)
    }
}

/**
 * The spin, three ways. A ball can only turn about three axes: end over end, like a door, and like
 * a drill. The number above says how much spin there is; this says what it is made of, which is
 * the part a player can change. Replaces the coaching line, which said one thing about the serve
 * and said it in prose.
 *
 * The three bars share one scale, the serve's own rate, so their lengths compare with each other.
 * They do not add up to it: the parts are at right angles, so they combine the way the sides of a
 * box make its diagonal, not the way coins make a pile.
 */
class SpinSplit(ctx: Context) : View(ctx) {
    var spin: Spin? = null
        set(v) { field = v; invalidate() }
    private val d = resources.displayMetrics.density
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Hud.mono(ctx); letterSpacing = 0.1f }
    private val value = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Hud.display(ctx, 600) }
    override fun onMeasure(wSpec: Int, hSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(wSpec), (62 * d).toInt())
    }
    override fun onDraw(c: Canvas) {
        val s = spin ?: return
        val total = maxOf(1.0, hypot(hypot(s.lift, s.curve), s.gyro))
        // a gyro whose direction the engine won't vouch for is drawn dim: the size is measured,
        // which way round it goes is not
        val rows = listOf(
            Triple(if (s.lift >= 0) "BACKSPIN" else "TOPSPIN", abs(s.lift), true),
            Triple(if (s.curve >= 0) "SIDE RIGHT" else "SIDE LEFT", abs(s.curve), true),
            Triple("GYRO", abs(s.gyro), s.dirKnown))
        val labW = 76 * d; val valW = 30 * d
        val x0 = labW; val x1 = width - valW
        label.textSize = 9.5f * d
        value.textSize = 11f * d
        label.color = Hud.DIM
        val unit = "REV/S"
        c.drawText(unit, width - label.measureText(unit), 8f * d, label)
        for ((i, r) in rows.withIndex()) {
            val y = 22f * d + i * 17f * d
            label.color = if (r.third) Hud.DIM else Color.argb(90, 95, 242, 255)
            c.drawText(r.first, 0f, y, label)
            val mid = y - 3.2f * d
            bar.color = Color.argb(34, 95, 242, 255)
            c.drawRect(x0, mid - 2.5f * d, x1 - 6 * d, mid + 2.5f * d, bar)
            bar.color = if (r.third) Hud.CYAN else Color.argb(110, 95, 242, 255)
            val w = (x1 - 6 * d - x0) * (r.second / total).toFloat()
            c.drawRect(x0, mid - 2.5f * d, x0 + w, mid + 2.5f * d, bar)
            value.color = if (r.third) Hud.WHITE else Color.argb(150, 240, 248, 250)
            val v = r.second.roundToInt().toString()
            c.drawText(v, width - value.measureText(v), y, value)
        }
    }
}

/** A cut-corner (hexagon) button face: outline and a faint fill in the given colour. */
class HexDrawable(private val density: Float) : Drawable() {
    var color: Int = Hud.CYAN
        set(v) { field = v; invalidateSelf() }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    override fun draw(c: Canvas) {
        val b = bounds; val inset = 1.5f * density; val cut = b.height() * 0.5f
        val p = Path()
        p.moveTo(b.left + cut, b.top + inset); p.lineTo(b.right - cut, b.top + inset); p.lineTo(b.right - inset, b.exactCenterY())
        p.lineTo(b.right - cut, b.bottom - inset); p.lineTo(b.left + cut, b.bottom - inset); p.lineTo(b.left + inset, b.exactCenterY()); p.close()
        fill.color = Color.argb(46, Color.red(color), Color.green(color), Color.blue(color))
        line.color = color; line.strokeWidth = 2 * density
        c.drawPath(p, fill); c.drawPath(p, line)
    }
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(cf: android.graphics.ColorFilter?) {}
    @Deprecated("Drawable") override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
}
