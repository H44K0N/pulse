package dev.tischbein.pulse.ui.mockups

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.github.takahirom.roborazzi.captureRoboImage
import dev.tischbein.pulse.coaching.SessionMode
import dev.tischbein.pulse.ui.MainFrame
import dev.tischbein.pulse.ui.SettingsContent
import dev.tischbein.pulse.ui.TrainContent
import dev.tischbein.pulse.ui.UpdateCard
import dev.tischbein.pulse.ui.UpdateCardUi
import dev.tischbein.pulse.ui.design.BottomBar
import dev.tischbein.pulse.ui.design.Destination
import dev.tischbein.pulse.ui.design.RowDivider
import dev.tischbein.pulse.ui.theme.Pulse
import dev.tischbein.pulse.ui.theme.PulseTheme
import dev.tischbein.pulse.update.ExpectedFile
import dev.tischbein.pulse.update.UpdateOffer
import dev.tischbein.pulse.update.UpdatePhase
import dev.tischbein.pulse.update.UpdateTexts
import dev.tischbein.pulse.update.UpdateView
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The in-app update with invented data, at 360 dp and font scale 1.3 (a small phone with larger
 * text): Train's banner offering an update, downloading, the "Install unknown apps" ask, a failure
 * with Try again, Start waiting while Android installs, and the Settings version line. Written
 * outside the repo, only when asked:
 *
 *   PULSE_UPDATE_OUT=/some/dir ./gradlew :app:recordRoborazziDebug --tests '*UpdateShots*'
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h780dp-port-night-xxhdpi", sdk = [36])
class UpdateShots {
    private val out get() = File(System.getenv("PULSE_UPDATE_OUT").orEmpty())

    @Before
    fun onlyWhenAsked() {
        assumeTrue(System.getProperty("roborazzi.test.record") == "true")
        assumeTrue(!System.getenv("PULSE_UPDATE_OUT").isNullOrBlank())
    }

    private fun shot(
        name: String,
        content: @Composable () -> Unit,
    ) {
        captureRoboImage(File(out, "$name.png").path) {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, FONT_SCALE)) { PulseTheme { content() } }
        }
    }

    private fun train(
        name: String,
        phase: UpdatePhase,
    ) {
        val view = view(phase)
        shot(name) {
            MainFrame(Destination.TRAIN) { m ->
                TrainContent(
                    MockData.settings,
                    MockData.device,
                    SessionMode.INTERVAL_4X4,
                    m,
                    update = UpdateCard.of(view, sessionRunning = false),
                    startBlocked = UpdateCard.startBlocked(view),
                )
            }
        }
    }

    @Test
    fun u1_offer() = train("u1-train-update-offered", UpdatePhase.Ready)

    @Test
    fun u2_downloading() = train("u2-train-downloading", UpdatePhase.Downloading(42))

    @Test
    fun u3_ask() = train("u3-train-unknown-apps-ask", UpdatePhase.AskPermission)

    @Test
    fun u4_failed() = train("u4-train-failed-try-again", UpdatePhase.Failed(UpdateTexts.DAMAGED))

    @Test
    fun u5_installing() = train("u5-train-installing-start-waits", UpdatePhase.Installing)

    @Test
    @Config(qualifiers = "+h2400dp")
    fun u6_settings() = settings("u6-settings-version-and-update", UpdateCard.of(view(UpdatePhase.Ready), sessionRunning = false))

    @Test
    @Config(qualifiers = "+h2400dp")
    fun u7_settingsUpToDate() = settings("u7-settings-version-up-to-date", null)

    private fun settings(
        name: String,
        update: UpdateCardUi?,
    ) {
        shot(name) {
            Column(Modifier.fillMaxSize().background(Pulse.bg)) {
                SettingsContent(MockData.settings, scrollable = false, version = "0.5.1", update = update)
                RowDivider()
                BottomBar(Destination.SETTINGS)
            }
        }
        // The whole page on a tall canvas, cut below the bottom bar.
        ContactSheet.trimTallShots(out, listOf(name))
    }

    private companion object {
        const val FONT_SCALE = 1.3f
        const val NOTES = "Pulse now updates itself: one tap on Update."

        fun view(phase: UpdatePhase) =
            UpdateView(UpdateOffer(7, "0.5.2", NOTES, "/api/app/apk?v=7", ExpectedFile(28_000_000, "0".repeat(64))), phase)
    }
}
