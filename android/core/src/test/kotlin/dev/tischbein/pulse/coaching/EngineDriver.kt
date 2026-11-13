package dev.tischbein.pulse.coaching

import dev.tischbein.pulse.sensor.HeartRateSample

/** Returned by an HR script to mean "no sample at all this second" (link down). */
const val NO_SIGNAL = -1

/**
 * Test helper: drives a [CoachingEngine] with one sample and one tick per second, the way
 * the H10 and [CoachingRunner] do, starting at time 0. Fully synchronous and deterministic.
 */
class EngineDriver(
    setup: SessionSetup,
    config: CoachingConfig = CoachingConfig(),
) {
    val engine = CoachingEngine(setup, config)
    var nowMs = 0L
        private set

    init {
        engine.start(0)
    }

    /**
     * Advance second by second until [untilMs] (inclusive) or until the session finishes.
     * [hr] gets the session time and returns bpm, null (sample without valid HR) or [NO_SIGNAL].
     */
    fun runUntil(untilMs: Long, hr: (tMs: Long) -> Int?) {
        while (nowMs < untilMs && !engine.isFinished) {
            nowMs += 1_000
            val value = hr(nowMs)
            if (value != NO_SIGNAL) engine.onSample(HeartRateSample(nowMs, value, emptyList()))
            engine.onTick(nowMs)
        }
    }

    fun runToEnd(hr: (tMs: Long) -> Int?) = runUntil(Long.MAX_VALUE, hr)

    fun events(type: EventType) = engine.eventLog.filter { it.type == type }

    fun eventTimes(type: EventType) = events(type).map { it.tMs }
}

fun sec(s: Long) = s * 1_000

/** Default 4x4 timeline (seconds): warmup 0-600, work n starts at 600 + (n-1)*420, cooldown 2280-2580. */
fun workStartSec(n: Int) = 600L + (n - 1) * 420L

fun recoveryStartSec(n: Int) = workStartSec(n) + 240L

/** HR script that holds one value per phase of the default 4x4 protocol. */
fun protocolHr(
    warmup: Int = 125,
    work: List<Int> = listOf(172, 172, 172, 172),
    recovery: Int = 125,
    cooldown: Int = 110,
): (Long) -> Int {
    return { tMs ->
        val s = tMs / 1_000
        when {
            s < 600 -> warmup
            s >= 2280 -> cooldown
            else -> {
                val inBlock = (s - 600) % 420
                val n = ((s - 600) / 420).toInt()
                if (inBlock < 240) work[n] else recovery
            }
        }
    }
}

const val HR_MAX = 190
val FOUR_BY_FOUR = SessionSetup(SessionMode.INTERVAL_4X4, HR_MAX)
