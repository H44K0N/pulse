package dev.tischbein.pulse.ui

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.tischbein.pulse.AppConstants
import dev.tischbein.pulse.coaching.SessionMode
import dev.tischbein.pulse.ui.design.BarTrend
import dev.tischbein.pulse.ui.design.CalendarHeatmap
import dev.tischbein.pulse.ui.design.CardShape
import dev.tischbein.pulse.ui.design.DeltaChip
import dev.tischbein.pulse.ui.design.Overline
import dev.tischbein.pulse.ui.design.PulseCard
import dev.tischbein.pulse.ui.design.ScreenPadding
import dev.tischbein.pulse.ui.design.SectionTitle
import dev.tischbein.pulse.ui.design.Segmented
import dev.tischbein.pulse.ui.design.Sparkline
import dev.tischbein.pulse.ui.design.Tag
import dev.tischbein.pulse.ui.design.TrendChart
import dev.tischbein.pulse.ui.design.ValueUnit
import dev.tischbein.pulse.ui.theme.InterDisplay
import dev.tischbein.pulse.ui.theme.InterText
import dev.tischbein.pulse.ui.theme.Pulse
import dev.tischbein.pulse.ui.theme.PulseType

/**
 * The Progress page, stateless. Top to bottom: two tiles (sessions, time in zone: last 3 weeks
 * vs the 3 before; tap Sessions for the training calendar), ONE trend chart with a selector
 * (Recovery / In zone / Drift), and the last [AppConstants.RECENT_SESSIONS] sessions with
 * "Show all". Tapping a session opens its detail.
 */
