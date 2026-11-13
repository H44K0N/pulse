package dev.tischbein.pulse.data.export

import dev.tischbein.pulse.data.db.SessionStatus
import dev.tischbein.pulse.data.db.StoredSession
import dev.tischbein.pulse.data.decodeRr
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.time.Instant

/*
 * The session JSON document of docs/session-format.md, as Kotlin classes.
 *
 * kotlinx.serialization generates the JSON code at compile time from these classes; the
 * @SerialName values are the contract's field names. To add a field to the contract: add it
 * here (and to docs/session-format.md), and SessionDocumentTest checks the rest.
 *
 * Extra fields (allowed by the contract, "unknown extra fields must be accepted"):
 *   - `pauses`: user pauses [{start_ms, end_ms}].
 *   - `complete`: false if the app died mid-session (see SessionStatus.INCOMPLETE).
 */

const val SESSION_SCHEMA_VERSION = 1

@Serializable
data class SessionDocument(
    @SerialName("schema_version") val schemaVersion: Int = SESSION_SCHEMA_VERSION,
    val id: String,
    val mode: String,
    @SerialName("started_at") val startedAt: String,
    @SerialName("ended_at") val endedAt: String,
    @SerialName("hr_max_used") val hrMaxUsed: Int,
    val device: DeviceDoc,
    val settings: JsonObject,
    val summary: SummaryDoc,
    val samples: List<SampleDoc>,
    val gaps: List<SpanDoc>,
    val phases: List<PhaseDoc>,
    val intervals: List<IntervalDoc>,
    val events: List<EventDoc>,
    val pauses: List<SpanDoc>,
    val complete: Boolean,
)

@Serializable
data class DeviceDoc(val kind: String, val id: String?)

@Serializable
data class SummaryDoc(
    @SerialName("duration_ms") val durationMs: Long,
    @SerialName("avg_hr") val avgHr: Int?,
    @SerialName("max_hr") val maxHr: Int?,
    @SerialName("time_in_target_ms") val timeInTargetMs: Long,
    @SerialName("new_hr_max") val newHrMax: Int?,
    @SerialName("drift_pct") val driftPct: Double?,
)

@Serializable
data class SampleDoc(
    @SerialName("t_ms") val tMs: Long,
    val hr: Int?,
    @SerialName("rr_ms") val rrMs: List<Int>,
)

@Serializable
data class SpanDoc(
    @SerialName("start_ms") val startMs: Long,
    @SerialName("end_ms") val endMs: Long,
)

@Serializable
data class PhaseDoc(
    val index: Int,
    val kind: String,
    @SerialName("start_ms") val startMs: Long,
    @SerialName("end_ms") val endMs: Long,
    @SerialName("target_low") val targetLow: Int,
    @SerialName("target_high") val targetHigh: Int,
    @SerialName("interval_number") val intervalNumber: Int?,
)

@Serializable
data class IntervalDoc(
    val number: Int,
    @SerialName("avg_hr") val avgHr: Int?,
    @SerialName("peak_hr") val peakHr: Int?,
    @SerialName("pct_in_zone") val pctInZone: Double,
    val hrr60: Int?,
)

@Serializable
data class EventDoc(
    @SerialName("t_ms") val tMs: Long,
    val type: String,
    val text: String,
)

object SessionJson {
    /**
     * explicitNulls: write `"new_hr_max": null` instead of leaving the key out (the contract
     * shows every key). ignoreUnknownKeys: reading a document from a newer app version works.
     */
    val format =
        Json {
            encodeDefaults = true
            explicitNulls = true
            ignoreUnknownKeys = true
        }

    fun encode(document: SessionDocument): String = format.encodeToString(SessionDocument.serializer(), document)

    fun parseSettings(settingsJson: String): JsonObject = format.decodeFromString(JsonObject.serializer(), settingsJson)
}

/** ISO 8601 in UTC with a `Z` suffix, e.g. "2026-11-14T17:02:11.250Z" (millis only when non-zero). */
fun isoUtc(epochMs: Long): String = Instant.ofEpochMilli(epochMs).toString()

/**
 * Builds the upload document from the database rows.
 *
 * For an incomplete session (app died mid-session) the summary and the end of the last phase
 * were filled in from the recorded samples by SessionRepository.closeAbandonedSessions; any
 * value still missing falls back to the last recorded moment, never to an invented number.
 */
fun StoredSession.toDocument(): SessionDocument {
    val s = session
    val durationMs = s.durationMs ?: samples.maxOfOrNull { it.tMs } ?: 0L
    return SessionDocument(
        id = s.id,
        mode = s.mode,
        startedAt = isoUtc(s.startedAtEpochMs),
        endedAt = isoUtc(s.endedAtEpochMs ?: (s.startedAtEpochMs + durationMs)),
        hrMaxUsed = s.hrMaxUsed,
        device = DeviceDoc(s.deviceKind, s.deviceId),
        settings = SessionJson.parseSettings(s.settingsJson),
        summary = SummaryDoc(durationMs, s.avgHr, s.maxHr, s.timeInTargetMs ?: 0L, s.newHrMax, s.driftPct),
        samples = samples.map { SampleDoc(it.tMs, it.hr, decodeRr(it.rrMs)) },
        gaps = gaps.map { SpanDoc(it.startMs, it.endMs) },
        phases =
            phases.map {
                PhaseDoc(it.phaseIndex, it.kind, it.startMs, it.endMs ?: durationMs, it.targetLow, it.targetHigh, it.intervalNumber)
            },
        intervals = intervals.map { IntervalDoc(it.number, it.avgHr, it.peakHr, it.pctInZone, it.hrr60) },
        events = events.map { EventDoc(it.tMs, it.type, it.text) },
        pauses = pauses.map { SpanDoc(it.startMs, it.endMs) },
        complete = s.status == SessionStatus.COMPLETE,
    )
}
