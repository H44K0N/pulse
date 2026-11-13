package dev.tischbein.pulse.ui

import dev.tischbein.pulse.coaching.CoachingState
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
import kotlin.test.assertNull
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
        assertEquals("172", ui.hrText)
        assertEquals(ZoneIndicator.IN, ui.zone)
        assertEquals("Interval 2", ui.phaseTitle)
        assertEquals("3:12 left", ui.remainingText)
        assertEquals("Interval 2 of 4", ui.progressText)
        assertEquals("Target 162-181", ui.targetText)
        assertNull(ui.voiceWarning)
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
    fun `unknown HR shows dashes, paused and voice problems are visible`() {
        val ui =
            liveUi(
                work2.copy(smoothedHr = null, zoneState = ZoneState.UNKNOWN, sensorLost = true, paused = true),
                VoiceStatus.Unavailable("No English text-to-speech voice installed"),
            )
        assertEquals("--", ui.hrText)
        assertEquals(ZoneIndicator.NO_SIGNAL, ui.zone)
        assertTrue(ui.paused)
        assertEquals("Voice off: No English text-to-speech voice installed", ui.voiceWarning)
    }

    @Test
    fun `before the first phase it says starting`() {
        val ui = liveUi(work2.copy(started = false, phaseKind = null, intervalNumber = null), VoiceStatus.Starting)
        assertEquals("Starting...", ui.phaseTitle)
        assertNull(ui.progressText)
    }

    @Test
    fun `easy mode shows elapsed and target, even past the target`() {
        val easy =
            work2.copy(mode = SessionMode.EASY, phaseKind = PhaseKind.EASY, intervalNumber = null, intervalCount = null, phaseRemainingMs = 0)
        val ui = liveUi(easy.copy(elapsedActiveMs = 46 * MS_PER_MINUTE), VoiceStatus.Ready, easyTargetMs = 45 * MS_PER_MINUTE)
        assertEquals("Easy run", ui.phaseTitle)
        assertEquals("46:00 / 45:00", ui.progressText)
        assertNull(ui.remainingText)
        assertEquals("20:00", liveUi(easy.copy(phaseRemainingMs = null), VoiceStatus.Ready).progressText)
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
    fun `status row`() {
        val strap = DeviceStatus(null, "A1B2C3D4", ConnectionState.CONNECTED, 80, true)
        assertEquals(listOf("Battery 80 %", "Contact OK", "Saved on phone"), statusItems(strap))
        assertEquals(
            listOf("Strap connecting", "NO CONTACT", "Saved on phone"),
            statusItems(strap.copy(connection = ConnectionState.CONNECTING, skinContact = false)),
        )
        assertEquals(listOf("Simulator", "Saved on phone"), statusItems(strap.copy(simulatorProfile = SimProfile.GOOD_4X4)))
    }

    @Test
    fun `easy target input`() {
        assertEquals(0, parseEasyTargetMin(""))
        assertEquals(45, parseEasyTargetMin("45"))
        assertNull(parseEasyTargetMin("601"))
    }
}
