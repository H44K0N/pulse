package dev.tischbein.pulse.data

import dev.tischbein.pulse.AppConstants
import dev.tischbein.pulse.coaching.MS_PER_SECOND
import dev.tischbein.pulse.coaching.RecordedSample
import dev.tischbein.pulse.coaching.SessionMode
import dev.tischbein.pulse.coaching.SessionResult
import dev.tischbein.pulse.data.db.SessionEntity
import dev.tischbein.pulse.data.db.SessionStatus

/**
 * What the Progress page and the session list need about one session, without loading its
 * samples. Stored as columns of the `sessions` row (computed when the session is saved, and
 * back-filled by the 1 -> 2 migration for older sessions).
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
    /** Downsampled HR for the list row ([AppConstants.SPARKLINE_POINTS] points). */
    val sparkline: List<Int?>,
    val hrMaxUsed: Int,
    val complete: Boolean = true,
) {
    companion object {
        fun of(
            id: String,
            result: SessionResult,
            startedAtEpochMs: Long,
        ): SessionDigest {
            val ivs = result.intervals
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
                sparkline = sparklineOf(result.samples, result.summary.durationMs),
                hrMaxUsed = result.setup.hrMax,
            )
        }
    }
}

/**
 * HR per [stepSec]-second bucket for charts: the median of the bucket's valid readings, so a
 * single bad beat (the strap sometimes reports one wild value) does not draw a spike; the
 * engine smooths the same way before judging zones. Buckets without a reading are null (gaps).
 */
fun bucketMedians(
    samples: List<RecordedSample>,
    durationMs: Long,
    stepSec: Int,
): List<Int?> {
    val stepMs = stepSec * MS_PER_SECOND
    val n = ((durationMs + stepMs - 1) / stepMs).toInt().coerceAtLeast(1)
    val buckets = List(n) { mutableListOf<Int>() }
    for (s in samples) {
        val hr = s.hr ?: continue
        val i = ((s.tMs - 1).coerceAtLeast(0) / stepMs).toInt()
        if (i in 0 until n) buckets[i] += hr
    }
    return buckets.map { b -> if (b.isEmpty()) null else b.sorted()[b.size / 2] }
}

/** The session squeezed into about [AppConstants.SPARKLINE_POINTS] points. */
fun sparklineOf(
    samples: List<RecordedSample>,
    durationMs: Long,
): List<Int?> {
    val step = ((durationMs / MS_PER_SECOND) / AppConstants.SPARKLINE_POINTS).toInt().coerceAtLeast(1)
    return bucketMedians(samples, durationMs, step)
}

/** Stored as "120,121,,130" (empty = no signal), readable with plain sqlite3. */
fun encodeSparkline(values: List<Int?>): String = values.joinToString(",") { it?.toString() ?: "" }

fun decodeSparkline(stored: String?): List<Int?> = if (stored.isNullOrEmpty()) emptyList() else stored.split(',').map { it.toIntOrNull() }

fun sessionModeOf(wireName: String): SessionMode = SessionMode.entries.firstOrNull { it.wireName == wireName } ?: SessionMode.EASY

/** The digest of a finished (complete or incomplete) session row. */
fun SessionEntity.toDigest(): SessionDigest =
    SessionDigest(
        id = id,
        startedAtEpochMs = startedAtEpochMs,
        mode = sessionModeOf(mode),
        durationMs = durationMs ?: 0L,
        avgHr = avgHr,
        maxHr = maxHr,
        timeInTargetMs = timeInTargetMs ?: 0L,
        avgHrr60 = avgHrr60,
        avgWorkHr = avgWorkHr,
        workPctInZone = workPctInZone,
        driftPct = driftPct,
        sparkline = decodeSparkline(sparkline),
        hrMaxUsed = hrMaxUsed,
        complete = status == SessionStatus.COMPLETE,
    )
