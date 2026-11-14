package dev.tischbein.pulse.ui

import androidx.compose.foundation.ScrollState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.tischbein.pulse.coaching.SessionMode
import dev.tischbein.pulse.ui.design.BarTrend
import dev.tischbein.pulse.ui.design.CalendarHeatmap
import dev.tischbein.pulse.ui.design.DeltaChip
import dev.tischbein.pulse.ui.design.Overline
import dev.tischbein.pulse.ui.design.PulseCard
import dev.tischbein.pulse.ui.design.ScreenPadding
import dev.tischbein.pulse.ui.design.SectionTitle
import dev.tischbein.pulse.ui.design.Sparkline
import dev.tischbein.pulse.ui.design.TrendChart
import dev.tischbein.pulse.ui.design.ValueUnit
import dev.tischbein.pulse.ui.theme.InterDisplay
import dev.tischbein.pulse.ui.theme.InterText
import dev.tischbein.pulse.ui.theme.Pulse
import dev.tischbein.pulse.ui.theme.PulseType

/**
 * The Progress page, stateless: a dashboard (3 weeks vs the 3 before, training calendar,
 * the trends that say something about fitness, personal bests) above the list of past
 * sessions. Tapping a session opens its detail.
 */
@Composable
fun ProgressContent(
    p: ProgressOverview,
    modifier: Modifier = Modifier,
    scrollable: Boolean = true,
    scrollState: ScrollState = rememberScrollState(),
    /** Sessions shown before "Show all" (the full list is its own screen). */
    sessionLimit: Int = 8,
    onShowAll: () -> Unit = {},
    onOpenSession: (String) -> Unit = {},
) {
    Column(
        modifier
            .then(if (scrollable) Modifier.fillMaxSize() else Modifier.fillMaxWidth())
            .background(Pulse.bg)
            .then(if (scrollable) Modifier.verticalScroll(scrollState) else Modifier)
            .padding(horizontal = ScreenPadding, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Progress", fontFamily = InterDisplay, fontWeight = FontWeight.Bold, fontSize = 30.sp, color = Pulse.text)
        Text(p.periodText, fontFamily = InterText, fontSize = 14.sp, color = Pulse.textMid)
        Spacer(Modifier.height(2.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            p.compare.forEach { CompareTile(it, Modifier.weight(1f)) }
        }

        PulseCard {
            Overline("Consistency · 6 weeks")
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CalendarHeatmap(p.calendar, Modifier.width(196.dp).height(178.dp))
                Spacer(Modifier.width(24.dp))
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Column {
                        ValueUnit("${p.activeDays}", "days", PulseType.number, Pulse.text, unitSize = 12.sp)
                        Text("with training", fontFamily = InterText, fontSize = 12.sp, color = Pulse.textLow)
                    }
                    Column {
                        ValueUnit("${p.streakWeeks}", if (p.streakWeeks == 1) "week" else "weeks", PulseType.number, Pulse.text, unitSize = 12.sp)
                        Text("streak, 2+ a week", fontFamily = InterText, fontSize = 12.sp, color = Pulse.textLow)
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        LegendDot(Pulse.inZone.accent, "4×4")
                        LegendDot(Pulse.below.accent, "Easy")
                    }
                }
            }
        }

        SectionTitle("Trends", trailing = "dashed = 3-week average")
        TrendCardView(p.hrr, Pulse.inZone.accent) {
            TrendChart(p.hrr.points, p.totalDays.toFloat(), p.splitDay.toFloat(), Pulse.inZone.accent, it, p.hrr.previousAvg, p.hrr.currentAvg)
        }
        TrendCardView(p.zone, Pulse.inZone.accent) {
            BarTrend(p.zoneBars, p.totalDays.toFloat(), p.splitDay.toFloat(), Pulse.inZone.accent, it)
        }
        TrendCardView(p.drift, Pulse.below.accent) {
            TrendChart(p.drift.points, p.totalDays.toFloat(), p.splitDay.toFloat(), Pulse.below.accent, it, p.drift.previousAvg, p.drift.currentAvg, yMin = 0f, yLabel = { "${it.toInt()}" })
        }

        SectionTitle("Personal bests")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            p.bests.forEach { b ->
                PulseCard(Modifier.weight(1f), padding = PaddingValues(14.dp)) {
                    Overline(b.label)
                    Spacer(Modifier.height(6.dp))
                    ValueUnit(b.value, b.unit, PulseType.number.copy(fontSize = 22.sp), Pulse.text, unitSize = 11.sp)
                    Text(b.note, fontFamily = InterText, fontSize = 12.sp, color = Pulse.textLow)
                }
            }
        }

        SectionTitle("Sessions", trailing = "${p.sessions.size} in 6 weeks")
        p.sessions.take(sessionLimit).forEach { SessionRowView(it) { onOpenSession(it.id) } }
        if (p.sessions.size > sessionLimit) {
            Text(
                "Show all ${p.sessions.size} sessions",
                fontFamily = InterText,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                color = Pulse.accent,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).clickable(onClick = onShowAll).padding(vertical = 14.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

@Composable
private fun LegendDot(
    color: Color,
    text: String,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(RoundedCornerShape(2.dp)).background(color))
        Spacer(Modifier.width(5.dp))
        Text(text, fontFamily = InterText, fontSize = 12.sp, color = Pulse.textMid)
    }
}

@Composable
private fun CompareTile(
    c: CompareStat,
    modifier: Modifier,
) {
    PulseCard(modifier, padding = PaddingValues(start = 14.dp, end = 10.dp, top = 14.dp, bottom = 14.dp)) {
        Overline(c.label)
        Spacer(Modifier.height(6.dp))
        Text(c.value, style = PulseType.number.copy(fontSize = 22.sp), color = Pulse.text, maxLines = 1)
        Spacer(Modifier.height(8.dp))
        DeltaChip(c.delta, c.trend)
        Spacer(Modifier.height(6.dp))
        Text(c.previous, fontFamily = InterText, fontSize = 11.sp, color = Pulse.textLow, maxLines = 1)
    }
}

@Composable
private fun TrendCardView(
    t: TrendCard,
    color: Color,
    chart: @Composable (Modifier) -> Unit,
) {
    PulseCard(padding = PaddingValues(start = 18.dp, end = 14.dp, top = 16.dp, bottom = 14.dp)) {
        Overline(t.title)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            ValueUnit(t.headline, t.unit, PulseType.bigNumber.copy(fontSize = 34.sp), color, unitSize = 14.sp)
            Spacer(Modifier.weight(1f))
            t.delta?.let { DeltaChip("$it vs prev", t.trend) }
        }
        Text(t.caption, fontFamily = InterText, fontSize = 12.sp, color = Pulse.textLow)
        Spacer(Modifier.height(12.dp))
        chart(Modifier.fillMaxWidth().height(112.dp))
        Spacer(Modifier.height(6.dp))
        Row {
            Text("6 wk ago", fontFamily = InterText, fontSize = 10.sp, color = Pulse.textLow, modifier = Modifier.weight(1f))
            Text("3 wk", fontFamily = InterText, fontSize = 10.sp, color = Pulse.textLow, modifier = Modifier.weight(1f))
            Text("today", fontFamily = InterText, fontSize = 10.sp, color = Pulse.textLow, modifier = Modifier.padding(end = 30.dp))
        }
    }
}

@Composable
fun SessionRowView(
    r: SessionListRow,
    onClick: () -> Unit = {},
) {
    val accent = if (r.mode == SessionMode.INTERVAL_4X4) Pulse.inZone.accent else Pulse.below.accent
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Pulse.surface).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(4.dp).height(40.dp).clip(CircleShape).background(accent))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(r.title, fontFamily = InterText, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = Pulse.text, maxLines = 1)
            Text(r.date, fontFamily = InterText, fontSize = 12.sp, color = Pulse.textLow, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                MiniStat(r.duration, null)
                MiniStat(r.avgHr, "bpm")
                MiniStat(r.inZone, "in zone")
            }
        }
        Sparkline(r.sparkline, Modifier.width(96.dp).height(40.dp), color = accent, strokeWidth = 1.5.dp, fillAlpha = 0.22f)
    }
}

@Composable
private fun MiniStat(
    value: String,
    unit: String?,
) {
    ValueUnit(value, unit, PulseType.smallNumber.copy(fontSize = 13.sp), Pulse.textMid, unitSize = 11.sp, unitColor = Pulse.textLow)
}
