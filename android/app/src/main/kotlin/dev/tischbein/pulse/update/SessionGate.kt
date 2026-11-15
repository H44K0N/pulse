package dev.tischbein.pulse.update

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The one rule between training and updating: an update must never kill a running recording.
 *
 * A session counts as running from the tap on Start ([tryStartSession]) until the foreground
 * service is gone ([serviceAlive] false): strap connecting, live screen, the voice saying the
 * summary. Meanwhile there is no banner, no download start and no install ([running]).
 *
 * Both directions go through one lock, so they cannot cross: an install is only handed to
 * Android if no session runs ([tryBeginInstall]), and Start does nothing while an install is
 * with Android ([tryStartSession]; Train says why).
 */
class SessionGate {
    private val lock = Any()
    private var starting = false
    private var serviceAlive = false
    private var installing = false
    private val _running = MutableStateFlow(false)

    /** A session is running or being set up. */
    val running: StateFlow<Boolean> = _running.asStateFlow()

    /** Start was tapped. False (and nothing starts) while an update is being installed. */
    fun tryStartSession(): Boolean =
        synchronized(lock) {
            if (installing) return false
            starting = true
            publish()
            true
        }

    /** Starting the service failed before it was created. */
    fun startFailed() =
        synchronized(lock) {
            starting = false
            publish()
        }

    /** SessionService.onCreate (true) and onDestroy (false). */
    fun serviceAlive(alive: Boolean) =
        synchronized(lock) {
            serviceAlive = alive
            if (!alive) starting = false
            publish()
        }

    /** About to hand the file to Android. False (do not install) while a session runs. */
    fun tryBeginInstall(): Boolean =
        synchronized(lock) {
            if (starting || serviceAlive) return false
            installing = true
            true
        }

    /** The install is over (failed, cancelled, timed out) or was never started. */
    fun endInstall() =
        synchronized(lock) {
            installing = false
        }

    val installingNow: Boolean get() = synchronized(lock) { installing }

    private fun publish() {
        _running.value = starting || serviceAlive
    }
}
