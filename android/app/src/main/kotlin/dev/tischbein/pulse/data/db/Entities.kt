package dev.tischbein.pulse.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/*
 * Room tables. They mirror docs/session-format.md: one row in `sessions` per session, child
 * tables for the arrays. All `*Ms` times inside a session are milliseconds since the session
 * started (the `t_ms` of the contract). Enum-like columns store the contract's wire strings
 * ("interval_4x4", "work", "phase_start", ...), so the database is readable with plain sqlite3.
 *
 * Child rows are deleted automatically with their session (ForeignKey CASCADE).
 *
 * CHANGING THE SCHEMA: bump the version in PulseDatabase and add a migration. The exported
 * schema JSON in app/schemas/ shows exactly what the previous version looked like.
 */

/** Lifecycle of a stored session (column `sessions.status`). */
object SessionStatus {
    /** Being recorded right now (or the app died while recording; see SessionRepository.closeAbandonedSessions). */
    const val RECORDING = "recording"

    /** Finished normally; every table holds the engine's final SessionResult. */
    const val COMPLETE = "complete"

    /** The app was killed mid-session. Samples/events/phases up to the last flush are kept. */
    const val INCOMPLETE = "incomplete"
}

object DeviceKind {
    const val POLAR_H10 = "polar_h10"
    const val SIMULATOR = "simulator"
}

@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey val id: String,
    val mode: String,
    val status: String,
    val startedAtEpochMs: Long,
    val endedAtEpochMs: Long?,
    val hrMaxUsed: Int,
    val deviceKind: String,
    val deviceId: String?,
    /** The `settings` object of the contract, as JSON text (protocol and zones in effect). */
    val settingsJson: String,
    // Summary: null while recording.
    val durationMs: Long? = null,
    val avgHr: Int? = null,
    val maxHr: Int? = null,
    val timeInTargetMs: Long? = null,
    val newHrMax: Int? = null,
    val driftPct: Double? = null,
    // Digest for the Progress page and the session list (schema version 2): computed when the
    // session is saved, so listing sessions never has to read their samples. See SessionDigest.
    /** 4x4: mean HRR60 of the intervals where it was measured. */
    val avgHrr60: Double? = null,
    /** 4x4: mean of the work intervals' average HR. */
    val avgWorkHr: Double? = null,
    /** 4x4: mean % in zone of the work intervals. */
    val workPctInZone: Double? = null,
    /** Downsampled HR, "120,121,,130" (empty = gap); null while recording. */
    val sparkline: String? = null,
)

@Entity(
    tableName = "samples",
    foreignKeys = [ForeignKey(SessionEntity::class, ["id"], ["sessionId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("sessionId", "tMs")],
)
data class SampleEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val sessionId: String,
    val tMs: Long,
    /** null = no valid reading (sensor gap / no skin contact). Never interpolated. */
    val hr: Int?,
    /** RR intervals in ms, comma separated ("652,648"), empty string for none. */
    val rrMs: String,
)

@Entity(
    tableName = "events",
    foreignKeys = [ForeignKey(SessionEntity::class, ["id"], ["sessionId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("sessionId")],
)
data class EventEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val sessionId: String,
    val tMs: Long,
    val type: String,
    val text: String,
)

@Entity(
    tableName = "phases",
    primaryKeys = ["sessionId", "phaseIndex"],
    foreignKeys = [ForeignKey(SessionEntity::class, ["id"], ["sessionId"], onDelete = ForeignKey.CASCADE)],
)
data class PhaseEntity(
    val sessionId: String,
    val phaseIndex: Int,
    val kind: String,
    val startMs: Long,
    /** null while the phase is still running (or when the app died during it). */
    val endMs: Long?,
    val targetLow: Int,
    val targetHigh: Int,
    val intervalNumber: Int?,
)

@Entity(
    tableName = "intervals",
    primaryKeys = ["sessionId", "number"],
    foreignKeys = [ForeignKey(SessionEntity::class, ["id"], ["sessionId"], onDelete = ForeignKey.CASCADE)],
)
data class IntervalEntity(
    val sessionId: String,
    val number: Int,
    val avgHr: Int?,
    val peakHr: Int?,
    val pctInZone: Double,
    val hrr60: Int?,
)

@Entity(
    tableName = "gaps",
    foreignKeys = [ForeignKey(SessionEntity::class, ["id"], ["sessionId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("sessionId")],
)
data class GapEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val sessionId: String,
    val startMs: Long,
    val endMs: Long,
)

/** User pauses. Not in session-format v1; exported as the extra `pauses` array. */
@Entity(
    tableName = "pauses",
    foreignKeys = [ForeignKey(SessionEntity::class, ["id"], ["sessionId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("sessionId")],
)
data class PauseEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val sessionId: String,
    val startMs: Long,
    val endMs: Long,
)
