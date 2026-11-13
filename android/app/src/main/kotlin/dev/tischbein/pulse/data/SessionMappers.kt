package dev.tischbein.pulse.data

import dev.tischbein.pulse.coaching.CoachingEvent
import dev.tischbein.pulse.coaching.SessionResult
import dev.tischbein.pulse.data.db.EventEntity
import dev.tischbein.pulse.data.db.GapEntity
import dev.tischbein.pulse.data.db.IntervalEntity
import dev.tischbein.pulse.data.db.PauseEntity
import dev.tischbein.pulse.data.db.PhaseEntity
import dev.tischbein.pulse.data.db.SampleEntity
import dev.tischbein.pulse.data.db.SessionEntity
import dev.tischbein.pulse.data.db.SessionStatus
import dev.tischbein.pulse.data.db.StoredSession

/** RR intervals are stored as "652,648" (simple, readable in sqlite3, no type converter). */
fun encodeRr(rrMs: List<Int>): String = rrMs.joinToString(",")

fun decodeRr(stored: String): List<Int> = if (stored.isEmpty()) emptyList() else stored.split(',').map { it.trim().toInt() }

fun CoachingEvent.toEntity(sessionId: String) = EventEntity(sessionId = sessionId, tMs = tMs, type = type.wireName, text = text)

/**
 * The engine's finished [SessionResult] as database rows. [session] is the row created at
 * session start (id, device, start date, settings); this adds the summary and end date.
 * `ended_at` = `started_at` + duration, so the two dates always agree with `duration_ms`.
 */
fun SessionResult.toStored(session: SessionEntity): StoredSession {
    val id = session.id
    return StoredSession(
        session =
            session.copy(
                status = SessionStatus.COMPLETE,
                endedAtEpochMs = session.startedAtEpochMs + summary.durationMs,
                hrMaxUsed = setup.hrMax,
                durationMs = summary.durationMs,
                avgHr = summary.avgHr,
                maxHr = summary.maxHr,
                timeInTargetMs = summary.timeInTargetMs,
                newHrMax = summary.newHrMax,
                driftPct = summary.driftPct,
            ),
        samples = samples.map { SampleEntity(sessionId = id, tMs = it.tMs, hr = it.hr, rrMs = encodeRr(it.rrMs)) },
        events = events.map { it.toEntity(id) },
        phases =
            phases.map {
                PhaseEntity(id, it.index, it.kind.wireName, it.startMs, it.endMs, it.targetLow, it.targetHigh, it.intervalNumber)
            },
        intervals = intervals.map { IntervalEntity(id, it.number, it.avgHr, it.peakHr, it.pctInZone, it.hrr60) },
        gaps = gaps.map { GapEntity(sessionId = id, startMs = it.startMs, endMs = it.endMs) },
        pauses = pauses.map { PauseEntity(sessionId = id, startMs = it.startMs, endMs = it.endMs) },
    )
}
