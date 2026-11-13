package dev.tischbein.pulse.sensor

import kotlin.test.Test
import kotlin.test.assertEquals

class PolarMappingTest {
    @Test
    fun `valid reading keeps hr and rr`() {
        assertEquals(HeartRateSample(1_000, 150, listOf(400, 401)), polarReadingToSample(1_000, 150, listOf(400, 401), true, true))
    }

    @Test
    fun `zero bpm is no reading`() {
        assertEquals(HeartRateSample(1_000, null, emptyList()), polarReadingToSample(1_000, 0, emptyList(), true, true))
    }

    @Test
    fun `no skin contact is no reading, even if the strap still reports a number`() {
        assertEquals(HeartRateSample(1_000, null, emptyList()), polarReadingToSample(1_000, 72, listOf(830), true, false))
    }

    @Test
    fun `contact flag is ignored when the sensor does not support it`() {
        assertEquals(HeartRateSample(1_000, 72, listOf(830)), polarReadingToSample(1_000, 72, listOf(830), false, false))
    }
}
