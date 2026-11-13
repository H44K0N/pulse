package dev.tischbein.pulse.coaching

import dev.tischbein.pulse.sensor.sim.SimProfile
import dev.tischbein.pulse.sensor.sim.SimulatedHeartRateSource
import dev.tischbein.pulse.time.MillisClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * End to end: simulator -> runner -> engine, in accelerated (virtual) time. A 43-minute
 * session runs in well under a second. These check that each scripted profile produces a
 * sensible spoken sequence, not exact numbers.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SimulatedSessionTest {
    private suspend fun TestScope.simulate(
        profile: SimProfile,
        setup: SessionSetup,
        endAfterMs: Long? = null,
    ): SessionResult {
        val clock = MillisClock { testScheduler.currentTime }
        val engine = CoachingEngine(setup)
        val runner = CoachingRunner(engine, clock)
        val source = SimulatedHeartRateSource(profile, backgroundScope, clock, hrMax = setup.hrMax)
        val result = async { runner.run(source.samples) }
        runCurrent() // runner is now collecting samples
        source.connect()
        if (endAfterMs != null) {
            launch {
                delay(endAfterMs)
                runner.end()
            }
        }
        return result.await().also { source.disconnect() }
    }

    private fun SessionResult.count(type: EventType) = events.count { it.type == type }

    private fun SessionResult.corrections() =
        events.count { it.type in setOf(EventType.CORRECTION_HIGH, EventType.CORRECTION_LOW, EventType.RAMP_NUDGE) }

    private fun assertFullProtocol(r: SessionResult) {
        assertEquals(sec(2580), r.summary.durationMs)
        assertEquals(10, r.count(EventType.PHASE_START))
        assertEquals(12, r.count(EventType.COUNTDOWN))
        assertEquals(4, r.count(EventType.INTERVAL_RECAP))
        assertTrue(r.events.filter { it.type == EventType.INTERVAL_RECAP }.none { "Recovery" in it.text || "recovery" in it.text })
        assertEquals(1, r.count(EventType.MIDWAY))
        assertEquals(EventType.SESSION_SUMMARY, r.events.last().type)
        assertEquals(4, r.intervals.size)
    }

    @Test
    fun `good 4x4 holds steady with few corrections and good recovery`() =
        runTest {
            val r = simulate(SimProfile.GOOD_4X4, FOUR_BY_FOUR)
            assertFullProtocol(r)
            assertTrue(r.corrections() <= 1, "corrections: ${r.events}")
            assertEquals(0, r.count(EventType.RAMP_NUDGE))
            assertTrue(r.events.single { it.type == EventType.MIDWAY }.text.startsWith("Holding steady"))
            r.intervals.forEach {
                assertTrue(it.avgHr!! in 165..181, "avg $it")
                assertTrue(it.pctInZone > 70, "pct $it")
                assertTrue(it.hrr60!! in 20..60, "hrr $it")
            }
            assertTrue(r.gaps.isEmpty())
            assertEquals(0, r.count(EventType.SENSOR_LOST))
            assertNull(r.summary.newHrMax)
            // Every interval's recovery gets its own HRR60 cue with the measured value.
            val hrrCues = r.events.filter { it.type == EventType.HRR60 }
            assertEquals(r.intervals.map { "Recovery: down ${it.hrr60} beats." }, hrrCues.map { it.text })
        }

    @Test
    fun `fading 4x4 is called fading at midway and gets pushed late`() =
        runTest {
            val r = simulate(SimProfile.FADING_4X4, FOUR_BY_FOUR)
            assertFullProtocol(r)
            assertTrue(r.events.single { it.type == EventType.MIDWAY }.text.startsWith("Fading"))
            assertTrue(r.count(EventType.CORRECTION_LOW) >= 2)
            assertEquals(1, r.count(EventType.RAMP_NUDGE)) // interval 4 starts clearly low
            assertEquals(0, r.count(EventType.CORRECTION_HIGH))
            val avgs = r.intervals.map { it.avgHr!! }
            assertEquals(avgs.sortedDescending(), avgs)
        }

    @Test
    fun `sensor dropout is announced once, recorded as a gap, never interpolated`() =
        runTest {
            val r = simulate(SimProfile.SENSOR_DROPOUT, FOUR_BY_FOUR)
            assertFullProtocol(r)
            assertEquals(1, r.count(EventType.SENSOR_LOST))
            assertEquals(1, r.count(EventType.SENSOR_BACK))
            val gap = r.gaps.single()
            val work2 = r.phases.single { it.kind == PhaseKind.WORK && it.intervalNumber == 2 }
            assertTrue(gap.startMs > work2.startMs && gap.endMs < work2.endMs, "gap $gap in $work2")
            assertTrue(gap.endMs - gap.startMs in sec(18)..sec(22))
            assertTrue(r.samples.none { it.tMs > gap.startMs && it.tMs < gap.endMs })
            assertTrue(r.events.single { it.type == EventType.MIDWAY }.text.startsWith("Holding"))
            assertEquals(0, r.count(EventType.CORRECTION_LOW)) // the gap must not look like "below zone"
        }

    @Test
    fun `easy run with drift reports drift at halfway and at the end`() =
        runTest {
            val setup = SessionSetup(SessionMode.EASY, HR_MAX, easyTargetDurationMs = sec(40 * 60))
            val r = simulate(SimProfile.EASY_WITH_DRIFT, setup, endAfterMs = sec(40 * 60))
            assertEquals(sec(40 * 60), r.summary.durationMs)
            val drift = r.events.filter { it.type == EventType.DRIFT }
            assertEquals(2, drift.size)
            assertEquals(sec(20 * 60), drift[0].tMs)
            assertTrue(drift[0].text.startsWith("Halfway. Cardiac drift so far"))
            assertTrue(r.summary.driftPct!! in 2.0..8.0, "drift ${r.summary.driftPct}")
            assertEquals(0, r.corrections())
            assertTrue(r.summary.timeInTargetMs > sec(35 * 60))
            assertEquals(listOf(EventType.PHASE_START, EventType.DRIFT, EventType.DRIFT, EventType.SESSION_SUMMARY), r.events.map { it.type })
        }

    @Test
    fun `pause and end buttons work through the runner`() =
        runTest {
            val clock = MillisClock { testScheduler.currentTime }
            val engine = CoachingEngine(FOUR_BY_FOUR)
            val runner = CoachingRunner(engine, clock)
            val source = SimulatedHeartRateSource(SimProfile.GOOD_4X4, backgroundScope, clock, hrMax = HR_MAX)
            val result = async { runner.run(source.samples) }
            runCurrent()
            source.connect()
            delay(sec(100))
            runner.pause()
            delay(sec(30))
            assertTrue(engine.state.value.paused)
            runner.resume()
            delay(sec(70))
            runner.end()
            val r = result.await()
            source.disconnect()
            assertEquals(1, r.pauses.size)
            assertTrue(r.summary.durationMs in sec(199)..sec(201))
            assertEquals(1, r.phases.size) // still in warmup: 170 s of active time
        }
}
