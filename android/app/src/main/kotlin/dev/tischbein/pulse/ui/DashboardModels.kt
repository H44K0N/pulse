package dev.tischbein.pulse.ui

import dev.tischbein.pulse.AppConstants
import dev.tischbein.pulse.coaching.CoachingState
import dev.tischbein.pulse.coaching.EasyConfig
import dev.tischbein.pulse.coaching.FourByFourConfig
import dev.tischbein.pulse.coaching.MS_PER_MINUTE
import dev.tischbein.pulse.coaching.MS_PER_SECOND
import dev.tischbein.pulse.coaching.PhaseKind
import dev.tischbein.pulse.coaching.SessionMode
import dev.tischbein.pulse.coaching.SessionResult
import dev.tischbein.pulse.data.SessionDigest
import dev.tischbein.pulse.data.bucketMedians
import dev.tischbein.pulse.sensor.ConnectionState
import dev.tischbein.pulse.sensor.DeviceStatus
import dev.tischbein.pulse.session.formatDuration
import dev.tischbein.pulse.ui.design.CalendarDay
import dev.tischbein.pulse.ui.design.ChartBand
import dev.tischbein.pulse.ui.design.TimelineSegment
import dev.tischbein.pulse.ui.design.Trend
import dev.tischbein.pulse.ui.design.TrendPoint
import dev.tischbein.pulse.voice.VoiceStatus
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * Plain values for the screens (live, session detail, progress). No Compose here, so the
 * mapping is unit-testable; the *Content composables only lay them out.
 */

// ---- Live ------------------------------------------------------------------------------

/** A small chip on the live screen. Only shown when something needs attention. */
data class LiveWarning(val text: String, val severe: Boolean)

data class LiveDashboard(
    val ui: LiveUi,
    val mode: SessionMode,
    val hr: Int?,
    val targetLow: Int?,
    val targetHigh: Int?,
    /** Work phase: the zone band is the accent green; other phases the calmer blue. */
    val isWork: Boolean,
    /** 4x4 during work and its recovery, else null. */
    val intervalNumber: Int?,
    val intervalCount: Int?,
    /** "2:14" time left in the phase (4x4) or elapsed (easy). */
    val bigTime: String,
    /** "left" (4x4), "of 45:00" or "elapsed" (easy). */
    val bigTimeLabel: String,
    /** Smoothed HR of the last [AppConstants.LIVE_WINDOW_S] seconds, 1 Hz, oldest first; null = no signal. */
    val recentHr: List<Int?>,
    /** Planned phases (4x4), one segment for an easy run with a target, else empty. */
    val timeline: List<TimelineSegment>,
    val timelinePosition: Float,
    /** Easy: drift so far, "+2.4 %"; null when not enough data yet. */
    val driftText: String?,
    /** Sensor lost: "Last 158 bpm · 14 s ago". */
    val lastSeenText: String?,
    val warnings: List<LiveWarning>,
)

fun planSegments(p: FourByFourConfig): List<TimelineSegment> =
    buildList {
        add(TimelineSegment(PhaseKind.WARMUP, p.warmupMs.toFloat()))
        repeat(p.intervalCount) {
            add(TimelineSegment(PhaseKind.WORK, p.workMs.toFloat()))
            add(TimelineSegment(PhaseKind.RECOVERY, p.recoveryMs.toFloat()))
        }
        add(TimelineSegment(PhaseKind.COOLDOWN, p.cooldownMs.toFloat()))
    }

fun planTotalMs(p: FourByFourConfig): Long = p.warmupMs + p.intervalCount * (p.workMs + p.recoveryMs) + p.cooldownMs

private fun phaseDurationMs(
    kind: PhaseKind,
    p: FourByFourConfig,
): Long =
    when (kind) {
        PhaseKind.WARMUP -> p.warmupMs
        PhaseKind.WORK -> p.workMs
        PhaseKind.RECOVERY -> p.recoveryMs
        PhaseKind.COOLDOWN -> p.cooldownMs
        PhaseKind.EASY -> 0
    }

