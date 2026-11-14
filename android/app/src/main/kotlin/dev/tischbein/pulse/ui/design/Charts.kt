package dev.tischbein.pulse.ui.design

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.tischbein.pulse.coaching.PhaseKind
import dev.tischbein.pulse.ui.theme.InterText
import dev.tischbein.pulse.ui.theme.Pulse
import dev.tischbein.pulse.ui.theme.TABULAR
import kotlin.math.max
import kotlin.math.min

/*
 * Charts, drawn directly on a Compose Canvas.
 *
 * WHY not a chart library (Vico was considered): the look we want is a smooth line with a soft
 * gradient fill, per-phase target bands, phase shading, gaps left visibly open, a calendar
 * heatmap and a slim phase timeline. Vico covers the line and the bars but needs custom
 * decorations for every one of the rest, its 3.x API is still moving, and its defaults would
 * have to be switched off one by one. These ~400 lines draw exactly what we want and nothing else.
 *
 * Conventions: x in seconds (or days), y in bpm (or the metric); `null` values are gaps and
 * are never bridged; labels are few and small; gridlines are hairlines at 8 % white.
 */

private val LabelStyle = TextStyle(fontFamily = InterText, fontSize = 10.sp, color = Pulse.textLow, fontFeatureSettings = TABULAR)

/** A run of a phase in a chart: shaded background and, if known, its target zone band. */
@Immutable
data class ChartBand(
    val startSec: Float,
    val endSec: Float,
    val kind: PhaseKind,
    val targetLow: Int?,
    val targetHigh: Int?,
    /** Short label drawn at the top ("1", "W", "C"), or null. */
    val label: String? = null,
)

/** 1, 2, 2.5 or 5 times a power of ten, at least [raw]. */
private fun niceStep(raw: Float): Float {
    if (raw <= 0f) return 1f
    val mag = Math.pow(10.0, kotlin.math.floor(kotlin.math.log10(raw.toDouble()))).toFloat()
    return listOf(1f, 2f, 2.5f, 5f, 10f).map { it * mag }.first { it >= raw }
}

/** Builds a smooth path through the points (monotone-ish cubic through midpoints). */
private fun smoothPath(points: List<Offset>): Path {
    val path = Path()
    if (points.isEmpty()) return path
    path.moveTo(points[0].x, points[0].y)
    if (points.size == 1) return path
    for (i in 1 until points.size) {
        val prev = points[i - 1]
        val cur = points[i]
        val midX = (prev.x + cur.x) / 2f
        path.cubicTo(midX, prev.y, midX, cur.y, cur.x, cur.y)
    }
    return path
}

/** Splits values into runs without nulls, mapped to screen points. */
private fun runs(
    xs: List<Float>,
    ys: List<Float?>,
    toOffset: (Float, Float) -> Offset,
): List<List<Offset>> {
    val out = mutableListOf<List<Offset>>()
    var cur = mutableListOf<Offset>()
    for (i in xs.indices) {
        val y = ys[i]
        if (y == null) {
            if (cur.isNotEmpty()) out += cur
            cur = mutableListOf()
        } else {
            cur += toOffset(xs[i], y)
        }
    }
    if (cur.isNotEmpty()) out += cur
    return out
}

