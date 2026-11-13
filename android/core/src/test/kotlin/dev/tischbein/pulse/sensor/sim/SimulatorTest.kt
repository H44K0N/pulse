package dev.tischbein.pulse.sensor.sim

import dev.tischbein.pulse.sensor.ConnectionState
import dev.tischbein.pulse.sensor.HeartRateSample
import dev.tischbein.pulse.time.MillisClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SimulatorTest {
    private fun series(profile: SimProfile, seconds: Int, seed: Long = 1): List<SimPoint> {
        val model = SimulatedHeartModel(profile, hrMax = 190, seed = seed)
        return (0..seconds).map { model.step(it * 1_000L) }
    }

    @Test
    fun `same profile and seed give identical readings`() {
        assertEquals(series(SimProfile.GOOD_4X4, 600), series(SimProfile.GOOD_4X4, 600))
    }

    @Test
    fun `HR ramps up with a lag at the start of a work interval and recovers afterwards`() {
        val hr = series(SimProfile.GOOD_4X4, 1100).map { it.hr ?: 0 }
        assertTrue(hr[0] in 70..82, "starts near rest: ${hr[0]}")
        assertTrue(hr[599] in 120..131, "warmup: ${hr[599]}")
        assertTrue(hr[610] in 130..160, "still ramping 10 s into work: ${hr[610]}")
        assertTrue(hr[830] in 172..183, "plateau late in work: ${hr[830]}")
        val drop60 = hr[840] - hr[900]
        assertTrue(drop60 in 25..55, "HRR60-like drop: $drop60")
    }

    @Test
    fun `RR intervals match the heart rate`() {
        series(SimProfile.GOOD_4X4, 1100).filter { it.hr != null }.forEachIndexed { i, p ->
            if (i % 157 == 0) return@forEachIndexed // bad-beat seconds report a wrong HR on purpose
            val rrHr = 60_000.0 / p.rrMs.average()
            assertTrue(abs(rrHr - p.hr!!) < 10, "hr ${p.hr} vs rr-derived $rrHr")
            assertTrue(p.rrMs.size in 1..4)
        }
    }

    @Test
    fun `fading profile is lower in later intervals`() {
        val good = series(SimProfile.GOOD_4X4, 2100)
        val fading = series(SimProfile.FADING_4X4, 2100)
        val lateWork3 = 1440 + 200
        assertTrue(fading[lateWork3].hr!! < good[lateWork3].hr!! - 10)
        assertTrue(fading[lateWork3].hr!! < 165, "around the bottom of the 85 % zone: ${fading[lateWork3].hr}")
    }

    @Test
    fun `dropout profile loses signal for 20 s in interval 2`() {
        val points = series(SimProfile.SENSOR_DROPOUT, 1300)
        val lost = points.indices.filter { points[it].signalLost }
        assertEquals((1140 until 1160).toList(), lost)
    }

    @Test
    fun `easy profile drifts upward`() {
        val hr = series(SimProfile.EASY_WITH_DRIFT, 3600).map { it.hr!! }
        val early = hr.subList(600, 900).average()
        val late = hr.subList(3300, 3600).average()
        assertTrue(late - early > 6, "early $early late $late")
        assertTrue(early in 114.0..133.0)
    }

    @Test
    fun `source emits timestamped samples in virtual time and reports dropout as reconnecting`() =
        runTest {
            val clock = MillisClock { testScheduler.currentTime }
            val source = SimulatedHeartRateSource(SimProfile.SENSOR_DROPOUT, backgroundScope, clock, hrMax = 190)
            assertEquals(ConnectionState.DISCONNECTED, source.connectionState.value)
            val received = mutableListOf<HeartRateSample>()
            backgroundScope.launch { source.samples.collect { received += it } }
            runCurrent()
            source.connect()
            advanceTimeBy(1_150_500)
            assertEquals(ConnectionState.CONNECTING, source.connectionState.value)
            advanceTimeBy(20_000)
            assertEquals(ConnectionState.CONNECTED, source.connectionState.value)
            assertEquals(true, source.skinContact.value)
            assertEquals(87, source.batteryPercent.value)
            source.disconnect()
            assertEquals(ConnectionState.DISCONNECTED, source.connectionState.value)

            assertEquals(0L, received.first().timestampMs)
            val times = received.map { it.timestampMs }
            assertTrue(times.none { it in 1_140_000 until 1_160_000 }, "no samples during dropout")
            assertEquals(1_171 - 20, received.size)
        }

    @Test
    fun `the first samples arrive one per second from connect`() =
        runTest {
            val clock = MillisClock { testScheduler.currentTime }
            val source = SimulatedHeartRateSource(SimProfile.GOOD_4X4, backgroundScope, clock, hrMax = 190)
            val job = async { source.samples.take(3).toList() }
            runCurrent()
            source.connect()
            val three = job.await()
            assertEquals(listOf(0L, 1_000L, 2_000L), three.map { it.timestampMs })
        }
}
