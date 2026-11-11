package com.monti.servecoach

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableString
import android.text.style.RelativeSizeSpan
import android.view.Gravity
import android.view.SurfaceView
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The live screen (0.9, the cockpit HUD): the camera view full height with cyan line art over it,
 * the state top left, the reading on the right with the spin-strength scale under it, the last
 * serves, Start/Stop bottom right, the tools bottom left. Its own class, so a test can draw it.
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
    val bigText = tv(64f, Hud.display(ctx, 300), Hud.CYAN).apply { text = "----"; setShadowLayer(dp(14).toFloat(), 0f, 0f, Color.argb(150, 95, 242, 255)) }
    val typeText = tv(16f, Hud.display(ctx, 700), Hud.WHITE, 0.12f).apply { gravity = Gravity.END }
    val scale = SpinScale(ctx)
    val spinBall = SpinBall(ctx)
    val spinNote = tv(9f, mono, Hud.DIM, 0.1f).apply { gravity = Gravity.CENTER_HORIZONTAL; text = "" }
    private lateinit var spinBox: LinearLayout
    val coachText = tv(12f, mono, Color.argb(220, 225, 245, 250)).apply {
        gravity = Gravity.END
        text = "Checking the setup…"
    }
    val historyText = tv(11.5f, mono, Color.argb(210, 95, 242, 255)).apply { setLineSpacing(0f, 1.25f) }
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
        setTextColor(Hud.CYAN); background = nextFace; stateListAnimator = null; visibility = android.view.View.GONE
        setOnClickListener { onNext() }
    }
    private val history = ArrayDeque<String>()
    lateinit var aiButton: TextView; private set
    /** what a reading is labelled with: "SERVE", or "VIDEO TEST · SERVE" while testing with a video */
    var source = "SERVE"

    init {
        root.addView(preview, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(frame, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(stateText, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply { leftMargin = dp(52); topMargin = dp(18) })
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END; setPadding(dp(8), dp(34), dp(46), dp(84)) }
        col.addView(serveLabel); col.addView(bigText); col.addView(typeText)
        col.addView(scale, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })

        col.addView(coachText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        val scroll = ScrollView(ctx).apply { isVerticalScrollBarEnabled = false; addView(historyText, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) }
        col.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(10) })
        root.addView(col, FrameLayout.LayoutParams(dp(330), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END))
        spinBox = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            addView(spinBall, LinearLayout.LayoutParams(dp(132), ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(spinNote, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2) })
            visibility = android.view.View.GONE
        }
        root.addView(spinBox, FrameLayout.LayoutParams(dp(150), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply { rightMargin = dp(336); topMargin = dp(44) })
        root.addView(startBtn, FrameLayout.LayoutParams(dp(176), dp(48), Gravity.BOTTOM or Gravity.END).apply { rightMargin = dp(46); bottomMargin = dp(26) })
        root.addView(nextBtn, FrameLayout.LayoutParams(dp(160), dp(48), Gravity.BOTTOM or Gravity.END).apply { rightMargin = dp(46 + 176 + 14); bottomMargin = dp(26) })
        fun small(label: String, onTap: () -> Unit) = TextView(ctx).apply {
            text = label; textSize = 11f; typeface = monoMed; letterSpacing = 0.14f; setTextColor(Hud.CYAN)
            setPadding(dp(16), dp(8), dp(16), dp(8)); background = HexDrawable(ctx.resources.displayMetrics.density).apply { color = Hud.DIM }
            setOnClickListener { onTap() }
        }
        val tools = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        tools.addView(small("TEST VIDEO", onTest))
        aiButton = small("AI COACH: OFF", onAi)
        tools.addView(aiButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(10) })
        tools.addView(small("REPORT", onReport), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(10) })
        val bottomLeft = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(statsText); addView(tools, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        }
        root.addView(bottomLeft, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START).apply { leftMargin = dp(52); bottomMargin = dp(26) })
    }

    fun state(s: String) { stateText.text = "● " + s.uppercase(Locale.US) }
    /** the HUD's colour for what is happening: cyan ready, amber measuring, red a problem */
    fun tone(c: Int) { stateText.setTextColor(c); frame.color = c }
    fun running(on: Boolean) {
        startBtn.text = if (on) "STOP" else "START"
        val c = if (on) Hud.RED else Hud.CYAN
        startFace.color = c; startBtn.setTextColor(c)
        nextBtn.visibility = if (on) android.view.View.VISIBLE else android.view.View.GONE
    }

    /** a serve read: its number, RPM (to 10), spin type, the coaching lines and the spin to draw */
    fun reading(serve: Int, rpm: Int, type: String, coach: String, spin: Spin? = null) {
        val level = SpinLevel.of(rpm)
        serveLabel.text = "$source %02d · SPIN RATE".format(serve)
        bigText.text = SpannableString("$rpm RPM").apply { setSpan(RelativeSizeSpan(0.3f), "$rpm".length, length, 0) }
        typeText.text = "${SpinLevel.NAMES[level]} · ${type.uppercase(Locale.US)}"
        scale.rpm = rpm
        showSpin(spin)
        coachText.text = coach
        remember("%02d  %4d  %s".format(serve, rpm, type.uppercase(Locale.US)))
    }
    /** a serve that couldn't be read, and why */
    fun noRead(serve: Int, why: String) {
        serveLabel.text = "$source %02d · NO READ".format(serve)
        bigText.text = "----"; typeText.text = ""; scale.rpm = null; showSpin(null)
        coachText.text = "Couldn't read it: $why"
        remember("%02d  ----  NO READ".format(serve))
    }
    /** setting up (before Start): the on-device check's advice; the state line names it */
    fun advice(short: String, text: String, good: Boolean) {
        state("Setup · $short"); tone(if (good) Hud.CYAN else Hud.AMBER)
        if (!aiTalking) coachText.text = text
    }
    private var aiLine = StringBuilder()
    private var aiTalking = false
    /** the AI coach's words as they come (a "\n" ends its turn) */
    fun aiText(t: String) {
        if (t == "\n") { aiTalking = false; aiLine = StringBuilder(); return }
        aiTalking = true; aiLine.append(t); coachText.text = "AI COACH  ${aiLine.toString().trim()}"
    }
    fun aiState(on: Boolean) { aiButton.text = if (on) "AI COACH: ON" else "AI COACH: OFF"; if (!on) aiTalking = false }

    /** a video test started: the file's name, while its frames are read */
    fun testStarted(name: String) {
        source = "VIDEO TEST · SERVE"
        serveLabel.text = "VIDEO TEST"
        bigText.text = "····"; typeText.text = "READING THE VIDEO"; scale.rpm = null
        coachText.text = name
    }
    /** a video test that found no serve, and what it saw instead */
    fun testFoundNone(why: String) {
        serveLabel.text = "VIDEO TEST"
        bigText.text = "NONE"; typeText.text = "NO SERVE FOUND"; scale.rpm = null
        coachText.text = why
    }
    /** the spin ball, and the one line that says how to read it */
    private fun showSpin(spin: Spin?) {
        if (spin == null) {
            spinBall.clear(); spinBox.visibility = android.view.View.GONE; spinNote.text = ""
            return
        }
        spinBox.visibility = android.view.View.VISIBLE
        spinBall.show(spin.lift, spin.curve, spin.gyro, spin.dots)
        // without the dots the engine cannot tell front from back, so the ring is drawn without
        // its chevrons and we say so rather than pointing the wrong way
        spinNote.text = if (spin.dots) "FROM BEHIND YOU\nSLOWED 55×" else "FROM BEHIND YOU\nDRILL WAY UNKNOWN"
    }

    private fun remember(line: String) {
        history.addFirst(line); while (history.size > 8) history.removeLast()
        historyText.text = history.joinToString("\n")
    }
}
