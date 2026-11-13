package dev.tischbein.pulse.sensor

import dev.tischbein.pulse.coaching.CoachingConfig
import dev.tischbein.pulse.time.MillisClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The HR stream recovery logic against a fake SDK. The fake mimics what matters of the Polar
 * SDK 8.6.0: one subscription per collection, a teardown when the collection ends, errors
 * delivered as a CancellationException (the SDK cancels its channel on disconnect), and the
 * possibility of a subscription that is open but silent (notifications switched off).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HrStreamSupervisorTest {
    /** One subscription the supervisor opened. */
    private class Sub(val openedAtMs: Long) {
        val channel = Channel<List<HrReading>>(Channel.UNLIMITED)
        var closedAtMs: Long? = null
    }

    private class FakeSdk(private val now: () -> Long) : HrStreamOpener {
        val subs = mutableListOf<Sub>()
        var active = 0
        var maxConcurrent = 0

        override fun open(deviceId: String): Flow<List<HrReading>> =
            callbackFlow {
                val sub = Sub(now())
                subs += sub
                active++
                maxConcurrent = maxOf(maxConcurrent, active)
                // Same bridge as the SDK's ChannelUtils.monitorNotifications.
                launch {
                    try {
                        for (r in sub.channel) send(r)
                        close()
                    } catch (e: Throwable) {
                        close(e)
                    }
                }
                awaitClose {
                    active--
                    sub.closedAtMs = now()
                }
            }
    }

    private class Harness(val test: TestScope) {
        val clock = MillisClock { test.testScheduler.currentTime }
        val sdk = FakeSdk { test.testScheduler.currentTime }
        val readings = mutableListOf<HrReading>()
        val restarts = mutableListOf<Pair<HrStreamEnd, Long>>()
        val supervisor =
            HrStreamSupervisor(test.backgroundScope, clock, sdk, { readings += it }, { reason, _, retry -> restarts += reason to retry })

        fun beat(sub: Sub = sdk.subs.last()) {
            sub.channel.trySend(listOf(HrReading(140, listOf(430), contactSupported = true, contact = true)))
        }
    }

    private fun TestScope.harness() = Harness(this)

    @Test
    fun `forwards readings of a healthy stream and never resubscribes`() =
        runTest {
            val h = harness()
            h.supervisor.start("A1")
            runCurrent()
            repeat(60) {
                h.beat()
                advanceTimeBy(1_000)
            }
            assertEquals(60, h.readings.size)
            assertEquals(1, h.sdk.subs.size)
            assertTrue(h.restarts.isEmpty())
        }

    @Test
    fun `repeated FEATURE_HR ready does not touch a running subscription`() =
        runTest {
            val h = harness()
            h.supervisor.start("A1")
            runCurrent()
            h.supervisor.start("A1")
            h.supervisor.start("A1")
            runCurrent()
            h.beat()
            runCurrent()
            assertEquals(1, h.sdk.subs.size)
            assertEquals(1, h.readings.size)
        }

    @Test
    fun `stream error while connected resubscribes after backoff`() =
        runTest {
            val h = harness()
            h.supervisor.start("A1")
            runCurrent()
            h.beat()
            runCurrent()
            h.sdk.subs.last().channel.close(IOException("GATT error 133"))
            runCurrent()
            assertEquals(listOf(HrStreamEnd.ERROR to HrStreamPolicy.RETRY_INITIAL_MS), h.restarts)
            assertEquals(1, h.sdk.subs.size)
            advanceTimeBy(HrStreamPolicy.RETRY_INITIAL_MS + 1)
            assertEquals(2, h.sdk.subs.size)
            h.beat()
            runCurrent()
            assertEquals(2, h.readings.size)
        }

    @Test
    fun `SDK disconnect error arriving as CancellationException is treated as an error, not as our cancellation`() =
        runTest {
            val h = harness()
            h.supervisor.start("A1")
            runCurrent()
            h.beat()
            runCurrent()
            // What ChannelUtils.postDisconnectedAndClearList does to the observer channel.
            h.sdk.subs.last().channel.cancel(CancellationException("Channel closed due to error", IOException("BleDisconnected")))
            runCurrent()
            advanceTimeBy(HrStreamPolicy.RETRY_INITIAL_MS + 1)
            assertEquals(HrStreamEnd.ERROR, h.restarts.single().first)
            assertEquals(2, h.sdk.subs.size)
        }

    @Test
    fun `stream completing normally while connected resubscribes`() =
        runTest {
            val h = harness()
            h.supervisor.start("A1")
            runCurrent()
            h.beat()
            runCurrent()
            h.sdk.subs.last().channel.close()
            advanceTimeBy(HrStreamPolicy.RETRY_INITIAL_MS + 1)
            assertEquals(HrStreamEnd.COMPLETED, h.restarts.single().first)
            assertEquals(2, h.sdk.subs.size)
        }

    @Test
    fun `watchdog resubscribes a silent stream well before the engine reports the sensor lost`() =
        runTest {
            val h = harness()
            h.supervisor.start("A1")
            runCurrent()
            h.beat()
            runCurrent()
            // The 0.3.0 failure: subscription open, link up, but no notifications any more.
            val silentFrom = currentTime
            advanceTimeBy(HrStreamPolicy.STALL_TIMEOUT_MS - 1)
            assertEquals(1, h.sdk.subs.size)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(HrStreamEnd.STALLED, h.restarts.single().first)
            assertEquals(silentFrom + HrStreamPolicy.STALL_TIMEOUT_MS, h.sdk.subs[0].closedAtMs)
            advanceTimeBy(HrStreamPolicy.RETRY_INITIAL_MS)
            runCurrent()
            assertEquals(2, h.sdk.subs.size)
            val recoveredAt = h.sdk.subs[1].openedAtMs - silentFrom
            assertTrue(recoveredAt < CoachingConfig().sensorGapThresholdMs, "resubscribed after $recoveredAt ms")
        }

    @Test
    fun `stall timeout is comfortably below the engine's sensor-lost threshold`() {
        assertTrue(HrStreamPolicy.STALL_TIMEOUT_MS + HrStreamPolicy.RETRY_INITIAL_MS < CoachingConfig().sensorGapThresholdMs)
    }

    @Test
    fun `strap without contact keeps notifying and is not treated as a stall`() =
        runTest {
            val h = harness()
            h.supervisor.start("A1")
            runCurrent()
            repeat(30) {
                h.sdk.subs.last().channel.trySend(listOf(HrReading(0, emptyList(), contactSupported = true, contact = false)))
                advanceTimeBy(1_000)
            }
            assertTrue(h.restarts.isEmpty())
            assertEquals(1, h.sdk.subs.size)
        }

    @Test
    fun `repeated failures back off exponentially up to the cap and cannot spin`() =
        runTest {
            val h = harness()
            h.supervisor.start("A1")
            // Every subscription is silent (stalls): one minute of that.
            advanceTimeBy(60_000)
            val waits = h.restarts.map { it.second }
            assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 8_000L), waits.take(5))
            assertTrue(h.sdk.subs.size <= 60_000 / HrStreamPolicy.STALL_TIMEOUT_MS + 1, "${h.sdk.subs.size} subscriptions")
            assertTrue(h.restarts.all { it.first == HrStreamEnd.STALLED })
        }

    @Test
    fun `failing immediately (service not ready yet) also backs off`() =
        runTest {
            val h = harness()
            val failing = HrStreamOpener { kotlinx.coroutines.flow.flow { throw IllegalStateException("service not ready") } }
            var opens = 0
            val supervisor =
                HrStreamSupervisor(backgroundScope, h.clock, { id -> opens++; failing.open(id) }, {}, { r, _, w -> h.restarts += r to w })
            supervisor.start("A1")
            advanceTimeBy(30_000)
            // 1 + 2 + 4 + 8 + 8 + 8 = 31 s: six or fewer attempts in 30 s, not thousands.
            assertTrue(opens in 4..6, "$opens attempts")
        }

    @Test
    fun `backoff resets once data flows again`() =
        runTest {
            val h = harness()
            h.supervisor.start("A1")
            advanceTimeBy(HrStreamPolicy.STALL_TIMEOUT_MS + 1_000 + HrStreamPolicy.STALL_TIMEOUT_MS + 2_000 + 1)
            assertEquals(3, h.sdk.subs.size)
            h.beat()
            runCurrent()
            h.sdk.subs.last().channel.close(IOException("hiccup"))
            runCurrent()
            assertEquals(HrStreamPolicy.RETRY_INITIAL_MS, h.restarts.last().second)
        }

    @Test
    fun `old subscription is fully torn down before the next one opens`() =
        runTest {
            val h = harness()
            h.supervisor.start("A1")
            runCurrent()
            // Disconnect and immediate reconnect, as the SDK's callbacks do it.
            h.supervisor.stop()
            h.supervisor.start("A1")
            runCurrent()
            h.sdk.subs.last().channel.close(IOException("x"))
            advanceTimeBy(20_000)
            assertEquals(1, h.sdk.maxConcurrent)
            for ((prev, next) in h.sdk.subs.zipWithNext()) {
                assertTrue(prev.closedAtMs != null && prev.closedAtMs!! <= next.openedAtMs)
            }
        }

    @Test
    fun `stop unsubscribes and nothing restarts afterwards`() =
        runTest {
            val h = harness()
            h.supervisor.start("A1")
            runCurrent()
            h.supervisor.stop()
            advanceTimeBy(60_000)
            assertEquals(1, h.sdk.subs.size)
            assertEquals(0, h.sdk.active)
            assertTrue(h.restarts.isEmpty())
        }

    @Test
    fun `full disconnect and reconnect resumes the stream`() =
        runTest {
            val h = harness()
            h.supervisor.start("A1")
            runCurrent()
            h.beat()
            runCurrent()
            // deviceDisconnected: the SDK errors the stream and the app stops the supervisor.
            h.sdk.subs.last().channel.close(IOException("BleDisconnected"))
            h.supervisor.stop()
            advanceTimeBy(30_000)
            assertEquals(1, h.sdk.subs.size)
            // Reconnected: FEATURE_HR ready (three times, like the SDK).
            repeat(3) { h.supervisor.start("A1") }
            runCurrent()
            assertEquals(2, h.sdk.subs.size)
            h.beat()
            runCurrent()
            assertEquals(2, h.readings.size)
        }
}
