package dev.tischbein.pulse.ui

import dev.tischbein.pulse.coaching.CoachingConfig
import dev.tischbein.pulse.coaching.IntervalStats
import dev.tischbein.pulse.coaching.MS_PER_MINUTE
import dev.tischbein.pulse.coaching.SessionMode
import dev.tischbein.pulse.coaching.SessionResult
import dev.tischbein.pulse.coaching.SessionSetup
import dev.tischbein.pulse.coaching.SessionSummary
import dev.tischbein.pulse.data.TestSessions
import org.junit.Test
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionDetailTest {
    private val utc = ZoneId.of("UTC")

    private fun result(
        mode: SessionMode,
        summary: SessionSummary,
        intervals: List<IntervalStats> = emptyList(),
    ) = SessionResult(SessionSetup(mode, 190), CoachingConfig(), summary, emptyList(), emptyList(), emptyList(), emptyList(), intervals, emptyList())

    @Test
    fun `4x4 detail has three stats, the interval table and the learned HRmax`() {
        val d =
            sessionDetail(
                result(
                    SessionMode.INTERVAL_4X4,
                    SessionSummary(43 * MS_PER_MINUTE, 151, 194, 14 * MS_PER_MINUTE, newHrMax = 194, driftPct = null),
                    listOf(IntervalStats(1, 172, 180, 87.6, 34), IntervalStats(2, null, null, 0.0, null)),
                ),
                TestSessions.STARTED_AT,
                utc,
            )
        assertEquals("4×4 intervals", d.title)
        assertEquals("Sat 14 Nov · 17:02", d.subtitle)
        assertEquals(listOf("Duration", "Avg HR", "HRR60"), d.stats.map { it.label })
        assertEquals(listOf("43:00", "151", "34"), d.stats.map { it.value })
        assertEquals(IntervalRow(1, "172", "180", "88 %", "34"), d.intervals[0])
        assertEquals(IntervalRow(2, "–", "–", "0 %", "–"), d.intervals[1])
        assertEquals("New max heart rate: 194 bpm", d.newHrMax)
        assertTrue(d.complete)
    }

    @Test
    fun `easy detail shows drift instead of HRR60, and marks simulated and incomplete sessions`() {
        val d =
            sessionDetail(
                result(SessionMode.EASY, SessionSummary(60 * MS_PER_MINUTE, null, null, 30 * MS_PER_MINUTE, newHrMax = null, driftPct = 4.2)),
                TestSessions.STARTED_AT,
                utc,
                simulated = true,
                complete = false,
            )
        assertEquals("Easy run", d.title)
        assertEquals("Sat 14 Nov · 17:02 · Simulator", d.subtitle)
        assertEquals(listOf("1:00:00", "–", "+4.2"), d.stats.map { it.value })
        assertEquals(50, d.inZonePct)
        assertEquals(emptyList(), d.intervals)
        assertNull(d.newHrMax)
        assertEquals(false, d.complete)
    }

    @Test
    fun `detail of a real engine result shows its gap`() {
        val d = sessionDetail(TestSessions.fourByFourResult(), TestSessions.STARTED_AT, utc)
        assertEquals(2, d.intervals.size)
        assertTrue(d.gapsText!!.startsWith("1 signal gap"))
    }
}
