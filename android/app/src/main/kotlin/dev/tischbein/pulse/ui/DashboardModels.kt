package dev.tischbein.pulse.ui

import dev.tischbein.pulse.coaching.CoachingState
import dev.tischbein.pulse.coaching.FourByFourConfig
import dev.tischbein.pulse.coaching.MS_PER_MINUTE
import dev.tischbein.pulse.coaching.MS_PER_SECOND
import dev.tischbein.pulse.coaching.PhaseKind
import dev.tischbein.pulse.coaching.SessionMode
import dev.tischbein.pulse.coaching.SessionResult
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
 * Plain values for the redesigned screens (live dashboard, session detail, progress). No
 * Compose here, so the mapping is unit-testable; the *Content composables only lay them out.
 */

// ---- Live ------------------------------------------------------------------------------

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
    /** Progress of the current phase (4x4) or of the target duration (easy), 0..1. */
    val ringProgress: Float,
    /** "2:14" time left in the phase (4x4) or elapsed (easy). */
    val bigTime: String,
    val bigTimeLabel: String,
    /** Smoothed HR of the last [LIVE_WINDOW_S] seconds, 1 Hz, oldest first; null = no signal. */
    val recentHr: List<Int?>,
    /** Planned phases (4x4), empty in easy mode. */
    val timeline: List<TimelineSegment>,
    val timelinePosition: Float,
    /** Easy: drift so far, "+2.4 %"; null when not enough data yet. */
    val driftText: String?,
    /** Sensor lost: "Last 158 bpm · 14 s ago". */
    val lastSeenText: String?,
    val battery: Int?,
    val contact: Boolean?,
    val connection: ConnectionState,
    val simulator: Boolean,
)

const val LIVE_WINDOW_S = 120

fun planSegments(p: FourByFourConfig): List<TimelineSegment> =
    buildList {
        add(TimelineSegment(PhaseKind.WARMUP, p.warmupMs.toFloat()))
        repeat(p.intervalCount) {
            add(TimelineSegment(PhaseKind.WORK, p.workMs.toFloat()))
            add(TimelineSegment(PhaseKind.RECOVERY, p.recoveryMs.toFloat()))
        }
        add(TimelineSegment(PhaseKind.COOLDOWN, p.cooldownMs.toFloat()))
    }

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
 * percent. Null below 10 minutes of data (too noisy to show). The engine computes the
 * official end-of-session value; this is only the live hint.
 */
fun liveDriftPct(hrBySecond: List<Int?>): Double? {
    val valid = hrBySecond.filterNotNull()
    if (valid.size < 10 * 60) return null
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
    val ui = liveUi(state, voice, easyTargetMs)
    val segments = if (state.mode == SessionMode.INTERVAL_4X4) planSegments(protocol) else emptyList()
    val kind = state.phaseKind
    val (ring, bigTime, bigLabel) =
        if (state.mode == SessionMode.INTERVAL_4X4 && kind != null) {
            val dur = phaseDurationMs(kind, protocol).coerceAtLeast(1)
            val left = state.phaseRemainingMs ?: 0
            val label =
                when (kind) {
                    PhaseKind.WORK -> "left in interval"
                    PhaseKind.RECOVERY -> "left in recovery"
                    PhaseKind.WARMUP -> "left in warmup"
                    else -> "left in cooldown"
                }
            Triple(1f - left.toFloat() / dur, formatDuration(left), label)
        } else {
            val target = easyTargetMs
            Triple(
                if (target != null) state.elapsedActiveMs.toFloat() / target else 0f,
                formatDuration(state.elapsedActiveMs),
                if (target != null) "of ${formatDuration(target)}" else "elapsed",
            )
        }
    val position =
        if (segments.isEmpty() || kind == null) {
            0f
        } else {
            val idx = phaseIndex(state, protocol)
            val before = segments.take(idx).sumOf { it.weight.toDouble() }
            val inPhase = phaseDurationMs(kind, protocol) - (state.phaseRemainingMs ?: 0)
            ((before + inPhase) / segments.sumOf { it.weight.toDouble() }).toFloat()
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
        ringProgress = ring.coerceIn(0f, 1f),
        bigTime = bigTime,
        bigTimeLabel = bigLabel,
        recentHr = recentHr.takeLast(LIVE_WINDOW_S),
        timeline = segments,
        timelinePosition = position,
        driftText = driftPct?.let { String.format(Locale.ROOT, "%+.1f %%", it) },
        lastSeenText =
            if (state.sensorLost && lastValidHr != null) {
                "Last $lastValidHr bpm · ${(msSinceLastValid ?: 0) / MS_PER_SECOND} s ago"
            } else {
                null
            },
        battery = device.batteryPercent,
        contact = device.skinContact,
        connection = device.connection,
        simulator = device.simulatorProfile != null,
    )
}

