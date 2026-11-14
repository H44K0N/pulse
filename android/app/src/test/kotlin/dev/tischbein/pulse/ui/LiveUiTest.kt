package dev.tischbein.pulse.ui

import dev.tischbein.pulse.coaching.CoachingState
import dev.tischbein.pulse.coaching.FourByFourConfig
import dev.tischbein.pulse.coaching.MS_PER_MINUTE
import dev.tischbein.pulse.coaching.PhaseKind
import dev.tischbein.pulse.coaching.SessionMode
import dev.tischbein.pulse.coaching.ZoneState
import dev.tischbein.pulse.sensor.ConnectionState
import dev.tischbein.pulse.sensor.DeviceStatus
import dev.tischbein.pulse.sensor.sim.SimProfile
import dev.tischbein.pulse.session.formatDuration
import dev.tischbein.pulse.voice.VoiceStatus
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LiveUiTest {
    private val work2 =
        CoachingState(
            mode = SessionMode.INTERVAL_4X4,
            started = true,
            finished = false,
            paused = false,
            phaseKind = PhaseKind.WORK,
            phaseRemainingMs = 192_400,
            elapsedActiveMs = 20 * MS_PER_MINUTE,
            intervalNumber = 2,
            intervalCount = 4,
            smoothedHr = 172,
            zoneState = ZoneState.IN,
            targetLow = 162,
            targetHigh = 181,
            sensorLost = false,
            hrMaxUsed = 190,
            learnedHrMax = 190,
        )

    @Test
    fun `4x4 work interval`() {
        val ui = liveUi(work2, VoiceStatus.Ready)
        assertEquals(ZoneIndicator.IN, ui.zone)
        assertEquals("Interval 2", ui.phaseTitle)
        assertEquals(false, ui.voiceOff)
    }

    @Test
    fun `zone states map to a label with an arrow, never color alone`() {
        assertEquals(ZoneIndicator.BELOW, zoneIndicator(ZoneState.BELOW, false))
        assertEquals(ZoneIndicator.ABOVE, zoneIndicator(ZoneState.ABOVE, false))
        assertEquals(ZoneIndicator.WAITING, zoneIndicator(ZoneState.UNKNOWN, false))
        assertEquals(ZoneIndicator.NO_SIGNAL, zoneIndicator(ZoneState.UNKNOWN, true))
        assertTrue(ZoneIndicator.BELOW.label.startsWith("▼"))
        assertTrue(ZoneIndicator.ABOVE.label.startsWith("▲"))
    }

    @Test
    fun `sensor lost, paused and voice problems are visible`() {
        val ui =
            liveUi(
                work2.copy(smoothedHr = null, zoneState = ZoneState.UNKNOWN, sensorLost = true, paused = true),
                VoiceStatus.Unavailable("No English text-to-speech voice installed"),
            )
        assertEquals(ZoneIndicator.NO_SIGNAL, ui.zone)
        assertTrue(ui.paused)
        assertTrue(ui.voiceOff)
    }

    @Test
    fun `before the first phase it says starting`() {
        val ui = liveUi(work2.copy(started = false, phaseKind = null, intervalNumber = null), VoiceStatus.Starting)
        assertEquals("Starting…", ui.phaseTitle)
    }

    @Test
    fun `easy mode shows elapsed and target, even past the target`() {
        val easy =
            work2.copy(mode = SessionMode.EASY, phaseKind = PhaseKind.EASY, intervalNumber = null, intervalCount = null, phaseRemainingMs = 0)
        val d = liveDashboard(easy.copy(elapsedActiveMs = 46 * MS_PER_MINUTE), VoiceStatus.Ready, strap, FourByFourConfig(), emptyList(), easyTargetMs = 45 * MS_PER_MINUTE)
        assertEquals("Easy run", d.ui.phaseTitle)
        assertEquals("46:00", d.bigTime)
        assertEquals("of 45:00", d.bigTimeLabel)
        assertEquals(1f, d.timelinePosition)
        val open = liveDashboard(easy, VoiceStatus.Ready, strap, FourByFourConfig(), emptyList())
        assertEquals("elapsed", open.bigTimeLabel)
        assertTrue(open.timeline.isEmpty())
    }

    @Test
    fun `phase titles`() {
        assertEquals("Warmup", phaseTitle(PhaseKind.WARMUP, null))
        assertEquals("Recovery", phaseTitle(PhaseKind.RECOVERY, 3))
        assertEquals("Cooldown", phaseTitle(PhaseKind.COOLDOWN, null))
    }

    @Test
    fun `time formatting`() {
        assertEquals("0:00", formatDuration(-5))
        assertEquals("0:59", formatDuration(59_999))
        assertEquals("12:34", formatDuration(754_000))
        assertEquals("1:02:03", formatDuration(3_723_000))
    }

    @Test
    fun `warnings only appear when something is wrong`() {
        fun warnings(device: DeviceStatus, voice: VoiceStatus = VoiceStatus.Ready, state: CoachingState = work2) =
            liveDashboard(state, voice, device, FourByFourConfig(), emptyList()).warnings.map { it.text }
        assertEquals(emptyList(), warnings(strap))
        assertEquals(listOf("Strap battery 15 %"), warnings(strap.copy(batteryPercent = 15)))
        assertEquals(listOf("Reconnecting", "No skin contact"), warnings(strap.copy(connection = ConnectionState.CONNECTING, skinContact = false)))
        // While the signal is lost the big "Reconnecting…" says it already.
        assertEquals(emptyList(), warnings(strap.copy(connection = ConnectionState.CONNECTING), state = work2.copy(zoneState = ZoneState.UNKNOWN, sensorLost = true)))
        assertEquals(listOf("Simulator", "Voice off"), warnings(strap.copy(simulatorProfile = SimProfile.GOOD_4X4, batteryPercent = 5), VoiceStatus.Unavailable("x")))
    }

    private val strap = DeviceStatus(null, "A1B2C3D4", ConnectionState.CONNECTED, 80, true)
}
