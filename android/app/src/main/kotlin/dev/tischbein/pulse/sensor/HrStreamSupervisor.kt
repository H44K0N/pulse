package dev.tischbein.pulse.sensor

import dev.tischbein.pulse.time.MillisClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/** One heart-rate notification from the strap, independent of the Polar SDK's types. */
data class HrReading(
    val hr: Int,
    val rrsMs: List<Int>,
    val contactSupported: Boolean,
    val contact: Boolean,
)

/**
 * The SDK-facing part of HR streaming: opens the strap's heart-rate notification stream.
 * In the app this is `PolarBleApi.startHrStreaming`; in tests a fake.
 *
 * Collecting the flow subscribes (enables notifications on the strap); cancelling the
 * collection unsubscribes. The flow may end with an error or complete at any time.
 */
fun interface HrStreamOpener {
    fun open(deviceId: String): Flow<List<HrReading>>
}

/** Timing of HR stream recovery. The only place these numbers are written. */
object HrStreamPolicy {
    /**
     * Connected but no notification at all for this long: the subscription is dead, resubscribe.
     *
     * The H10 sends a notification about once a second even when it is off the skin (heart
     * rate 0, contact false), so 5 s of total silence is never "strap loose", always a dead
     * stream. Deliberately well below the engine's 10 s "Sensor signal lost" threshold
     * (CoachingConfig.sensorGapThresholdMs), so a stall is usually repaired before the
     * runner hears about it.
     */
    const val STALL_TIMEOUT_MS = 5_000L

    /** Wait before the first resubscribe after a failure; doubles per consecutive failure. */
    const val RETRY_INITIAL_MS = 1_000L

    /** Upper bound of the backoff, so recovery stays quick even after many failures. */
    const val RETRY_MAX_MS = 8_000L

    /** Backoff before resubscribe number [failures] (1 = first failure in a row). */
    fun retryDelayMs(failures: Int): Long {
        val shift = (failures - 1).coerceIn(0, 10)
        return (RETRY_INITIAL_MS shl shift).coerceAtMost(RETRY_MAX_MS)
    }
}

/** Why a subscription ended and is being replaced. */
enum class HrStreamEnd {
    /** The SDK's flow threw (link hiccup, GATT error, "service not ready yet"). */
    ERROR,

    /** The SDK's flow completed normally although we still wanted data. */
    COMPLETED,

    /** Subscribed, but no notification for [HrStreamPolicy.STALL_TIMEOUT_MS] (watchdog). */
    STALLED,
}

/**
 * Keeps exactly one HR subscription alive while the strap is connected, and repairs it.
 *
 * Root cause of the 0.3.0 "Sensor signal lost while Battery 100 %" bug (Polar BLE SDK 8.6.0,
 * read from its source): the strap's HR notifications are switched on and off by writing the
 * one CCCD descriptor of the Heart Rate Measurement characteristic. `BleHrClient` writes
 * "notify ON" when ANY subscriber starts and "notify OFF" when ANY subscriber ends; there is
 * no reference counting. At the same time the SDK reports FEATURE_HR "ready" up to three
 * times per (re)connection (once at once, once after service discovery, once from its feature
 * check). The 0.3.0 code reacted to every "ready" by cancelling its running subscription and
 * starting a new one at the same moment. The cancelled subscription's teardown (OFF) was
 * queued on the GATT after the new subscription's ON, so the strap stopped notifying while the
 * link, battery service and the new subscription all stayed up. Nothing errors in that state,
 * no further "ready" comes, so the stream never came back until the connection was rebuilt.
 * The first "ready" also comes before service discovery, so that subscription simply fails;
 * the old code logged that and never retried either.
 *
 * Fixes, all here:
 * - [start] is idempotent: a repeated "ready" does not touch a running subscription.
 * - A subscription is only opened after the previous one has fully finished (cancelAndJoin),
 *   so its OFF write is always queued before the next ON.
 * - Error or completion while still wanted: resubscribe after a backoff.
 * - Watchdog: no notification for [HrStreamPolicy.STALL_TIMEOUT_MS] -> cancel, resubscribe.
 * - Backoff ([HrStreamPolicy.retryDelayMs]) between attempts, reset by the first notification,
 *   so a strap that keeps failing cannot make this spin.
 *
 * Threading: call [start]/[stop] from [scope]'s (single) thread; the app uses the main thread.
 *
 * @param onReading every notification of the current subscription.
 * @param onRestart a subscription ended and will be replaced (for logging and for clearing
 *   state such as skin contact that is unknown until data flows again).
 */