// ---- Session detail --------------------------------------------------------------------

data class HeroStat(val label: String, val value: String, val unit: String? = null)

data class SessionDetail(
    val title: String,
    val subtitle: String,
    val mode: SessionMode,
    val stats: List<HeroStat>,
    /** Two small numbers next to the hero (4x4: intervals done, mean HRR60; easy: drift, zone). */
    val side: List<HeroStat>,
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
    val drift: String?,
    val newHrMax: String?,
    /** "1 signal gap (20 s)" or null. */
    val gapsText: String?,
    val hrMaxUsed: Int,
)

/**
 * HR per [stepSec]-second bucket for charts: the median of the bucket's valid readings, so a
 * single bad beat (the strap sometimes reports one wild value) does not draw a spike; the
 * engine smooths the same way before judging zones. Buckets without a reading are null (gaps).
 */
fun bucketHr(
    result: SessionResult,
    stepSec: Int,
): List<Int?> {
    val stepMs = stepSec * MS_PER_SECOND
    val n = ((result.summary.durationMs + stepMs - 1) / stepMs).toInt().coerceAtLeast(1)
    val buckets = List(n) { mutableListOf<Int>() }
    for (s in result.samples) {
        val hr = s.hr ?: continue
        val i = ((s.tMs - 1).coerceAtLeast(0) / stepMs).toInt()
        if (i in 0 until n) buckets[i] += hr
    }
    return buckets.map { b -> if (b.isEmpty()) null else b.sorted()[b.size / 2] }
}

private val DATE_TIME = DateTimeFormatter.ofPattern("EEE d MMM · HH:mm", Locale.ENGLISH)
private val SHORT_DATE = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)

fun sessionDetail(
    result: SessionResult,
    startedAtEpochMs: Long,
    zone: ZoneId,
    sourceLabel: String = "Polar H10",
): SessionDetail {
    val s = result.summary
    val interval = result.setup.mode == SessionMode.INTERVAL_4X4
    val workMs = result.phases.filter { it.kind == PhaseKind.WORK }.sumOf { it.endMs - it.startMs }
    val targetBaseMs = if (interval) workMs else s.durationMs
    val pct = if (targetBaseMs > 0) (s.timeInTargetMs * 100.0 / targetBaseMs).roundToInt().coerceIn(0, 100) else 0
    val stepSec = 5
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
    return SessionDetail(
        title = if (interval) "4×4 intervals" else "Easy run",
        subtitle = "${DATE_TIME.format(started)} · $sourceLabel",
        mode = result.setup.mode,
        stats =
            listOf(
                HeroStat("Duration", formatDuration(s.durationMs)),
                HeroStat("Avg HR", s.avgHr?.toString() ?: "–", "bpm"),
                HeroStat("Max HR", s.maxHr?.toString() ?: "–", "bpm"),
            ),
        side =
            if (interval) {
                val hrrs = result.intervals.mapNotNull { it.hrr60 }
                listOf(
                    HeroStat("Intervals", "${result.intervals.size}/${result.config.fourByFour.intervalCount}"),
                    HeroStat("Avg HRR60", if (hrrs.isEmpty()) "–" else hrrs.average().roundToInt().toString(), "bpm"),
                )
            } else {
                val easy = result.phases.firstOrNull()
                listOf(
                    HeroStat("Drift", s.driftPct?.let { String.format(Locale.ROOT, "%+.1f", it) } ?: "–", "%"),
                    HeroStat("Zone", easy?.let { "${it.targetLow}–${it.targetHigh}" } ?: "–", "bpm"),
                )
            },
        inZonePct = pct,
        inZoneText = "${formatDuration(s.timeInTargetMs)} in zone",
        hr = hr,
        stepSec = stepSec.toFloat(),
        bands = bands,
        yMin = ((lowest - 10) / 10) * 10,
        yMax = ((highest + 15) / 10) * 10,
        intervals = summaryUi(result).intervals,
        intervalZonePct = result.intervals.map { it.pctInZone.toFloat() },
        drift = s.driftPct?.let { String.format(Locale.ROOT, "%+.1f %%", it) },
        newHrMax = s.newHrMax?.let { "New max heart rate: $it bpm" },
        gapsText =
            if (result.gaps.isEmpty()) {
                null
            } else {
                "${result.gaps.size} signal gap${if (result.gaps.size > 1) "s" else ""} (${gapMs / MS_PER_SECOND} s), shown as breaks"
            },
        hrMaxUsed = result.setup.hrMax,
    )
}

// ---- Progress --------------------------------------------------------------------------

/**
 * What the progress page needs to know about one finished session. In the app this comes
 * from Room (one row per session, computed when the session is saved).
 */
