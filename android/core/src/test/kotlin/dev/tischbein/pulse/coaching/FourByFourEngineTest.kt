package dev.tischbein.pulse.coaching

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FourByFourEngineTest {
    @Test
    fun `phases follow the default protocol and the session finishes by itself`() {
        val d = EngineDriver(FOUR_BY_FOUR)
        d.runToEnd(protocolHr())

        val result = assertNotNull(d.engine.result)
        assertEquals(sec(2580), result.summary.durationMs)
        val kinds = result.phases.map { it.kind }
        assertEquals(
            listOf(PhaseKind.WARMUP) +
                List(4) { listOf(PhaseKind.WORK, PhaseKind.RECOVERY) }.flatten() +
                PhaseKind.COOLDOWN,
            kinds,
        )
        assertEquals(sec(0), result.phases[0].startMs)
        assertEquals(sec(600), result.phases[0].endMs)
        val work2 = result.phases.first { it.kind == PhaseKind.WORK && it.intervalNumber == 2 }
        assertEquals(sec(1020), work2.startMs)
        assertEquals(sec(1260), work2.endMs)
        // 85-95 % of 190
        assertEquals(162, work2.targetLow)
        assertEquals(181, work2.targetHigh)
        assertEquals(114, result.phases[0].targetLow)
        assertEquals(133, result.phases[0].targetHigh)
        assertEquals(sec(2580), result.phases.last().endMs)

        assertEquals(
            listOf(0L, 600, 840, 1020, 1260, 1440, 1680, 1860, 2100, 2280).map { sec(it) },
            d.eventTimes(EventType.PHASE_START),
        )
        assertEquals("Interval 2 of 4. Go.", d.events(EventType.PHASE_START)[3].text)
        assertEquals("Warmup. 10 minutes, easy pace.", d.events(EventType.PHASE_START)[0].text)
        assertEquals(1, d.events(EventType.SESSION_SUMMARY).size)
        assertTrue(d.engine.state.value.finished)
    }

    @Test
    fun `countdown 3 2 1 before every work interval and nowhere else`() {
        val d = EngineDriver(FOUR_BY_FOUR)
        d.runToEnd(protocolHr())

        val expected = (1..4).flatMap { n -> listOf(3L, 2, 1).map { sec(workStartSec(n) - it) } }
        assertEquals(expected, d.eventTimes(EventType.COUNTDOWN))
        assertEquals(listOf("3", "2", "1"), d.events(EventType.COUNTDOWN).take(3).map { it.text })
    }

    @Test
    fun `clearly low at 90 s gets a ramp nudge then corrections respect the 45 s cooldown`() {
        val d = EngineDriver(FOUR_BY_FOUR)
        d.runUntil(sec(recoveryStartSec(1) - 1)) { t -> if (t < sec(600)) 125 else 140 }

        // Nothing at all about being low during the first 90 s of the interval.
        assertEquals(listOf(sec(690)), d.eventTimes(EventType.RAMP_NUDGE))
        assertEquals("Push a bit harder.", d.events(EventType.RAMP_NUDGE).single().text)
        // Nudge counts as a correction: next cue 45 s later, then every 45 s.
        assertEquals(listOf(sec(735), sec(780), sec(825)), d.eventTimes(EventType.CORRECTION_LOW))
        assertEquals("Below zone, pick it up.", d.events(EventType.CORRECTION_LOW).first().text)
    }

    @Test
    fun `slightly low at 90 s gets no nudge, only the normal 15 s correction`() {
        val d = EngineDriver(FOUR_BY_FOUR)
        // 160 is 2 bpm under the zone (162): not "clearly" low.
        d.runUntil(sec(recoveryStartSec(1) - 1)) { t -> if (t < sec(600)) 125 else 160 }

        assertTrue(d.events(EventType.RAMP_NUDGE).isEmpty())
        assertEquals(listOf(sec(705), sec(750), sec(795)), d.eventTimes(EventType.CORRECTION_LOW))
    }

    @Test
    fun `above zone needs 15 s continuous, then at most one cue per 45 s`() {
        val d = EngineDriver(FOUR_BY_FOUR)
        d.runUntil(sec(recoveryStartSec(1) + 100)) { t ->
            when {
                t < sec(600) -> 125
                t < sec(721) -> 175
                else -> 190
            }
        }
        // Smoothed HR crosses 181 at 723 s (3 of 5 readings at 190), +15 s = 738 s.
        assertEquals(listOf(sec(738), sec(783), sec(828)), d.eventTimes(EventType.CORRECTION_HIGH))
        assertEquals("Above zone, ease off.", d.events(EventType.CORRECTION_HIGH).first().text)
        // Still 190 during recovery: recovery is not corrected (HR lags; nagging would be wrong).
    }

    @Test
    fun `going back into zone restarts the 15 s timer`() {
        val d = EngineDriver(FOUR_BY_FOUR)
        d.runUntil(sec(recoveryStartSec(1) - 1)) { t ->
            val s = t / 1000
            when {
                s < 600 -> 125
                s in 700..711 -> 190 // 10 s above (smoothed), not enough
                s in 712..730 -> 172 // back in zone
                s >= 731 -> 190
                else -> 172
            }
        }
        // Second excursion: smoothed above at 733, cue at 748.
        assertEquals(listOf(sec(748)), d.eventTimes(EventType.CORRECTION_HIGH).take(1))
    }

    @Test
    fun `single noisy beats never trigger a cue or inflate max or HRmax`() {
        val d = EngineDriver(FOUR_BY_FOUR)
        val base = protocolHr()
        d.runToEnd { t ->
            val s = t / 1000
            when {
                s % 7 == 0L -> 235
                s % 11 == 0L -> 60
                else -> base(t)
            }
        }
        val result = assertNotNull(d.engine.result)
        val cueTypes = setOf(EventType.CORRECTION_HIGH, EventType.CORRECTION_LOW, EventType.RAMP_NUDGE)
        assertTrue(result.events.none { it.type in cueTypes }, "unexpected cues: ${result.events}")
        assertNull(result.summary.newHrMax)
        assertEquals(172, result.summary.maxHr)
    }

    @Test
    fun `HRR60 is the smoothed drop 60 s after work ends and is spoken for every interval`() {
        val d = EngineDriver(FOUR_BY_FOUR)
        d.runToEnd(protocolHr(work = listOf(175, 175, 175, 175), recovery = 145))

        val result = assertNotNull(d.engine.result)
        assertEquals(listOf(30, 30, 30, 30), result.intervals.map { it.hrr60 })
        assertEquals(listOf(175, 175, 175, 175), result.intervals.map { it.avgHr })
        assertEquals(listOf(175, 175, 175, 175), result.intervals.map { it.peakHr })

        val hrrCues = d.events(EventType.HRR60)
        assertEquals((1..4).map { sec(recoveryStartSec(it) + 60) }, hrrCues.map { it.tMs })
        assertTrue(hrrCues.all { it.text == "Recovery: down 30 beats." }, hrrCues.toString())

        // The recap at the start of recovery is about the interval just finished only.
        val recaps = d.events(EventType.INTERVAL_RECAP)
        assertEquals(4, recaps.size)
        assertEquals(sec(recoveryStartSec(1)), recaps[0].tMs)
        recaps.forEachIndexed { i, recap ->
            assertTrue(recap.text.matches(Regex("""Interval ${i + 1}: average 175, \d+ percent in zone\.""")), recap.text)
        }
    }

    @Test
    fun `HRR60 cue reports each interval's own value`() {
        val d = EngineDriver(FOUR_BY_FOUR)
        val drops = listOf(20, 25, 34, 40)
        d.runToEnd { t ->
            val s = t / 1000
            val n = ((s - 600) / 420).toInt()
            if (s in 600 until 2280 && (s - 600) % 420 >= 240) 175 - drops[n] else protocolHr(work = listOf(175, 175, 175, 175))(t)
        }
        val result = assertNotNull(d.engine.result)
        assertEquals(drops, result.intervals.map { it.hrr60 })
        assertEquals(drops.map { "Recovery: down $it beats." }, d.events(EventType.HRR60).map { it.text })
    }

    @Test
    fun `HRR60 is null and not spoken when the sensor has no data at the 60 s mark`() {
        val d = EngineDriver(FOUR_BY_FOUR)
        val base = protocolHr(work = listOf(175, 175, 175, 175), recovery = 145)
        val r1 = recoveryStartSec(1)
        d.runToEnd { t -> if (t / 1000 in (r1 + 40)..(r1 + 65)) NO_SIGNAL else base(t) }

        val result = assertNotNull(d.engine.result)
        assertNull(result.intervals[0].hrr60)
        assertEquals(30, result.intervals[1].hrr60)
        assertEquals((2..4).map { sec(recoveryStartSec(it) + 60) }, d.eventTimes(EventType.HRR60))
    }

    @Test
    fun `HRR60 is measured and spoken on wall-clock time even while paused`() {
        val d = EngineDriver(FOUR_BY_FOUR)
        val hr = protocolHr(work = listOf(175, 175, 175, 175), recovery = 145)
        val r1 = recoveryStartSec(1)
        d.runUntil(sec(r1 + 30), hr)
        d.engine.pause(sec(r1 + 30))
        d.runUntil(sec(r1 + 90)) { 145 }
        assertEquals(listOf(sec(r1 + 60)), d.eventTimes(EventType.HRR60))
        assertEquals(30, d.engine.end(sec(r1 + 90)).intervals[0].hrr60)
    }

    @Test
    fun `HRR60 can be recorded without being spoken`() {
        val config = CoachingConfig(fourByFour = FourByFourConfig(speakHrr60 = false))
        val d = EngineDriver(FOUR_BY_FOUR, config)
        d.runToEnd(protocolHr(work = listOf(175, 175, 175, 175), recovery = 145))
        assertEquals(listOf(30, 30, 30, 30), assertNotNull(d.engine.result).intervals.map { it.hrr60 })
        assertTrue(d.events(EventType.HRR60).isEmpty())
    }

    @Test
    fun `HRR60 cue wording`() {
        assertEquals("Recovery: down 34 beats.", Cues.hrr60(34))
        assertEquals("Recovery: down 1 beat.", Cues.hrr60(1))
        assertEquals("Recovery: up 3 beats.", Cues.hrr60(-3))
    }

    @Test
    fun `percent in zone and time in target count work intervals`() {
        val d = EngineDriver(FOUR_BY_FOUR)
        d.runToEnd(protocolHr())
        val result = assertNotNull(d.engine.result)
        // Smoothing needs ~2 s to cross from 125 into the zone each interval.
        result.intervals.forEach { assertTrue(it.pctInZone in 98.0..100.0, "pct ${it.pctInZone}") }
        assertTrue(result.summary.timeInTargetMs in sec(4 * 235)..sec(4 * 240), "${result.summary.timeInTargetMs}")
    }

    private fun midwayFor(work: List<Int>): String {
        val d = EngineDriver(FOUR_BY_FOUR)
        d.runUntil(sec(recoveryStartSec(2) + 1), protocolHr(work = work))
        val midway = d.events(EventType.MIDWAY).single()
        assertEquals(sec(recoveryStartSec(2)), midway.tMs)
        return midway.text
    }

    @Test
    fun `midway says holding when interval 2 matches interval 1`() {
        assertEquals("Holding steady. Same again for the last 2.", midwayFor(listOf(172, 174, 0, 0)))
    }

    @Test
    fun `midway says fading when average HR drops more than 3 bpm`() {
        assertEquals(
            "Fading compared to the first interval. Dig in for the last 2.",
            midwayFor(listOf(175, 170, 0, 0)),
        )
    }

    @Test
    fun `midway says fading when time in zone drops a lot even if the average holds`() {
        val d = EngineDriver(FOUR_BY_FOUR)
        val w2 = workStartSec(2)
        val base = protocolHr(work = listOf(170, 0, 0, 0))
        // Interval 2: 2 min at 158 (below) and 2 min at 183 (above): average ~170, ~0 % in zone.
        d.runUntil(sec(recoveryStartSec(2) + 1)) { t ->
            val s = t / 1000
            when {
                s in w2 until w2 + 120 -> 158
                s in (w2 + 120) until (w2 + 240) -> 183
                else -> base(t)
            }
        }
        assertTrue(d.events(EventType.MIDWAY).single().text.startsWith("Fading"))
    }

    @Test
    fun `midway says room to push when both intervals sit low in the zone`() {
        assertEquals(
            "Holding, with room to push. Go a bit harder on the last 2.",
            midwayFor(listOf(166, 167, 0, 0)),
        )
    }

    @Test
    fun `pause freezes the phase timer and corrections, resume starts the correction timer over`() {
        val d = EngineDriver(FOUR_BY_FOUR)
        val hr: (Long) -> Int = { t -> if (t < sec(690)) protocolHr()(t) else 190 }
        d.runUntil(sec(700), hr)
        d.engine.pause(sec(700))
        d.runUntil(sec(760), hr)
        val pausedState = d.engine.state.value
        assertTrue(pausedState.paused)
        assertEquals(sec(140), pausedState.phaseRemainingMs) // frozen since 700 s
        d.engine.resume(sec(760))
        d.runUntil(sec(1000), hr)

        // Without the pause the cue would have come at 707 s. During the pause: silence.
        // After resume: 15 s fresh -> 775 s.
        assertEquals(sec(775), d.eventTimes(EventType.CORRECTION_HIGH).first())
        assertTrue(d.engine.eventLog.none { it.tMs in sec(701)..sec(774) })
        d.runToEnd(protocolHr())

        val result = assertNotNull(d.engine.result)
        assertEquals(listOf(Pause(sec(700), sec(760))), result.pauses)
        val work1 = result.phases.first { it.kind == PhaseKind.WORK }
        assertEquals(sec(900), work1.endMs) // 840 + 60 s pause
        assertEquals(sec(2580 + 60), result.summary.durationMs)
    }

    @Test
    fun `ending early closes the open phase and still produces a full result`() {
        val d = EngineDriver(FOUR_BY_FOUR)
        d.runUntil(sec(1100), protocolHr(work = listOf(175, 175, 175, 175), recovery = 145))
        val result = d.engine.end(sec(1100))

        assertEquals(sec(1100), result.summary.durationMs)
        assertEquals(4, result.phases.size)
        assertEquals(sec(1100), result.phases.last().endMs)
        assertEquals(listOf(1, 2), result.intervals.map { it.number })
        assertEquals(30, result.intervals[0].hrr60)
        assertNull(result.intervals[1].hrr60) // interval 2 never ended normally
        assertEquals(1, result.events.count { it.type == EventType.HRR60 }) // only interval 1's
        assertEquals(EventType.SESSION_SUMMARY, result.events.last().type)
        assertTrue(result.events.last().text.startsWith("Session complete. 18 minutes."))
        assertTrue(d.engine.isFinished)
        // Inputs after the end are ignored, end() is idempotent.
        d.engine.onTick(sec(2000))
        assertEquals(result, d.engine.end(sec(2000)))
    }

    @Test
    fun `state reports phase, interval, time left and zone`() {
        val d = EngineDriver(FOUR_BY_FOUR)
        d.runUntil(sec(workStartSec(2) + 60), protocolHr(work = listOf(172, 185, 172, 172)))
        val state = d.engine.state.value
        assertEquals(PhaseKind.WORK, state.phaseKind)
        assertEquals(2, state.intervalNumber)
        assertEquals(4, state.intervalCount)
        assertEquals(sec(180), state.phaseRemainingMs)
        assertEquals(185, state.smoothedHr)
        assertEquals(ZoneState.ABOVE, state.zoneState)
        assertEquals(162, state.targetLow)
        assertEquals(181, state.targetHigh)
    }
}
