package dev.tischbein.pulse.coaching

import dev.tischbein.pulse.sensor.HeartRateSample
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt

/**
 * The coach. Pure Kotlin: no Android, no threads, no system clock.
 *
 * HOW IT IS DRIVEN
 * - Call [start] once, then [onSample] for every sensor reading and [onTick] regularly
 *   (every second; [CoachingRunner] does this). Ticks matter: when the sensor goes quiet
 *   no samples arrive, but timers, gap detection and phase changes must keep running.
 * - [pause], [resume] and [end] come from the live screen buttons.
 * - Every method takes the current time from the caller. The engine never reads a clock,
 *   so tests can replay a whole workout instantly and get identical results every time.
 * - Not thread-safe: call it from one coroutine ([CoachingRunner] does).
 *
 * WHAT COMES OUT
 * - [state]: snapshot for the live screen (phase, time left, interval x of N, smoothed HR,
 *   zone below/in/above, paused...).
 * - [events]: every cue as it happens (type + text to speak). [eventLog] keeps them all.
 * - [result]: when the session finishes (protocol complete, or [end]) the full session,
 *   shaped like docs/session-format.md.
 *
 * TWO KINDS OF TIME
 * - "t" (tMs): wall-clock ms since start, pauses included. Used for everything stored
 *   (samples, events, phases, gaps) so it lines up with started_at/ended_at.
 * - "active" time: excludes pauses. Phase timers and correction timers use it, so a
 *   pause freezes the workout.
 */
