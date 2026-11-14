package dev.tischbein.pulse.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.tischbein.pulse.coaching.SessionMode
import dev.tischbein.pulse.ui.design.HoldToEndButton
import dev.tischbein.pulse.ui.design.Icon
import dev.tischbein.pulse.ui.design.Overline
import dev.tischbein.pulse.ui.design.PhaseTimeline
import dev.tischbein.pulse.ui.design.PillButton
import dev.tischbein.pulse.ui.design.PulseCard
import dev.tischbein.pulse.ui.design.PulseIcon
import dev.tischbein.pulse.ui.design.ScreenPadding
import dev.tischbein.pulse.ui.design.Sparkline
import dev.tischbein.pulse.ui.theme.InterDisplay
import dev.tischbein.pulse.ui.theme.InterText
import dev.tischbein.pulse.ui.theme.Pulse
import dev.tischbein.pulse.ui.theme.PulseType
import dev.tischbein.pulse.ui.theme.ZoneColor

/** Accent of a zone state. NO_SIGNAL / WAITING are neutral grey: nothing to act on. */
fun zoneColor(zone: ZoneIndicator): ZoneColor =
    when (zone) {
        ZoneIndicator.BELOW -> Pulse.below
        ZoneIndicator.IN -> Pulse.inZone
        ZoneIndicator.ABOVE -> Pulse.above
        ZoneIndicator.NO_SIGNAL, ZoneIndicator.WAITING -> Pulse.neutral
    }

/**
 * The live screen, stateless. Built for a glance from a treadmill: only what the runner acts
 * on. The HR is the biggest thing, its NUMBER colored by zone (the background stays neutral),
 * with the zone spelled out above it and the target range small below. Then the time left.
 * One progress indicator: the slim session strip at the top. Warnings (strap, voice) appear as
 * small chips only when something is wrong. The last-2-minutes graph is one tap on the HR away.
 *
 * @param showGraph whether the last-2-minutes graph is open (tap the HR to toggle).
 * @param endHoldProgress preview only: draws the End button part-way through a hold.
 */
@Composable
fun LiveContent(
    d: LiveDashboard,
    modifier: Modifier = Modifier,
    showGraph: Boolean = false,
    endHoldProgress: Float = 0f,
    onToggleGraph: () -> Unit = {},
    onPause: () -> Unit = {},
    onResume: () -> Unit = {},
    onEnd: () -> Unit = {},
) {
    val noData = d.ui.zone == ZoneIndicator.NO_SIGNAL || d.ui.zone == ZoneIndicator.WAITING
    val zc = if (d.ui.paused) Pulse.neutral else zoneColor(d.ui.zone)
    Box(modifier.fillMaxSize().background(Pulse.bg)) {
        Column(Modifier.fillMaxSize().padding(horizontal = ScreenPadding, vertical = 16.dp)) {
            LiveHeader(d)
            if (d.timeline.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                PhaseTimeline(d.timeline, d.timelinePosition, Modifier.fillMaxWidth().height(14.dp))
            }
            Spacer(Modifier.weight(1f))
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(PulseShapes.hrArea)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClickLabel = "Show the last two minutes", onClick = onToggleGraph)
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                HeartRate(d, zc, noData)
            }
            Spacer(Modifier.height(if (showGraph) 12.dp else 28.dp))
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(d.bigTime, style = PulseType.bigNumber.copy(fontSize = 60.sp), color = Pulse.text)
                Text(d.bigTimeLabel, fontFamily = InterText, fontSize = 16.sp, color = Pulse.textMid)
            }
            Spacer(Modifier.weight(1f))
            AnimatedVisibility(showGraph) {
                Column {
                    GraphCard(d)
                    Spacer(Modifier.height(16.dp))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (d.ui.paused) {
                    PillButton("Resume", Modifier.weight(1f), primary = true, onClick = onResume)
                } else {
                    PillButton("Pause", Modifier.weight(1f), primary = false, color = Pulse.text, onClick = onPause)
                }
                HoldToEndButton(onEnd, Modifier.weight(1f), previewProgress = endHoldProgress)
            }
        }
    }
}

private object PulseShapes {
    val hrArea = androidx.compose.foundation.shape.RoundedCornerShape(32.dp)
}

