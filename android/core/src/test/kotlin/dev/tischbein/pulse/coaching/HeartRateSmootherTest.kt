package dev.tischbein.pulse.coaching

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HeartRateSmootherTest {
    private fun smoother() = HeartRateSmoother(windowMs = 5_000, minSamples = 3)

    @Test
    fun `median ignores a single outlier`() {
        val s = smoother()
        listOf(150, 151, 240, 152, 150).forEachIndexed { i, hr -> s.add(i * 1_000L, hr) }
        assertEquals(151, s.valueAt(4_000))
    }

    @Test
    fun `even count averages the two middle values`() {
        val s = smoother()
        listOf(150, 152, 160, 170).forEachIndexed { i, hr -> s.add(i * 1_000L, hr) }
        assertEquals(156, s.valueAt(3_000))
    }

    @Test
    fun `old readings leave the window and too few readings give null`() {
        val s = smoother()
        listOf(150, 151, 152).forEachIndexed { i, hr -> s.add(i * 1_000L, hr) }
        assertEquals(151, s.valueAt(2_000))
        assertEquals(151, s.valueAt(4_999))
        assertNull(s.valueAt(5_500)) // the 0 s reading expired: only 2 left, fewer than 3
        assertNull(s.valueAt(60_000))
    }
}
