package dev.tischbein.pulse.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.tischbein.pulse.coaching.SessionMode
import dev.tischbein.pulse.sensor.DeviceStatus
import dev.tischbein.pulse.session.ActiveSession
import dev.tischbein.pulse.time.MillisClock

/**
 * The live screen wired to the running session: engine state, voice status, the strap and
 * the service's HR history go through [liveDashboard] into the stateless [LiveContent].
 * Recomposes about once a second (the engine ticks every second even without samples).
 */
@Composable
fun LiveScreen(
    session: ActiveSession,
    device: DeviceStatus,
    clock: MillisClock,
) {
    val state by session.state.collectAsStateWithLifecycle()
    val voice by session.voice.collectAsStateWithLifecycle()
    val liveHr by session.liveHr.collectAsStateWithLifecycle()
    var showGraph by rememberSaveable { mutableStateOf(false) }
    KeepScreenOn()

    val d =
        liveDashboard(
            state = state,
            voice = voice,
            device = device,
            protocol = session.config.fourByFour,
            recentHr = liveHr.bySecond,
            easyTargetMs = session.easyTargetMs,
            driftPct = if (session.mode == SessionMode.EASY) liveDriftPct(liveHr.bySecond, session.config.easy) else null,
            lastValidHr = liveHr.lastValidHr,
            msSinceLastValid = liveHr.lastValidAtMs?.let { clock.nowMs() - it },
        )
    LiveContent(
        d,
        showGraph = showGraph,
        onToggleGraph = { showGraph = !showGraph },
        onPause = session::pause,
        onResume = session::resume,
        onEnd = session::end,
    )
}

/**
 * Keeps the display on while this screen is shown, via the window flag that View.keepScreenOn
 * sets (FLAG_KEEP_SCREEN_ON). WHY here and not in the service: the flag belongs to the visible
 * window, so Android drops it by itself when the user leaves the screen, locks the phone or
 * the app goes to the background; nothing can leak. The session itself never needs the
 * screen: it runs in the foreground service with a partial (CPU-only) wake lock.
 */
@Composable
private fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
}