/** Line + gradient fill for each run. */
private fun DrawScope.lineWithFill(
    runs: List<List<Offset>>,
    color: Color,
    strokeWidth: Float,
    fillAlpha: Float,
    bottom: Float,
    top: Float,
) {
    for (run in runs) {
        val line = smoothPath(run)
        if (fillAlpha > 0f && run.size > 1) {
            val fill = Path().apply {
                addPath(line)
                lineTo(run.last().x, bottom)
                lineTo(run.first().x, bottom)
                close()
            }
            drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = fillAlpha), color.copy(alpha = 0f)), startY = top, endY = bottom))
        }
        drawPath(line, color, style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

private fun DrawScope.label(
    measurer: TextMeasurer,
    text: String,
    at: Offset,
    style: TextStyle = LabelStyle,
    alignRight: Boolean = false,
    centerX: Boolean = false,
) {
    val layout = measurer.measure(text, style)
    val x =
        when {
            alignRight -> at.x - layout.size.width
            centerX -> at.x - layout.size.width / 2f
            else -> at.x
        }
    drawText(layout, topLeft = Offset(x, at.y - layout.size.height / 2f))
}

/** Color of a phase's target band. Work is the one that matters, so it gets the accent. */
private fun bandColor(kind: PhaseKind): Color =
    when (kind) {
        PhaseKind.WORK -> Pulse.inZone.accent
        else -> Pulse.below.accent
    }

/**
 * The session graph: HR over the whole session, phase shading, the target zone of each phase
 * as a band, minute labels along the bottom and a few bpm gridlines.
 *
 * @param hr one value per [stepSec] seconds from t = 0; null = no signal (drawn as a gap).
 */
@Composable
fun SessionHrChart(
    hr: List<Int?>,
    stepSec: Float,
    bands: List<ChartBand>,
    modifier: Modifier = Modifier,
    yMin: Int = 90,
    yMax: Int = 200,
    gridStep: Int = 30,
) {
    val measurer = rememberTextMeasurer()
    Canvas(modifier) {
        val topPad = 18.dp.toPx()
        val bottomPad = 18.dp.toPx()
        val leftPad = 0f
        val rightPad = 28.dp.toPx()
        val w = size.width - leftPad - rightPad
        val h = size.height - topPad - bottomPad
        val totalSec = max(hr.size * stepSec, bands.maxOfOrNull { it.endSec } ?: 1f)
        fun x(sec: Float) = leftPad + sec / totalSec * w
        fun y(bpm: Float) = topPad + (1f - (bpm - yMin) / (yMax - yMin)) * h

        // Phase shading: work phases get a faint lift so the intervals read at a glance.
        for (b in bands) {
            val lo = b.targetLow
            val hi = b.targetHigh
            if (lo != null && hi != null) {
                val c = bandColor(b.kind)
                val top = y(hi.toFloat().coerceAtMost(yMax.toFloat()))
                val bottom = y(lo.toFloat().coerceAtLeast(yMin.toFloat()))
                drawRect(c.copy(alpha = when (b.kind) { PhaseKind.WORK -> 0.16f; PhaseKind.EASY -> 0.12f; else -> 0.07f }), Offset(x(b.startSec), top), Size(x(b.endSec) - x(b.startSec), bottom - top))
                drawLine(c.copy(alpha = if (b.kind == PhaseKind.WORK) 0.55f else 0.22f), Offset(x(b.startSec), top), Offset(x(b.endSec), top), 1.dp.toPx())
            }
            b.label?.let { label(measurer, it, Offset((x(b.startSec) + x(b.endSec)) / 2f, topPad / 2f), LabelStyle.copy(fontWeight = FontWeight.SemiBold, color = if (b.kind == PhaseKind.WORK) Pulse.textMid else Pulse.textLow), centerX = true) }
        }

        // Gridlines with bpm labels on the right.
        var g = ((yMin + gridStep - 1) / gridStep) * gridStep
        while (g <= yMax) {
            val yy = y(g.toFloat())
            drawLine(Pulse.chartGrid, Offset(leftPad, yy), Offset(leftPad + w, yy), 1f)
            label(measurer, g.toString(), Offset(size.width, yy), alignRight = true)
            g += gridStep
        }

        // Minute labels.
        val totalMin = (totalSec / 60f).toInt()
        val tickMin = if (totalMin > 60) 15 else 10
        var m = 0
        while (m <= totalMin) {
            label(measurer, if (m == 0) "0" else "$m′", Offset(x(m * 60f), size.height - bottomPad / 2f + 2f), centerX = m != 0)
            m += tickMin
        }

        val xs = hr.indices.map { it * stepSec }
        val ys = hr.map { v -> v?.toFloat()?.coerceIn(yMin.toFloat(), yMax.toFloat()) }
        lineWithFill(runs(xs, ys) { sx, sy -> Offset(x(sx), y(sy)) }, Pulse.chartLine, 2.dp.toPx(), 0f, topPad + h, topPad)

        // Gaps: a dotted baseline marker so a signal loss is visible, never hidden.
        var i = 0
        while (i < hr.size) {
            if (hr[i] == null) {
                val start = i
                while (i < hr.size && hr[i] == null) i++
                val gx0 = x(start * stepSec)
                val gx1 = x(i * stepSec)
                // A short gap is only a few px wide: mark it with a full-height band too.
                val gw = maxOf(gx1 - gx0, 4.dp.toPx())
                drawRect(Pulse.above.accent.copy(alpha = 0.18f), Offset((gx0 + gx1 - gw) / 2f, topPad), Size(gw, h))
                drawLine(
                    Pulse.above.accent.copy(alpha = 0.8f),
                    Offset(gx0, topPad + h - 2.dp.toPx()),
                    Offset(gx1, topPad + h - 2.dp.toPx()),
                    2.dp.toPx(),
                    StrokeCap.Round,
                )
            } else {
                i++
            }
        }
    }
}

/**
 * Small line chart without axes. Used live (last 2 minutes with the target band) and in the
 * session list rows.
 */
@Composable
fun Sparkline(
    values: List<Int?>,
    modifier: Modifier = Modifier,
    color: Color = Pulse.chartLine,
    bandLow: Int? = null,
    bandHigh: Int? = null,
    bandColor: Color = Pulse.inZone.accent,
    strokeWidth: Dp = 2.dp,
    fillAlpha: Float = 0.18f,
    yMin: Int? = null,
    yMax: Int? = null,
    showLastDot: Boolean = false,
) {
    Canvas(modifier) {
        val valid = values.filterNotNull()
        if (valid.isEmpty() && bandLow == null) return@Canvas
        val lo = yMin ?: (min(valid.minOrNull() ?: bandLow!!, bandLow ?: Int.MAX_VALUE) - 6)
        val hi = yMax ?: (max(valid.maxOrNull() ?: bandHigh!!, bandHigh ?: Int.MIN_VALUE) + 6)
        val pad = strokeWidth.toPx()
        fun x(i: Int) = if (values.size <= 1) 0f else i / (values.size - 1f) * size.width
        fun y(v: Float) = pad + (1f - (v - lo) / (hi - lo).coerceAtLeast(1)) * (size.height - 2 * pad)
        if (bandLow != null && bandHigh != null) {
            val top = y(bandHigh.toFloat())
            val bottom = y(bandLow.toFloat())
            drawRoundRect(bandColor.copy(alpha = 0.13f), Offset(0f, top), Size(size.width, bottom - top), CornerRadius(4.dp.toPx()))
            val dash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))
            drawLine(bandColor.copy(alpha = 0.5f), Offset(0f, top), Offset(size.width, top), 1.dp.toPx(), pathEffect = dash)
            drawLine(bandColor.copy(alpha = 0.5f), Offset(0f, bottom), Offset(size.width, bottom), 1.dp.toPx(), pathEffect = dash)
        }
        val rs = runs(values.indices.map { it.toFloat() }, values.map { it?.toFloat() }) { i, v -> Offset(x(i.toInt()), y(v)) }
        lineWithFill(rs, color, strokeWidth.toPx(), fillAlpha, size.height, 0f)
        if (showLastDot) {
            rs.lastOrNull()?.lastOrNull()?.let {
                drawCircle(color.copy(alpha = 0.25f), 7.dp.toPx(), it)
                drawCircle(color, 3.5.dp.toPx(), it)
            }
        }
    }
}

