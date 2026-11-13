package dev.tischbein.pulse.coaching

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EasyEngineTest {
    private val easy40 = SessionSetup(SessionMode.EASY, HR_MAX, easyTargetDurationMs = sec(40 * 60))

    @Test
    fun `easy corrections need 30 s out of zone and are at least 90 s apart`() {
        val d = EngineDriver(SessionSetup(SessionMode.EASY, HR_MAX))
        d.runUntil(sec(300)) { 150 } // easy zone is 114-133
        // Smoothed known (and above) from 3 s; +30 s = 33 s; then every 90 s.
        assertEquals(listOf(sec(33), sec(123), sec(213)), d.eventTimes(EventType.CORRECTION_HIGH))
    }

    @Test
    fun `easy mode does not complain about low HR during the first 3 minutes`() {
        val d = EngineDriver(SessionSetup(SessionMode.EASY, HR_MAX))
        d.runUntil(sec(400)) { 100 }
        assertEquals(listOf(sec(210), sec(300), sec(390)), d.eventTimes(EventType.CORRECTION_LOW))
    }

    @Test
    fun `drift is reported at halfway and at the end`() {
        val d = EngineDriver(easy40)
        // First 22.5 min at 120, then 126. Drift ignores the first 5 min.
        d.runUntil(sec(2400)) { t -> if (t < sec(1350)) 120 else 126 }
        val result = d.engine.end(sec(2400))

        val drift = d.events(EventType.DRIFT)
        assertEquals(2, drift.size)
        assertEquals(sec(1200), drift[0].tMs)
        assertEquals("Halfway. Cardiac drift so far 0 percent.", drift[0].text)
        // Window 5-40 min, halves split at 22.5 min: 120 vs 126 = +5.0 %.
        assertEquals("Cardiac drift 5 percent.", drift[1].text)
        assertEquals(5.0, result.summary.driftPct)
        assertTrue(result.summary.timeInTargetMs in sec(2395)..sec(2400))
        assertEquals(listOf(PhaseKind.EASY), result.phases.map { it.kind })
        assertTrue(result.intervals.isEmpty())
    }

    @Test
    fun `no halfway report without a target duration, drift still at the end`() {
        val d = EngineDriver(SessionSetup(SessionMode.EASY, HR_MAX))
        d.runUntil(sec(1800)) { t -> if (t < sec(1050)) 120 else 123 }
        assertNull(d.engine.state.value.phaseRemainingMs) // free-running: no countdown
        val result = d.engine.end(sec(1800))
        assertEquals(1, d.events(EventType.DRIFT).size)
        assertEquals(2.5, result.summary.driftPct)
    }

    @Test
    fun `too short for drift reports none`() {
        val d = EngineDriver(SessionSetup(SessionMode.EASY, HR_MAX))
        d.runUntil(sec(500)) { 120 }
        val result = d.engine.end(sec(500))
        assertNull(result.summary.driftPct)
        assertTrue(d.events(EventType.DRIFT).isEmpty())
    }

    @Test
    fun `easy state counts down to the target`() {
        val d = EngineDriver(easy40)
        d.runUntil(sec(600)) { 120 }
        val state = d.engine.state.value
        assertEquals(PhaseKind.EASY, state.phaseKind)
        assertEquals(sec(1800), state.phaseRemainingMs)
        assertEquals(ZoneState.IN, state.zoneState)
        assertNull(state.intervalNumber)
        assertNotNull(state.smoothedHr)
    }
}
