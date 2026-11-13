package dev.tischbein.pulse.sensor

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * One heart-rate reading as delivered by the sensor (the Polar H10 sends about one per second).
 *
 * @property timestampMs when the reading arrived, from the shared [dev.tischbein.pulse.time.MillisClock].
 * @property hr heart rate in bpm, or null when the sensor has no valid reading (for example
 *   no skin contact). Never fill this in with a guessed value: gaps must stay visible.
 * @property rrMs RR intervals (time between beats, ms) received with this reading. Can be
 *   empty; at higher heart rates there can be two or more per reading.
 */
data class HeartRateSample(
    val timestampMs: Long,
    val hr: Int?,
    val rrMs: List<Int> = emptyList(),
)

enum class ConnectionState {
    DISCONNECTED,

    /** Connecting for the first time, or reconnecting after the link dropped. */
    CONNECTING,
    CONNECTED,
}

/**
 * A heart-rate sensor. Implemented by the real Polar H10 (Milestone 2) and by
 * [dev.tischbein.pulse.sensor.sim.SimulatedHeartRateSource], so the rest of the app cannot
 * tell them apart.
 *
 * Contract:
 * - [connect] starts connecting (and keeps reconnecting after drops) until [disconnect].
 *   Both return immediately; progress shows up in [connectionState].
 * - [samples] is a hot stream: it emits while connected, whether or not anyone collects,
 *   and readings emitted while nobody collects are lost. Start collecting before [connect].
 * - While the link is down no samples arrive at all. Consumers must not assume a steady
 *   1 Hz stream; the coaching engine detects gaps from the time since the last valid reading.
 * - [batteryPercent] and [skinContact] are null when unknown (for example before connecting).
 */
interface HeartRateSource {
    val connectionState: StateFlow<ConnectionState>
    val samples: Flow<HeartRateSample>
    val batteryPercent: StateFlow<Int?>
    val skinContact: StateFlow<Boolean?>

    fun connect()

    fun disconnect()
}