/** One value in a trend chart. [x] is a day index (0 = first day of the window). */
@Immutable
data class TrendPoint(val x: Float, val y: Float)

/**
 * A metric over the last weeks: dots joined by a soft line, the previous and the current
 * window's averages as dashed reference lines, and the current window lightly highlighted.
 *
 * @param splitX day where the current window starts (e.g. 21 of 42).
 */
@Composable
fun TrendChart(
    points: List<TrendPoint>,
    totalX: Float,
    splitX: Float,
    color: Color,
    modifier: Modifier = Modifier,
    previousAvg: Float? = null,
    currentAvg: Float? = null,
    yMin: Float? = null,
    yMax: Float? = null,
    yLabel: (Float) -> String = { it.toInt().toString() },
) {
    val measurer = rememberTextMeasurer()
    Canvas(modifier) {
        if (points.isEmpty()) return@Canvas
        val rightPad = 30.dp.toPx()
        val vPad = 8.dp.toPx()
        val w = size.width - rightPad
        val h = size.height - 2 * vPad
        // Round the range to "nice" steps so the three labels are readable numbers.
        val rawLo = yMin ?: points.minOf { it.y }
        val rawHi = yMax ?: points.maxOf { it.y }
        val step = niceStep((rawHi - rawLo) / 2f)
        val lo = yMin ?: (kotlin.math.floor(rawLo / step) * step)
        val hi = yMax ?: (lo + 2 * step * kotlin.math.ceil(((rawHi - lo) / (2 * step)).coerceAtLeast(1f)))
        fun x(d: Float) = d / totalX * w
        fun y(v: Float) = vPad + (1f - (v - lo) / (hi - lo)) * h

        // Current window highlight.
        drawRoundRect(Color.White.copy(alpha = 0.03f), Offset(x(splitX), 0f), Size(w - x(splitX), size.height), CornerRadius(10.dp.toPx()))

        listOf(lo, (lo + hi) / 2f, hi).forEach { v ->
            drawLine(Pulse.chartGrid, Offset(0f, y(v)), Offset(w, y(v)), 1f)
            label(measurer, yLabel(v), Offset(size.width, y(v)), alignRight = true)
        }
        val dash = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx()))
        previousAvg?.let { drawLine(Pulse.textLow, Offset(0f, y(it)), Offset(x(splitX), y(it)), 1.5.dp.toPx(), pathEffect = dash) }
        currentAvg?.let { drawLine(color, Offset(x(splitX), y(it)), Offset(w, y(it)), 1.5.dp.toPx(), pathEffect = dash) }

        val offsets = points.map { Offset(x(it.x), y(it.y)) }
        lineWithFill(listOf(offsets), color.copy(alpha = 0.9f), 2.dp.toPx(), 0.12f, size.height, 0f)
        points.forEachIndexed { i, p ->
            val current = p.x >= splitX
            drawCircle(Pulse.surface, 4.5.dp.toPx(), offsets[i])
            drawCircle(if (current) color else Pulse.textMid, 3.dp.toPx(), offsets[i])
        }
    }
}

