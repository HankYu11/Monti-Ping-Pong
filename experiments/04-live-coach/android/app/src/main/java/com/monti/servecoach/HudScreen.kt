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
class HudScreen(private val ctx: Context, onStart: () -> Unit, onTest: () -> Unit, onReport: () -> Unit, onAi: () -> Unit = {}, onNext: () -> Unit = {},
                onWindows: () -> Unit = {}) {
    private fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).roundToInt()
    private val mono = Hud.mono(ctx); private val monoMed = Hud.mono(ctx, medium = true)
    private fun tv(size: Float, face: Typeface, color: Int = Hud.CYAN, spacing: Float = 0f) = TextView(ctx).apply {
        textSize = size; typeface = face; setTextColor(color); letterSpacing = spacing; includeFontPadding = false
    }

    val root = FrameLayout(ctx).apply { setBackgroundColor(Color.BLACK) }
    val preview = SurfaceView(ctx)
    val frame = HudFrame(ctx)
    // 0.13.22 (review 16 Nov): sized for the table, 2-3 m away, not for a hand. The state line was
    // 12.5 sp, the coaching line 12, the qualifier 10.5; only the number could be read.
    // one line that shrinks (down to 12 sp) rather than run into the readout column on the right
    val stateText = tv(20f, monoMed, Hud.CYAN, 0.1f).apply { text = "● STARTING…"; maxLines = 1
        setAutoSizeTextTypeUniformWithConfiguration(12, 20, 1, android.util.TypedValue.COMPLEX_UNIT_SP) }
    val serveLabel = tv(13f, mono, Hud.DIM, 0.18f).apply { text = "SPIN RATE" }
    // a 400 dp column leaves about 270 dp beside the 112 dp ball: room for 64 sp
    val bigText = tv(64f, Hud.display(ctx, 300), Hud.CYAN).apply { text = "----"; setShadowLayer(dp(12).toFloat(), 0f, 0f, Color.argb(150, 95, 242, 255)) }
    /** the outcome, the beginner's line: "STRONG BACKSPIN". Shrinks to fit rather than wrap. */
    val typeText = tv(28f, Hud.display(ctx, 700), Hud.WHITE, 0.06f).apply {
        maxLines = 1
        setAutoSizeTextTypeUniformWithConfiguration(16, 28, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
    }
    val qualText = tv(14f, mono, Hud.DIM, 0.04f)
    val scale = SpinScale(ctx)
    val spinBall = SpinBall(ctx)
    val split = SpinSplit(ctx)
    /** setup advice, the AI coach, a video test's progress, or why a serve could not be read */
    val coachText = tv(16f, mono, Color.argb(235, 235, 248, 252)).apply {
        text = "Checking the setup…"; setLineSpacing(0f, 1.15f)
        setPadding(dp(12), dp(9), dp(12), dp(10))
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = dp(6).toFloat(); setColor(Color.argb(150, 0, 20, 30)); setStroke(dp(1), Color.argb(90, 95, 242, 255))
        }
    }
    /** the screen-wide "ready" flash: a border round the whole picture, seen from across the table */
    private val flash = View(ctx).apply {
        alpha = 0f
        background = android.graphics.drawable.GradientDrawable().apply { setColor(Color.TRANSPARENT); setStroke(dp(10), Hud.CYAN) }
    }
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
    lateinit var windowsButton: TextView; private set
    private lateinit var bottomLeft: LinearLayout
    private lateinit var col: LinearLayout
    /** test video, report, chip windows: the developer's tools, hidden until the version line is long-pressed */
    private lateinit var devTools: LinearLayout
    /** the long-press developer row is open: error toasts show only then */
    val devMode get() = devTools.visibility == View.VISIBLE
    private lateinit var ballRow: LinearLayout
    /** what a reading is labelled with: "SERVE", or "VIDEO TEST · SERVE" while testing with a video */
    var source = "SERVE"

    init {
        root.addView(preview, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(frame, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(flash, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(stateText, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(30), Gravity.TOP or Gravity.START).apply { leftMargin = dp(40); topMargin = dp(20); rightMargin = dp(400 + 16) })

        col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.START
            setPadding(dp(8), dp(22), dp(36), dp(84))
        }
        col.addView(serveLabel)
        // the ball and the number side by side: one glance instead of two, and the scale tucks
        // under the number, which is the only thing it gives context to
        val numbers = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        numbers.addView(bigText)
        numbers.addView(scale, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        ballRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(spinBall, LinearLayout.LayoutParams(dp(112), ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(numbers, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(10) })
        }
        col.addView(ballRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        col.addView(typeText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(36)).apply { topMargin = dp(8) })
        col.addView(qualText)
        col.addView(split, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        col.addView(coachText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        root.addView(col, FrameLayout.LayoutParams(dp(400), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END))

        root.addView(startBtn, FrameLayout.LayoutParams(dp(176), dp(48), Gravity.BOTTOM or Gravity.END).apply { rightMargin = dp(36); bottomMargin = dp(24) })
        root.addView(nextBtn, FrameLayout.LayoutParams(dp(160), dp(48), Gravity.BOTTOM or Gravity.START).apply { leftMargin = dp(40 + 176 + 14); bottomMargin = dp(24) })
        fun small(label: String, onTap: () -> Unit) = TextView(ctx).apply {
            text = label; textSize = 11f; typeface = monoMed; letterSpacing = 0.14f; setTextColor(Hud.CYAN)
            setPadding(dp(16), dp(8), dp(16), dp(8)); background = HexDrawable(ctx.resources.displayMetrics.density).apply { color = Hud.DIM }
            setOnClickListener { onTap() }
        }
        // All three are used before a session and after it, never during: a serve is the only
        // thing on the screen while one runs.
        // it helps place the phone, and only before Start: "AI COACH" promised more (review 16 Nov)
        aiButton = small("SETUP ASSISTANT: OFF", onAi)
        // cutting the ball's window on the chip as the serve happens, instead of decoding the
        // serve again afterwards to cut it: new, so it is a choice and not yet the way it works
        windowsButton = small("CHIP WINDOWS: OFF", onWindows)
        val tools = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        tools.addView(aiButton)
        // the developer's tools: out of a player's (and a demo's) way, a long press on the version
        // line away for whoever needs them
        devTools = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; visibility = View.GONE }
        devTools.addView(small("TEST VIDEO", onTest))
        devTools.addView(small("REPORT", onReport), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(10) })
        devTools.addView(windowsButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(10) })
        statsText.setOnLongClickListener { devTools.visibility = if (devTools.visibility == View.VISIBLE) View.GONE else View.VISIBLE; true }
        bottomLeft = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(statsText); addView(tools, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
            addView(devTools, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        }
        root.addView(bottomLeft, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START).apply { leftMargin = dp(40); bottomMargin = dp(24) })
        showSpin(null)
    }

    fun state(s: String) { stateText.text = "● " + s.uppercase(Locale.US) }
    /** a serve is being measured: hold the ball still so it isn't drawing while the engine works */
    fun busy(on: Boolean) { spinBall.hold(on) }
    /** the HUD's colour for what is happening: cyan ready, amber measuring, red a problem */
    fun tone(c: Int) { stateText.setTextColor(c); frame.color = c }
    /** ready for the next serve: the border flashes once, bright enough to see from the table */
    fun readyCue() {
        flash.animate().cancel()
        flash.alpha = 0f
        flash.animate().alpha(1f).setDuration(140).withEndAction { flash.animate().alpha(0f).setStartDelay(260).setDuration(700) }
    }
    fun running(on: Boolean) {
        // while serving, STOP and NEXT SERVE move to the bottom left (where the tools were), so the
        // readout has the whole height of its column
        (startBtn.layoutParams as FrameLayout.LayoutParams).apply {
            gravity = Gravity.BOTTOM or (if (on) Gravity.START else Gravity.END)
            leftMargin = if (on) dp(40) else 0; rightMargin = if (on) 0 else dp(36)
        }.also { startBtn.layoutParams = it }
        col.setPadding(dp(8), dp(22), dp(36), if (on) dp(14) else dp(84))
        startBtn.text = if (on) "STOP" else "START"
        val c = if (on) Hud.RED else Hud.CYAN
        startFace.color = c; startBtn.setTextColor(c)
        nextBtn.visibility = if (on) View.VISIBLE else View.GONE
        // while serving, the screen carries the serve and nothing else: the tools and the stats
        // are both for the other side of a session
        bottomLeft.visibility = if (on) View.GONE else View.VISIBLE
    }

    /** a serve read: its number, RPM (to 10), what the spin is, any warning, and the spin to draw */
    fun reading(serve: Int, rpm: Int, type: String, coach: String, spin: Spin? = null, kmh: Int? = null) {
        val t = type.lowercase(Locale.US)
        // The speed goes beside the serve's name rather than under the spin: 0.7.9 showed two
        // numbers the same size and the player called it "kinda confusing", and the one big
        // number is still the spin.
        serveLabel.text = "$source %02d".format(serve) + (kmh?.let { " · $it km/h" } ?: "")
        bigText.text = SpannableString("$rpm RPM").apply { setSpan(RelativeSizeSpan(0.3f), "$rpm".length, length, 0) }
        // the outcome in two words, strength and kind (0.13.22): the beginner's line, and what the
        // voice says. 0.10 had dropped the strength here as the scale showed it, but a scale is
        // read, and from the table a word is what lands.
        typeText.text = SpinLevel.outcome(rpm, t).uppercase(Locale.US)
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
        aiTalking = true; aiLine.append(t); message("ASSISTANT  ${aiLine.toString().trim()}")
    }
    fun aiState(on: Boolean) { aiButton.text = if (on) "SETUP ASSISTANT: ON" else "SETUP ASSISTANT: OFF"; if (!on) aiTalking = false }
    fun windowsState(on: Boolean) { windowsButton.text = if (on) "CHIP WINDOWS: ON" else "CHIP WINDOWS: OFF" }

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