/** Index of the current phase in [planSegments]. */
private fun phaseIndex(
    state: CoachingState,
    p: FourByFourConfig,
): Int =
    when (state.phaseKind) {
        PhaseKind.WORK -> 1 + 2 * ((state.intervalNumber ?: 1) - 1)
        PhaseKind.RECOVERY -> 2 + 2 * ((state.intervalNumber ?: 1) - 1)
        PhaseKind.COOLDOWN -> 1 + 2 * p.intervalCount
        else -> 0
    }

/**
 * Cardiac drift of the session so far: mean HR of the second half vs the first half, in
 * percent, with the same warm-up exclusion and minimum window as the engine's official
 * end-of-session value ([EasyConfig.driftIgnoreFirstMs], [EasyConfig.driftMinWindowMs]).
 * Null while there is not enough data. Only a live hint; the engine's value is the record.
 */
fun liveDriftPct(
    hrBySecond: List<Int?>,
    easy: EasyConfig = EasyConfig(),
): Double? {
    val skip = (easy.driftIgnoreFirstMs / MS_PER_SECOND).toInt()
    if (hrBySecond.size <= skip) return null
    val valid = hrBySecond.subList(skip, hrBySecond.size).filterNotNull()
    if (valid.size < easy.driftMinWindowMs / MS_PER_SECOND) return null
    val half = valid.size / 2
    val first = valid.subList(0, half).average()
    val second = valid.subList(half, valid.size).average()
    return (second - first) / first * 100.0
}

fun liveDashboard(
    state: CoachingState,
    voice: VoiceStatus,
    device: DeviceStatus,
    protocol: FourByFourConfig,
    recentHr: List<Int?>,
    easyTargetMs: Long? = null,
    driftPct: Double? = null,
    lastValidHr: Int? = null,
    msSinceLastValid: Long? = null,
): LiveDashboard {
    val ui = liveUi(state, voice)
    val kind = state.phaseKind
    val interval = state.mode == SessionMode.INTERVAL_4X4
    val segments =
        when {
            interval -> planSegments(protocol)
            easyTargetMs != null -> listOf(TimelineSegment(PhaseKind.EASY, easyTargetMs.toFloat()))
            else -> emptyList()
        }
    val (bigTime, bigLabel) =
        if (interval && kind != null) {
            formatDuration(state.phaseRemainingMs ?: 0) to "left"
        } else {
            formatDuration(state.elapsedActiveMs) to (easyTargetMs?.let { "of ${formatDuration(it)}" } ?: "elapsed")
        }
    val position =
        when {
            segments.isEmpty() || kind == null -> 0f
            !interval -> state.elapsedActiveMs.toFloat() / (easyTargetMs ?: 1L)
            else -> {
                val before = segments.take(phaseIndex(state, protocol)).sumOf { it.weight.toDouble() }
                val inPhase = phaseDurationMs(kind, protocol) - (state.phaseRemainingMs ?: 0)
                ((before + inPhase) / segments.sumOf { it.weight.toDouble() }).toFloat()
            }
        }
    val sensorLost = ui.zone == ZoneIndicator.NO_SIGNAL
    val warnings =
        buildList {
            if (device.simulatorProfile != null) {
                add(LiveWarning("Simulator", severe = false))
            } else {
                // The big "Reconnecting…" already says it when the signal is gone.
                if (device.connection != ConnectionState.CONNECTED && !sensorLost) add(LiveWarning("Reconnecting", severe = true))
                if (device.skinContact == false) add(LiveWarning("No skin contact", severe = true))
                device.batteryPercent?.takeIf { it < AppConstants.LOW_BATTERY_PCT }?.let { add(LiveWarning("Strap battery $it %", severe = false)) }
            }
            if (ui.voiceOff) add(LiveWarning("Voice off", severe = true))
        }
    return LiveDashboard(
        ui = ui,
        mode = state.mode,
        hr = state.smoothedHr,
        targetLow = state.targetLow,
        targetHigh = state.targetHigh,
        isWork = state.phaseKind == PhaseKind.WORK,
        intervalNumber = state.intervalNumber,
        intervalCount = state.intervalCount,
        bigTime = bigTime,
        bigTimeLabel = bigLabel,
        recentHr = recentHr.takeLast(AppConstants.LIVE_WINDOW_S),
        timeline = segments,
        timelinePosition = position.coerceIn(0f, 1f),
        driftText = driftPct?.let { String.format(Locale.ROOT, "%+.1f %%", it) },
        lastSeenText =
            if (state.sensorLost && lastValidHr != null) {
                "Last $lastValidHr bpm · ${(msSinceLastValid ?: 0) / MS_PER_SECOND} s ago"
            } else {
                null
            },
        warnings = warnings,
    )
}

