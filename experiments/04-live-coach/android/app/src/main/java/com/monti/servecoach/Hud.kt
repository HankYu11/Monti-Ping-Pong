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
import kotlin.math.min

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
    private val band = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Hud.mono(ctx) }
    private val mark = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    /** this serve's RPM, or null for none */
    var rpm: Int? = null
        set(v) { field = v; invalidate() }
    private val d = resources.displayMetrics.density
    override fun onMeasure(wSpec: Int, hSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(wSpec), (58 * d).toInt())
    }
    private fun x(r: Float) = width * min(r, maxRpm) / maxRpm
    override fun onDraw(c: Canvas) {
        val top = 16 * d; val barH = 7 * d; val gap = 3 * d
        val lvl = rpm?.let { SpinLevel.of(it) }
        val edges = floatArrayOf(0f) + SpinLevel.EDGES.map { it.toFloat() }.toFloatArray() + floatArrayOf(maxRpm)
        text.textSize = 9.5f * d
        for (k in 0 until 4) {
            val x0 = x(edges[k]) + (if (k > 0) gap / 2 else 0f); val x1 = x(edges[k + 1]) - (if (k < 3) gap / 2 else 0f)
            val on = lvl == k
            band.color = if (on) Hud.CYAN else Color.argb(if (lvl != null && k < lvl) 120 else 45, 95, 242, 255)
            c.drawRect(x0, top, x1, top + barH, band)
            text.color = if (on) Hud.WHITE else Hud.DIM
            text.isFakeBoldText = on
            val label = SpinLevel.NAMES[k]
            val tw = text.measureText(label)
            c.drawText(label, ((x0 + x1) / 2 - tw / 2).coerceIn(0f, width - tw), top + barH + 14 * d, text)
        }
        // the world-class marks above the bar
        text.isFakeBoldText = false; text.textSize = 8.5f * d; text.color = Hud.AMBER
        mark.color = Hud.AMBER
        for ((r, label) in listOf(SpinLevel.WORLD_AVG_RPM to "PRO AVG", SpinLevel.WORLD_MAX_RPM to "PRO MAX")) {
            val xx = x(r.toFloat())
            c.drawRect(xx - 0.75f * d, top - 4 * d, xx + 0.75f * d, top + barH, mark)
            val tw = text.measureText(label)
            c.drawText(label, (xx - tw / 2).coerceIn(0f, width - tw), top - 6 * d, text)
        }
        // this serve: a marker under the bar
        rpm?.let {
            val xx = x(it.toFloat()).coerceIn(5 * d, width - 5 * d)
            mark.color = Hud.WHITE
            val p = Path(); p.moveTo(xx, top + barH + 1 * d); p.lineTo(xx - 5 * d, top + barH + 1 * d + 0f); p.close()
            val tri = Path(); tri.moveTo(xx, top - 1 * d); tri.lineTo(xx - 5 * d, top - 8 * d); tri.lineTo(xx + 5 * d, top - 8 * d); tri.close()
            c.drawPath(tri, mark)
            c.drawRect(xx - 1 * d, top - 2 * d, xx + 1 * d, top + barH + 2 * d, mark)
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
