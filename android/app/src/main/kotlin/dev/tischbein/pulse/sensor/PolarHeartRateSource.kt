package dev.tischbein.pulse.sensor

import android.content.Context
import android.util.Log
import com.polar.androidcommunications.api.ble.model.DisInfo
import com.polar.sdk.api.PolarBleApi
import com.polar.sdk.api.PolarBleApiCallback
import com.polar.sdk.api.PolarBleApiDefaultImpl
import com.polar.sdk.api.PolarBleDisconnectInfo
import com.polar.sdk.api.PolarBleRecoveryAction
import com.polar.sdk.api.model.PolarDeviceInfo
import com.polar.sdk.api.model.PolarHealthThermometerData
import dev.tischbein.pulse.time.MillisClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** A strap found by [PolarHeartRateSource.scan]. [deviceId] is the id printed on the sensor. */
data class FoundDevice(val deviceId: String, val name: String, val rssi: Int)

/**
 * Turns one H10 reading into a [HeartRateSample].
 *
 * The H10 keeps sending notifications when it is not on the skin (strap loose, or dry
 * electrodes at the start of a workout), with heart rate 0 and/or the contact flag false.
 * Those mean "no valid reading", so hr becomes null, never 0 and never a guessed value.
 * RR intervals measured without contact are noise, so they are dropped too.
 */
internal fun polarReadingToSample(
    timestampMs: Long,
    hr: Int,
    rrsMs: List<Int>,
    contactSupported: Boolean,
    contact: Boolean,
): HeartRateSample {
    val valid = hr > 0 && (!contactSupported || contact)
    return HeartRateSample(timestampMs, if (valid) hr else null, if (valid) rrsMs else emptyList())
}

/**
 * The real Polar H10, behind the same [HeartRateSource] interface as the simulator.
 *
 * About the Polar SDK (8.x):
 * - Since 8.0 its Android API is Kotlin coroutines/Flow (RxJava is only used inside the SDK,
 *   via kotlinx-coroutines-rx3). So this class bridges SDK callbacks and Flows to our flows;
 *   there is no RxJava code in the app.
 * - Connection state, battery and "feature ready" arrive through one callback object
 *   ([PolarBleApiCallback]). The SDK 8.6 delivers them on the main thread, but that is not a
 *   documented promise, so the callback only writes thread-safe StateFlows and hands anything
 *   else to [scope] (main thread).
 * - Heart rate: `startHrStreaming(deviceId)` may only be called after the SDK reports the
 *   FEATURE_HR feature ready for that device. The stream ends (with an error) whenever the
 *   link drops.
 *
 * Reconnection (the SDK's mechanism, enabled with setAutomaticReconnection(true), which is
 * also its default): after a drop the SDK "parks" the device and reconnects on its own, both
 * by scanning (with Polar-specific scan filters, which Android allows with the screen off) and
 * with a background `autoConnect` GATT request. QUIRK: the HR stream does NOT come back by
 * itself after a reconnect. The SDK reports FEATURE_HR ready again, and we must call
 * startHrStreaming again; [onHrFeatureReady] does exactly that. Until then no samples arrive,
 * which the coaching engine sees as a sensor gap ("Sensor signal lost" after 10 s).
 *
 * The SDK instance is created on first use, because creating it touches Bluetooth, which needs
 * the BLUETOOTH_CONNECT permission (the UI asks for it before anything calls this class).
 *
 * @param scope long-lived scope (the app's), main dispatcher.
 */
