package dev.tischbein.pulse.session

import dev.tischbein.pulse.coaching.CoachingRunner
import dev.tischbein.pulse.coaching.CoachingState
import dev.tischbein.pulse.coaching.SessionMode
import kotlinx.coroutines.flow.StateFlow

/**
 * The session the foreground service is running right now, as the UI sees it.
 * Published in AppContainer.activeSession (null when no session runs).
 *
 * The buttons call the [CoachingRunner], which queues the command for the engine's own
 * coroutine: safe to call from the UI thread (the engine itself is never called directly).
 */
class ActiveSession(
    val id: String,
    val mode: SessionMode,
    /** "Polar H10 A1B2C3D4" or "Simulator: GOOD_4X4". */
    val sourceLabel: String,
    val state: StateFlow<CoachingState>,
    private val runner: CoachingRunner,
) {
    fun pause() = runner.pause()

    fun resume() = runner.resume()

    fun end() = runner.end()
}
