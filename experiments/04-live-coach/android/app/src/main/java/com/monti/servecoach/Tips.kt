package com.monti.servecoach

/**
 * Which coaching tip to give, and when (PLAN.md: the outcome every serve, one cue now and then;
 * 0.13.21 said a sentence a serve). Every [every] readable serves it gives the tip for the fault
 * seen most often since the last tip, or none if they were all good, and starts counting afresh.
 *
 * Lived in MainActivity until 0.13.22, where nothing could test it; moved out unchanged.
 */
class TipPicker(private val every: Int = 3) {
    private var since = 0
    private val faults = HashMap<String, Int>()

    /** a new session: nothing counted */
    fun reset() { since = 0; faults.clear() }

    /** this serve's fault counted ([kind] is the coach's verdict: "thick", "slip", "slow", "good"
     *  or ""); on every [every]th serve, the commonest fault's (title, fix), or null */
    fun next(kind: String): Pair<String, String>? {
        if (kind.isNotEmpty() && kind != "good" && TITLES.containsKey(kind)) faults[kind] = (faults[kind] ?: 0) + 1
        if (++since < every) return null
        since = 0
        val worst = faults.maxByOrNull { it.value }?.key
        faults.clear()
        return worst?.let { TITLES[it] }
    }

    companion object {
        val TITLES = mapOf(
            "thick" to ("You pushed the ball instead of brushing it" to "Open the face and brush along under the ball."),
            "good" to ("Strong backspin" to "Keep this contact."),
            "slip" to ("The ball slid on the rubber" to "Let the ball drop lower and brush slightly into it."),
            "slow" to ("The brush was too slow" to "Accelerate through the contact: snap the wrist."),
        )
    }
}