class PolarHeartRateSource(
    context: Context,
    private val clock: MillisClock,
    private val scope: CoroutineScope,
) : HeartRateSource {
    private val appContext = context.applicationContext

    private val connection = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<ConnectionState> = connection.asStateFlow()

    // Buffered so a slow collector never blocks the SDK's thread; ~1 reading/s, 64 is plenty.
    private val sampleFlow = MutableSharedFlow<HeartRateSample>(extraBufferCapacity = 64)
    override val samples: SharedFlow<HeartRateSample> = sampleFlow.asSharedFlow()

    private val battery = MutableStateFlow<Int?>(null)
    override val batteryPercent: StateFlow<Int?> = battery.asStateFlow()

    private val contact = MutableStateFlow<Boolean?>(null)
    override val skinContact: StateFlow<Boolean?> = contact.asStateFlow()

    private val problem = MutableStateFlow<String?>(null)

    /** A connection problem the user has to fix themselves (shown on the home screen), else null. */
    val connectionProblem: StateFlow<String?> = problem.asStateFlow()

    /** The device [connect] connects to (the remembered one). Set by SensorController. */
    @Volatile var deviceId: String? = null

    /** True between connect() and disconnect(): a drop in between means "reconnecting". */
    @Volatile private var wantConnected = false

    private var hrStreamJob: Job? = null
    private var sdk: PolarBleApi? = null

    private fun api(): PolarBleApi =
        sdk ?: PolarBleApiDefaultImpl
            .defaultImplementation(
                appContext,
                // Only what we use. Fewer features = fewer GATT services the SDK sets up.
                setOf(
                    PolarBleApi.PolarBleSdkFeature.FEATURE_HR,
                    PolarBleApi.PolarBleSdkFeature.FEATURE_BATTERY_INFO,
                ),
            ).also {
                it.setApiCallback(callback)
                it.setAutomaticReconnection(true)
                sdk = it
            }

    /**
     * Scans for nearby Polar H10 straps while collected (cancel the collecting coroutine to
     * stop). Each device is reported once. Needs BLUETOOTH_SCAN.
     */
    fun scan(): Flow<FoundDevice> =
        api()
            .searchForDevice()
            .filter { it.name.contains(H10_NAME) }
            .map { FoundDevice(it.deviceId, it.name, it.rssi) }

    /**
     * Should be called when the app comes to the foreground. Polar: restarts the SDK's BLE
     * scan, which Android may have stopped while the screen was off, so a pending reconnect
     * gets another chance.
     */
    fun foregroundEntered() {
        sdk?.foregroundEntered()
    }

    override fun connect() {
        val id = deviceId ?: return // no strap remembered yet: the UI offers "Scan"
        if (wantConnected && connection.value != ConnectionState.DISCONNECTED) return
        wantConnected = true
        connection.value = ConnectionState.CONNECTING
        try {
            // With a device id (not a MAC address) the SDK first scans for that id, then
            // connects. That also works after the phone rebooted or the strap's address changed.
            api().connectToDevice(id)
        } catch (e: Exception) {
            // PolarInvalidArgument (malformed id) or a Bluetooth SecurityException.
            Log.w(TAG, "connectToDevice failed: ${e.message}")
            wantConnected = false
            connection.value = ConnectionState.DISCONNECTED
        }
    }

    override fun disconnect() {
        wantConnected = false
        stopHrStream()
        val id = deviceId
        if (id != null && sdk != null) {
            try {
                // Also cancels a connect that is still searching for the strap.
                sdk?.disconnectFromDevice(id)
            } catch (e: Exception) {
                Log.w(TAG, "disconnectFromDevice failed: ${e.message}")
            }
        }
        connection.value = ConnectionState.DISCONNECTED
        battery.value = null
        contact.value = null
    }

    private fun isOurs(id: String) = id == deviceId

    private fun onHrFeatureReady(id: String) {
        stopHrStream()
        hrStreamJob =
            scope.launch {
                try {
                    api().startHrStreaming(id).collect { data ->
                        for (s in data.samples) {
                            contact.value = if (s.contactStatusSupported) s.contactStatus else null
                            sampleFlow.emit(polarReadingToSample(clock.nowMs(), s.hr, s.rrsMs, s.contactStatusSupported, s.contactStatus))
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Normal when the link drops; the SDK reconnects and calls bleSdkFeatureReady again.
                    Log.i(TAG, "HR stream ended: ${e.javaClass.simpleName} ${e.message}")
                }
            }
    }

    private fun stopHrStream() {
        hrStreamJob?.cancel()
        hrStreamJob = null
    }

    private val callback =
        object : PolarBleApiCallback() {
            override fun deviceConnecting(polarDeviceInfo: PolarDeviceInfo) {
                if (isOurs(polarDeviceInfo.deviceId)) connection.value = ConnectionState.CONNECTING
            }

            override fun deviceConnected(polarDeviceInfo: PolarDeviceInfo) {
                if (!isOurs(polarDeviceInfo.deviceId)) return
                problem.value = null
                Log.i(TAG, "connected to ${polarDeviceInfo.deviceId}")
                connection.value = ConnectionState.CONNECTED
            }

            override fun deviceDisconnected(
                polarDeviceInfo: PolarDeviceInfo,
                info: PolarBleDisconnectInfo,
            ) {
                if (!isOurs(polarDeviceInfo.deviceId)) return
                Log.i(TAG, "disconnected from ${polarDeviceInfo.deviceId} ($info), reconnecting=$wantConnected")
                // The SDK says when retrying cannot help: the phone's and the strap's pairing
                // no longer match (e.g. the strap was reset or paired to another phone).
                if (info.recoveryAction == PolarBleRecoveryAction.REMOVE_PAIRING_AND_PAIR_AGAIN ||
                    info.recoveryAction == PolarBleRecoveryAction.RETRY_PAIRING
                ) {
                    problem.value =
                        "Pairing problem: forget the H10 in Android's Bluetooth settings, " +
                        "then scan and connect again."
                }
                scope.launch { stopHrStream() }
                contact.value = null
                // The SDK's automatic reconnection is already running if we still want the strap.
                connection.value = if (wantConnected) ConnectionState.CONNECTING else ConnectionState.DISCONNECTED
            }

            override fun bleSdkFeatureReady(identifier: String, feature: PolarBleApi.PolarBleSdkFeature) {
                if (feature == PolarBleApi.PolarBleSdkFeature.FEATURE_HR && isOurs(identifier) && wantConnected) {
                    scope.launch { onHrFeatureReady(identifier) }
                }
            }

            override fun batteryLevelReceived(identifier: String, level: Int) {
                if (isOurs(identifier)) battery.value = level
            }

            // Abstract in the SDK's callback class but not used here (device info service,
            // thermometer devices): required no-op overrides.
            override fun disInformationReceived(identifier: String, disInfo: DisInfo) = Unit

            override fun htsNotificationReceived(identifier: String, data: PolarHealthThermometerData) = Unit

            override fun blePowerStateChanged(powered: Boolean) {
                // Bluetooth switched off: the link is gone. The SDK reconnects when it is back on.
                if (!powered && wantConnected) {
                    connection.value = ConnectionState.CONNECTING
                    contact.value = null
                }
            }
        }

    private companion object {
        const val TAG = "Pulse/Polar"

        /** Polar straps advertise as "Polar H10 A1B2C3D4"; the brief targets the H10 only. */
        const val H10_NAME = "H10"
    }
}
