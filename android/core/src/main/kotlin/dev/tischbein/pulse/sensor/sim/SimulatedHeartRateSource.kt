package dev.tischbein.pulse.sensor.sim

import dev.tischbein.pulse.coaching.FourByFourConfig
import dev.tischbein.pulse.sensor.ConnectionState
import dev.tischbein.pulse.sensor.HeartRateSample
import dev.tischbein.pulse.sensor.HeartRateSource
import dev.tischbein.pulse.time.MillisClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * A fake Polar H10 that plays a scripted [SimProfile]. Same [HeartRateSource] interface as
 * the real strap, so the whole app (service, coach, voice, history) can be tried without
 * wearing it.
 *
 * Time: readings are produced by a coroutine that waits [sampleIntervalMs] between readings
 * and stamps them with [clock]. In the app that is real time. In tests, run it inside
 * `runTest` with a clock reading the test scheduler, and `delay` becomes instant virtual
 * time: a 45-minute session takes milliseconds.
 *
 * During the SENSOR_DROPOUT gap it behaves like a strap out of range: [connectionState]
 * goes to CONNECTING (reconnecting) and no samples are emitted at all.
 *
 * @param scope where the reading loop runs (the session's scope in the app).
 */
class SimulatedHeartRateSource(
    private val profile: SimProfile,
    private val scope: CoroutineScope,
    private val clock: MillisClock,
    private val hrMax: Int,
    private val protocol: FourByFourConfig = FourByFourConfig(),
    private val seed: Long = 1L,
    private val sampleIntervalMs: Long = 1_000L,
) : HeartRateSource {
    private val connection = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<ConnectionState> = connection.asStateFlow()

    private val sampleFlow = MutableSharedFlow<HeartRateSample>(extraBufferCapacity = 64)
    override val samples: SharedFlow<HeartRateSample> = sampleFlow.asSharedFlow()

    private val battery = MutableStateFlow<Int?>(null)
    override val batteryPercent: StateFlow<Int?> = battery.asStateFlow()

    private val contact = MutableStateFlow<Boolean?>(null)
    override val skinContact: StateFlow<Boolean?> = contact.asStateFlow()

    private var job: Job? = null

    override fun connect() {
        if (job != null) return
        connection.value = ConnectionState.CONNECTING
        job = scope.launch { produceReadings() }
    }

    override fun disconnect() {
        job?.cancel()
        job = null
        connection.value = ConnectionState.DISCONNECTED
        contact.value = null
    }

    private suspend fun produceReadings() {
        val model = SimulatedHeartModel(profile, hrMax, protocol, seed)
        val startMs = clock.nowMs()
        battery.value = SIMULATED_BATTERY_PERCENT
        while (true) {
            val nowMs = clock.nowMs()
            val point = model.step(nowMs - startMs)
            if (point.signalLost) {
                connection.value = ConnectionState.CONNECTING
                contact.value = null
            } else {
                connection.value = ConnectionState.CONNECTED
                contact.value = true
                sampleFlow.emit(HeartRateSample(nowMs, point.hr, point.rrMs))
            }
            delay(sampleIntervalMs)
        }
    }

    private companion object {
        const val SIMULATED_BATTERY_PERCENT = 87
    }
}
