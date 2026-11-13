package dev.tischbein.pulse.coaching

import kotlin.math.roundToInt

/** HRmax estimate: Tanaka formula, `208 - 0.7 * age` (26 years -> 190). */
fun estimateHrMax(age: Int, config: CoachingConfig = CoachingConfig()): Int =
    (config.tanakaInterceptBpm - config.tanakaAgeFactor * age).roundToInt()

/**
 * Learns a higher HRmax from what the session actually shows.
 *
 * Rule (from the brief): if the smoothed HR stays above the current HRmax for at least
 * [sustainMs] (5 s), HRmax is raised. It is never lowered automatically (only the user can
 * lower it manually): one easy week must not shrink your zones.
 *
 * The new value is the highest HR that was held for the whole sustain window, i.e. the
 * minimum of the smoothed HR over the last 5 s. Example: 191, 194, 196, 195, 193, 192 over
 * 5 s gives 191, not the 196 peak. That keeps a brief peak from inflating HRmax.
 *
 * A gap in the smoothed HR (null) breaks the "sustained" run and starts it over.
 */
class HrMaxLearner(initialHrMax: Int, private val sustainMs: Long) {
    var hrMax: Int = initialHrMax
        private set

    private val window = ArrayDeque<Pair<Long, Int>>()

    /** Feed the current smoothed HR. Returns true if HRmax was raised by this call. */
    fun update(nowMs: Long, smoothedHr: Int?): Boolean {
        if (smoothedHr == null) {
            window.clear()
            return false
        }
        window.addLast(nowMs to smoothedHr)
        // Drop old entries, but keep the newest entry at or before the window start: it
        // proves the run has lasted the whole window.
        while (window.size >= 2 && window[1].first <= nowMs - sustainMs) {
            window.removeFirst()
        }
        val coversWholeWindow = window.first().first <= nowMs - sustainMs
        if (!coversWholeWindow) return false
        val heldForWholeWindow = window.minOf { it.second }
        if (heldForWholeWindow > hrMax) {
            hrMax = heldForWholeWindow
            return true
        }
        return false
    }
}
