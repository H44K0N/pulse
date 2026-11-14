package dev.tischbein.pulse.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.tischbein.pulse.coaching.SessionMode
import dev.tischbein.pulse.sensor.ConnectionState
import dev.tischbein.pulse.ui.design.Icon
import dev.tischbein.pulse.ui.design.Overline
import dev.tischbein.pulse.ui.design.PhaseTimeline
import dev.tischbein.pulse.ui.design.PillButton
import dev.tischbein.pulse.ui.design.ProgressRing
import dev.tischbein.pulse.ui.design.PulseCard
import dev.tischbein.pulse.ui.design.PulseIcon
import dev.tischbein.pulse.ui.design.ScreenPadding
import dev.tischbein.pulse.ui.design.Sparkline
import dev.tischbein.pulse.ui.design.Tag
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
 * The redesigned live screen, stateless. Readable at arm's length: the HR is the biggest
 * thing on screen, in the zone's color, inside a ring showing how far the phase is; the time
 * left sits right under it. Smaller, below: the last two minutes against the target band,
 * and (4x4) the whole session as a slim phase timeline.
 */
@Composable
fun LiveContent(
    d: LiveDashboard,
    modifier: Modifier = Modifier,
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
                Spacer(Modifier.height(14.dp))
                PhaseTimeline(d.timeline, d.timelinePosition, Modifier.fillMaxWidth().height(14.dp))
            }
            Spacer(Modifier.weight(1f))
            ProgressRing(
                progress = d.ringProgress,
                color = zc.accent,
                dashed = noData,
                modifier =
                    Modifier
                        .size(336.dp)
                        .align(Alignment.CenterHorizontally)
                        .drawBehind {
                            // Soft glow of the zone color: the state reads from across the room.
                            if (!noData && !d.ui.paused) {
                                val r = size.width * 1.05f
                                drawCircle(Brush.radialGradient(0f to zc.accent.copy(alpha = 0.22f), 0.6f to zc.accent.copy(alpha = 0.05f), 1f to Color.Transparent, center = center, radius = r), r, center)
                            }
                        },
            ) {
                RingCenter(d, zc, noData)
            }
            // Time left, right under the ring's open bottom.
            Column(Modifier.fillMaxWidth().offset(y = (-34).dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(d.bigTime, style = PulseType.bigNumber.copy(fontSize = 52.sp), color = Pulse.text)
                Text(d.bigTimeLabel, fontFamily = InterText, fontSize = 15.sp, color = Pulse.textMid)
            }
            Spacer(Modifier.weight(0.6f))
            ContextCard(d)
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (d.ui.paused) {
                    PillButton("Resume", Modifier.weight(1f), primary = true, onClick = onResume)
                } else {
                    PillButton("Pause", Modifier.weight(1f), primary = false, color = Pulse.text, onClick = onPause)
                }
                PillButton("End", Modifier.weight(1f), primary = false, color = Pulse.danger, subtitle = "hold", onClick = onEnd)
            }
        }
    }
}

@Composable
private fun LiveHeader(d: LiveDashboard) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Overline(
                when {
                    d.ui.paused -> "Paused"
                    d.mode == SessionMode.INTERVAL_4X4 -> "4×4 session"
                    else -> "Easy session"
                },
                color = if (d.ui.paused) Pulse.warning else Pulse.textLow,
            )
            Spacer(Modifier.height(2.dp))
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
            )
        }
        SensorStatus(d)
    }
}