data class SessionDigest(
    val id: String,
    val startedAtEpochMs: Long,
    val mode: SessionMode,
    val durationMs: Long,
    val avgHr: Int?,
    val maxHr: Int?,
    val timeInTargetMs: Long,
    /** 4x4: mean HRR60 over the intervals where it was measured. */
    val avgHrr60: Double?,
    /** 4x4: mean of the work intervals' average HR. */
    val avgWorkHr: Double?,
    /** 4x4: mean % in zone over the work intervals. */
    val workPctInZone: Double?,
    val driftPct: Double?,
    /** Downsampled HR for the list row (about 48 points). */
    val sparkline: List<Int?>,
    val hrMaxUsed: Int,
) {
    companion object {
        fun of(
            id: String,
            result: SessionResult,
            startedAtEpochMs: Long,
        ): SessionDigest {
            val ivs = result.intervals
            val step = ((result.summary.durationMs / MS_PER_SECOND) / SPARK_POINTS).toInt().coerceAtLeast(1)
            return SessionDigest(
                id = id,
                startedAtEpochMs = startedAtEpochMs,
                mode = result.setup.mode,
                durationMs = result.summary.durationMs,
                avgHr = result.summary.avgHr,
                maxHr = result.summary.maxHr,
                timeInTargetMs = result.summary.timeInTargetMs,
                avgHrr60 = ivs.mapNotNull { it.hrr60 }.takeIf { it.isNotEmpty() }?.average(),
                avgWorkHr = ivs.mapNotNull { it.avgHr }.takeIf { it.isNotEmpty() }?.average(),
                workPctInZone = ivs.takeIf { it.isNotEmpty() }?.map { it.pctInZone }?.average(),
                driftPct = result.summary.driftPct,
                sparkline = bucketHr(result, step),
                hrMaxUsed = result.setup.hrMax,
            )
        }

        private const val SPARK_POINTS = 48
    }
}

data class CompareStat(
    val label: String,
    val value: String,
    val unit: String?,
    val delta: String,
    val trend: Trend,
    val previous: String,
)

