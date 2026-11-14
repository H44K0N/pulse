package dev.tischbein.pulse.session

import dev.tischbein.pulse.coaching.CoachingState
import dev.tischbein.pulse.coaching.MS_PER_SECOND
import dev.tischbein.pulse.sensor.HeartRateSample
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the live screen needs beyond the engine state. */
data class LiveHr(
    /** The engine's smoothed HR once per second since the start; null = no signal that second. */
    val bySecond: List<Int?> = emptyList(),
    /** Last valid raw reading and when it came (clock ms), for "Last 158 bpm · 14 s ago". */
    val lastValidHr: Int? = null,
    val lastValidAtMs: Long? = null,
)

/**
 * Keeps the running session's HR history for the live screen (sparkline, live drift,
 * reconnecting state). Lives in the service, not the UI, so the history is complete when the
 * screen is turned back on. One Int per second: a 2-hour session is 7 200 values.
 *
 * Not thread-safe: SessionService calls it from its main-thread scope only.
 */
class LiveHrBuffer(private val startClockMs: Long) {
    private val bySecond = ArrayList<Int?>()
    private val _state = MutableStateFlow(LiveHr())
    val state: StateFlow<LiveHr> = _state.asStateFlow()

    fun onSample(sample: HeartRateSample) {
        val hr = sample.hr?.takeIf { it > 0 } ?: return
        _state.value = _state.value.copy(lastValidHr = hr, lastValidAtMs = sample.timestampMs)
    }

    /** Called with every engine state; records one value per elapsed second. */
    fun onState(
        state: CoachingState,
        nowMs: Long,
    ) {
        val seconds = ((nowMs - startClockMs) / MS_PER_SECOND).toInt()
        if (seconds <= bySecond.size) return
        while (bySecond.size < seconds) bySecond += state.smoothedHr
        _state.value = _state.value.copy(bySecond = bySecond.toList())
    }
}