/** Battery and skin contact, tiny. Contact unknown is a dash, never a question mark. */
@Composable
private fun SensorStatus(d: LiveDashboard) {
    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (d.simulator) {
            Tag("SIMULATOR", color = Pulse.warning)
            return
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(PulseIcon.BATTERY, Pulse.textMid, size = 16.dp)
            Spacer(Modifier.width(5.dp))
            Text(
                if (d.connection == ConnectionState.CONNECTED) d.battery?.let { "$it %" } ?: "–" else "Reconnecting",
                style = PulseType.smallNumber.copy(fontSize = 13.sp),
                color = if (d.connection == ConnectionState.CONNECTED) Pulse.textMid else Pulse.warning,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            val (text, color) =
                when (d.contact) {
                    true -> "Contact" to Pulse.inZone.accent
                    false -> "No contact" to Pulse.above.accent
                    null -> "Contact –" to Pulse.textLow
                }
            Box(Modifier.size(7.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(6.dp))
            Text(text, fontFamily = InterText, fontSize = 13.sp, color = if (d.contact == false) color else Pulse.textMid)
        }
    }
}

@Composable
private fun RingCenter(
    d: LiveDashboard,
    zc: ZoneColor,
    noData: Boolean,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        ZonePill(d, zc)
        if (noData) {
            // No number at all rather than placeholder glyphs: an empty big number reads as broken.
            Spacer(Modifier.height(18.dp))
            Icon(PulseIcon.HEART, Pulse.textLow.copy(alpha = 0.6f), size = 64.dp)
            Spacer(Modifier.height(14.dp))
            Text(
                if (d.ui.zone == ZoneIndicator.NO_SIGNAL) "Reconnecting…" else "Put the strap on",
                fontFamily = InterText,
                fontWeight = FontWeight.Medium,
                fontSize = 17.sp,
                color = Pulse.textMid,
            )
            d.lastSeenText?.let { Text(it, fontFamily = InterText, fontSize = 13.sp, color = Pulse.textLow, modifier = Modifier.padding(top = 4.dp)) }
        } else {
            Text(
                d.hr?.toString() ?: "",
                style = PulseType.hero.copy(fontSize = 128.sp, lineHeight = 128.sp),
                color = if (d.ui.paused) Pulse.textMid else zc.accent,
                textAlign = TextAlign.Center,
            )
            Text(
                listOfNotNull("bpm", d.targetLow?.let { "target $it–${d.targetHigh}" }).joinToString("  ·  "),
                fontFamily = InterText,
                fontSize = 15.sp,
                color = Pulse.textMid,
            )
        }
    }
}

@Composable
private fun ZonePill(
    d: LiveDashboard,
    zc: ZoneColor,
) {
    val text = if (d.ui.paused) "PAUSED" else d.ui.zone.label
    val color = if (d.ui.paused) Pulse.warning else if (d.ui.zone == ZoneIndicator.NO_SIGNAL) Pulse.warning else zc.accent
    Text(
        text,
        fontFamily = InterText,
        fontWeight = FontWeight.Bold,
        fontSize = 13.sp,
        letterSpacing = 1.sp,
        color = color,
        modifier = Modifier.clip(CircleShape).background(color.copy(alpha = 0.14f)).padding(horizontal = 12.dp, vertical = 5.dp),
    )
}

/** Last two minutes against the target band, plus drift in easy mode. */
@Composable
private fun ContextCard(d: LiveDashboard) {
    PulseCard(padding = androidx.compose.foundation.layout.PaddingValues(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Overline("Last 2 min", Modifier.weight(1f))
            if (d.ui.zone == ZoneIndicator.NO_SIGNAL) {
                Text("signal gap", fontFamily = InterText, fontSize = 12.sp, color = Pulse.warning)
            } else if (d.mode == SessionMode.EASY && d.driftText != null) {
                Text("Drift ", fontFamily = InterText, fontSize = 12.sp, color = Pulse.textLow)
                Text(d.driftText, style = PulseType.smallNumber.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold), color = Pulse.text)
            }
            if (d.targetLow != null) {
                Spacer(Modifier.width(12.dp))
                Box(Modifier.size(8.dp).clip(CircleShape).background(bandColorFor(d).copy(alpha = 0.5f)))
                Spacer(Modifier.width(5.dp))
                Text("${d.targetLow}–${d.targetHigh} target", style = PulseType.smallNumber.copy(fontSize = 12.sp), color = Pulse.textLow)
            }
        }
        Spacer(Modifier.height(10.dp))
        Sparkline(
            values = d.recentHr,
            bandLow = d.targetLow,
            bandHigh = d.targetHigh,
            bandColor = bandColorFor(d),
            color = if (d.ui.paused) Pulse.textMid else Pulse.chartLine,
            modifier = Modifier.fillMaxWidth().height(96.dp),
            showLastDot = d.ui.zone != ZoneIndicator.NO_SIGNAL,
        )
    }
}

private fun bandColorFor(d: LiveDashboard): Color = if (d.isWork) Pulse.inZone.accent else Pulse.below.accent
