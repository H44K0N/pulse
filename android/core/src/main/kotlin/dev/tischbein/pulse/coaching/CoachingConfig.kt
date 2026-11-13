package dev.tischbein.pulse.coaching

import kotlin.math.roundToInt

/*
 * THE single place for every coaching threshold and protocol value.
 *
 * If you want to change how the coach behaves (durations, zones, how quickly it nags,
 * how long it waits), change a default here. No other file in the coaching engine contains
 * a tuning number. Milestone 2 will let some of these be overridden from Settings by
 * constructing a CoachingConfig with different values.
 *
 * All durations are in milliseconds. Zone values are fractions of HRmax (0.85 = 85 %).
 */

/** Unit conversions (not tuning values; kept here so the engine contains no bare numbers). */
const val MS_PER_SECOND: Long = 1_000L
const val MS_PER_MINUTE: Long = 60 * MS_PER_SECOND
const val MS_PER_HOUR: Long = 60 * MS_PER_MINUTE
const val PERCENT: Double = 100.0
const val TENTHS: Double = 10.0

/** A heart-rate zone as fractions of HRmax, e.g. 0.85..0.95. */
data class ZonePercent(val low: Double, val high: Double) {
    fun lowBpm(hrMax: Int): Int = (hrMax * low).roundToInt()

    fun highBpm(hrMax: Int): Int = (hrMax * high).roundToInt()
}

data class CoachingConfig(
    // ---- Heart-rate signal processing -------------------------------------------------

    /**
     * Zones are judged on the median of the last 5 s of readings, not on single readings.
     * WHY: the strap occasionally reports one wildly wrong beat (movement, poor contact).
     * A median ignores a single outlier completely, so one noisy beat can never cause a cue.
     */
    val smoothingWindowMs: Long = 5 * MS_PER_SECOND,

    /**
     * Minimum readings inside the window before a smoothed value is reported. WHY: right
     * after a gap there may be just one reading, and a median of one reading is that
     * reading, outlier or not. With 3 readings one outlier is already outvoted.
     */
    val smoothingMinSamples: Int = 3,

    /**
     * No valid reading for longer than this = "Sensor signal lost" (brief: more than 10 s).
     * Shorter dropouts are normal Bluetooth hiccups and are not announced or recorded as gaps.
     */
    val sensorGapThresholdMs: Long = 10 * MS_PER_SECOND,

    /** How often the runner ticks the engine when no samples arrive (timers must still run). */
    val tickIntervalMs: Long = MS_PER_SECOND,

    /** Heart-rate values at or below this are treated as "no reading" (the H10 reports 0 without contact). */
    val invalidHrAtOrBelow: Int = 0,

    // ---- HRmax -----------------------------------------------------------------------

    /** Tanaka formula: HRmax = 208 - 0.7 * age. Better than "220 - age" for adults. */
    val tanakaInterceptBpm: Double = 208.0,
    val tanakaAgeFactor: Double = 0.7,

    /**
     * HRmax learning: the smoothed HR must stay above the current HRmax for at least this
     * long before HRmax is raised. The new value is the highest HR that was held for the
     * whole window (a rolling 5 s minimum), so a brief spike cannot inflate HRmax.
     */
    val hrMaxSustainMs: Long = 5 * MS_PER_SECOND,

    val fourByFour: FourByFourConfig = FourByFourConfig(),
    val easy: EasyConfig = EasyConfig(),
)