class HrStreamSupervisor(
    private val scope: CoroutineScope,
    private val clock: MillisClock,
    private val opener: HrStreamOpener,
    private val onReading: (HrReading) -> Unit,
    private val onRestart: (reason: HrStreamEnd, detail: String?, retryInMs: Long) -> Unit,
) {
    private var job: Job? = null
    private var currentDeviceId: String? = null

    /** True while a subscription loop is running. */
    val isRunning: Boolean get() = job?.isActive == true

    /**
     * The strap [deviceId] is connected and its HR service is (reported) ready: make sure a
     * subscription loop runs. Does nothing if one already runs for this device.
     */
    fun start(deviceId: String) {
        if (isRunning && currentDeviceId == deviceId) return
        val previous = job
        currentDeviceId = deviceId
        job =
            scope.launch {
                // Let the old subscription's teardown (notify OFF) reach the GATT queue first.
                previous?.cancelAndJoin()
                superviseLoop(deviceId)
            }
    }

    /** The link is gone or the strap is no longer wanted: unsubscribe, no restarts. */
    fun stop() {
        job?.cancel()
        job = null
        currentDeviceId = null
    }

    private suspend fun superviseLoop(deviceId: String) {
        var failures = 0
        while (true) {
            val outcome = subscribeOnce(deviceId)
            failures = if (outcome.gotData) 1 else failures + 1
            val wait = HrStreamPolicy.retryDelayMs(failures)
            onRestart(outcome.end, outcome.detail, wait)
            delay(wait)
        }
    }

    private class Outcome(val end: HrStreamEnd, val detail: String?, val gotData: Boolean)

    /** Runs one subscription until it errors, completes or stalls. Returns after it is fully torn down. */
    private suspend fun subscribeOnce(deviceId: String): Outcome =
        coroutineScope {
            val ended = CompletableDeferred<Pair<HrStreamEnd, String?>>()
            var lastNotificationMs = clock.nowMs()
            var gotData = false
            val collector =
                launch {
                    try {
                        opener.open(deviceId).collect { readings ->
                            lastNotificationMs = clock.nowMs()
                            gotData = true
                            readings.forEach(onReading)
                        }
                        ended.complete(HrStreamEnd.COMPLETED to null)
                    } catch (e: Throwable) {
                        // The SDK ends a stream on disconnect by cancelling its channel, so its
                        // error reaches us as a CancellationException (cause BleDisconnected).
                        // Only rethrow if WE were cancelled; otherwise it is an SDK error.
                        currentCoroutineContext().ensureActive()
                        ended.complete(HrStreamEnd.ERROR to "${e.javaClass.simpleName}: ${e.message} ${e.cause?.javaClass?.simpleName ?: ""}".trim())
                    }
                }
            val watchdog =
                launch {
                    while (true) {
                        val silentMs = clock.nowMs() - lastNotificationMs
                        if (silentMs >= HrStreamPolicy.STALL_TIMEOUT_MS) {
                            ended.complete(HrStreamEnd.STALLED to "no notification for ${silentMs / 1000} s")
                            break
                        }
                        delay(HrStreamPolicy.STALL_TIMEOUT_MS - silentMs)
                    }
                }
            val (end, detail) = ended.await()
            watchdog.cancel()
            // Wait for the SDK flow's teardown before the caller may subscribe again.
            collector.cancelAndJoin()
            Outcome(end, detail, gotData)
        }
}
