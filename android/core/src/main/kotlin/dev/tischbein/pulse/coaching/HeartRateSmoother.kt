package dev.tischbein.pulse.coaching

/**
 * Rolling median of heart rate over a time window (5 s by default).
 *
 * WHY a median and not an average: a single wrong reading (e.g. 230 bpm from a bad beat)
 * pulls an average up noticeably, but does not move a median at all. Coaching decisions
 * (zones, corrections, HRmax learning) all use this smoothed value, so one noisy beat can
 * never trigger a cue.
 *
 * Gaps: only real readings are added. If too few readings are inside the window (sensor
 * gap), [valueAt] returns null ("unknown") instead of guessing.
 */
class HeartRateSmoother(
    private val windowMs: Long,
    private val minSamples: Int,
) {
    private val readings = ArrayDeque<Pair<Long, Int>>()

    fun add(timestampMs: Long, hr: Int) {
        readings.addLast(timestampMs to hr)
    }

    /** Median of readings in the window (nowMs - windowMs, nowMs], or null if too few. */
    fun valueAt(nowMs: Long): Int? {
        while (readings.isNotEmpty() && readings.first().first <= nowMs - windowMs) {
            readings.removeFirst()
        }
        val values = readings.filter { it.first <= nowMs }.map { it.second }.sorted()
        if (values.size < minSamples || values.isEmpty()) return null
        val middle = values.size / 2
        return if (values.size % 2 == 1) {
            values[middle]
        } else {
            // Even count: average the two middle values, rounding half up.
            (values[middle - 1] + values[middle] + 1) / 2
        }
    }
}
