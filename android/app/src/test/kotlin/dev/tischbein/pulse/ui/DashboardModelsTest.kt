package dev.tischbein.pulse.ui

import dev.tischbein.pulse.coaching.SessionMode
import dev.tischbein.pulse.sensor.sim.SimProfile
import dev.tischbein.pulse.ui.design.Trend
import dev.tischbein.pulse.ui.mockups.MockData
import dev.tischbein.pulse.voice.VoiceStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DashboardModelsTest {
    @Test
    fun `chart buckets use the median, so one bad beat draws no spike, and gaps stay null`() {
        val run = MockData.run(dev.tischbein.pulse.coaching.SessionSetup(SessionMode.EASY, MockData.HR_MAX), untilSec = 60) { t ->
            when (t / 1000) {
                in 21L..30L -> null // link down
                12L -> 230 // one wild reading
                else -> 120
            }
        }
        val buckets = bucketHr(run.result, 5)
        assertEquals(12, buckets.size)
        assertEquals(120, buckets[2]) // 11-15 s contains the 230
        assertNull(buckets[4])
        assertNull(buckets[5])
        assertEquals(120, buckets[6])
    }

    @Test
    fun `live dashboard places interval 2 on the timeline and reports phase progress`() {
        val run = MockData.simRun(SimProfile.GOOD_4X4, untilSec = 1140)
        val d = liveDashboard(run.states.last(), VoiceStatus.Ready, MockData.device, MockData.config.fourByFour, run.recent(1140))
        assertEquals("2:00", d.bigTime) // work 2 runs 1020-1260 s
        assertEquals(0.5f, d.ringProgress, 0.01f)
        assertEquals(1140f / 2580f, d.timelinePosition, 0.01f)
        assertEquals(120, d.recentHr.size)
        assertEquals(2, d.intervalNumber)
    }

    @Test
    fun `progress compares the last 3 weeks with the 3 before`() {
        val history = MockData.history().map { it.first }
        val p = progressOverview(history, MockData.today, MockData.zone, MockData.HR_MAX, hrMaxLearned = true)
        val sessions = p.compare.first()
        assertEquals("${history.count { it.startedAtEpochMs >= MockData.epochAt(MockData.today.minusDays(20), 0, 0) }}", sessions.value)
        assertEquals(6, p.calendar.size)
        assertTrue(p.calendar.last().any { it.isToday })
        // The synthetic athlete gets fitter: recovery up, drift down.
        assertEquals(Trend.UP_GOOD, p.hrr.trend)
        assertEquals(Trend.DOWN_GOOD, p.drift.trend)
        assertEquals(history.size, p.sessions.size)
        assertTrue(p.sessions.zipWithNext().all { (a, b) -> a.date != b.date })
    }
}
