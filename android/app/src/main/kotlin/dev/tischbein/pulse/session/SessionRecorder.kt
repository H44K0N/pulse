package dev.tischbein.pulse.session

import android.util.Log
import dev.tischbein.pulse.coaching.CoachingEvent
import dev.tischbein.pulse.coaching.CoachingState
import dev.tischbein.pulse.data.SessionRepository
import dev.tischbein.pulse.data.db.EventEntity
import dev.tischbein.pulse.data.db.PhaseEntity
import dev.tischbein.pulse.data.db.SampleEntity
import dev.tischbein.pulse.data.encodeRr
import dev.tischbein.pulse.data.toEntity
import dev.tischbein.pulse.sensor.HeartRateSample
import dev.tischbein.pulse.time.MillisClock

/**
 * Writes a running session to Room incrementally, so a crash or kill loses at most one
 * flush interval (SessionService flushes every few seconds).
 *
 * The coaching engine keeps the authoritative record in memory and hands it over as a
 * SessionResult at the end; then SessionRepository.finishSession replaces these rows with it.
 * So this class only has to be good enough for the crash case:
 *  - samples: every reading as it arrives, t = sample time - session start.
 *  - events: every cue the engine emits.
 *  - phases: a row when a phase starts (end_ms null) and its end when the next one starts,
 *    taken from engine.state. Gaps, intervals and pauses are only stored at the end.
 *
 * Not thread-safe: SessionService calls it from its main-thread scope only.
 */
class SessionRecorder(
    private val repository: SessionRepository,
    private val sessionId: String,
    /** Clock reading (same clock as the sample timestamps) when the session started. */
    private val startClockMs: Long,
    private val clock: MillisClock,
) {
    private var samples = mutableListOf<SampleEntity>()
    private var events = mutableListOf<EventEntity>()
    private var phases = mutableListOf<PhaseEntity>()
    private var currentPhase: PhaseEntity? = null
    private var phaseCount = 0

    private fun t(clockMs: Long) = maxOf(0L, clockMs - startClockMs)

    fun onSample(sample: HeartRateSample) {
        // Same rule as the engine: 0 or below is "no reading" (sources already map it to null).
        val hr = sample.hr?.takeIf { it > 0 }
        samples += SampleEntity(sessionId = sessionId, tMs = t(sample.timestampMs), hr = hr, rrMs = encodeRr(sample.rrMs))
    }

    fun onEvent(event: CoachingEvent) {
        events += event.toEntity(sessionId)
    }

    fun onState(state: CoachingState) {
        if (!state.started || state.finished) return
        val kind = state.phaseKind?.wireName ?: return
        val current = currentPhase
        if (current != null && current.kind == kind && current.intervalNumber == state.intervalNumber) return
        val now = t(clock.nowMs())
        if (current != null) phases += current.copy(endMs = now)
        val next =
            PhaseEntity(
                sessionId = sessionId,
                phaseIndex = phaseCount++,
                kind = kind,
                startMs = if (current == null) 0L else now,
                endMs = null,
                targetLow = state.targetLow ?: 0,
                targetHigh = state.targetHigh ?: 0,
                intervalNumber = state.intervalNumber,
            )
        phases += next
        currentPhase = next
    }

    /** Writes everything collected since the last flush in one transaction. */
    suspend fun flush() {
        // Swap the buffers before suspending, so readings arriving during the write go to the next batch.
        val batchSamples = samples
        val batchEvents = events
        val batchPhases = phases
        samples = mutableListOf()
        events = mutableListOf()
        phases = mutableListOf()
        try {
            repository.appendRecording(batchSamples, batchEvents, batchPhases)
        } catch (e: Exception) {
            // Keep the rows for the next attempt rather than losing them.
            Log.e(TAG, "flush failed, will retry", e)
            samples.addAll(0, batchSamples)
            events.addAll(0, batchEvents)
            phases.addAll(0, batchPhases)
        }
    }

    private companion object {
        const val TAG = "Pulse/Recorder"
    }
}
