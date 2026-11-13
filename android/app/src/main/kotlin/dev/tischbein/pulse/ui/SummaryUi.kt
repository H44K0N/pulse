package dev.tischbein.pulse.ui

import dev.tischbein.pulse.coaching.Cues
import dev.tischbein.pulse.coaching.SessionMode
import dev.tischbein.pulse.coaching.SessionResult
import dev.tischbein.pulse.session.formatDuration
import java.util.Locale
import kotlin.math.roundToInt

/*
 * What the summary screen shows after a session, as plain values (unit-tested without
 * Compose). Charts and history come in Milestone 4; this is the plain numbers view.
 */

data class IntervalRow(
    val number: Int,
    val avg: String,
    val peak: String,
    val pctInZone: String,
    val hrr60: String,
)

data class SummaryUi(
    val title: String,
    val duration: String,
    val avgHr: String,
    val maxHr: String,
    /** "18:30 (72 %)": time in the target zone and its share of the session. */
    val timeInTarget: String,
    /** 4x4 only (empty for easy). */
    val intervals: List<IntervalRow>,
    /** Easy only: "+4.2 %", or null when not enough data / 4x4. */
    val drift: String?,
    /** "New max heart rate recorded: 194." when this session raised HRmax. */
    val newHrMax: String?,
)

fun summaryUi(result: SessionResult): SummaryUi {
    val s = result.summary
    val pct = if (s.durationMs > 0) (s.timeInTargetMs * PERCENT / s.durationMs).roundToInt() else 0
    return SummaryUi(
        title = if (result.setup.mode == SessionMode.INTERVAL_4X4) "4x4 complete" else "Easy run complete",
        duration = formatDuration(s.durationMs),
        avgHr = s.avgHr?.toString() ?: DASH,
        maxHr = s.maxHr?.toString() ?: DASH,
        timeInTarget = "${formatDuration(s.timeInTargetMs)} ($pct %)",
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
        drift = s.driftPct?.let { String.format(Locale.ROOT, "%+.1f %%", it) },
        newHrMax = s.newHrMax?.let { Cues.hrMaxLearned(it) },
    )
}

private const val DASH = "--"
private const val PERCENT = 100.0