class CoachingEngine(
    val setup: SessionSetup,
    val config: CoachingConfig = CoachingConfig(),
) {
    /** One planned phase of the session. Easy mode has a single phase with no fixed end. */
    private data class PlannedPhase(
        val kind: PhaseKind,
        val durationMs: Long?,
        val zone: ZonePercent,
        val intervalNumber: Int?,
    )

    /** Running totals for the phase in progress. */
    private class PhaseAccumulator {
        var hrSum = 0L
        var hrCount = 0
        var peakSmoothed: Int? = null
        var measuredMs = 0L
        var inZoneMs = 0L
    }

    /** A sample kept for drift: its active time and HR. */
    private data class ActiveReading(val activeMs: Long, val hr: Int)

    /** HRR60 waiting to be measured: HR at the end of the interval and when to measure. */
    private data class PendingHrr(val intervalIndex: Int, val hrAtEnd: Int?, val dueAtMs: Long)

    private val fourByFour = config.fourByFour
    private val easy = config.easy
    private val plan: List<PlannedPhase> = buildPlan()
    private val hrMaxUsed = setup.hrMax

    // ---- Outputs ----
    private val stateFlow = MutableStateFlow(initialState())
    val state: StateFlow<CoachingState> = stateFlow.asStateFlow()

    // Buffered so emitting never blocks the engine; cues are rare, 64 is plenty.
    private val eventFlow = MutableSharedFlow<CoachingEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<CoachingEvent> = eventFlow.asSharedFlow()

    private val eventList = mutableListOf<CoachingEvent>()
    val eventLog: List<CoachingEvent> get() = eventList.toList()

    var result: SessionResult? = null
        private set
    val isFinished: Boolean get() = result != null

    // ---- Clock bookkeeping ----
    private var started = false
    private var startMs = 0L
    private var lastNowMs = 0L
    private var activeMs = 0L
    private var paused = false
    private var pauseStartMs = 0L

    // ---- Current phase ----
    private var phaseIndex = 0
    private var phaseElapsedMs = 0L
    private var phaseStartMs = 0L
    private var phaseOpen = false
    private var phaseStats = PhaseAccumulator()

    // ---- Heart rate ----
    private val smoother = HeartRateSmoother(config.smoothingWindowMs, config.smoothingMinSamples)
    private val hrMaxLearner = HrMaxLearner(hrMaxUsed, config.hrMaxSustainMs)
    private var smoothedHr: Int? = null
    private var zone = ZoneState.UNKNOWN

    // ---- Sensor gap ----
    private var lastValidHrAtMs = 0L
    private var sensorLost = false
    private var gapStartMs = 0L

    // ---- Corrections ----
    private var outOfZoneSinceActiveMs: Long? = null
    private var outOfZoneDirection = ZoneState.UNKNOWN
    private var lastCorrectionActiveMs: Long? = null
    private var rampNudgeChecked = false
    private var lastCountdownSpoken = Int.MAX_VALUE

    // ---- Recording ----
    private val samples = mutableListOf<RecordedSample>()
    private val gaps = mutableListOf<Gap>()
    private val pauses = mutableListOf<Pause>()
    private val phaseRecords = mutableListOf<PhaseRecord>()
    private val intervals = mutableListOf<IntervalStats>()
    private val pendingHrr = mutableListOf<PendingHrr>()
    private val activeReadings = mutableListOf<ActiveReading>()
    private var sessionHrSum = 0L
    private var sessionHrCount = 0
    private var sessionMaxSmoothed: Int? = null
    private var timeInTargetMs = 0L
    private var easyHalfwayDone = false

    // ======================================================================================
    // Inputs
    // ======================================================================================

    fun start(nowMs: Long) {
        check(!started) { "start() called twice" }
        started = true
        startMs = nowMs
        lastNowMs = nowMs
        // Counting the gap from the start means "no sensor at all" is reported after 10 s too.
        lastValidHrAtMs = nowMs
        enterPhase(0, nowMs, carryOverMs = 0)
        publishState()
    }

    fun onSample(sample: HeartRateSample) {
        if (!started || isFinished) return
        val nowMs = maxOf(sample.timestampMs, lastNowMs)
        advanceTime(nowMs)
        if (isFinished) return

        val hr = sample.hr?.takeIf { it > config.invalidHrAtOrBelow }
        samples += RecordedSample(t(nowMs), hr, sample.rrMs)
        if (hr != null) {
            smoother.add(nowMs, hr)
            if (sensorLost) {
                sensorLost = false
                gaps += Gap(t(gapStartMs), t(nowMs))
                emit(nowMs, EventType.SENSOR_BACK, Cues.SENSOR_BACK)
            }
            lastValidHrAtMs = nowMs
            if (!paused) {
                phaseStats.hrSum += hr
                phaseStats.hrCount++
                sessionHrSum += hr
                sessionHrCount++
                activeReadings += ActiveReading(activeMs, hr)
            }
        }
        evaluate(nowMs)
    }

    fun onTick(nowMs: Long) {
        if (!started || isFinished) return
        advanceTime(nowMs)
        evaluate(nowMs)
    }

    /** Freeze phase and correction timers. Gap detection and HRR60 keep running (they are physiology/hardware, not workout). */
    fun pause(nowMs: Long) {
        if (!started || isFinished || paused) return
        onTick(nowMs)
        if (isFinished) return
        paused = true
        pauseStartMs = lastNowMs
        publishState()
    }

    fun resume(nowMs: Long) {
        if (!started || isFinished || !paused) return
        advanceTime(nowMs)
        pauses += Pause(t(pauseStartMs), t(lastNowMs))
        paused = false
        // Decision: after a pause the out-of-zone timer starts over. The user just started
        // moving again; they get a fresh correction window instead of an instant cue.
        outOfZoneSinceActiveMs = null
        evaluate(lastNowMs)
    }

    /** End the session now (End button, or the end of an easy run). Returns the result. Safe to call twice. */
    fun end(nowMs: Long): SessionResult {
        check(started) { "end() before start()" }
        if (!isFinished) {
            onTick(nowMs)
            if (!isFinished) finish(lastNowMs)
        }
        return result!!
    }

    // ======================================================================================
    // Time and phases
    // ======================================================================================

    /** Move the clock forward: accumulate time-in-zone and change phase when one is complete. */
    private fun advanceTime(nowMs: Long) {
        val dt = nowMs - lastNowMs
        if (dt <= 0) return
        lastNowMs = nowMs
        if (paused) return

        activeMs += dt
        // The elapsed slice is attributed to the zone state seen at the previous evaluation.
        // With 1 s ticks that is at most 1 s of approximation.
        if (smoothedHr != null) {
            phaseStats.measuredMs += dt
            if (zone == ZoneState.IN) phaseStats.inZoneMs += dt
        }
        phaseElapsedMs += dt

        while (true) {
            val duration = plan[phaseIndex].durationMs ?: break
            if (phaseElapsedMs < duration) break
            val overflowMs = phaseElapsedMs - duration
            val boundaryMs = nowMs - overflowMs
            closePhase(boundaryMs)
            if (phaseIndex + 1 >= plan.size) {
                finish(boundaryMs)
                return
            }
            enterPhase(phaseIndex + 1, boundaryMs, overflowMs)
        }
    }

    private fun enterPhase(index: Int, atMs: Long, carryOverMs: Long) {
        phaseIndex = index
        phaseElapsedMs = carryOverMs
        phaseStartMs = atMs
        phaseOpen = true
        phaseStats = PhaseAccumulator()
        outOfZoneSinceActiveMs = null
        rampNudgeChecked = false
        lastCountdownSpoken = Int.MAX_VALUE

        val phase = plan[index]
        when (phase.kind) {
            PhaseKind.WARMUP -> emit(atMs, EventType.PHASE_START, Cues.warmupStart(fourByFour.warmupMs))
            PhaseKind.WORK -> emit(atMs, EventType.PHASE_START, Cues.workStart(phase.intervalNumber!!, fourByFour.intervalCount))
            PhaseKind.RECOVERY -> {
                emit(atMs, EventType.PHASE_START, Cues.recoveryStart(fourByFour.recoveryMs))
                speakRecapAndMidway(atMs, phase.intervalNumber!!)
            }
            PhaseKind.COOLDOWN -> emit(atMs, EventType.PHASE_START, Cues.cooldownStart(fourByFour.cooldownMs))
            PhaseKind.EASY -> emit(atMs, EventType.PHASE_START, Cues.easyStart(setup.easyTargetDurationMs))
        }
    }

    private fun closePhase(atMs: Long) {
        if (!phaseOpen) return
        phaseOpen = false
        val phase = plan[phaseIndex]
        phaseRecords +=
            PhaseRecord(
                index = phaseIndex,
                kind = phase.kind,
                startMs = t(phaseStartMs),
                endMs = t(atMs),
                targetLow = phase.zone.lowBpm(hrMaxUsed),
                targetHigh = phase.zone.highBpm(hrMaxUsed),
                intervalNumber = phase.intervalNumber,
            )
        if (phase.kind == PhaseKind.WORK) {
            intervals +=
                IntervalStats(
                    number = phase.intervalNumber!!,
                    avgHr = average(phaseStats.hrSum, phaseStats.hrCount),
                    peakHr = phaseStats.peakSmoothed,
                    pctInZone = percentOneDecimal(phaseStats.inZoneMs, phaseStats.measuredMs),
                    hrr60 = null,
                )
            pendingHrr += PendingHrr(intervals.lastIndex, smoothedHr, atMs + fourByFour.hrrDelayMs)
        }
        // 4x4 "time in target" counts work intervals only (the 85-95 % zone is the point of
        // the session). Easy mode counts its single phase.
        if (phase.kind == PhaseKind.WORK || phase.kind == PhaseKind.EASY) {
            timeInTargetMs += phaseStats.inZoneMs
        }
    }

    // ======================================================================================
    // Coaching rules (run after every input)
    // ======================================================================================

    private fun evaluate(nowMs: Long) {
        if (isFinished) return
        smoothedHr = smoother.valueAt(nowMs)
        zone = zoneOf(smoothedHr)

        checkSensorGap(nowMs)
        measureDueHrr(nowMs)
        // A raised HRmax is only announced at the end ("tell me after the session"), and the
        // zones of THIS session stay based on hrMaxUsed so its stats remain consistent.
        hrMaxLearner.update(nowMs, smoothedHr)

        if (!paused) {
            smoothedHr?.let { hr ->
                phaseStats.peakSmoothed = maxOf(phaseStats.peakSmoothed ?: hr, hr)
                sessionMaxSmoothed = maxOf(sessionMaxSmoothed ?: hr, hr)
            }
            countdown(nowMs)
            rampNudge(nowMs)
            corrections(nowMs)
            easyHalfway(nowMs)
        }
        publishState()
    }

    /**
     * Sensor gap: more than [CoachingConfig.sensorGapThresholdMs] without a valid reading
     * -> say "Sensor signal lost" once. "Sensor back" is said by [onSample] on the next
     * valid reading, and the gap is recorded then. Nothing is interpolated: during the gap
     * the smoothed HR is null, the zone is UNKNOWN, and it counts neither in nor out of zone.
     */
    private fun checkSensorGap(nowMs: Long) {
        if (!sensorLost && nowMs - lastValidHrAtMs > config.sensorGapThresholdMs) {
            sensorLost = true
            gapStartMs = lastValidHrAtMs
            emit(nowMs, EventType.SENSOR_LOST, Cues.SENSOR_LOST)
        }
    }

    /**
     * "3", "2", "1" in the last seconds before a work interval. Only the current number is
     * spoken: if ticks were late or the countdown was interrupted by a pause, the coach
     * says the right number instead of rattling off missed ones.
     */
    private fun countdown(nowMs: Long) {
        if (setup.mode != SessionMode.INTERVAL_4X4) return
        val next = plan.getOrNull(phaseIndex + 1) ?: return
        if (next.kind != PhaseKind.WORK) return
        val duration = plan[phaseIndex].durationMs ?: return
        val remainingMs = duration - phaseElapsedMs
        val step = fourByFour.countdownStepMs
        val secondsLeft = ((remainingMs + step - 1) / step).toInt()
        if (secondsLeft in 1..fourByFour.countdownFrom && secondsLeft < lastCountdownSpoken) {
            lastCountdownSpoken = secondsLeft
            emit(nowMs, EventType.COUNTDOWN, Cues.countdown(secondsLeft))
        }
    }

    /**
     * Ramp grace for work intervals: HR lags effort by 1-2 minutes, so being below zone
     * early is normal. At the end of the grace period (90 s), if HR is CLEARLY low (more
     * than the margin below the zone), say "Push a bit harder." once. This counts as a
     * correction for the cooldown, so "Below zone" cannot follow a second later.
     * If there is no HR at 90 s (gap), the check waits for the first valid HR.
     */
    private fun rampNudge(nowMs: Long) {
        val phase = plan[phaseIndex]
        if (phase.kind != PhaseKind.WORK || rampNudgeChecked) return
        if (phaseElapsedMs < fourByFour.rampGraceMs) return
        val hr = smoothedHr ?: return
        rampNudgeChecked = true
        val clearlyLowBelow = phase.zone.lowBpm(hrMaxUsed) - fourByFour.rampNudgeClearlyLowMarginBpm
        if (hr < clearlyLowBelow) {
            emit(nowMs, EventType.RAMP_NUDGE, Cues.RAMP_NUDGE)
            lastCorrectionActiveMs = activeMs
        }
    }

    /**
     * Corrections: out of zone CONTINUOUSLY for the threshold (15 s in 4x4, 30 s easy) ->
     * one cue. Then at least the cooldown (45 s / 90 s) before the next one.
     *
     * The continuous timer starts over when HR returns to the zone, switches between above
     * and below, the phase changes, the user resumes from pause, or the HR becomes unknown
     * (gap): we cannot claim "continuously out of zone" across a period we did not measure.
     * The cooldown is not reset by gaps or pauses (it is about not talking too often).
     * Timers use active time, so a pause freezes them.
     */
    private fun corrections(nowMs: Long) {
        val phase = plan[phaseIndex]
        val rules = correctionRules(phase.kind)
        if (rules == null || zone == ZoneState.UNKNOWN || zone == ZoneState.IN) {
            outOfZoneSinceActiveMs = null
            return
        }
        // Below zone during the grace period is expected, not a reason to start the timer.
        if (zone == ZoneState.BELOW && phaseElapsedMs < rules.belowGraceMs) {
            outOfZoneSinceActiveMs = null
            return
        }
        if (outOfZoneSinceActiveMs == null || outOfZoneDirection != zone) {
            outOfZoneSinceActiveMs = activeMs
            outOfZoneDirection = zone
        }
        val outForMs = activeMs - outOfZoneSinceActiveMs!!
        val lastCorrection = lastCorrectionActiveMs
        val cooledDown = lastCorrection == null || activeMs - lastCorrection >= rules.cooldownMs
        if (outForMs >= rules.afterMs && cooledDown) {
            if (zone == ZoneState.ABOVE) {
                emit(nowMs, EventType.CORRECTION_HIGH, Cues.ABOVE_ZONE)
            } else {
                emit(nowMs, EventType.CORRECTION_LOW, Cues.BELOW_ZONE)
            }
            lastCorrectionActiveMs = activeMs
        }
    }

    private class CorrectionRules(val afterMs: Long, val cooldownMs: Long, val belowGraceMs: Long)

    /** Correction thresholds for this phase, or null if this phase is not corrected. */
    private fun correctionRules(kind: PhaseKind): CorrectionRules? =
        when {
            kind == PhaseKind.EASY ->
                CorrectionRules(easy.correctionAfterMs, easy.correctionCooldownMs, easy.startGraceMs)
            kind in fourByFour.correctedPhases -> {
                val grace = if (kind == PhaseKind.WORK) fourByFour.rampGraceMs else 0L
                CorrectionRules(fourByFour.correctionAfterMs, fourByFour.correctionCooldownMs, grace)
            }
            else -> null
        }

    /**
     * HRR60: smoothed HR when the work interval ended minus smoothed HR 60 s later.
     * Smoothed values are used so one bad beat cannot fake a great (or awful) recovery.
     * If either value is missing (sensor gap) the HRR60 stays null rather than guessed.
     */
    private fun measureDueHrr(nowMs: Long) {
        val due = pendingHrr.filter { it.dueAtMs <= nowMs }
        for (pending in due) {
            val now = smoothedHr
            val hrr = if (pending.hrAtEnd != null && now != null) pending.hrAtEnd - now else null
            intervals[pending.intervalIndex] = intervals[pending.intervalIndex].copy(hrr60 = hrr)
        }
        pendingHrr.removeAll(due)
    }

    /** Spoken at the start of each recovery: recap of the interval just finished, then midway feedback after interval 2. */
    private fun speakRecapAndMidway(atMs: Long, number: Int) {
        val stats = intervals.last { it.number == number }
        val previousHrr =
            if (number >= fourByFour.recapHrrFromInterval) intervals.firstOrNull { it.number == number - 1 }?.hrr60 else null
        emit(atMs, EventType.INTERVAL_RECAP, Cues.intervalRecap(number, stats.avgHr, stats.pctInZone, previousHrr))

        if (number == fourByFour.midwayAfterInterval) {
            val before = intervals.firstOrNull { it.number == number - 1 } ?: return
            val text = midwayText(before, stats, fourByFour.intervalCount - number) ?: return
            emit(atMs, EventType.MIDWAY, text)
        }
    }

    /**
     * Midway verdict, comparing interval 2 with interval 1:
     * - FADING if average HR dropped by more than 3 bpm, or time in zone dropped by more
     *   than 15 percentage points. At the same effort HR normally creeps UP over a session,
     *   so a clear drop means effort is falling off.
     * - otherwise ROOM TO PUSH if the average sits in the bottom 35 % of the work zone.
     * - otherwise HOLDING.
     * Returns null when either interval has no HR data (nothing honest to say).
     */
    private fun midwayText(before: IntervalStats, now: IntervalStats, intervalsLeft: Int): String? {
        val avgBefore = before.avgHr ?: return null
        val avgNow = now.avgHr ?: return null
        val fading =
            avgBefore - avgNow > fourByFour.midwayFadingAvgDropBpm ||
                before.pctInZone - now.pctInZone > fourByFour.midwayFadingPctInZoneDrop
        val low = fourByFour.workZone.lowBpm(hrMaxUsed)
        val high = fourByFour.workZone.highBpm(hrMaxUsed)
        val roomBelow = low + (high - low) * fourByFour.midwayRoomToPushZoneFraction
        return when {
            fading -> Cues.midwayFading(intervalsLeft)
            avgNow < roomBelow -> Cues.midwayRoomToPush(intervalsLeft)
            else -> Cues.midwayHolding(intervalsLeft)
        }
    }

    /** Easy mode with a target duration: report cardiac drift so far at the halfway point. */
    private fun easyHalfway(nowMs: Long) {
        val target = setup.easyTargetDurationMs ?: return
        if (setup.mode != SessionMode.EASY || easyHalfwayDone) return
        if (activeMs < target / 2) return
        easyHalfwayDone = true
        emit(nowMs, EventType.DRIFT, Cues.driftHalfway(driftPct(activeMs)))
    }

    /**
     * Cardiac drift: how much average HR rose from the first half to the second half of the
     * run (after ignoring the warm-up minutes), in percent. At steady easy effort HR slowly
     * rises (heat, dehydration, fatigue); a large drift means the pace was not really easy
     * or the aerobic base needs work. Null if there is not enough data.
     */
    private fun driftPct(upToActiveMs: Long): Double? {
        val from = easy.driftIgnoreFirstMs
        if (upToActiveMs - from < easy.driftMinWindowMs) return null
        val middle = from + (upToActiveMs - from) / 2
        val firstHalf = activeReadings.filter { it.activeMs in from until middle }.map { it.hr }
        val secondHalf = activeReadings.filter { it.activeMs in middle..upToActiveMs }.map { it.hr }
        if (firstHalf.isEmpty() || secondHalf.isEmpty()) return null
        val first = firstHalf.average()
        val second = secondHalf.average()
        return roundOneDecimal((second - first) / first * PERCENT)
    }

    // ======================================================================================
    // Finish
    // ======================================================================================

    private fun finish(atMs: Long) {
        if (isFinished) return
        if (paused) {
            pauses += Pause(t(pauseStartMs), t(atMs))
            paused = false
        }
        closePhase(atMs)
        if (sensorLost) gaps += Gap(t(gapStartMs), t(atMs))

        val drift = if (setup.mode == SessionMode.EASY) driftPct(activeMs) else null
        if (drift != null) emit(atMs, EventType.DRIFT, Cues.driftEnd(drift))

        val avgHr = average(sessionHrSum, sessionHrCount)
        val durationMs = t(atMs)
        emit(atMs, EventType.SESSION_SUMMARY, Cues.sessionSummary(durationMs, avgHr, timeInTargetMs))

        val newHrMax = hrMaxLearner.hrMax.takeIf { it > hrMaxUsed }
        if (newHrMax != null) emit(atMs, EventType.HR_MAX_LEARNED, Cues.hrMaxLearned(newHrMax))

        result =
            SessionResult(
                setup = setup,
                config = config,
                summary =
                    SessionSummary(
                        durationMs = durationMs,
                        avgHr = avgHr,
                        maxHr = sessionMaxSmoothed,
                        timeInTargetMs = timeInTargetMs,
                        newHrMax = newHrMax,
                        driftPct = drift,
                    ),
                samples = samples.toList(),
                gaps = gaps.toList(),
                pauses = pauses.toList(),
                phases = phaseRecords.toList(),
                intervals = intervals.toList(),
                events = eventList.toList(),
            )
        publishState()
    }

    // ======================================================================================
    // Helpers
    // ======================================================================================

    private fun buildPlan(): List<PlannedPhase> =
        when (setup.mode) {
            SessionMode.EASY -> listOf(PlannedPhase(PhaseKind.EASY, null, config.easy.zone, null))
            SessionMode.INTERVAL_4X4 ->
                buildList {
                    add(PlannedPhase(PhaseKind.WARMUP, fourByFour.warmupMs, fourByFour.warmupZone, null))
                    for (n in 1..fourByFour.intervalCount) {
                        add(PlannedPhase(PhaseKind.WORK, fourByFour.workMs, fourByFour.workZone, n))
                        add(PlannedPhase(PhaseKind.RECOVERY, fourByFour.recoveryMs, fourByFour.recoveryZone, n))
                    }
                    add(PlannedPhase(PhaseKind.COOLDOWN, fourByFour.cooldownMs, fourByFour.cooldownZone, null))
                }
        }

    private fun zoneOf(hr: Int?): ZoneState {
        if (hr == null) return ZoneState.UNKNOWN
        val zonePct = plan[phaseIndex].zone
        return when {
            hr < zonePct.lowBpm(hrMaxUsed) -> ZoneState.BELOW
            hr > zonePct.highBpm(hrMaxUsed) -> ZoneState.ABOVE
            else -> ZoneState.IN
        }
    }

    private fun emit(atMs: Long, type: EventType, text: String) {
        val event = CoachingEvent(t(atMs), type, text)
        eventList += event
        eventFlow.tryEmit(event)
    }

    private fun t(clockMs: Long): Long = clockMs - startMs

    private fun average(sum: Long, count: Int): Int? = if (count == 0) null else (sum.toDouble() / count).roundToInt()

    private fun percentOneDecimal(part: Long, whole: Long): Double =
        if (whole == 0L) 0.0 else roundOneDecimal(part.toDouble() / whole * PERCENT)

    private fun roundOneDecimal(value: Double): Double = (value * TENTHS).roundToInt() / TENTHS

    private fun initialState() =
        CoachingState(
            mode = setup.mode,
            started = false,
            finished = false,
            paused = false,
            phaseKind = null,
            phaseRemainingMs = null,
            elapsedActiveMs = 0,
            intervalNumber = null,
            intervalCount = if (setup.mode == SessionMode.INTERVAL_4X4) fourByFour.intervalCount else null,
            smoothedHr = null,
            zoneState = ZoneState.UNKNOWN,
            targetLow = null,
            targetHigh = null,
            sensorLost = false,
            hrMaxUsed = setup.hrMax,
            learnedHrMax = setup.hrMax,
        )

    private fun publishState() {
        val phase = plan[phaseIndex]
        val remaining =
            if (isFinished) {
                0L
            } else if (phase.durationMs != null) {
                phase.durationMs - phaseElapsedMs
            } else {
                setup.easyTargetDurationMs?.let { maxOf(0L, it - activeMs) }
            }
        stateFlow.value =
            stateFlow.value.copy(
                started = started,
                finished = isFinished,
                paused = paused,
                phaseKind = phase.kind,
                phaseRemainingMs = remaining,
                elapsedActiveMs = activeMs,
                intervalNumber = phase.intervalNumber,
                smoothedHr = smoothedHr,
                zoneState = zone,
                targetLow = phase.zone.lowBpm(hrMaxUsed),
                targetHigh = phase.zone.highBpm(hrMaxUsed),
                sensorLost = sensorLost,
                learnedHrMax = hrMaxLearner.hrMax,
            )
    }
}
