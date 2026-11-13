package dev.tischbein.pulse.coaching

/*
 * Plain data types going in and out of the coaching engine.
 *
 * The `wireName` values are the exact strings of docs/session-format.md, so Milestone 2 can
 * serialize a [SessionResult] without a translation table.
 */

enum class SessionMode(val wireName: String) {
    INTERVAL_4X4("interval_4x4"),
    EASY("easy"),
}

enum class PhaseKind(val wireName: String) {
    WARMUP("warmup"),
    WORK("work"),
    RECOVERY("recovery"),
    COOLDOWN("cooldown"),
    EASY("easy"),
}

/** Where the smoothed HR is relative to the current phase's target zone. */
enum class ZoneState {
    BELOW,
    IN,
    ABOVE,

    /** No smoothed HR available (start of session or sensor gap). Never counted as in or out. */
    UNKNOWN,
}

enum class EventType(val wireName: String) {
    PHASE_START("phase_start"),
    COUNTDOWN("countdown"),
    RAMP_NUDGE("ramp_nudge"),
    CORRECTION_HIGH("correction_high"),
    CORRECTION_LOW("correction_low"),
    INTERVAL_RECAP("interval_recap"),
    HRR60("hrr60"),
    MIDWAY("midway"),
    DRIFT("drift"),
    SENSOR_LOST("sensor_lost"),
    SENSOR_BACK("sensor_back"),
    SESSION_SUMMARY("session_summary"),
    HR_MAX_LEARNED("hr_max_learned"),
}

/**
 * Something the coach says. [text] is exactly what is spoken (and stored).
 * @property tMs milliseconds since the session started (wall-clock, pauses included).
 */
data class CoachingEvent(val tMs: Long, val type: EventType, val text: String)

/**
 * What the user chose when starting a session.
 * @property hrMax the HRmax used for all zones of this session (stored with the session).
 * @property easyTargetDurationMs optional target duration for easy mode; enables the
 *   halfway drift report. Ignored in 4x4 mode.
 */
data class SessionSetup(
    val mode: SessionMode,
    val hrMax: Int,
    val easyTargetDurationMs: Long? = null,
)

/** Snapshot for the live screen. Published by the engine after every input. */
data class CoachingState(
    val mode: SessionMode,
    val started: Boolean,
    val finished: Boolean,
    val paused: Boolean,
    val phaseKind: PhaseKind?,
    /** Time left in the current phase; null in easy mode without a target duration. */
    val phaseRemainingMs: Long?,
    /** Active (not paused) time since the session started. */
    val elapsedActiveMs: Long,
    /** Work interval number (1-based) during work and its recovery, else null. */
    val intervalNumber: Int?,
    val intervalCount: Int?,
    val smoothedHr: Int?,
    val zoneState: ZoneState,
    val targetLow: Int?,
    val targetHigh: Int?,
    val sensorLost: Boolean,
    val hrMaxUsed: Int,
    /** Highest HRmax seen so far (equals [hrMaxUsed] unless this session raised it). */
    val learnedHrMax: Int,
)

// ---- Session result (maps 1:1 onto docs/session-format.md) ----------------------------

/** One reading as recorded. [hr] null = no valid reading; values are never interpolated. */
data class RecordedSample(val tMs: Long, val hr: Int?, val rrMs: List<Int>)

/** A period without signal longer than the gap threshold (`gaps` in the session format). */
data class Gap(val startMs: Long, val endMs: Long)

/**
 * A pause the user made. Not in session-format v1 (which allows extra fields);
 * Milestone 2 can upload it as an extra `pauses` array.
 */
data class Pause(val startMs: Long, val endMs: Long)

data class PhaseRecord(
    val index: Int,
    val kind: PhaseKind,
    val startMs: Long,
    val endMs: Long,
    val targetLow: Int,
    val targetHigh: Int,
    val intervalNumber: Int?,
)

data class IntervalStats(
    val number: Int,
    /** Mean of valid readings during the interval (paused time excluded); null if none. */
    val avgHr: Int?,
    /** Highest smoothed HR during the interval (smoothed so one bad beat is not the "peak"). */
    val peakHr: Int?,
    /** Share of measured time with smoothed HR inside the work zone, 0-100, one decimal. */
    val pctInZone: Double,
    /** HR drop 60 s after the interval ended, bpm; null if it could not be measured. */
    val hrr60: Int?,
)

data class SessionSummary(
    /** Wall-clock duration from start to end, pauses included (matches started_at/ended_at). */
    val durationMs: Long,
    val avgHr: Int?,
    val maxHr: Int?,
    /** 4x4: time in the work zone during work intervals. Easy: time in the easy zone. */
    val timeInTargetMs: Long,
    /** Set when this session raised HRmax, else null. */
    val newHrMax: Int?,
    /** Easy mode cardiac drift in percent (one decimal), else null. */
    val driftPct: Double?,
)

data class SessionResult(
    val setup: SessionSetup,
    val config: CoachingConfig,
    val summary: SessionSummary,
    val samples: List<RecordedSample>,
    val gaps: List<Gap>,
    val pauses: List<Pause>,
    val phases: List<PhaseRecord>,
    val intervals: List<IntervalStats>,
    val events: List<CoachingEvent>,
)
