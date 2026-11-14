package dev.tischbein.pulse.data

import dev.tischbein.pulse.coaching.CoachingConfig
import dev.tischbein.pulse.coaching.CoachingEvent
import dev.tischbein.pulse.coaching.EventType
import dev.tischbein.pulse.coaching.Gap
import dev.tischbein.pulse.coaching.IntervalStats
import dev.tischbein.pulse.coaching.Pause
import dev.tischbein.pulse.coaching.PhaseKind
import dev.tischbein.pulse.coaching.PhaseRecord
import dev.tischbein.pulse.coaching.RecordedSample
import dev.tischbein.pulse.coaching.SessionResult
import dev.tischbein.pulse.coaching.SessionSetup
import dev.tischbein.pulse.coaching.SessionSummary
import dev.tischbein.pulse.data.db.EventEntity
import dev.tischbein.pulse.data.db.GapEntity
import dev.tischbein.pulse.data.db.IntervalEntity
import dev.tischbein.pulse.data.db.PauseEntity
import dev.tischbein.pulse.data.db.PhaseEntity
import dev.tischbein.pulse.data.db.SampleEntity
import dev.tischbein.pulse.data.db.SessionEntity
import dev.tischbein.pulse.data.db.SessionStatus
import dev.tischbein.pulse.data.db.StoredSession
import dev.tischbein.pulse.data.export.SettingsSnapshot

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
            ).withDigest(SessionDigest.of(id, this, session.startedAtEpochMs)),
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

/** Copies the digest columns (see SessionDigest) onto a session row. */
fun SessionEntity.withDigest(d: SessionDigest): SessionEntity =
    copy(avgHrr60 = d.avgHrr60, avgWorkHr = d.avgWorkHr, workPctInZone = d.workPctInZone, sparkline = encodeSparkline(d.sparkline))

/**
 * A stored session back as the engine's [SessionResult], so the detail screen draws a session
 * from the database exactly like the one that just finished. For an incomplete session (app
 * died mid-session) a phase without an end ends at the last recorded moment, and there are no
 * interval stats (the engine computes those only at the end).
 */
fun StoredSession.toResult(): SessionResult {
    val s = session
    val mode = sessionModeOf(s.mode)
    val snapshot = SettingsSnapshot.parse(s.settingsJson)
    val durationMs = s.durationMs ?: samples.maxOfOrNull { it.tMs } ?: 0L
    return SessionResult(
        setup = snapshot?.toSetup(mode, s.hrMaxUsed) ?: SessionSetup(mode, s.hrMaxUsed),
        config = snapshot?.toConfig() ?: CoachingConfig(),
        summary = SessionSummary(durationMs, s.avgHr, s.maxHr, s.timeInTargetMs ?: 0L, s.newHrMax, s.driftPct),
        samples = samples.map { RecordedSample(it.tMs, it.hr, decodeRr(it.rrMs)) },
        gaps = gaps.map { Gap(it.startMs, it.endMs) },
        pauses = pauses.map { Pause(it.startMs, it.endMs) },
        phases =
            phases.map { p ->
                PhaseRecord(
                    index = p.phaseIndex,
                    kind = PhaseKind.entries.firstOrNull { it.wireName == p.kind } ?: PhaseKind.EASY,
                    startMs = p.startMs,
                    endMs = p.endMs ?: durationMs,
                    targetLow = p.targetLow,
                    targetHigh = p.targetHigh,
                    intervalNumber = p.intervalNumber,
                )
            },
        intervals = intervals.map { IntervalStats(it.number, it.avgHr, it.peakHr, it.pctInZone, it.hrr60) },
        events =
            events.mapNotNull { e ->
                EventType.entries.firstOrNull { it.wireName == e.type }?.let { CoachingEvent(e.tMs, it, e.text) }
            },
    )
}
