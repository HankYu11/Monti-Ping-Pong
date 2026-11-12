package com.monti.servecoach

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableString
import android.text.style.RelativeSizeSpan
import android.view.Gravity
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The live screen (0.10): the camera full height with cyan line art over it, the state top left,
 * and one readout column on the right — the ball beside the number, then what the spin is, then
 * what it is made of. Its own class, so a test can draw it.
 *
 * 0.9.10 put twelve groups on this screen and the player reads it for about a second between
 * serves, from a metre and a half away. Four are gone: the history (it is the report), the stats
 * block and the timing numbers (both mine, not the player's), and the coaching line, which said
 * one vague thing in prose where the three-way split now says three measured ones. What is left
 * is the number, what kind of spin it is, what it is made of, and the ball showing it turn.
 */
class HudScreen(private val ctx: Context, onStart: () -> Unit, onTest: () -> Unit, onReport: () -> Unit, onAi: () -> Unit = {}, onNext: () -> Unit = {}) {
    private fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).roundToInt()
    private val mono = Hud.mono(ctx); private val monoMed = Hud.mono(ctx, medium = true)
    private fun tv(size: Float, face: Typeface, color: Int = Hud.CYAN, spacing: Float = 0f) = TextView(ctx).apply {
        textSize = size; typeface = face; setTextColor(color); letterSpacing = spacing; includeFontPadding = false
    }

    val root = FrameLayout(ctx).apply { setBackgroundColor(Color.BLACK) }
    val preview = SurfaceView(ctx)
    val frame = HudFrame(ctx)
    val stateText = tv(12.5f, monoMed, Hud.CYAN, 0.12f).apply { text = "● STARTING…" }
    val serveLabel = tv(11f, mono, Hud.DIM, 0.2f).apply { text = "SPIN RATE" }
    // beside a 116 dp ball there is about 150 dp for the number, so 48 sp, not the old 64
    val bigText = tv(48f, Hud.display(ctx, 300), Hud.CYAN).apply { text = "----"; setShadowLayer(dp(11).toFloat(), 0f, 0f, Color.argb(150, 95, 242, 255)) }
    val typeText = tv(17f, Hud.display(ctx, 700), Hud.WHITE, 0.1f)
    val qualText = tv(10.5f, mono, Hud.DIM, 0.04f)
    val scale = SpinScale(ctx)
    val spinBall = SpinBall(ctx)
    val split = SpinSplit(ctx)
    /** setup advice, the AI coach, a video test's progress, or why a serve could not be read */
    val coachText = tv(12f, mono, Color.argb(220, 225, 245, 250)).apply { text = "Checking the setup…" }
    val statsText = tv(9.5f, mono, Hud.DIM)
    val startFace = HexDrawable(ctx.resources.displayMetrics.density)
    val startBtn = Button(ctx).apply {
        text = "START"; isAllCaps = false; textSize = 15f; typeface = Hud.display(ctx, 700); letterSpacing = 0.3f
        setTextColor(Hud.CYAN); background = startFace; stateListAnimator = null
        setOnClickListener { onStart() }
    }
    /** while a session runs: ready for the next serve now, without waiting for this one's answer (a helper's button) */
    val nextFace = HexDrawable(ctx.resources.displayMetrics.density)
    val nextBtn = Button(ctx).apply {
        text = "NEXT SERVE"; isAllCaps = false; textSize = 13f; typeface = Hud.display(ctx, 700); letterSpacing = 0.2f
        setTextColor(Hud.CYAN); background = nextFace; stateListAnimator = null; visibility = View.GONE
        setOnClickListener { onNext() }
    }
    lateinit var aiButton: TextView; private set
    private lateinit var bottomLeft: LinearLayout
    private lateinit var ballRow: LinearLayout
    /** what a reading is labelled with: "SERVE", or "VIDEO TEST · SERVE" while testing with a video */
    var source = "SERVE"

    init {
        root.addView(preview, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(frame, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(stateText, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply { leftMargin = dp(52); topMargin = dp(18) })

        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.START
            setPadding(dp(8), dp(34), dp(46), dp(84))
        }
        col.addView(serveLabel)
        // the ball and the number side by side: one glance instead of two, and the scale tucks
        // under the number, which is the only thing it gives context to
        val numbers = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        numbers.addView(bigText)
        numbers.addView(scale, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        ballRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(spinBall, LinearLayout.LayoutParams(dp(116), ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(numbers, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(10) })
        }
        col.addView(ballRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        col.addView(typeText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
        col.addView(qualText)
        col.addView(split, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        col.addView(coachText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        root.addView(col, FrameLayout.LayoutParams(dp(330), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END))

        root.addView(startBtn, FrameLayout.LayoutParams(dp(176), dp(48), Gravity.BOTTOM or Gravity.END).apply { rightMargin = dp(46); bottomMargin = dp(26) })
        root.addView(nextBtn, FrameLayout.LayoutParams(dp(160), dp(48), Gravity.BOTTOM or Gravity.END).apply { rightMargin = dp(46 + 176 + 14); bottomMargin = dp(26) })
        fun small(label: String, onTap: () -> Unit) = TextView(ctx).apply {
            text = label; textSize = 11f; typeface = monoMed; letterSpacing = 0.14f; setTextColor(Hud.CYAN)
            setPadding(dp(16), dp(8), dp(16), dp(8)); background = HexDrawable(ctx.resources.displayMetrics.density).apply { color = Hud.DIM }
            setOnClickListener { onTap() }
        }
        // All three are used before a session and after it, never during: a serve is the only
        // thing on the screen while one runs.
        aiButton = small("AI COACH: OFF", onAi)
        val tools = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        tools.addView(small("TEST VIDEO", onTest))
        tools.addView(aiButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(10) })
        tools.addView(small("REPORT", onReport), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(10) })
        bottomLeft = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(statsText); addView(tools, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        }
        root.addView(bottomLeft, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START).apply { leftMargin = dp(52); bottomMargin = dp(26) })
        showSpin(null)
    }

    fun state(s: String) { stateText.text = "● " + s.uppercase(Locale.US) }
    /** the HUD's colour for what is happening: cyan ready, amber measuring, red a problem */
    fun tone(c: Int) { stateText.setTextColor(c); frame.color = c }
    fun running(on: Boolean) {
        startBtn.text = if (on) "STOP" else "START"
        val c = if (on) Hud.RED else Hud.CYAN
        startFace.color = c; startBtn.setTextColor(c)
        nextBtn.visibility = if (on) View.VISIBLE else View.GONE
        // while serving, the screen carries the serve and nothing else: the tools and the stats
        // are both for the other side of a session
        bottomLeft.visibility = if (on) View.GONE else View.VISIBLE
    }

    /** a serve read: its number, RPM (to 10), what the spin is, any warning, and the spin to draw */
    fun reading(serve: Int, rpm: Int, type: String, coach: String, spin: Spin? = null) {
        serveLabel.text = "$source %02d".format(serve)
        bigText.text = SpannableString("$rpm RPM").apply { setSpan(RelativeSizeSpan(0.3f), "$rpm".length, length, 0) }
        val t = type.lowercase(Locale.US)
        // the strength word lived here and on the scale; the scale keeps it. What is left is the
        // kind of spin, with the qualifier under it instead of wrapping onto a second line.
        typeText.text = t.substringBefore(" (").substringBefore(" + ").uppercase(Locale.US)
        qualText.text = qualifier(t, spin)
        scale.rpm = rpm
        showSpin(spin)
        message(coach)
    }

    /** a serve that couldn't be read, and why */
    fun noRead(serve: Int, why: String) {
        serveLabel.text = "$source %02d · NO READ".format(serve)
        bigText.text = "----"; typeText.text = ""; qualText.text = ""; scale.rpm = null
        showSpin(null)
        message("Couldn't read it: $why")
    }

    /** setting up (before Start): the on-device check's advice; the state line names it */
    fun advice(short: String, text: String, good: Boolean) {
        state("Setup · $short"); tone(if (good) Hud.CYAN else Hud.AMBER)
        if (!aiTalking) message(text)
    }
    private var aiLine = StringBuilder()
    private var aiTalking = false
    /** the AI coach's words as they come (a "\n" ends its turn) */
    fun aiText(t: String) {
        if (t == "\n") { aiTalking = false; aiLine = StringBuilder(); return }
        aiTalking = true; aiLine.append(t); message("AI COACH  ${aiLine.toString().trim()}")
    }
    fun aiState(on: Boolean) { aiButton.text = if (on) "AI COACH: ON" else "AI COACH: OFF"; if (!on) aiTalking = false }

    /** a video test started: the file's name, while its frames are read */
    fun testStarted(name: String) {
        source = "VIDEO TEST · SERVE"
        serveLabel.text = "VIDEO TEST"
        bigText.text = "····"; typeText.text = "READING THE VIDEO"; qualText.text = ""; scale.rpm = null
        showSpin(null)
        message(name)
    }
    /** a video test that found no serve, and what it saw instead */
    fun testFoundNone(why: String) {
        serveLabel.text = "VIDEO TEST"
        bigText.text = "NONE"; typeText.text = "NO SERVE FOUND"; qualText.text = ""; scale.rpm = null
        showSpin(null)
        message(why)
    }

    /** the one line under the readout: it has something to say, or it takes up no room */
    private fun message(text: String) {
        coachText.text = text
        coachText.visibility = if (text.isBlank()) View.GONE else View.VISIBLE
    }

    /** what the spin was, beside the number and under it: the ball turning, and the three parts */
    private fun showSpin(spin: Spin?) {
        split.spin = spin
        if (spin == null) {
            spinBall.clear()
            spinBall.visibility = View.INVISIBLE      // keeps the number where it was
            split.visibility = View.GONE
            return
        }
        spinBall.visibility = View.VISIBLE
        spinBall.show(spin)
        split.visibility = View.VISIBLE
    }

    /** what else is true of this spin, in a few words: which way it bends, which stretch, and
     *  whether the engine will vouch for the drill direction */
    private fun qualifier(type: String, spin: Spin?): String {
        val bits = ArrayList<String>()
        Regex("\\((.*?)\\)").find(type)?.let { bits.add(it.groupValues[1]) }
        bits.add(if (spin?.afterBounce == true) "after the bounce" else "off the bat")
        if (spin != null && !spin.dirKnown && abs(spin.gyro) >= 1) bits.add("drill way unknown")
        return bits.joinToString(" · ")
    }
}