// ---- Session detail --------------------------------------------------------------------

data class HeroStat(val label: String, val value: String, val unit: String? = null)

data class IntervalRow(
    val number: Int,
    val avg: String,
    val peak: String,
    val pctInZone: String,
    val hrr60: String,
)

data class SessionDetail(
    val title: String,
    val subtitle: String,
    val mode: SessionMode,
    /** False when the app died mid-session (data up to the last write). */
    val complete: Boolean,
    /** At most three numbers under the hero. */
    val stats: List<HeroStat>,
    /** Share of the target time in zone (4x4: work time in work zone), 0..100. */
    val inZonePct: Int,
    val inZoneText: String,
    /** HR averaged per [stepSec]; null = no signal in that bucket. */
    val hr: List<Int?>,
    val stepSec: Float,
    val bands: List<ChartBand>,
    val yMin: Int,
    val yMax: Int,
    val intervals: List<IntervalRow>,
    val intervalZonePct: List<Float>,
    val newHrMax: String?,
    /** "1 signal gap (20 s)" or null. */
    val gapsText: String?,
    /** Not on the server yet / rejected; null when synced or sync is off. */
    val sync: SyncBadge? = null,
)

/** HR per [stepSec]-second bucket (median, gaps null); see [bucketMedians]. */
fun bucketHr(
    result: SessionResult,
    stepSec: Int,
): List<Int?> = bucketMedians(result.samples, result.summary.durationMs, stepSec)

private val DATE_TIME = DateTimeFormatter.ofPattern("EEE d MMM · HH:mm", Locale.ENGLISH)
private const val DASH = "–"

