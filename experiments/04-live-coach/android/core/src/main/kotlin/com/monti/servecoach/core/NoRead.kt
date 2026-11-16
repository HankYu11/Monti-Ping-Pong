package com.monti.servecoach.core

import kotlin.math.abs

/**
 * Why a serve couldn't be read, in the player's words, and the setup change that would fix it.
 *
 * Until now an unreadable serve only said "Couldn't read that one", though the engine's result
 * already carried why: `issues` (engine.js diagnose()), `marking` and `toward` (the flight's angle
 * to the camera). A cause is only claimed when the result supports it; otherwise [of] gives null
 * and the old words stay.
 *
 * Checked against the 15 Nov 2026 sessions (NoReadTest): 21:44 #1, a half-black ball that kept its
 * black side to the camera, gives [Cause.MARKING]; 23:04 #3, flying at 85 degrees toward the camera,
 * gives [Cause.TOWARD]; 21:44 #4, where the capture window lagged the ball (an app fault, no setup
 * change fixes it), gives nothing.
 */
object NoRead {
    enum class Cause(val short: String, val fix: String) {
        /** the half-black ball showed one side all the way: the spin never showed */
        MARKING("the marking stayed facing the camera", "Use the dot-marked ball."),
        /** the ball flew along the camera's line of sight; [short] is for a ball flying toward it */
        TOWARD("it flew toward the camera", "Film from the side of its flight."),
        /** smeared by motion blur, or its marking faint: a slow shutter in a dim room */
        LIGHT("the ball was blurred or faint", "Add more light."),
        /** the ball too few pixels across to follow its marking */
        SMALL("the ball was too small", "Move the phone closer."),
        /** found in too few frames: it blended into what was behind it */
        LOST("the ball got lost in the background", "Put a plain wall behind its flight."),
    }

    /** a cause, with the words for this serve ([short] differs from the cause's for a ball flying away) */
    class Reason(val cause: Cause, val short: String) {
        val fix get() = cause.fix
        override fun toString() = "$cause: $short"
    }

    /**
     * Steeper than this to the camera and unread: the flight is the cause. Clear readings on 15 Nov
     * came at up to 61 degrees (21:45 #6), unclear ones at 65 and 85 (and 52, the window lag).
     */
    const val TOWARD_DEG = 63.0
    /**
     * Found in fewer than this share of the frames and unread: lost against the background. Clear
     * readings on 15 Nov still had the ball in 63-68% of frames, so the bar sits below them.
     */
    const val FOUND_SHARE = 0.6

    // the start of each engine.js diagnose() text matched here (NoReadTest checks they still exist there)
    const val SAME_SIDE = "The ball showed the same side all the way"
    const val BLUR = "The ball is smeared by motion blur"
    const val FAINT = "The black half is faint"
    const val SMALL_PX = "The ball is only "
    private val FOUND = Regex("""The ball was found in only (\d+) of (\d+) frames""")

    /**
     * The cause of an unclear reading, or null when the result doesn't support one.
     * [marking] "half" or "dots" (or null), [toward] degrees toward the camera (negative: away;
     * NaN when unknown), [issues] the engine's diagnose() texts, [error] the result's error.
     */
    fun of(marking: String?, toward: Double, issues: List<String>, error: String? = null): Reason? {
        if (!error.isNullOrEmpty()) return null                    // no flight measured: nothing to say about it
        if (marking == "half" && issues.any { it.startsWith(SAME_SIDE) }) return Reason(Cause.MARKING, Cause.MARKING.short)
        if (toward.isFinite() && abs(toward) >= TOWARD_DEG)
            return Reason(Cause.TOWARD, if (toward > 0) Cause.TOWARD.short else "it flew away from the camera")
        if (issues.any { it.startsWith(BLUR) || it.startsWith(FAINT) }) return Reason(Cause.LIGHT, Cause.LIGHT.short)
        if (issues.any { it.startsWith(SMALL_PX) && it.contains("px across") }) return Reason(Cause.SMALL, Cause.SMALL.short)
        for (t in issues) {
            val m = FOUND.find(t) ?: continue
            val (seen, of) = m.destructured
            if (of.toInt() > 0 && seen.toDouble() / of.toInt() < FOUND_SHARE) return Reason(Cause.LOST, Cause.LOST.short)
        }
        return null
    }

    /**
     * What to say and show for an unread serve, the fix said once per cause every [every] serves
     * (like the coaching tips): the cause alone in between, so the same advice isn't heard serve
     * after serve.
     */
    class Advisor(private val every: Int = 4) {
        private val lastFix = HashMap<Cause, Int>()
        private var serves = 0

        fun reset() { lastFix.clear(); serves = 0 }

        /** count a serve that was read (between advice) */
        fun read() { serves++ }

        /** an unread serve: (spoken, on screen). [r] null: the old words */
        fun next(r: Reason?): Pair<String, String> {
            serves++
            if (r == null) return "Couldn't read that one." to "Couldn't read it."
            val said = lastFix[r.cause]
            val fix = said == null || serves - said >= every
            if (fix) lastFix[r.cause] = serves
            return "Couldn't read that one: ${r.short}." + (if (fix) " ${r.fix}" else "") to
                "Couldn't read it: ${r.short}. ${r.fix}"
        }
    }
}