/** The 4x4 interval protocol (Norwegian 4x4) and its coaching rules. */
data class FourByFourConfig(
    // ---- Protocol ------------------------------------------------------------------------
    val warmupMs: Long = 10 * MS_PER_MINUTE,
    val workMs: Long = 4 * MS_PER_MINUTE,
    val recoveryMs: Long = 3 * MS_PER_MINUTE,
    val cooldownMs: Long = 5 * MS_PER_MINUTE,
    val intervalCount: Int = 4,

    val warmupZone: ZonePercent = ZonePercent(0.60, 0.70),
    val workZone: ZonePercent = ZonePercent(0.85, 0.95),
    val recoveryZone: ZonePercent = ZonePercent(0.60, 0.70),

    /** The brief gives no cooldown target. 50-65 % = "walk it off"; it is only displayed, never coached. */
    val cooldownZone: ZonePercent = ZonePercent(0.50, 0.65),

    // ---- Phase cues --------------------------------------------------------------------

    /** Spoken "3", "2", "1" before each work interval, one number per [countdownStepMs]. */
    val countdownFrom: Int = 3,
    val countdownStepMs: Long = MS_PER_SECOND,

    // ---- Corrections -------------------------------------------------------------------

    /**
     * Phases in which "Above zone / Below zone" corrections are spoken. Only work phases.
     * WHY: at the start of recovery HR is always above the recovery zone (it lags effort),
     * and at the start of warmup it is always below. Correcting those would nag every
     * single session for something the user cannot and should not change. The zones of the
     * other phases are still shown on screen and used for stats.
     */
    val correctedPhases: Set<PhaseKind> = setOf(PhaseKind.WORK),

    /**
     * Ramp grace: no "below zone" complaints during the first 90 s of a work interval.
     * WHY: HR needs 1-2 minutes to climb after effort goes up; being low early is expected.
     */
    val rampGraceMs: Long = 90 * MS_PER_SECOND,

    /**
     * At the end of the ramp grace, "Push a bit harder." is said only if HR is CLEARLY low:
     * more than this many bpm below the bottom of the work zone. WHY: 1-4 bpm under the
     * zone at 90 s usually means HR is still climbing and will get there on its own; the
     * normal 15 s correction rule catches it if it does not.
     */
    val rampNudgeClearlyLowMarginBpm: Int = 5,

    /** Out of zone continuously for this long (after grace) before a correction is spoken. */
    val correctionAfterMs: Long = 15 * MS_PER_SECOND,

    /** Minimum time between two corrections (the ramp nudge counts as one). WHY: no cue spam. */
    val correctionCooldownMs: Long = 45 * MS_PER_SECOND,

    // ---- Interval recap, HRR60, midway --------------------------------------------------

    /**
     * HRR60 = smoothed HR when the work interval ends minus smoothed HR this long after.
     * A bigger drop means better recovery. Measured on wall-clock time (HR keeps
     * recovering during a pause), so the measurement and its cue happen [hrrDelayMs] after
     * the interval ended even if the user paused in between.
     */
    val hrrDelayMs: Long = 60 * MS_PER_SECOND,

    /**
     * Speak HRR60 as its own cue ("Recovery: down 34 beats.") at the moment it is measured,
     * i.e. [hrrDelayMs] into each recovery, for every interval. WHY its own cue and not part
     * of the recap: the recap is spoken at the START of recovery, when the HRR60 of the
     * interval just finished does not exist yet. A separate cue reports the fresh value
     * instead of the previous interval's. No cue when HRR60 could not be measured (sensor
     * gap) or the session ended first. Set to false to only record it.
     */
    val speakHrr60: Boolean = true,

    /** Midway feedback is spoken after this interval, comparing it with the one before. */
    val midwayAfterInterval: Int = 2,

    /** Midway says "fading" if average HR dropped by MORE than this many bpm... */
    val midwayFadingAvgDropBpm: Int = 3,

    /** ...or if time in zone dropped by MORE than this many percentage points. */
    val midwayFadingPctInZoneDrop: Double = 15.0,

    /**
     * If not fading, midway says "room to push" when the interval's average HR sits in the
     * bottom part of the work zone: below low + this fraction of the zone width. (The
     * average includes the ramp-up, so it always reads a few bpm under the plateau.)
     */
    val midwayRoomToPushZoneFraction: Double = 0.35,
)

/** Easy / long session mode: one free-running phase. */
data class EasyConfig(
    val zone: ZonePercent = ZonePercent(0.60, 0.70),

    /** Out of zone continuously for this long before a correction (longer than 4x4: easy pace drifts slowly). */
    val correctionAfterMs: Long = 30 * MS_PER_SECOND,

    /** Minimum time between corrections in easy mode. */
    val correctionCooldownMs: Long = 90 * MS_PER_SECOND,

    /**
     * No "below zone" complaints for the first 3 minutes. WHY: the session starts from
     * resting HR, which is always below the easy zone; same reasoning as the 4x4 ramp grace.
     */
    val startGraceMs: Long = 3 * MS_PER_MINUTE,

    /**
     * Cardiac drift ignores the first 5 minutes. WHY: those minutes include the climb from
     * resting HR, which would make the first-half average artificially low and the drift
     * artificially high. Drift = (avg HR second half - avg HR first half) / first half, in %.
     */
    val driftIgnoreFirstMs: Long = 5 * MS_PER_MINUTE,

    /** Drift is not reported unless at least this much time (after the ignored start) is available. */
    val driftMinWindowMs: Long = 5 * MS_PER_MINUTE,
)
