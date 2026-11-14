package dev.tischbein.pulse.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.tischbein.pulse.coaching.SessionMode
import dev.tischbein.pulse.ui.design.Overline
import dev.tischbein.pulse.ui.design.PillButton
import dev.tischbein.pulse.ui.design.PulseCard
import dev.tischbein.pulse.ui.design.RowDivider
import dev.tischbein.pulse.ui.design.ScreenPadding
import dev.tischbein.pulse.ui.design.SessionHrChart
import dev.tischbein.pulse.ui.design.Stat
import dev.tischbein.pulse.ui.design.Tag
import dev.tischbein.pulse.ui.design.ValueUnit
import dev.tischbein.pulse.ui.theme.InterDisplay
import dev.tischbein.pulse.ui.theme.InterText
import dev.tischbein.pulse.ui.theme.Pulse
import dev.tischbein.pulse.ui.theme.PulseType

/**
 * Post-session summary and session detail, stateless. One hero number (time in zone), at most
 * three numbers under it, then the HR graph and (4x4) the per-interval table.
 *
 * @param justFinished true right after a session (Done button), false when opened from the
 *   history (back arrow).
 */
@Composable
fun SessionDetailContent(
    d: SessionDetail,
    modifier: Modifier = Modifier,
    scrollable: Boolean = true,
    justFinished: Boolean = true,
    onBack: () -> Unit = {},
    onDone: () -> Unit = {},
    onDelete: () -> Unit = {},
) {
    Column(
        modifier
            .fillMaxSize()
            .background(Pulse.bg)
            .then(if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier)
            .padding(horizontal = ScreenPadding, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (!justFinished) BackRow(onBack)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Overline(d.subtitle)
                Spacer(Modifier.height(4.dp))
                Text(d.title, fontFamily = InterDisplay, fontWeight = FontWeight.Bold, fontSize = 30.sp, color = Pulse.text)
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (!d.complete) Tag("Incomplete", color = Pulse.warning)
                d.sync?.let { SyncTag(it) }
            }
        }

        // Hero: time in zone, then three basics.
        PulseCard {
            Overline(if (d.mode == SessionMode.INTERVAL_4X4) "Work time in zone" else "Time in zone")
            Row(verticalAlignment = Alignment.Bottom) {
                ValueUnit("${d.inZonePct}", "%", PulseType.hero.copy(fontSize = 64.sp, lineHeight = 70.sp), Pulse.inZone.accent, unitSize = 26.sp, unitColor = Pulse.inZone.accent)
                Spacer(Modifier.width(12.dp))
                Text(d.inZoneText, fontFamily = InterText, fontSize = 13.sp, color = Pulse.textMid, modifier = Modifier.padding(bottom = 12.dp))
            }
            Spacer(Modifier.height(14.dp))
            RowDivider()
            Spacer(Modifier.height(16.dp))
            Row {
                d.stats.forEach { Stat(it.label, it.value, Modifier.weight(1f), unit = it.unit) }
            }
        }

        d.newHrMax?.let {
            Text(
                "▲  $it",
                fontFamily = InterText,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                color = Pulse.above.accent,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Pulse.above.accent.copy(alpha = 0.10f)).padding(14.dp),
            )
        }

        // The graph.
        PulseCard(padding = PaddingValues(start = 16.dp, end = 12.dp, top = 16.dp, bottom = 12.dp)) {
            Text("Heart rate", fontFamily = InterText, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = Pulse.text)
            Spacer(Modifier.height(10.dp))
            SessionHrChart(d.hr, d.stepSec, d.bands, Modifier.fillMaxWidth().height(230.dp), yMin = d.yMin, yMax = d.yMax)
            d.gapsText?.let {
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(12.dp).height(3.dp).clip(CircleShape).background(Pulse.above.accent))
                    Spacer(Modifier.width(6.dp))
                    Text(it, fontFamily = InterText, fontSize = 12.sp, color = Pulse.textLow)
                }
            }
        }

        if (d.intervals.isNotEmpty()) IntervalTable(d)

        Spacer(Modifier.height(4.dp))
        if (justFinished) PillButton("Done", Modifier.fillMaxWidth(), height = 56.dp, onClick = onDone)
        Text(
            "Delete session",
            fontFamily = InterText,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
            color = Pulse.danger,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).clickable(onClick = onDelete).padding(vertical = 12.dp),
        )
    }
}

/** "‹ Back" at the top of a secondary screen. */
@Composable
fun BackRow(
    onBack: () -> Unit,
    label: String = "Back",
) {
    Row(
        Modifier.clip(CircleShape).clickable(onClick = onBack).padding(start = 2.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("‹", fontFamily = InterText, fontSize = 26.sp, color = Pulse.textMid, modifier = Modifier.padding(end = 6.dp, bottom = 3.dp))
        Text(label, fontFamily = InterText, fontWeight = FontWeight.Medium, fontSize = 15.sp, color = Pulse.textMid)
    }
}

@Composable
private fun IntervalTable(d: SessionDetail) {
    PulseCard(padding = PaddingValues(horizontal = 18.dp, vertical = 16.dp)) {
        Text("Intervals", fontFamily = InterText, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = Pulse.text)
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Overline("#", Modifier.width(28.dp))
            Overline("Avg", Modifier.weight(1f))
            Overline("Peak", Modifier.weight(1f))
            Overline("In zone", Modifier.weight(2f))
            Overline("HRR60", Modifier.weight(1f))
        }
        d.intervals.forEachIndexed { i, row ->
            Spacer(Modifier.height(10.dp))
            RowDivider()
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(22.dp).clip(CircleShape).background(Pulse.surfaceRaised), contentAlignment = Alignment.Center) {
                    Text("${row.number}", style = PulseType.smallNumber.copy(fontWeight = FontWeight.Bold, fontSize = 12.sp), color = Pulse.textMid)
                }
                Spacer(Modifier.width(8.dp))
                Text(row.avg, style = PulseType.number.copy(fontSize = 18.sp), color = Pulse.text, modifier = Modifier.weight(1f))
                Text(row.peak, style = PulseType.number.copy(fontSize = 18.sp), color = Pulse.textMid, modifier = Modifier.weight(1f))
                Row(Modifier.weight(2f), verticalAlignment = Alignment.CenterVertically) {
                    val pct = d.intervalZonePct.getOrElse(i) { 0f }
                    Box(Modifier.weight(1f).height(6.dp).clip(CircleShape).background(Pulse.surfaceRaised)) {
                        Box(Modifier.fillMaxWidth(pct / 100f).height(6.dp).clip(CircleShape).background(if (pct >= 70f) Pulse.inZone.accent else Pulse.below.accent))
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(row.pctInZone, style = PulseType.smallNumber.copy(fontSize = 13.sp), color = Pulse.text, modifier = Modifier.width(40.dp), textAlign = TextAlign.End)
                }
                Text(
                    row.hrr60,
                    style = PulseType.number.copy(fontSize = 18.sp),
                    color = Pulse.text,
                    modifier = Modifier.weight(1f).padding(start = 10.dp),
                )
            }
        }
    }
}