data class TrendCard(
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

data class PersonalBest(val label: String, val value: String, val unit: String?, val note: String)

data class SessionListRow(
    val id: String,
    val title: String,
    val date: String,
    val duration: String,
    val avgHr: String,
    val inZone: String,
    val mode: SessionMode,
    val sparkline: List<Int?>,
)

data class ProgressOverview(
    val periodText: String,
    val compare: List<CompareStat>,
    val calendar: List<List<CalendarDay>>,
    val consistencyText: String,
    val activeDays: Int,
    val streakWeeks: Int,
    val hrr: TrendCard,
    val zone: TrendCard,
    /** (day index, % in zone) per 4x4 session, for the bar chart. */
    val zoneBars: List<Pair<Float, Float>>,
    val drift: TrendCard,
    val bests: List<PersonalBest>,
    val sessions: List<SessionListRow>,
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

/**
 * Progress page: the last [windowDays] days against the [windowDays] before, a calendar of
 * the whole period, trend charts, personal bests and the session list (newest first).
 */
fun progressOverview(
    sessions: List<SessionDigest>,
    today: LocalDate,
    zone: ZoneId,
    hrMax: Int,
    hrMaxLearned: Boolean,
    windowDays: Int = 21,
): ProgressOverview {
    val totalDays = windowDays * 2
    val firstDay = today.minusDays(totalDays - 1L)
    fun dayIndex(d: SessionDigest) = ChronoUnit.DAYS.between(firstDay, Instant.ofEpochMilli(d.startedAtEpochMs).atZone(zone).toLocalDate()).toInt()
    val inPeriod = sessions.filter { dayIndex(it) in 0 until totalDays }.sortedBy { it.startedAtEpochMs }
    val (prev, cur) = inPeriod.partition { dayIndex(it) < windowDays }

    fun compare(
        label: String,
        unit: String?,
        f: (List<SessionDigest>) -> Double,
        fmt: (Double) -> String,
    ): CompareStat {
        val c = f(cur)
        val p = f(prev)
        return CompareStat(label, fmt(c), unit, pctDelta(c, p), trendOf(c, p, higherIsBetter = true, flatWithin = p * 0.03), "prev ${fmt(p)}")
    }
    val compare =
        listOf(
            compare("Sessions", null, { it.size.toDouble() }, { it.toInt().toString() }),
            compare("Training", null, { l -> l.sumOf { it.durationMs }.toDouble() }, { hoursMinutes(it.toLong()) }),
            compare("In zone", null, { l -> l.sumOf { it.timeInTargetMs }.toDouble() }, { hoursMinutes(it.toLong()) }),
        )

    // Calendar: the last totalDays / 7 weeks, Monday..Sunday, oldest week first.
    val calStart = today.with(DayOfWeek.MONDAY).minusWeeks(totalDays / 7 - 1L)
    val byDay = inPeriod.groupBy { Instant.ofEpochMilli(it.startedAtEpochMs).atZone(zone).toLocalDate() }
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
    val activeDays = inPeriod.map { Instant.ofEpochMilli(it.startedAtEpochMs).atZone(zone).toLocalDate() }.toSet()
    // Consecutive weeks (ending this week) with at least two sessions.
    var streak = 0
    var ws = today.with(DayOfWeek.MONDAY)
    while (true) {
        val n = inPeriod.count { val d = Instant.ofEpochMilli(it.startedAtEpochMs).atZone(zone).toLocalDate(); !d.isBefore(ws) && d.isBefore(ws.plusWeeks(1)) }
        if (n >= 2 || (ws == today.with(DayOfWeek.MONDAY) && n >= 1)) streak++ else break
        ws = ws.minusWeeks(1)
        if (ws.isBefore(firstDay.minusWeeks(1))) break
    }
    val consistency = "${activeDays.size} training days in ${totalDays / 7} weeks · $streak-week streak"

    val fours = inPeriod.filter { it.mode == SessionMode.INTERVAL_4X4 }
    val easies = inPeriod.filter { it.mode == SessionMode.EASY }
    fun avg(
        l: List<SessionDigest>,
        f: (SessionDigest) -> Double?,
    ) = l.mapNotNull(f).takeIf { it.isNotEmpty() }?.average()

    fun trendCard(
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
        val delta = if (c != null && p != null) fmt(abs(c - p)) + " $unit" else null
        return TrendCard(
            title = title,
            headline = c?.let(fmt) ?: "–",
            unit = unit,
            caption = caption,
            delta = delta,
            trend = if (c != null && p != null) trendOf(c, p, higherIsBetter, flatWithin = abs(p) * 0.02) else Trend.FLAT,
            points = list.mapNotNull { d -> f(d)?.let { TrendPoint(dayIndex(d) + 0.5f, it.toFloat()) } },
            previousAvg = p?.toFloat(),
            currentAvg = c?.toFloat(),
        )
    }
    val hrr = trendCard("Recovery · HRR60", "bpm", fours, true, "HR drop 60 s after each work interval. Higher = fitter.", { it.avgHrr60 }, { it.roundToInt().toString() })
    val zoneCard = trendCard("Time in zone · 4×4", "%", fours, true, "Share of work time at 85–95 % HRmax.", { it.workPctInZone }, { it.roundToInt().toString() })
    val drift = trendCard("Cardiac drift · easy", "%", easies, false, "HR rise from first to second half. Lower = fitter base.", { it.driftPct }, { String.format(Locale.ROOT, "%.1f", it) })

    val bestHrr = sessions.filter { it.avgHrr60 != null }.maxByOrNull { it.avgHrr60!! }
    val bestZone = sessions.maxByOrNull { it.timeInTargetMs }
    val bests =
        listOfNotNull(
            bestHrr?.let { PersonalBest("Best HRR60", it.avgHrr60!!.roundToInt().toString(), "bpm", SHORT_DATE.format(Instant.ofEpochMilli(it.startedAtEpochMs).atZone(zone))) },
            bestZone?.let { PersonalBest("Most in zone", formatDuration(it.timeInTargetMs), null, SHORT_DATE.format(Instant.ofEpochMilli(it.startedAtEpochMs).atZone(zone))) },
            PersonalBest("HRmax", hrMax.toString(), "bpm", if (hrMaxLearned) "learned" else "estimated"),
        )

    val rows =
        sessions.sortedByDescending { it.startedAtEpochMs }.map { d ->
            val t = Instant.ofEpochMilli(d.startedAtEpochMs).atZone(zone)
            val base = if (d.mode == SessionMode.INTERVAL_4X4) d.workPctInZone else d.timeInTargetMs * 100.0 / d.durationMs.coerceAtLeast(1)
            SessionListRow(
                id = d.id,
                title = if (d.mode == SessionMode.INTERVAL_4X4) "4×4 intervals" else "Easy run",
                date = DATE_TIME.format(t),
                duration = formatDuration(d.durationMs),
                avgHr = d.avgHr?.toString() ?: "–",
                inZone = base?.let { "${it.roundToInt()} %" } ?: "–",
                mode = d.mode,
                sparkline = d.sparkline,
            )
        }
    return ProgressOverview(
        periodText = "Last 3 weeks vs the 3 before",
        compare = compare,
        calendar = weeks,
        consistencyText = consistency,
        activeDays = activeDays.size,
        streakWeeks = streak,
        hrr = hrr,
        zone = zoneCard,
        zoneBars = fours.mapNotNull { d -> d.workPctInZone?.let { dayIndex(d).toFloat() to it.toFloat() } },
        drift = drift,
        bests = bests,
        sessions = rows,
        totalDays = totalDays,
        splitDay = windowDays,
    )
}