@Composable
fun ProgressContent(
    p: ProgressOverview,
    modifier: Modifier = Modifier,
    scrollable: Boolean = true,
    scrollState: ScrollState = rememberScrollState(),
    initialTrend: TrendKind = TrendKind.RECOVERY,
    initialCalendarOpen: Boolean = false,
    onShowAll: () -> Unit = {},
    onOpenSession: (String) -> Unit = {},
) {
    var trend by rememberSaveable { mutableStateOf(initialTrend) }
    var calendarOpen by rememberSaveable { mutableStateOf(initialCalendarOpen) }
    Column(
        modifier
            .then(if (scrollable) Modifier.fillMaxSize() else Modifier.fillMaxWidth())
            .background(Pulse.bg)
            .then(if (scrollable) Modifier.verticalScroll(scrollState) else Modifier)
            .padding(horizontal = ScreenPadding, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Progress", fontFamily = InterDisplay, fontWeight = FontWeight.Bold, fontSize = 30.sp, color = Pulse.text)
        if (p.totalSessions == 0) {
            EmptyProgress()
            return@Column
        }
        Text(p.periodText, fontFamily = InterText, fontSize = 14.sp, color = Pulse.textMid)
        Spacer(Modifier.height(2.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            p.compare.forEachIndexed { i, c ->
                // The first tile (sessions) opens the training calendar.
                CompareTile(c, Modifier.weight(1f), expandable = i == 0, expanded = calendarOpen, onClick = if (i == 0) ({ calendarOpen = !calendarOpen }) else null)
            }
        }
        AnimatedVisibility(calendarOpen) { CalendarCard(p) }

        TrendCardView(p, p.trends.first { it.kind == trend }) { trend = it }

        SectionTitle("Recent sessions")
        p.recent.forEach { SessionRowView(it) { onOpenSession(it.id) } }
        if (p.totalSessions > p.recent.size) {
            Text(
                "Show all ${p.totalSessions} sessions",
                fontFamily = InterText,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                color = Pulse.accent,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).clickable(onClick = onShowAll).padding(vertical = 14.dp),
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun EmptyProgress() {
    PulseCard {
        Text("No sessions yet", fontFamily = InterText, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = Pulse.text)
        Spacer(Modifier.height(6.dp))
        Text(
            "Finish a session and it shows up here, with your trends once there are a few.",
            fontFamily = InterText,
            fontSize = 14.sp,
            color = Pulse.textMid,
        )
    }
}

/** Every finished session, newest first (the "Show all" screen). */
@Composable
fun AllSessionsContent(
    rows: List<SessionListRow>,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    onOpenSession: (String) -> Unit = {},
) {
    LazyColumn(
        modifier.fillMaxSize().background(Pulse.bg),
        contentPadding = PaddingValues(horizontal = ScreenPadding, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { BackRow(onBack, "Progress") }
        item {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("All sessions", fontFamily = InterDisplay, fontWeight = FontWeight.Bold, fontSize = 30.sp, color = Pulse.text, modifier = Modifier.weight(1f))
                Text("${rows.size}", style = PulseType.smallNumber.copy(fontSize = 15.sp), color = Pulse.textLow, modifier = Modifier.padding(bottom = 6.dp))
            }
        }
        items(rows, key = { it.id }) { r -> SessionRowView(r) { onOpenSession(r.id) } }
    }
}

@Composable
private fun CalendarCard(p: ProgressOverview) {
    PulseCard(padding = PaddingValues(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CalendarHeatmap(p.calendar, Modifier.width(168.dp).height(152.dp))
            Spacer(Modifier.width(20.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(p.consistencyText, fontFamily = InterText, fontSize = 13.sp, color = Pulse.textMid)
                LegendDot(Pulse.inZone.accent, "4×4")
                LegendDot(Pulse.below.accent, "Easy")
            }
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
    expandable: Boolean,
    expanded: Boolean,
    onClick: (() -> Unit)?,
) {
    PulseCard(
        modifier.then(if (onClick != null) Modifier.clip(CardShape).clickable(onClickLabel = "Show training calendar", onClick = onClick) else Modifier),
        padding = PaddingValues(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Overline(c.label, Modifier.weight(1f))
            if (expandable) Text(if (expanded) "▴" else "▾", fontSize = 16.sp, color = Pulse.textMid)
        }
        Spacer(Modifier.height(6.dp))
        Text(c.value, style = PulseType.number.copy(fontSize = 24.sp), color = Pulse.text, maxLines = 1)
        Spacer(Modifier.height(8.dp))
        DeltaChip(c.delta, c.trend)
    }
}

@Composable
private fun TrendCardView(
    p: ProgressOverview,
    t: TrendCard,
    onSelect: (TrendKind) -> Unit,
) {
    val color = if (t.kind == TrendKind.DRIFT) Pulse.below.accent else Pulse.inZone.accent
    PulseCard(padding = PaddingValues(start = 16.dp, end = 14.dp, top = 14.dp, bottom = 14.dp)) {
        Segmented(TrendKind.entries.map { it.tab }, TrendKind.entries.indexOf(t.kind), Modifier.fillMaxWidth(), background = Pulse.bg) {
            onSelect(TrendKind.entries[it])
        }
        Spacer(Modifier.height(16.dp))
        Overline(t.title)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            ValueUnit(t.headline, t.unit, PulseType.bigNumber.copy(fontSize = 34.sp), color, unitSize = 14.sp)
            Spacer(Modifier.weight(1f))
            t.delta?.let { DeltaChip("$it vs prev", t.trend) }
        }
        Text(t.caption, fontFamily = InterText, fontSize = 12.sp, color = Pulse.textLow)
        Spacer(Modifier.height(12.dp))
        val chart = Modifier.fillMaxWidth().height(120.dp)
        if (t.points.isEmpty()) {
            Box(chart, contentAlignment = Alignment.Center) {
                Text("No ${if (t.kind == TrendKind.DRIFT) "easy runs" else "4×4 sessions"} in the last 6 weeks", fontFamily = InterText, fontSize = 13.sp, color = Pulse.textLow)
            }
        } else if (t.kind == TrendKind.IN_ZONE) {
            BarTrend(t.points.map { it.x - 0.5f to it.y }, p.totalDays.toFloat(), p.splitDay.toFloat(), color, chart)
        } else {
            TrendChart(
                t.points, p.totalDays.toFloat(), p.splitDay.toFloat(), color, chart, t.previousAvg, t.currentAvg,
                yMin = if (t.kind == TrendKind.DRIFT) 0f else null,
            )
        }
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(r.title, fontFamily = InterText, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = Pulse.text, maxLines = 1)
                if (!r.complete) {
                    Spacer(Modifier.width(8.dp))
                    Tag("Incomplete", color = Pulse.warning)
                }
                r.sync?.let {
                    Spacer(Modifier.width(8.dp))
                    SyncTag(it)
                }
            }
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

/** "Not synced" (quiet grey) or "Upload failed" (warning), next to a session's title. */
@Composable
fun SyncTag(badge: SyncBadge) {
    Tag(badge.label, color = if (badge == SyncBadge.REJECTED) Pulse.warning else Pulse.textLow)
}
