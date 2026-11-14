package dev.tischbein.pulse.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** Summary numbers computed from the raw samples (used for sessions that never finished). */
data class SampleStats(val lastTMs: Long?, val avgHr: Double?, val maxHr: Int?)

/** Everything stored for one session, for export and the session detail screen. */
data class StoredSession(
    val session: SessionEntity,
    val samples: List<SampleEntity>,
    val events: List<EventEntity>,
    val phases: List<PhaseEntity>,
    val intervals: List<IntervalEntity>,
    val gaps: List<GapEntity>,
    val pauses: List<PauseEntity>,
)

@Dao
abstract class SessionDao {
    // ---- Writes ----
    @Insert
    abstract suspend fun insertSession(session: SessionEntity)

    @Update
    abstract suspend fun updateSession(session: SessionEntity)

    @Insert
    abstract suspend fun insertSamples(samples: List<SampleEntity>)

    @Insert
    abstract suspend fun insertEvents(events: List<EventEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertPhases(phases: List<PhaseEntity>)

    @Insert
    abstract suspend fun insertIntervals(intervals: List<IntervalEntity>)

    @Insert
    abstract suspend fun insertGaps(gaps: List<GapEntity>)

    @Insert
    abstract suspend fun insertPauses(pauses: List<PauseEntity>)

    @Query("DELETE FROM samples WHERE sessionId = :id")
    abstract suspend fun deleteSamples(id: String)

    @Query("DELETE FROM events WHERE sessionId = :id")
    abstract suspend fun deleteEvents(id: String)

    @Query("DELETE FROM phases WHERE sessionId = :id")
    abstract suspend fun deletePhases(id: String)

    @Query("DELETE FROM intervals WHERE sessionId = :id")
    abstract suspend fun deleteIntervals(id: String)

    @Query("DELETE FROM gaps WHERE sessionId = :id")
    abstract suspend fun deleteGaps(id: String)

    @Query("DELETE FROM pauses WHERE sessionId = :id")
    abstract suspend fun deletePauses(id: String)

    @Query("DELETE FROM sessions WHERE id = :id")
    abstract suspend fun deleteSession(id: String)

    @Query("UPDATE phases SET endMs = :endMs WHERE sessionId = :id AND endMs IS NULL")
    abstract suspend fun closeOpenPhases(id: String, endMs: Long)

    /** One incremental flush during recording: all-or-nothing, so a kill mid-write leaves no half batch. */
    @Transaction
    open suspend fun appendRecording(samples: List<SampleEntity>, events: List<EventEntity>, phases: List<PhaseEntity>) {
        if (samples.isNotEmpty()) insertSamples(samples)
        if (events.isNotEmpty()) insertEvents(events)
        if (phases.isNotEmpty()) upsertPhases(phases)
    }

    /**
     * Replaces the incrementally recorded rows with the engine's final result in one
     * transaction. If the app dies halfway, the old incremental rows are still there.
     */
    @Transaction
    open suspend fun replaceWithFinal(stored: StoredSession) {
        val id = stored.session.id
        deleteSamples(id)
        deleteEvents(id)
        deletePhases(id)
        deleteIntervals(id)
        deleteGaps(id)
        deletePauses(id)
        insertSamples(stored.samples)
        insertEvents(stored.events)
        upsertPhases(stored.phases)
        insertIntervals(stored.intervals)
        insertGaps(stored.gaps)
        insertPauses(stored.pauses)
        updateSession(stored.session)
    }

    // ---- Reads ----
    @Query("SELECT * FROM sessions WHERE id = :id")
    abstract suspend fun session(id: String): SessionEntity?

    @Query("SELECT * FROM sessions WHERE status = :status AND startedAtEpochMs < :startedBefore")
    abstract suspend fun sessionsWithStatus(status: String, startedBefore: Long): List<SessionEntity>

    // History reads. "Finished" = complete or incomplete, i.e. not the one being recorded.
    // Only the `sessions` rows (with their digest columns): never the samples.

    /** Finished sessions started at or after [fromEpochMs], newest first (Progress window). */
    @Query("SELECT * FROM sessions WHERE status != 'recording' AND startedAtEpochMs >= :fromEpochMs ORDER BY startedAtEpochMs DESC")
    abstract fun observeFinishedSince(fromEpochMs: Long): Flow<List<SessionEntity>>

    /** The [limit] most recent finished sessions (Progress list). */
    @Query("SELECT * FROM sessions WHERE status != 'recording' ORDER BY startedAtEpochMs DESC LIMIT :limit")
    abstract fun observeRecentFinished(limit: Int): Flow<List<SessionEntity>>

    /** Every finished session, newest first ("Show all"). One row is ~200 bytes: years fit in memory. */
    @Query("SELECT * FROM sessions WHERE status != 'recording' ORDER BY startedAtEpochMs DESC")
    abstract fun observeAllFinished(): Flow<List<SessionEntity>>

    @Query("SELECT COUNT(*) FROM sessions WHERE status != 'recording'")
    abstract fun observeFinishedCount(): Flow<Int>

    @Query("SELECT * FROM samples WHERE sessionId = :id ORDER BY tMs, rowId")
    abstract suspend fun samples(id: String): List<SampleEntity>

    @Query("SELECT * FROM events WHERE sessionId = :id ORDER BY tMs, rowId")
    abstract suspend fun events(id: String): List<EventEntity>

    @Query("SELECT * FROM phases WHERE sessionId = :id ORDER BY phaseIndex")
    abstract suspend fun phases(id: String): List<PhaseEntity>

    @Query("SELECT * FROM intervals WHERE sessionId = :id ORDER BY number")
    abstract suspend fun intervals(id: String): List<IntervalEntity>

    @Query("SELECT * FROM gaps WHERE sessionId = :id ORDER BY startMs")
    abstract suspend fun gaps(id: String): List<GapEntity>

    @Query("SELECT * FROM pauses WHERE sessionId = :id ORDER BY startMs")
    abstract suspend fun pauses(id: String): List<PauseEntity>

    @Query("SELECT MAX(tMs) AS lastTMs, AVG(hr) AS avgHr, MAX(hr) AS maxHr FROM samples WHERE sessionId = :id")
    abstract suspend fun sampleStats(id: String): SampleStats

    @Transaction
    open suspend fun load(id: String): StoredSession? {
        val session = session(id) ?: return null
        return StoredSession(session, samples(id), events(id), phases(id), intervals(id), gaps(id), pauses(id))
    }
}
