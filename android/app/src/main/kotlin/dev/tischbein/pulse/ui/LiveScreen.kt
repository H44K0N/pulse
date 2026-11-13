package dev.tischbein.pulse.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.tischbein.pulse.sensor.DeviceStatus
import dev.tischbein.pulse.session.ActiveSession
import kotlinx.coroutines.delay

/**
 * The live screen: readable at arm's length on a treadmill console. Huge HR on a panel
 * colored by zone, phase and time left, a small status row, Pause/Resume and a long-press End.
 */
@Composable
fun LiveScreen(
    session: ActiveSession,
    device: DeviceStatus,
) {
    val state by session.state.collectAsStateWithLifecycle()
    val voice by session.voice.collectAsStateWithLifecycle()
    val ui = liveUi(state, voice, session.easyTargetMs)
    KeepScreenOn()

    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Text(ui.phaseTitle, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            ui.remainingText?.let { Text(it, style = MaterialTheme.typography.displaySmall) }
        }
        ui.progressText?.let { Text(it, style = MaterialTheme.typography.headlineMedium) }

        HeartRatePanel(ui, Modifier.weight(1f).fillMaxWidth())

        ui.voiceWarning?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge) }
        Text(
            statusItems(device).joinToString("  ·  "),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val buttonModifier = Modifier.weight(1f).height(72.dp)
            if (state.paused) {
                Button(onClick = session::resume, modifier = buttonModifier) { Text("Resume", fontSize = 24.sp) }
            } else {
                Button(onClick = session::pause, enabled = state.started, modifier = buttonModifier) { Text("Pause", fontSize = 24.sp) }
            }
            HoldToEndButton(onEnd = session::end, modifier = buttonModifier)
        }
    }
}

@Composable
private fun HeartRatePanel(
    ui: LiveUi,
    modifier: Modifier,
) {
    val colors = zoneColors(ui.zone)
    Box(
        modifier
            .clip(RoundedCornerShape(24.dp))
            .background(colors.background)
            .padding(16.dp),
    ) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            if (ui.paused) {
                Text(
                    "PAUSED",
                    color = Color.White,
                    fontSize = 40.sp,
                    fontWeight = FontWeight.Black,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Color.Black).padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            BasicText(
                ui.hrText,
                maxLines = 1,
                style = TextStyle(color = colors.content, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center),
                // As big as the panel allows: this number must be readable from a treadmill.
                autoSize = TextAutoSize.StepBased(minFontSize = 48.sp, maxFontSize = 220.sp),
                modifier = Modifier.weight(1f, fill = false),
            )
            Text(ui.zone.label, color = colors.content, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            ui.targetText?.let { Text(it, color = colors.content, fontSize = 20.sp) }
        }
    }
}

private data class ZoneColors(val background: Color, val content: Color)

/**
 * Okabe-Ito palette (designed to stay distinguishable with the common color-vision
 * deficiencies): sky blue = below, bluish green = in, orange = above. Black text on all
 * three for contrast. The zone is also spelled out with an arrow, so color is never the only cue.
 */
private fun zoneColors(zone: ZoneIndicator): ZoneColors =
    when (zone) {
        ZoneIndicator.BELOW -> ZoneColors(Color(0xFF56B4E9), Color.Black)
        ZoneIndicator.IN -> ZoneColors(Color(0xFF009E73), Color.Black)
        ZoneIndicator.ABOVE -> ZoneColors(Color(0xFFE69F00), Color.Black)
        ZoneIndicator.NO_SIGNAL, ZoneIndicator.WAITING -> ZoneColors(Color(0xFF3A3A3A), Color.White)
    }

/**
 * End needs a long press so a sweaty hand brushing the screen cannot stop the workout.
 * A short tap only shows the hint.
 */
@Composable
private fun HoldToEndButton(
    onEnd: () -> Unit,
    modifier: Modifier,
) {
    val haptics = LocalHapticFeedback.current
    var showHint by remember { mutableStateOf(false) }
    LaunchedEffect(showHint) {
        if (showHint) {
            delay(HINT_VISIBLE_MS)
            showHint = false
        }
    }
    Surface(
        modifier =
            modifier.clip(RoundedCornerShape(36.dp)).combinedClickable(
                onClickLabel = "Show how to end",
                onLongClickLabel = "End session",
                onClick = { showHint = true },
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onEnd()
                },
            ),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text("End", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text(if (showHint) "Press and HOLD to end" else "Hold to end", fontSize = 14.sp)
        }
    }
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

private const val HINT_VISIBLE_MS = 3_000L
