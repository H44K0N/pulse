package dev.tischbein.pulse.coaching

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SensorGapTest {
    @Test
    fun `gap longer than 10 s is announced once, recovered once, and recorded`() {
        val d = EngineDriver(FOUR_BY_FOUR)
        d.runUntil(sec(300)) { t -> if (t / 1000 in 101..119) NO_SIGNAL else 125 }

        assertEquals(listOf(sec(111)), d.eventTimes(EventType.SENSOR_LOST))
        assertEquals("Sensor signal lost.", d.events(EventType.SENSOR_LOST).single().text)
        assertEquals(listOf(sec(120)), d.eventTimes(EventType.SENSOR_BACK))
        assertEquals("Sensor back.", d.events(EventType.SENSOR_BACK).single().text)
        val result = d.engine.end(sec(300))
        assertEquals(listOf(Gap(sec(100), sec(120))), result.gaps)
        // Never interpolated: no samples were invented for the gap.
        assertTrue(result.samples.none { it.tMs in sec(101)..sec(119) })
    }

    @Test
    fun `short dropouts are not announced or recorded`() {
        val d = EngineDriver(FOUR_BY_FOUR)
        d.runUntil(sec(300)) { t -> if (t / 1000 in 200..207) NO_SIGNAL else 125 }
        val result = d.engine.end(sec(300))
        assertTrue(d.events(EventType.SENSOR_LOST).isEmpty())
        assertTrue(result.gaps.isEmpty())
    }

    @Test
    fun `samples without valid HR count as a gap and are kept as null`() {
        val d = EngineDriver(FOUR_BY_FOUR)
        d.runUntil(sec(400)) { t -> if (t / 1000 in 300..315) null else 125 }
        val result = d.engine.end(sec(400))
        assertEquals(listOf(sec(310)), d.eventTimes(EventType.SENSOR_LOST))
        assertEquals(listOf(Gap(sec(299), sec(316))), result.gaps)
        assertEquals(16, result.samples.count { it.hr == null })
    }

    @Test
    fun `no sensor at all from the start is reported`() {
        val d = EngineDriver(FOUR_BY_FOUR)
        d.runUntil(sec(30)) { NO_SIGNAL }
        assertEquals(listOf(sec(11)), d.eventTimes(EventType.SENSOR_LOST))
        val result = d.engine.end(sec(30))
        assertEquals(listOf(Gap(0, sec(30))), result.gaps)
        assertNull(result.summary.avgHr)
    }

    @Test
    fun `a gap is neither in nor out of zone and restarts the correction timer`() {
        val d = EngineDriver(FOUR_BY_FOUR)
        d.runUntil(sec(800)) { t ->
            val s = t / 1000
            when {
                s < 600 -> 125
                s < 700 -> 172
                s in 711..722 -> NO_SIGNAL
                else -> 190
            }
        }
        // Above from 702 s, but the gap (smoothed HR unknown from 713 s) interrupts it before
        // 15 s. After the signal returns at 723 s the smoothed value is back at 725 s -> cue at 740 s.
        assertEquals(sec(740), d.eventTimes(EventType.CORRECTION_HIGH).first())
        assertEquals(listOf(sec(721)), d.eventTimes(EventType.SENSOR_LOST))
        assertEquals(listOf(sec(723)), d.eventTimes(EventType.SENSOR_BACK))
        assertEquals(ZoneState.ABOVE, d.engine.state.value.zoneState)
    }

    @Test
    fun `during a gap the zone is unknown`() {
        val d = EngineDriver(FOUR_BY_FOUR)
        d.runUntil(sec(120)) { t -> if (t < sec(100)) 125 else NO_SIGNAL }
        val state = d.engine.state.value
        assertEquals(ZoneState.UNKNOWN, state.zoneState)
        assertNull(state.smoothedHr)
        assertTrue(state.sensorLost)
    }

    @Test
    fun `a gap still open at the end is closed at the end`() {
        val d = EngineDriver(FOUR_BY_FOUR)
        d.runUntil(sec(200)) { t -> if (t < sec(150)) 125 else NO_SIGNAL }
        val result = assertNotNull(d.engine.end(sec(200)))
        assertEquals(listOf(Gap(sec(149), sec(200))), result.gaps)
    }
}