fun sessionDetail(
    result: SessionResult,
    startedAtEpochMs: Long,
    zone: ZoneId,
    simulated: Boolean = false,
    complete: Boolean = true,
    sync: SyncBadge? = null,
): SessionDetail {
    val s = result.summary
    val interval = result.setup.mode == SessionMode.INTERVAL_4X4
    val workMs = result.phases.filter { it.kind == PhaseKind.WORK }.sumOf { it.endMs - it.startMs }
    val targetBaseMs = if (interval) workMs else s.durationMs
    val pct = if (targetBaseMs > 0) (s.timeInTargetMs * 100.0 / targetBaseMs).roundToInt().coerceIn(0, 100) else 0
    val stepSec = AppConstants.DETAIL_CHART_STEP_S
    val hr = bucketHr(result, stepSec)
    val valid = hr.filterNotNull()
    val bands =
        result.phases.map { p ->
            ChartBand(
                startSec = p.startMs / 1000f,
                endSec = p.endMs / 1000f,
                kind = p.kind,
                targetLow = p.targetLow,
                targetHigh = p.targetHigh,
                label =
                    when (p.kind) {
                        PhaseKind.WORK -> "${p.intervalNumber}"
                        PhaseKind.WARMUP -> "Warmup"
                        PhaseKind.COOLDOWN -> "Cool"
                        else -> null
                    },
            )
        }
    val lowest = minOf(valid.minOrNull() ?: 100, bands.minOfOrNull { it.targetLow ?: 999 } ?: 999)
    val highest = maxOf(valid.maxOrNull() ?: 180, bands.maxOfOrNull { it.targetHigh ?: 0 } ?: 0)
    val gapMs = result.gaps.sumOf { it.endMs - it.startMs }
    val started = Instant.ofEpochMilli(startedAtEpochMs).atZone(zone)
    val third =
        if (interval) {
            val hrrs = result.intervals.mapNotNull { it.hrr60 }
            HeroStat("HRR60", if (hrrs.isEmpty()) DASH else hrrs.average().roundToInt().toString(), "bpm")
        } else {
            HeroStat("Drift", s.driftPct?.let { String.format(Locale.ROOT, "%+.1f", it) } ?: DASH, "%")
        }
    return SessionDetail(
        title = if (interval) "4×4 intervals" else "Easy run",
        subtitle = DATE_TIME.format(started) + if (simulated) " · Simulator" else "",
        mode = result.setup.mode,
        complete = complete,
        sync = sync,
        stats =
            listOf(
                HeroStat("Duration", formatDuration(s.durationMs)),
                HeroStat("Avg HR", s.avgHr?.toString() ?: DASH, "bpm"),
                third,
            ),
        inZonePct = pct,
        inZoneText = "${formatDuration(s.timeInTargetMs)} in zone",
        hr = hr,
        stepSec = stepSec.toFloat(),
        bands = bands,
        yMin = ((lowest - 10) / 10) * 10,
        yMax = ((highest + 15) / 10) * 10,
        intervals =
            result.intervals.map {
                IntervalRow(
                    number = it.number,
                    avg = it.avgHr?.toString() ?: DASH,
                    peak = it.peakHr?.toString() ?: DASH,
                    pctInZone = "${it.pctInZone.roundToInt()} %",
                    hrr60 = it.hrr60?.toString() ?: DASH,
                )
            },
        intervalZonePct = result.intervals.map { it.pctInZone.toFloat() },
        newHrMax = s.newHrMax?.let { "New max heart rate: $it bpm" },
        gapsText =
            if (result.gaps.isEmpty()) {
                null
            } else {
                "${result.gaps.size} signal gap${if (result.gaps.size > 1) "s" else ""} (${gapMs / MS_PER_SECOND} s), shown as breaks"
            },
    )
}

// ---- Progress --------------------------------------------------------------------------

data class CompareStat(
    val label: String,
    val value: String,
    val delta: String,
    val trend: Trend,
)

enum class TrendKind(val tab: String) { RECOVERY("Recovery"), IN_ZONE("In zone"), DRIFT("Drift") }

data class TrendCard(
    val kind: TrendKind,
    val title: String,
    val headline: String,
    val unit: String,
    val caption: String,
    val delta: String?,
    val trend: Trend,
    val points: List<TrendPoint>,
    val previousAvg: Float?,
    val currentAvg: Float?,
)

data class SessionListRow(
    val id: String,
    val title: String,
    val date: String,
    val duration: String,
    val avgHr: String,
    val inZone: String,
    val mode: SessionMode,
    val sparkline: List<Int?>,
    val complete: Boolean,
    /** Not on the server yet / rejected; null when synced or sync is off. */
    val sync: SyncBadge? = null,
)

data class ProgressOverview(
    val periodText: String,
    /** Two tiles: sessions and time in zone, this window vs the one before. */
    val compare: List<CompareStat>,
    val calendar: List<List<CalendarDay>>,
    val consistencyText: String,
    /** Recovery, In zone, Drift: one chart card with a selector. */
    val trends: List<TrendCard>,
    /** The most recent sessions (Progress shows these, "Show all" the rest). */
    val recent: List<SessionListRow>,
    val totalSessions: Int,
    val totalDays: Int,
    val splitDay: Int,
)

private fun pctDelta(
    cur: Double,
    prev: Double,
): String =
    if (prev <= 0.0) {
        "new"
    } else {
        "${abs(((cur - prev) / prev * 100).roundToInt())} %"
    }

