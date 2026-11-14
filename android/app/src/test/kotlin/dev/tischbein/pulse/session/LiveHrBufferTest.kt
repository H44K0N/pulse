package dev.tischbein.pulse.session

import dev.tischbein.pulse.coaching.CoachingConfig
import dev.tischbein.pulse.coaching.CoachingEngine
import dev.tischbein.pulse.coaching.SessionMode
import dev.tischbein.pulse.coaching.SessionSetup
import dev.tischbein.pulse.sensor.HeartRateSample
import org.junit.Test
import kotlin.test.assertEquals

class LiveHrBufferTest {
    @Test
    fun `one smoothed value per second, gaps stay null, last valid reading remembered`() {
        val start = 50_000L
        val engine = CoachingEngine(SessionSetup(SessionMode.EASY, 190), CoachingConfig())
        engine.start(start)
        val buffer = LiveHrBuffer(start)
        for (s in 1..30) {
            val t = start + s * 1_000L
            val sample = HeartRateSample(t, if (s in 11..25) null else 120)
            engine.onSample(sample)
            buffer.onSample(sample)
            engine.onTick(t)
            buffer.onState(engine.state.value, t)
            buffer.onState(engine.state.value, t) // a second state in the same second adds nothing
        }
        val live = buffer.state.value
        assertEquals(30, live.bySecond.size)
        assertEquals(120, live.bySecond[5])
        assertEquals(null, live.bySecond[20]) // smoothed HR is gone inside the dropout
        assertEquals(120, live.lastValidHr)
        assertEquals(start + 30_000L, live.lastValidAtMs)
    }
}
