package dev.tischbein.pulse.sensor.sim

import dev.tischbein.pulse.coaching.FourByFourConfig
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.random.Random

/** Scripted workouts the simulator can play. */
enum class SimProfile {
    /** A well-executed 4x4: HR settles high in the work zone every interval, good recovery. */
    GOOD_4X4,

    /** Strong first interval, then effort falls off: interval 2 lower, 3 and 4 below zone, slower recovery. */
    FADING_4X4,

    /** Like GOOD_4X4, but the sensor drops out for 20 s in the middle of interval 2. */
    SENSOR_DROPOUT,

    /** Steady easy run whose HR slowly drifts upward (cardiac drift). Runs until stopped. */
    EASY_WITH_DRIFT,
}

/** One simulated reading. [signalLost] = the sensor is out of range: no reading at all. */
data class SimPoint(val hr: Int?, val rrMs: List<Int>, val signalLost: Boolean)

/**
 * The deterministic heart behind the simulator, separate from any coroutine or clock so it
 * can be tested directly. Call [step] with increasing elapsed times (ms since the sensor
 * connected); the same profile and seed always produce the same readings.
 *
 * Model: HR moves toward an effort-dependent target like a real heart, with a lag:
 *   hr += (target - hr) * (1 - e^(-dt / tau))
 * A shorter tau going up (ramp, ~30 s) than coming down (recovery, 45-70 s). On top of that
 * +-2 bpm noise, and every ~2.5 minutes a single bogus reading 35 bpm too high (like a real
 * strap's occasional bad beat), which the coaching engine's median smoothing must ignore.
 *
 * The 4x4 scripts follow the timings in [protocol], so the simulated effort changes exactly
 * when the coach changes phase (the user "does what the coach says").
 *
 * All numbers below are simulator script values, not coaching thresholds.
 */
class SimulatedHeartModel(
    private val profile: SimProfile,
    private val hrMax: Int,
    private val protocol: FourByFourConfig = FourByFourConfig(),
    seed: Long = 1L,
) {
    private val random = Random(seed)
    private var hr: Double = REST_FRACTION * hrMax
    private var lastElapsedMs = 0L

    fun step(elapsedMs: Long): SimPoint {
        val dtMs = (elapsedMs - lastElapsedMs).coerceAtLeast(0)
        lastElapsedMs = elapsedMs

        val target = targetFraction(elapsedMs) * hrMax
        val tau = if (target > hr) RAMP_TAU_MS else recoveryTauMs()
        hr += (target - hr) * (1 - exp(-dtMs.toDouble() / tau))

        if (isDropout(elapsedMs)) return SimPoint(hr = null, rrMs = emptyList(), signalLost = true)

        val trueHr = hr + random.nextInt(-NOISE_BPM, NOISE_BPM + 1)
        val second = elapsedMs / MS_PER_SECOND
        val isBadBeat = second > 0 && second % BAD_BEAT_EVERY_S == 0L
        val reportedHr = (if (isBadBeat) trueHr + BAD_BEAT_EXTRA_BPM else trueHr).roundToInt()
        return SimPoint(hr = reportedHr, rrMs = rrIntervals(trueHr), signalLost = false)
    }

    /**
     * Plausible RR intervals for one ~1 s reading: about hr/60 beats, each close to
     * 60000/hr ms with a little beat-to-beat variation.
     */
    private fun rrIntervals(trueHr: Double): List<Int> {
        val meanRr = MS_PER_MINUTE / trueHr
        val beats = (trueHr / SECONDS_PER_MINUTE).roundToInt().coerceAtLeast(1)
        return List(beats) { (meanRr + random.nextInt(-RR_JITTER_MS, RR_JITTER_MS + 1)).roundToInt() }
    }

    private fun recoveryTauMs(): Double = if (profile == SimProfile.FADING_4X4) SLOW_RECOVERY_TAU_MS else RECOVERY_TAU_MS

    /** Effort target as a fraction of HRmax at this moment of the script. */
    private fun targetFraction(elapsedMs: Long): Double {
        if (profile == SimProfile.EASY_WITH_DRIFT) {
            return EASY_START_FRACTION + EASY_DRIFT_FRACTION_PER_HOUR * elapsedMs / MS_PER_HOUR
        }
        var t = elapsedMs
        if (t < protocol.warmupMs) return WARMUP_FRACTION
        t -= protocol.warmupMs
        for (i in 0 until protocol.intervalCount) {
            if (t < protocol.workMs) return workFractions().getOrElse(i) { workFractions().last() }
            t -= protocol.workMs
            if (t < protocol.recoveryMs) return RECOVERY_FRACTION
            t -= protocol.recoveryMs
        }
        return COOLDOWN_FRACTION
    }

    private fun workFractions(): List<Double> =
        when (profile) {
            SimProfile.FADING_4X4 -> FADING_WORK_FRACTIONS
            else -> GOOD_WORK_FRACTIONS
        }

    /** SENSOR_DROPOUT: no signal for 20 s starting 2 minutes into interval 2. */
    private fun isDropout(elapsedMs: Long): Boolean {
        if (profile != SimProfile.SENSOR_DROPOUT) return false
        val start = protocol.warmupMs + protocol.workMs + protocol.recoveryMs + DROPOUT_INTO_WORK_MS
        return elapsedMs >= start && elapsedMs < start + DROPOUT_LENGTH_MS
    }

    private companion object {
        const val MS_PER_SECOND = 1_000L
        const val MS_PER_MINUTE = 60_000.0
        const val MS_PER_HOUR = 3_600_000.0
        const val SECONDS_PER_MINUTE = 60.0

        const val REST_FRACTION = 0.40
        const val WARMUP_FRACTION = 0.66
        const val RECOVERY_FRACTION = 0.62
        const val COOLDOWN_FRACTION = 0.55
        val GOOD_WORK_FRACTIONS = listOf(0.935, 0.94, 0.945, 0.945)
        val FADING_WORK_FRACTIONS = listOf(0.935, 0.875, 0.85, 0.82)

        const val EASY_START_FRACTION = 0.62
        const val EASY_DRIFT_FRACTION_PER_HOUR = 0.08

        const val RAMP_TAU_MS = 28_000.0
        const val RECOVERY_TAU_MS = 45_000.0
        const val SLOW_RECOVERY_TAU_MS = 70_000.0

        const val NOISE_BPM = 2
        const val RR_JITTER_MS = 15
        const val BAD_BEAT_EVERY_S = 157L
        const val BAD_BEAT_EXTRA_BPM = 35

        const val DROPOUT_INTO_WORK_MS = 120_000L
        const val DROPOUT_LENGTH_MS = 20_000L
    }
}