private fun trendOf(
    cur: Double,
    prev: Double,
    higherIsBetter: Boolean,
    flatWithin: Double,
): Trend =
    when {
        abs(cur - prev) <= flatWithin -> Trend.FLAT
        cur > prev -> if (higherIsBetter) Trend.UP_GOOD else Trend.UP_BAD
        else -> if (higherIsBetter) Trend.DOWN_BAD else Trend.DOWN_GOOD
    }

private fun hoursMinutes(ms: Long): String {
    val totalMin = ms / MS_PER_MINUTE
    return if (totalMin >= 60) "${totalMin / 60}h ${totalMin % 60}m" else "${totalMin}m"
}

/** The rows of the session lists, newest first. [syncConfigured] false = no sync marks at all. */
fun sessionRows(
    sessions: List<SessionDigest>,
    zone: ZoneId,
    syncConfigured: Boolean = false,
): List<SessionListRow> =
    sessions.sortedByDescending { it.startedAtEpochMs }.map { d ->
        val t = Instant.ofEpochMilli(d.startedAtEpochMs).atZone(zone)
        val pct = if (d.mode == SessionMode.INTERVAL_4X4) d.workPctInZone else d.timeInTargetMs * 100.0 / d.durationMs.coerceAtLeast(1)
        SessionListRow(
            id = d.id,
            title = if (d.mode == SessionMode.INTERVAL_4X4) "4×4 intervals" else "Easy run",
            date = DATE_TIME.format(t),
            duration = formatDuration(d.durationMs),
            avgHr = d.avgHr?.toString() ?: DASH,
            inZone = if (d.complete && pct != null) "${pct.roundToInt()} %" else DASH,
            mode = d.mode,
            sparkline = d.sparkline,
            complete = d.complete,
            sync = syncBadge(d.syncState, syncConfigured),
        )
    }

/**
 * Progress page: the last [windowDays] days against the [windowDays] before (two tiles, a
 * calendar and three trends over the whole period), plus the most recent sessions.
 *
 * @param window every finished session of the last 2 x [windowDays] days.
 * @param recent the most recent sessions (any age) for the list.
 * @param totalSessions how many finished sessions exist in all ("Show all N").
 */
