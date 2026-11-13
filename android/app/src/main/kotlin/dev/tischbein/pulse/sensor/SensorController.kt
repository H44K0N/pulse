package dev.tischbein.pulse.sensor

import android.content.Context
import dev.tischbein.pulse.coaching.CoachingConfig
import dev.tischbein.pulse.data.settings.AppSettings
import dev.tischbein.pulse.data.settings.SettingsRepository
import dev.tischbein.pulse.sensor.sim.SimProfile
import dev.tischbein.pulse.sensor.sim.SimulatedHeartRateSource
import dev.tischbein.pulse.time.MillisClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** What the home screen shows about the sensor. */
data class DeviceStatus(
    /** Non-null when the simulator is selected in Settings (the strap is then ignored). */
    val simulatorProfile: SimProfile?,
    val deviceId: String?,
    val connection: ConnectionState,
    val batteryPercent: Int?,
    val skinContact: Boolean?,
    /** Something the user must fix (e.g. re-pair the strap), else null. */
    val problem: String? = null,
)

/**
 * Decides which [HeartRateSource] the app uses (real H10 or simulator, from Settings) and
 * manages the H10 connection outside of sessions: remembered device, auto-connect, scan.
 */
class SensorController(
    context: Context,
    private val clock: MillisClock,
    private val settings: SettingsRepository,
    scope: CoroutineScope,
) {
    val polar = PolarHeartRateSource(context, clock, scope)

    val status: StateFlow<DeviceStatus> =
        combine(
            settings.settings,
            polar.connectionState,
            polar.batteryPercent,
            polar.skinContact,
            polar.connectionProblem,
        ) { s, conn, battery, contact, problem ->
            DeviceStatus(s.simulatorProfile.takeIf { s.simulatorEnabled }, s.deviceId, conn, battery, contact, problem)
        }.stateIn(scope, SharingStarted.Eagerly, DeviceStatus(null, null, ConnectionState.DISCONNECTED, null, null))

    /**
     * Connects to the remembered strap, if there is one and the simulator is off ("auto-connect
     * next time"). Called when the app comes to the foreground with permissions granted.
     */
    suspend fun autoConnect() {
        val s = settings.current()
        polar.deviceId = s.deviceId
        if (s.simulatorEnabled) {
            polar.disconnect()
        } else if (s.deviceId != null) {
            polar.connect()
            polar.foregroundEntered()
        }
    }

    /** Disconnects the strap (saves the strap's and the phone's battery when no session runs). */
    fun disconnect() = polar.disconnect()

    fun scan(): Flow<FoundDevice> = polar.scan()

    /** Remembers [device] and connects to it. */
    suspend fun choose(device: FoundDevice) {
        polar.disconnect()
        settings.setDeviceId(device.deviceId)
        polar.deviceId = device.deviceId
        polar.connect()
    }

    suspend fun forget() {
        polar.disconnect()
        settings.setDeviceId(null)
        polar.deviceId = null
    }

    /**
     * The source for a new session. The simulator is created fresh per session (its script
     * starts when it connects) in [sessionScope]; the H10 source is shared and stays connected
     * after the session.
     */
    fun sourceForSession(
        settings: AppSettings,
        config: CoachingConfig,
        sessionScope: CoroutineScope,
    ): HeartRateSource =
        if (settings.simulatorEnabled) {
            SimulatedHeartRateSource(settings.simulatorProfile, sessionScope, clock, settings.hrMax, config.fourByFour)
        } else {
            polar.deviceId = settings.deviceId
            polar
        }
}
