package dev.tischbein.pulse.data

import dev.tischbein.pulse.coaching.RecordedSample
import dev.tischbein.pulse.coaching.SessionResult
import dev.tischbein.pulse.data.db.DeviceKind
import dev.tischbein.pulse.data.db.EventEntity
import dev.tischbein.pulse.data.db.PhaseEntity
import dev.tischbein.pulse.data.db.SampleEntity
import dev.tischbein.pulse.data.db.SessionDao
import dev.tischbein.pulse.data.db.SessionEntity
import dev.tischbein.pulse.data.db.SessionStatus
import dev.tischbein.pulse.data.db.SyncOverview
import dev.tischbein.pulse.data.db.SyncState
import dev.tischbein.pulse.data.export.SessionJson
import dev.tischbein.pulse.data.export.toDocument
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.math.roundToInt

/**
 * All session storage goes through here (the rest of the app never touches the DAO).
 *
 * Recording lifecycle:
 *  1. [startSession]: inserts the session row with status "recording".
 *  2. [appendRecording]: called every few seconds by the SessionRecorder with the new
 *     samples/events/phase changes. A crash loses at most the last unflushed batch.
 *  3. [finishSession]: replaces the incremental rows with the engine's final SessionResult
 *     and marks the session "complete".
 *
 * If the process dies between 1 and 3, the session stays "recording" in the database. On the
 * next app start [closeAbandonedSessions] turns it into "incomplete" (it is never resumed:
 * the coaching engine's in-memory state is gone, and a half-remembered 4x4 is worse than a new
 * one). Its data up to the last flush is kept and it will be listed/synced like any other
 * session, with `complete: false` in the JSON.
 *
 * Sync: a new row starts "pending"; once the session is finished the uploader sends it and
 * marks it "synced" (or "failed" if the server rejects it for good). See data/sync.
 */
class SessionRepository(private val dao: SessionDao) {
    suspend fun startSession(session: SessionEntity) = dao.insertSession(session)

    suspend fun appendRecording(samples: List<SampleEntity>, events: List<EventEntity>, phases: List<PhaseEntity>) {
        if (samples.isEmpty() && events.isEmpty() && phases.isEmpty()) return
        dao.appendRecording(samples, events, phases)
    }

    suspend fun finishSession(sessionId: String, result: SessionResult) {
        val session = checkNotNull(dao.session(sessionId)) { "unknown session $sessionId" }
        dao.replaceWithFinal(result.toStored(session))
    }

    /**
     * Marks sessions that were still "recording" when the app died as "incomplete", with a
     * best-effort summary from the stored samples (duration = last sample, average/max of the
     * raw readings; time in target unknown = 0). Only touches sessions started before
     * [startedBefore] (the app's start time), so a session that just started is never hit.
     * Returns how many sessions were closed.
     */
    suspend fun closeAbandonedSessions(startedBefore: Long): Int {
        val abandoned = dao.sessionsWithStatus(SessionStatus.RECORDING, startedBefore)
        abandoned.forEach { markIncomplete(it) }
        return abandoned.size
    }

    /** Marks one session "incomplete" (used directly when a session fails or its service is destroyed). */
    suspend fun markIncomplete(sessionId: String) {
        dao.session(sessionId)?.takeIf { it.status == SessionStatus.RECORDING }?.let { markIncomplete(it) }
    }

    private suspend fun markIncomplete(session: SessionEntity) {
        val stats = dao.sampleStats(session.id)
        val durationMs = stats.lastTMs ?: 0L
        dao.closeOpenPhases(session.id, durationMs)
        // Digest: only the HR line is known (interval stats are computed at the end).
        val samples = dao.samples(session.id).map { RecordedSample(it.tMs, it.hr, emptyList()) }
        dao.updateSession(
            session.copy(
                status = SessionStatus.INCOMPLETE,
                endedAtEpochMs = session.startedAtEpochMs + durationMs,
                durationMs = durationMs,
                avgHr = stats.avgHr?.roundToInt(),
                maxHr = stats.maxHr,
                timeInTargetMs = 0L,
                sparkline = encodeSparkline(sparklineOf(samples, durationMs)),
            ),
        )
    }

    // ---- History (finished sessions only; digests, never samples) ----

    /** Finished sessions started at or after [fromEpochMs], newest first. */
    fun observeSince(fromEpochMs: Long): Flow<List<SessionDigest>> = dao.observeFinishedSince(fromEpochMs).map { rows -> rows.map { it.toDigest() } }

    fun observeRecent(limit: Int): Flow<List<SessionDigest>> = dao.observeRecentFinished(limit).map { rows -> rows.map { it.toDigest() } }

    fun observeAll(): Flow<List<SessionDigest>> = dao.observeAllFinished().map { rows -> rows.map { it.toDigest() } }

    fun observeCount(): Flow<Int> = dao.observeFinishedCount()

    /** One session with everything needed for its detail screen, or null if it does not exist (any more). */
    suspend fun load(sessionId: String): LoadedSession? =
        dao.load(sessionId)?.let { LoadedSession(it.session, it.toResult()) }

    suspend fun session(sessionId: String): SessionEntity? = dao.session(sessionId)

    /** Deletes the session and (cascade) all its samples, events, phases, ... */
    suspend fun delete(sessionId: String) = dao.deleteSession(sessionId)

    /** The session as the JSON document of docs/session-format.md (what the uploader sends). */
    suspend fun exportJson(sessionId: String): String? = dao.load(sessionId)?.let { SessionJson.encode(it.toDocument()) }

    // ---- Sync state (written by data/sync/SessionUploader) ----

    /** Ids of the finished sessions not on the server yet, oldest first. */
    suspend fun pendingUploads(): List<String> = dao.pendingUploadIds()

    suspend fun markSynced(sessionId: String, atEpochMs: Long) = dao.markSynced(sessionId, atEpochMs)

    /** A failed attempt that will be retried (no connection, wrong token, server error). */
    suspend fun markUploadRetry(sessionId: String, error: String, atEpochMs: Long) = dao.recordSyncFailure(sessionId, SyncState.PENDING, error, atEpochMs)

    /** The server rejected this session for good; it is not retried automatically. */
    suspend fun markUploadRejected(sessionId: String, error: String, atEpochMs: Long) = dao.recordSyncFailure(sessionId, SyncState.FAILED, error, atEpochMs)

    /** Everything synced or rejected becomes pending again (a different server was configured). */
    suspend fun resetAllSync(): Int = dao.resetSync(listOf(SyncState.SYNCED, SyncState.FAILED))

    /** Rejected sessions get one more try ("Sync now": the server may have been fixed meanwhile). */
    suspend fun retryRejected(): Int = dao.resetSync(listOf(SyncState.FAILED))

    fun observeSyncOverview(): Flow<SyncOverview> = dao.observeSyncOverview()
}

/** A stored session for the detail screen: the row (dates, device, status) and its data. */
class LoadedSession(val entity: SessionEntity, val result: SessionResult) {
    val complete: Boolean get() = entity.status == SessionStatus.COMPLETE
    val simulated: Boolean get() = entity.deviceKind == DeviceKind.SIMULATOR
    val syncState: String get() = entity.syncState
}