/** Vertical bars (e.g. % in zone per 4x4), current window in [color], earlier ones muted. */
@Composable
fun BarTrend(
    values: List<Pair<Float, Float>>,
    totalX: Float,
    splitX: Float,
    color: Color,
    modifier: Modifier = Modifier,
    yMax: Float = 100f,
    yLabel: (Float) -> String = { "${it.toInt()}" },
) {
    val measurer = rememberTextMeasurer()
    Canvas(modifier) {
        val rightPad = 30.dp.toPx()
        val w = size.width - rightPad
        val h = size.height
        val barW = (w / totalX * 0.8f).coerceIn(4.dp.toPx(), 9.dp.toPx())
        listOf(0f, yMax / 2f, yMax).forEach { v ->
            val yy = h - v / yMax * h
            drawLine(Pulse.chartGrid, Offset(0f, yy), Offset(w, yy), 1f)
            label(measurer, yLabel(v), Offset(size.width, yy.coerceIn(6.dp.toPx(), h - 6.dp.toPx())), alignRight = true)
        }
        for ((d, v) in values) {
            val cx = (d + 0.5f) / totalX * w
            val bh = (v / yMax).coerceIn(0f, 1f) * h
            drawRoundRect(
                if (d >= splitX) color else Pulse.textLow.copy(alpha = 0.6f),
                Offset(cx - barW / 2f, h - bh),
                Size(barW, bh),
                CornerRadius(barW / 2f),
            )
        }
    }
}

/** One day of the training calendar. [level] 0 = rest, 1-3 = more training. */
@Immutable
data class CalendarDay(val level: Int, val interval: Boolean, val isToday: Boolean, val isFuture: Boolean)

