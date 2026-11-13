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
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SummaryUiTest {
    private fun result(
        mode: SessionMode,
        summary: SessionSummary,
        intervals: List<IntervalStats> = emptyList(),
    ) = SessionResult(SessionSetup(mode, 190), CoachingConfig(), summary, emptyList(), emptyList(), emptyList(), emptyList(), intervals, emptyList())

    @Test
    fun `4x4 summary with interval table and learned HRmax`() {
        val ui =
            summaryUi(
                result(
                    SessionMode.INTERVAL_4X4,
                    SessionSummary(43 * MS_PER_MINUTE, 151, 194, 14 * MS_PER_MINUTE, newHrMax = 194, driftPct = null),
                    listOf(IntervalStats(1, 172, 180, 87.6, 34), IntervalStats(2, null, null, 0.0, null)),
                ),
            )
        assertEquals("4x4 complete", ui.title)
        assertEquals("43:00", ui.duration)
        assertEquals("151", ui.avgHr)
        assertEquals("194", ui.maxHr)
        assertEquals("14:00 (33 %)", ui.timeInTarget)
        assertEquals(IntervalRow(1, "172", "180", "88 %", "34"), ui.intervals[0])
        assertEquals(IntervalRow(2, "--", "--", "0 %", "--"), ui.intervals[1])
        assertEquals("New max heart rate recorded: 194.", ui.newHrMax)
        assertNull(ui.drift)
    }

    @Test
    fun `easy summary shows drift`() {
        val ui =
            summaryUi(
                result(SessionMode.EASY, SessionSummary(60 * MS_PER_MINUTE, null, null, 0, newHrMax = null, driftPct = 4.2)),
            )
        assertEquals("Easy run complete", ui.title)
        assertEquals("--", ui.avgHr)
        assertEquals("+4.2 %", ui.drift)
        assertEquals(emptyList(), ui.intervals)
        assertNull(ui.newHrMax)
    }

    @Test
    fun `summary of a real engine result`() {
        val ui = summaryUi(TestSessions.fourByFourResult())
        assertEquals(2, ui.intervals.size)
    }
}
