package dev.tischbein.pulse.coaching

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HrMaxTest {
    @Test
    fun `Tanaka estimate for 26 years is 190`() {
        assertEquals(190, estimateHrMax(26))
        assertEquals(180, estimateHrMax(40))
    }

    @Test
    fun `learner raises only after 5 s sustained, to the value held for the whole window`() {
        val learner = HrMaxLearner(190, sustainMs = 5_000)
        val values = listOf(192, 196, 195, 194, 193)
        values.forEachIndexed { i, v -> assertFalse(learner.update(i * 1_000L, v)) }
        assertEquals(190, learner.hrMax)
        assertTrue(learner.update(5_000, 197)) // window 0..5 s covered, minimum = 192
        assertEquals(192, learner.hrMax)
    }

    @Test
    fun `learner never lowers HRmax`() {
        val learner = HrMaxLearner(190, sustainMs = 5_000)
        for (s in 0L..60L) learner.update(s * 1_000, 170)
        assertEquals(190, learner.hrMax)
    }

    @Test
    fun `a gap breaks the sustained run`() {
        val learner = HrMaxLearner(190, sustainMs = 5_000)
        for (s in 0L..3L) learner.update(s * 1_000, 195)
        learner.update(4_000, null)
        for (s in 5L..9L) learner.update(s * 1_000, 195)
        assertEquals(190, learner.hrMax)
        learner.update(10_000, 195)
        assertEquals(195, learner.hrMax)
    }

    @Test
    fun `engine learns from sustained HR, ignores a short burst and reports after the session`() {
        val d = EngineDriver(SessionSetup(SessionMode.EASY, HR_MAX))
        d.runUntil(sec(600)) { t ->
            val s = t / 1000
            when (s) {
                in 100L..103L -> 199 // 4 readings: smoothed above for only 3 s
                in 200L..209L -> 196 // 10 readings: smoothed 196 for 9 s
                else -> 125
            }
        }
        assertEquals(196, d.engine.state.value.learnedHrMax)
        assertEquals(HR_MAX, d.engine.state.value.hrMaxUsed)
        // Learned mid-session but only spoken at the end.
        assertTrue(d.events(EventType.HR_MAX_LEARNED).isEmpty())
        val result = d.engine.end(sec(600))
        assertEquals(196, result.summary.newHrMax)
        assertEquals("New max heart rate recorded: 196.", d.events(EventType.HR_MAX_LEARNED).single().text)
        assertEquals(EventType.HR_MAX_LEARNED, result.events.last().type)
    }

    @Test
    fun `engine does not learn from a burst shorter than 5 s`() {
        val d = EngineDriver(SessionSetup(SessionMode.EASY, HR_MAX))
        d.runUntil(sec(300)) { t -> if (t / 1000 in 100L..104L) 199 else 125 } // smoothed 199 for only 4 s
        val result = d.engine.end(sec(300))
        assertNull(result.summary.newHrMax)
        assertTrue(d.events(EventType.HR_MAX_LEARNED).isEmpty())
    }
}
