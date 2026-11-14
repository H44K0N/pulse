package dev.tischbein.pulse.ui.mockups

import androidx.compose.runtime.Composable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import dev.tischbein.pulse.ui.design.BottomBar
import dev.tischbein.pulse.ui.design.RowDivider
import dev.tischbein.pulse.ui.theme.Pulse
import com.github.takahirom.roborazzi.captureRoboImage
import dev.tischbein.pulse.coaching.SessionMode
import dev.tischbein.pulse.sensor.ConnectionState
import dev.tischbein.pulse.sensor.sim.SimProfile
import dev.tischbein.pulse.ui.AllSessionsContent
import dev.tischbein.pulse.ui.LiveContent
import dev.tischbein.pulse.ui.ProgressContent
import dev.tischbein.pulse.ui.SessionDetailContent
import dev.tischbein.pulse.data.SessionDigest
import dev.tischbein.pulse.ui.SettingsContent
import dev.tischbein.pulse.ui.SettingsSection
import dev.tischbein.pulse.ui.TrainContent
import dev.tischbein.pulse.ui.MainFrame
import dev.tischbein.pulse.ui.design.Destination
import dev.tischbein.pulse.ui.liveDashboard
import dev.tischbein.pulse.ui.liveDriftPct
import dev.tischbein.pulse.ui.progressOverview
import dev.tischbein.pulse.ui.sessionDetail
import dev.tischbein.pulse.ui.sessionRows
import dev.tischbein.pulse.ui.theme.PulseTheme
import dev.tischbein.pulse.voice.VoiceStatus
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Screenshots of the real screens (the stateless *Content composables the app uses), rendered on the JVM (Robolectric native
 * graphics + Roborazzi) at a modern phone size: 411 x 914 dp at 420 dpi = 1079 x 2399 px.
 * Data comes from the real coaching engine fed by the simulator profiles and a synthetic
 * six-week history (MockData).
 *
 * Only runs when recording: `./gradlew :app:recordRoborazziDebug --tests '*DesignMockups*'`.
 * Writes PNGs to app/src/test/screenshots/, plus a contact sheet (last test).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h914dp-port-night-420dpi", sdk = [36])
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class DesignMockups {
    @Before
    fun onlyWhenRecording() {
        assumeTrue("design mockups render only with recordRoborazziDebug", System.getProperty("roborazzi.test.record") == "true")
    }

    private fun shot(
        name: String,
        content: @Composable () -> Unit,
    ) {
        captureRoboImage("$DIR/$name.png") { PulseTheme { content() } }
    }

    private val voice = VoiceStatus.Ready

    // ---- Live ---------------------------------------------------------------------------

    private fun live(
        name: String,
        run: MockData.Run,
        atSec: Int,
        device: dev.tischbein.pulse.sensor.DeviceStatus = MockData.device,
        easyTargetMs: Long? = null,
        showGraph: Boolean = false,
        endHold: Float = 0f,
    ) {
        val state = run.states[atSec - 1]
        val (last, ago) = run.lastValid(atSec)
        val d =
            liveDashboard(
                state = state,
                voice = voice,
                device = device,
                protocol = MockData.config.fourByFour,
                recentHr = run.recent(atSec),
                easyTargetMs = easyTargetMs,
                driftPct = if (state.mode == SessionMode.EASY) liveDriftPct(run.smoothed.subList(0, atSec)) else null,
                lastValidHr = last,
                msSinceLastValid = ago,
            )
        shot(name) { LiveContent(d, showGraph = showGraph, endHoldProgress = endHold) }
    }

    /** Work 2 starts at 600 + 420 = 1020 s; 2:10 into it, comfortably in zone. */
    @Test
    fun a01_liveWorkInZone() = live("01-live-work-in-zone", MockData.simRun(SimProfile.GOOD_4X4, untilSec = 1150), 1150)

    /** 25 s into interval 3 (starts 1440 s): still ramping, below the zone. */
    @Test
    fun a02_liveBelowZoneRamp() = live("02-live-below-zone-ramp", MockData.simRun(SimProfile.GOOD_4X4, untilSec = 1465), 1465)

    /** An over-eager first interval: target 98 % HRmax. */
    @Test
    fun a03_liveAboveZone() {
        val p = MockData.config.fourByFour
        val model = dev.tischbein.pulse.sensor.sim.SimulatedHeartModel(SimProfile.GOOD_4X4, MockData.HR_MAX, p, 3)
        // Same simulator, but the runner goes harder than asked in interval 1.
        val run =
            MockData.run(dev.tischbein.pulse.coaching.SessionSetup(SessionMode.INTERVAL_4X4, MockData.HR_MAX), untilSec = 795) { t ->
                val base = model.step(t).hr ?: return@run null
                if (t in 600_000..840_000) base + ((t - 600_000) / 1000 / 12).toInt().coerceAtMost(8) else base
            }
        live("03-live-above-zone", run, 795)
    }

    /** SENSOR_DROPOUT: the strap is silent from 1140 s for 20 s; 14 s in. */
    @Test
    fun a04_liveSensorLost() =
        live(
            "04-live-sensor-lost",
            MockData.simRun(SimProfile.SENSOR_DROPOUT, untilSec = 1154),
            1154,
            device = MockData.device.copy(connection = ConnectionState.CONNECTING, skinContact = null),
        )

    /** Paused 1:20 into recovery 3. */
    @Test
    fun a05_livePaused() = live("05-live-paused", MockData.simRun(SimProfile.GOOD_4X4, untilSec = 1785, pauseAtSec = 1760), 1785)

    /** Easy run with drift, 31 min into a 45 min target. */
    @Test
    fun a06_liveEasy() = live("06-live-easy", MockData.simRun(SimProfile.EASY_WITH_DRIFT, untilSec = 31 * 60, easyTargetMin = 45), 31 * 60, easyTargetMs = 45 * 60_000L)

    /** The last-2-minutes graph, opened by tapping the HR. */
    @Test
    fun a18_liveGraphOpen() = live("18-live-graph-open", MockData.simRun(SimProfile.GOOD_4X4, untilSec = 1150), 1150, showGraph = true)

    /** End held for about 60 % of the hold time: the fill sweeps across. */
    @Test
    fun a19_liveEndHolding() = live("19-live-end-holding", MockData.simRun(SimProfile.GOOD_4X4, untilSec = 1150), 1150, endHold = 0.6f)

    // ---- Summary ------------------------------------------------------------------------

    private val todayStart = MockData.epochAt(MockData.today, 17, 32)

    @Test
    fun b07_summary4x4() {
        val d = sessionDetail(MockData.simRun(SimProfile.GOOD_4X4).result, todayStart, MockData.zone)
        shot("07-summary-4x4") { SessionDetailContent(d) }
    }

    @Test
    @Config(qualifiers = "+h1300dp")
    fun b08_summary4x4Full() {
        val d = sessionDetail(MockData.simRun(SimProfile.GOOD_4X4).result, todayStart, MockData.zone)
        shot("08-summary-4x4-full") { SessionDetailContent(d, scrollable = false) }
    }

    @Test
    @Config(qualifiers = "+h1300dp")
    fun b09_summaryFadingWithDropout() {
        // FADING_4X4 for the shape, plus the SENSOR_DROPOUT gap, to show both on one graph.
        val fading = dev.tischbein.pulse.sensor.sim.SimulatedHeartModel(SimProfile.FADING_4X4, MockData.HR_MAX, MockData.config.fourByFour, 5)
        val run =
            MockData.run(dev.tischbein.pulse.coaching.SessionSetup(SessionMode.INTERVAL_4X4, MockData.HR_MAX)) { t ->
                val v = fading.step(t).hr
                if (t in 1_140_000L until 1_160_000L) null else v
            }
        val d = sessionDetail(run.result, todayStart - 3 * 86_400_000L, MockData.zone)
        // Opened from the history: Back instead of Done.
        shot("09-summary-fading-dropout-full") { SessionDetailContent(d, scrollable = false, justFinished = false) }
    }

    @Test
    fun b10_summaryEasy() {
        val run = MockData.simRun(SimProfile.EASY_WITH_DRIFT, untilSec = 45 * 60, easyTargetMin = 45)
        val d = sessionDetail(run.result, todayStart - 86_400_000L, MockData.zone)
        shot("10-summary-easy") { SessionDetailContent(d) }
    }

    // ---- Progress -----------------------------------------------------------------------

    private fun overview(): dev.tischbein.pulse.ui.ProgressOverview {
        val history = MockData.history().map { it.first }
        val todaySession = SessionDigest.of("today", MockData.simRun(SimProfile.GOOD_4X4).result, todayStart)
        val all = history + todaySession
        return progressOverview(all, all.sortedByDescending { it.startedAtEpochMs }.take(8), all.size, MockData.today, MockData.zone)
    }

    @Test
    fun c11_progress() {
        val p = overview()
        shot("11-progress") { MainFrame(Destination.PROGRESS) { m -> ProgressContent(p, m) } }
    }

    @Test
    @Config(qualifiers = "+h2700dp")
    fun c12_progressFull() {
        val p = overview()
        shot("12-progress-full") { FullPage(Destination.PROGRESS) { ProgressContent(p, scrollable = false, initialCalendarOpen = true) } }
    }

    /** "Show all": every session. */
    @Test
    fun c13_progressSessions() {
        val history = MockData.history().map { it.first }
        val todaySession = SessionDigest.of("today", MockData.simRun(SimProfile.GOOD_4X4).result, todayStart)
        val rows = sessionRows(history + todaySession, MockData.zone)
        shot("13-progress-sessions") { AllSessionsContent(rows) }
    }

    // ---- Train / Settings ---------------------------------------------------------------

    @Test
    fun d14_train4x4() =
        shot("14-train-4x4") {
            MainFrame(Destination.TRAIN) { m -> TrainContent(MockData.settings, MockData.device, SessionMode.INTERVAL_4X4, m) }
        }

    /** Easy mode with the plan details opened (target duration stepper). */
    @Test
    fun d15_trainEasy() =
        shot("15-train-easy") {
            MainFrame(Destination.TRAIN) { m -> TrainContent(MockData.settings, MockData.device, SessionMode.EASY, m, initialPlanOpen = true) }
        }

    @Test
    fun d16_settings() = shot("16-settings") { MainFrame(Destination.SETTINGS) { m -> SettingsContent(MockData.settings, m) } }

    /** Every group opened, simulator on. */
    @Test
    @Config(qualifiers = "+h2200dp")
    fun d17_settingsFull() =
        shot("17-settings-full") {
            FullPage(Destination.SETTINGS) {
                SettingsContent(MockData.settings.copy(simulatorEnabled = true, serverUrl = "https://pulse.example.com", apiToken = "not-a-real-token"), scrollable = false, initialOpen = SettingsSection.entries.toSet())
            }
        }

    /** Whole page, content then the bottom bar, on a tall canvas (trimmed afterwards). */
    @Composable
    private fun FullPage(
        selected: Destination,
        content: @Composable () -> Unit,
    ) {
        androidx.compose.foundation.layout.Column(Modifier.fillMaxSize().background(Pulse.bg)) {
            content()
            RowDivider()
            BottomBar(selected)
        }
    }

    // ---- Contact sheet ------------------------------------------------------------------

    @Test
    fun z99_contactSheet() {
        ContactSheet.trimTallShots(File(DIR), listOf("08-summary-4x4-full", "09-summary-fading-dropout-full", "12-progress-full", "17-settings-full"))
        ContactSheet.build(
            File(DIR),
            listOf(
                "01-live-work-in-zone", "02-live-below-zone-ramp", "03-live-above-zone", "04-live-sensor-lost", "05-live-paused", "06-live-easy",
                "07-summary-4x4", "10-summary-easy", "11-progress", "13-progress-sessions", "14-train-4x4", "16-settings",
                "18-live-graph-open", "19-live-end-holding", "15-train-easy",
            ),
            File(DIR, "00-contact-sheet.png"),
        )
    }

    private companion object {
        const val DIR = "src/test/screenshots"
    }
}
