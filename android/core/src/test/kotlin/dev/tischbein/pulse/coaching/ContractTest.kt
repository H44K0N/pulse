package dev.tischbein.pulse.coaching

import kotlin.test.Test
import kotlin.test.assertEquals

/** Keeps the engine's names in line with docs/session-format.md. */
class ContractTest {
    @Test
    fun `event types match the session format`() {
        assertEquals(
            listOf(
                "phase_start", "countdown", "ramp_nudge", "correction_high", "correction_low",
                "interval_recap", "midway", "drift", "sensor_lost", "sensor_back",
                "session_summary", "hr_max_learned",
            ),
            EventType.entries.map { it.wireName },
        )
    }

    @Test
    fun `phase kinds and modes match the session format`() {
        assertEquals(listOf("warmup", "work", "recovery", "cooldown", "easy"), PhaseKind.entries.map { it.wireName })
        assertEquals(listOf("interval_4x4", "easy"), SessionMode.entries.map { it.wireName })
    }
}