/** Training days of the last weeks: rows = weeks (oldest on top), columns = Mon..Sun. */
@Composable
fun CalendarHeatmap(
    weeks: List<List<CalendarDay>>,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    Canvas(modifier) {
        val header = 16.dp.toPx()
        val gap = 6.dp.toPx()
        val cell = min((size.width - 6 * gap) / 7f, (size.height - header - (weeks.size - 1) * gap) / weeks.size)
        val x0 = 0f
        listOf("M", "T", "W", "T", "F", "S", "S").forEachIndexed { i, d ->
            label(measurer, d, Offset(x0 + i * (cell + gap) + cell / 2f, header / 2f - 2f), centerX = true)
        }
        weeks.forEachIndexed { r, week ->
            week.forEachIndexed { c, day ->
                val tl = Offset(x0 + c * (cell + gap), header + r * (cell + gap))
                val base = if (day.interval) Pulse.inZone.accent else Pulse.below.accent
                val color =
                    when {
                        day.isFuture -> Color.Transparent
                        day.level == 0 -> Pulse.surfaceRaised
                        else -> base.copy(alpha = 0.35f + 0.65f * (day.level / 3f))
                    }
                drawRoundRect(color, tl, Size(cell, cell), CornerRadius(cell * 0.28f))
                if (day.isFuture) {
                    drawRoundRect(Pulse.stroke, tl, Size(cell, cell), CornerRadius(cell * 0.28f), style = Stroke(1.dp.toPx()))
                }
                if (day.isToday) {
                    drawRoundRect(Pulse.text, tl, Size(cell, cell), CornerRadius(cell * 0.28f), style = Stroke(1.5.dp.toPx()))
                }
            }
        }
    }
}

/** One phase of the planned session in the slim timeline. [weight] = its duration. */
@Immutable
data class TimelineSegment(val kind: PhaseKind, val weight: Float)

/**
 * The whole session as a slim bar of phases (warmup | work/recovery x4 | cooldown) with the
 * current position. Done phases are solid, the rest dim; work phases are taller.
 *
 * @param position 0..1 through the whole session.
 */
@Composable
fun PhaseTimeline(
    segments: List<TimelineSegment>,
    position: Float,
    modifier: Modifier = Modifier,
    accent: Color = Pulse.text,
) {
    Canvas(modifier) {
        val gap = 3.dp.toPx()
        val total = segments.sumOf { it.weight.toDouble() }.toFloat()
        val usable = size.width - gap * (segments.size - 1)
        val markerX = position.coerceIn(0f, 1f) * size.width
        var x = 0f
        val tall = size.height * 0.62f
        val short = size.height * 0.30f
        val cy = size.height / 2f
        for (s in segments) {
            val w = s.weight / total * usable
            val hh = if (s.kind == PhaseKind.WORK) tall else short
            val tl = Offset(x, cy - hh / 2f)
            val base = if (s.kind == PhaseKind.WORK) Pulse.inZone.accent else Pulse.textMid
            drawRoundRect(base.copy(alpha = 0.22f), tl, Size(w, hh), CornerRadius(hh / 2f))
            val doneW = (markerX - x).coerceIn(0f, w)
            if (doneW > 0f) drawRoundRect(base, tl, Size(max(doneW, hh), hh), CornerRadius(hh / 2f))
            x += w + gap
        }
        drawCircle(Pulse.bg, size.height / 2f, Offset(markerX, cy))
        drawCircle(accent, size.height / 2f - 2.5.dp.toPx(), Offset(markerX, cy))
    }
}

/**
 * A 270-degree gauge (open at the bottom) around [content]: phase progress on the live screen.
 */
@Composable
fun ProgressRing(
    progress: Float,
    color: Color,
    modifier: Modifier = Modifier,
    strokeWidth: Dp = 14.dp,
    dashed: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) {
            val sw = strokeWidth.toPx()
            val d = min(size.width, size.height) - sw
            val tl = Offset((size.width - d) / 2f, (size.height - d) / 2f)
            val start = 135f
            val sweep = 270f
            val track =
                if (dashed) {
                    Stroke(sw / 3f, cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 9.dp.toPx())))
                } else {
                    Stroke(sw, cap = StrokeCap.Round)
                }
            drawArc(Color.White.copy(alpha = if (dashed) 0.25f else 0.07f), start, sweep, false, tl, Size(d, d), style = track)
            val p = progress.coerceIn(0f, 1f)
            if (p > 0f && !dashed) {
                // Soft glow under the arc, then the arc itself.
                drawArc(color.copy(alpha = 0.18f), start, sweep * p, false, tl, Size(d, d), style = Stroke(sw * 2.2f, cap = StrokeCap.Round))
                drawArc(color, start, sweep * p, false, tl, Size(d, d), style = Stroke(sw, cap = StrokeCap.Round))
            }
        }
        content()
    }
}
