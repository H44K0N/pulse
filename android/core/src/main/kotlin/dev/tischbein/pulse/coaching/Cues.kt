package dev.tischbein.pulse.coaching

import kotlin.math.roundToInt

/**
 * Every sentence the coach speaks. To change the wording of a cue, change it here.
 *
 * Style: short, plain English that Android TextToSpeech pronounces well. Numbers are
 * written as digits (TTS reads "172" as "one hundred seventy-two").
 */
object Cues {
    fun warmupStart(durationMs: Long) = "Warmup. ${spokenDuration(durationMs)}, easy pace."

    fun workStart(number: Int, count: Int) = "Interval $number of $count. Go."

    fun recoveryStart(durationMs: Long) = "Recover. ${spokenDuration(durationMs)} easy."

    fun cooldownStart(durationMs: Long) = "Cooldown. ${spokenDuration(durationMs)}, easy."

    fun easyStart(targetDurationMs: Long?) =
        if (targetDurationMs == null) "Easy session started." else "Easy session. ${spokenDuration(targetDurationMs)}."

    fun countdown(secondsLeft: Int) = "$secondsLeft"

    const val RAMP_NUDGE = "Push a bit harder."
    const val ABOVE_ZONE = "Above zone, ease off."
    const val BELOW_ZONE = "Below zone, pick it up."
    const val SENSOR_LOST = "Sensor signal lost."
    const val SENSOR_BACK = "Sensor back."

    /**
     * "Interval 2: average 172, 88 percent in zone." plus, when known, the HRR60 of the
     * previous interval (see [FourByFourConfig.recapHrrFromInterval] for why "previous").
     */
    fun intervalRecap(number: Int, avgHr: Int?, pctInZone: Double, previousHrr60: Int?): String {
        val main =
            if (avgHr == null) {
                "Interval $number: no heart rate data."
            } else {
                "Interval $number: average $avgHr, ${pctInZone.roundToInt()} percent in zone."
            }
        return if (previousHrr60 == null) main else "$main Last recovery: down $previousHrr60 beats in one minute."
    }

    fun midwayHolding(intervalsLeft: Int) = "Holding steady. Same again for the last $intervalsLeft."

    fun midwayFading(intervalsLeft: Int) = "Fading compared to the first interval. Dig in for the last $intervalsLeft."

    fun midwayRoomToPush(intervalsLeft: Int) = "Holding, with room to push. Go a bit harder on the last $intervalsLeft."

    fun driftHalfway(driftPct: Double?) =
        if (driftPct == null) "Halfway." else "Halfway. Cardiac drift so far ${driftPct.roundToInt()} percent."

    fun driftEnd(driftPct: Double) = "Cardiac drift ${driftPct.roundToInt()} percent."

    fun sessionSummary(durationMs: Long, avgHr: Int?, timeInTargetMs: Long): String {
        val minutes = durationMs / MS_PER_MINUTE
        val inTarget = timeInTargetMs / MS_PER_MINUTE
        val avg = if (avgHr == null) "" else " Average heart rate $avgHr."
        return "Session complete. $minutes ${plural(minutes, "minute")}.$avg $inTarget ${plural(inTarget, "minute")} in target zone."
    }

    fun hrMaxLearned(newHrMax: Int) = "New max heart rate recorded: $newHrMax."

    /** "10 minutes", "1 minute", "90 seconds". */
    fun spokenDuration(durationMs: Long): String =
        if (durationMs % MS_PER_MINUTE == 0L) {
            val minutes = durationMs / MS_PER_MINUTE
            "$minutes ${plural(minutes, "minute")}"
        } else {
            val seconds = durationMs / MS_PER_SECOND
            "$seconds ${plural(seconds, "second")}"
        }

    private fun plural(n: Long, word: String) = if (n == 1L) word else "${word}s"
}