@Composable
private fun LiveHeader(d: LiveDashboard) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            buildAnnotatedString {
                append(d.ui.phaseTitle)
                if (d.intervalNumber != null && d.intervalCount != null) {
                    withStyle(SpanStyle(color = Pulse.textLow)) {
                        append(if (d.ui.phaseTitle.startsWith("Interval")) " of ${d.intervalCount}" else " · ${d.intervalNumber} of ${d.intervalCount}")
                    }
                }
            },
            fontFamily = InterDisplay,
            fontWeight = FontWeight.Bold,
            fontSize = 30.sp,
            color = Pulse.text,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            d.warnings.forEach { WarningChip(it) }
        }
    }
}

@Composable
private fun WarningChip(w: LiveWarning) {
    val color = if (w.severe) Pulse.warning else Pulse.textMid
    Row(
        Modifier.clip(CircleShape).background(color.copy(alpha = 0.14f)).padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(w.text, fontFamily = InterText, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = color, maxLines = 1)
    }
}

@Composable
private fun HeartRate(
    d: LiveDashboard,
    zc: ZoneColor,
    noData: Boolean,
) {
    ZoneLabel(d, zc)
    if (noData) {
        // No number at all rather than placeholder glyphs: an empty big number reads as broken.
        Spacer(Modifier.height(28.dp))
        Icon(PulseIcon.HEART, Pulse.textLow.copy(alpha = 0.6f), size = 72.dp)
        Spacer(Modifier.height(18.dp))
        Text(
            if (d.ui.zone == ZoneIndicator.NO_SIGNAL) "Reconnecting…" else "Put the strap on",
            fontFamily = InterText,
            fontWeight = FontWeight.Medium,
            fontSize = 20.sp,
            color = Pulse.textMid,
        )
        d.lastSeenText?.let { Text(it, fontFamily = InterText, fontSize = 15.sp, color = Pulse.textLow, modifier = Modifier.padding(top = 4.dp)) }
        Spacer(Modifier.height(20.dp))
    } else {
        Text(
            d.hr?.toString() ?: "",
            style = PulseType.hero.copy(fontSize = 168.sp, lineHeight = 168.sp),
            color = if (d.ui.paused) Pulse.textMid else zc.accent,
            textAlign = TextAlign.Center,
        )
        d.targetLow?.let {
            Text("$it–${d.targetHigh}", style = PulseType.smallNumber.copy(fontSize = 17.sp), color = Pulse.textMid)
        }
    }
}

/** "▼ BELOW ZONE" etc.: the word that goes with the color (colorblind-safe), or PAUSED. */
@Composable
private fun ZoneLabel(
    d: LiveDashboard,
    zc: ZoneColor,
) {
    val text = if (d.ui.paused) "PAUSED" else d.ui.zone.label
    val color = if (d.ui.paused || d.ui.zone == ZoneIndicator.NO_SIGNAL) Pulse.warning else zc.accent
    Text(
        text,
        fontFamily = InterText,
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp,
        letterSpacing = 1.sp,
        color = color,
        modifier = Modifier.clip(CircleShape).background(color.copy(alpha = 0.14f)).padding(horizontal = 14.dp, vertical = 6.dp),
    )
}

/** Last two minutes against the target band, plus drift in easy mode. Shown on tap. */
@Composable
private fun GraphCard(d: LiveDashboard) {
    PulseCard(padding = PaddingValues(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Overline("Last 2 min", Modifier.weight(1f))
            if (d.mode == SessionMode.EASY && d.driftText != null) {
                Text("Drift ", fontFamily = InterText, fontSize = 12.sp, color = Pulse.textLow)
                Text(d.driftText, style = PulseType.smallNumber.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold), color = Pulse.text)
            }
        }
        Spacer(Modifier.height(10.dp))
        Sparkline(
            values = d.recentHr,
            bandLow = d.targetLow,
            bandHigh = d.targetHigh,
            bandColor = bandColorFor(d),
            color = if (d.ui.paused) Pulse.textMid else Pulse.chartLine,
            modifier = Modifier.fillMaxWidth().height(88.dp),
            showLastDot = d.ui.zone != ZoneIndicator.NO_SIGNAL,
        )
    }
}

private fun bandColorFor(d: LiveDashboard): Color = if (d.isWork) Pulse.inZone.accent else Pulse.below.accent