fun progressOverview(
    window: List<SessionDigest>,
    recent: List<SessionDigest>,
    totalSessions: Int,
    today: LocalDate,
    zone: ZoneId,
    windowDays: Int = AppConstants.PROGRESS_WINDOW_DAYS,
    syncConfigured: Boolean = false,
): ProgressOverview {
    val totalDays = windowDays * 2
    val firstDay = today.minusDays(totalDays - 1L)
    fun dateOf(d: SessionDigest) = Instant.ofEpochMilli(d.startedAtEpochMs).atZone(zone).toLocalDate()
    fun dayIndex(d: SessionDigest) = ChronoUnit.DAYS.between(firstDay, dateOf(d)).toInt()
    val inPeriod = window.filter { dayIndex(it) in 0 until totalDays }.sortedBy { it.startedAtEpochMs }
    val (prev, cur) = inPeriod.partition { dayIndex(it) < windowDays }

    fun compare(
        label: String,
        f: (List<SessionDigest>) -> Double,
        fmt: (Double) -> String,
    ): CompareStat {
        val c = f(cur)
        val p = f(prev)
        return CompareStat(label, fmt(c), pctDelta(c, p), trendOf(c, p, higherIsBetter = true, flatWithin = p * 0.03))
    }
    val compare =
        listOf(
            compare("Sessions", { it.size.toDouble() }, { it.toInt().toString() }),
            compare("Time in zone", { l -> l.sumOf { it.timeInTargetMs }.toDouble() }, { hoursMinutes(it.toLong()) }),
        )

    // Calendar: the last totalDays / 7 weeks, Monday..Sunday, oldest week first.
    val calStart = today.with(DayOfWeek.MONDAY).minusWeeks(totalDays / 7 - 1L)
    val byDay = inPeriod.groupBy { dateOf(it) }
    val weeks = mutableListOf<List<CalendarDay>>()
    var weekStart = calStart
    while (!weekStart.isAfter(today)) {
        weeks +=
            (0 until 7).map { i ->
                val d = weekStart.plusDays(i.toLong())
                val list = byDay[d].orEmpty()
                val minutes = list.sumOf { it.durationMs } / MS_PER_MINUTE
                CalendarDay(
                    level = when { list.isEmpty() -> 0; minutes < 35 -> 1; minutes < 55 -> 2; else -> 3 },
                    interval = list.any { it.mode == SessionMode.INTERVAL_4X4 },
                    isToday = d == today,
                    isFuture = d.isAfter(today),
                )
            }
        weekStart = weekStart.plusWeeks(1)
    }
    val activeDays = inPeriod.map { dateOf(it) }.toSet()
    // Consecutive weeks (ending this week) with at least two sessions; this week counts with one.
    var streak = 0
    var ws = today.with(DayOfWeek.MONDAY)
    while (!ws.isBefore(firstDay.minusWeeks(1))) {
        val n = inPeriod.count { val d = dateOf(it); !d.isBefore(ws) && d.isBefore(ws.plusWeeks(1)) }
        if (n >= 2 || (ws == today.with(DayOfWeek.MONDAY) && n >= 1)) streak++ else break
        ws = ws.minusWeeks(1)
    }
    val consistency = "${activeDays.size} training days\nin ${totalDays / 7} weeks\n\n$streak-week streak"

    val fours = inPeriod.filter { it.mode == SessionMode.INTERVAL_4X4 && it.complete }
    val easies = inPeriod.filter { it.mode == SessionMode.EASY && it.complete }
    fun avg(
        l: List<SessionDigest>,
        f: (SessionDigest) -> Double?,
    ) = l.mapNotNull(f).takeIf { it.isNotEmpty() }?.average()

    fun trendCard(
        kind: TrendKind,
        title: String,
        unit: String,
        list: List<SessionDigest>,
        higherIsBetter: Boolean,
        caption: String,
        f: (SessionDigest) -> Double?,
        fmt: (Double) -> String,
    ): TrendCard {
        val c = avg(list.filter { dayIndex(it) >= windowDays }, f)
        val p = avg(list.filter { dayIndex(it) < windowDays }, f)
        return TrendCard(
            kind = kind,
            title = title,
            headline = c?.let(fmt) ?: DASH,
            unit = unit,
            caption = caption,
            delta = if (c != null && p != null) fmt(abs(c - p)) + " $unit" else null,
            trend = if (c != null && p != null) trendOf(c, p, higherIsBetter, flatWithin = abs(p) * 0.02) else Trend.FLAT,
            points = list.mapNotNull { d -> f(d)?.let { TrendPoint(dayIndex(d) + 0.5f, it.toFloat()) } },
            previousAvg = p?.toFloat(),
            currentAvg = c?.toFloat(),
        )
    }
    val trends =
        listOf(
            trendCard(TrendKind.RECOVERY, "Recovery · HRR60", "bpm", fours, true, "HR drop 60 s after each work interval. Higher = fitter.", { it.avgHrr60 }, { it.roundToInt().toString() }),
            trendCard(TrendKind.IN_ZONE, "Time in zone · 4×4", "%", fours, true, "Share of work time in the work zone.", { it.workPctInZone }, { it.roundToInt().toString() }),
            trendCard(TrendKind.DRIFT, "Cardiac drift · easy", "%", easies, false, "HR rise from first to second half. Lower = fitter base.", { it.driftPct }, { String.format(Locale.ROOT, "%.1f", it) }),
        )

    return ProgressOverview(
        periodText = "Last ${windowDays / 7} weeks vs the ${windowDays / 7} before",
        compare = compare,
        calendar = weeks,
        consistencyText = consistency,
        trends = trends,
        recent = sessionRows(recent, zone, syncConfigured),
        totalSessions = totalSessions,
        totalDays = totalDays,
        splitDay = windowDays,
    )
}
