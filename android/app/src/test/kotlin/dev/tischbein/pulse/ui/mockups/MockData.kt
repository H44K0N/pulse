package dev.tischbein.pulse.ui.mockups

import dev.tischbein.pulse.coaching.CoachingConfig
import dev.tischbein.pulse.coaching.CoachingEngine
import dev.tischbein.pulse.coaching.CoachingState
import dev.tischbein.pulse.coaching.MS_PER_MINUTE
import dev.tischbein.pulse.coaching.SessionMode
import dev.tischbein.pulse.coaching.SessionResult
import dev.tischbein.pulse.coaching.SessionSetup
import dev.tischbein.pulse.data.settings.AppSettings
import dev.tischbein.pulse.sensor.ConnectionState
import dev.tischbein.pulse.sensor.DeviceStatus
import dev.tischbein.pulse.sensor.HeartRateSample
import dev.tischbein.pulse.sensor.sim.SimProfile
import dev.tischbein.pulse.sensor.sim.SimulatedHeartModel
import dev.tischbein.pulse.data.SessionDigest
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Realistic data for the design mockups, produced by the real coaching engine: the
 * simulator's scripted profiles for "today", and a synthetic athlete for six weeks of history.
 */
object MockData {
    const val HR_MAX = 193
    val zone: ZoneId = ZoneId.of("Europe/Oslo")
    val today: LocalDate = LocalDate.of(2026, 11, 14)
    val config = CoachingConfig()

    val settings =
        AppSettings(
            age = 26,
            hrMaxOverride = HR_MAX,
            hrMaxLearned = true,
            easyTargetMin = 45,
            deviceId = "C0FFEE42",
        )

    val device = DeviceStatus(null, "C0FFEE42", ConnectionState.CONNECTED, 100, true)

    fun epochAt(
        day: LocalDate,
        h: Int,
        m: Int,
    ): Long = day.atTime(LocalTime.of(h, m)).atZone(zone).toInstant().toEpochMilli()

    /** A recorded run: the engine, its state at each second, and the smoothed HR per second. */
    class Run(
        val engine: CoachingEngine,
        val states: MutableList<CoachingState> = mutableListOf(),
        val smoothed: MutableList<Int?> = mutableListOf(),
        val raw: MutableList<Int?> = mutableListOf(),
    ) {
        val result: SessionResult get() = engine.end(states.size * 1_000L)

        /** Smoothed HR of the [seconds] up to [atSec]. */
        fun recent(
            atSec: Int,
            seconds: Int = 120,
        ): List<Int?> = smoothed.subList((atSec - seconds).coerceAtLeast(0), atSec)

        /** Last valid raw HR before [atSec] and how many ms ago. */
        fun lastValid(atSec: Int): Pair<Int?, Long> {
            for (i in atSec - 1 downTo 0) raw[i]?.let { return it to (atSec - 1 - i) * 1_000L }
            return null to 0L
        }
    }

    /**
     * Drives the engine one sample + one tick per second until [untilSec] or the end, like
     * the H10 and CoachingRunner do. [hr] returns null for "no sample at all" (link down).
     */
    fun run(
        setup: SessionSetup,
        untilSec: Int = Int.MAX_VALUE,
        pauseAtSec: Int? = null,
        hr: (tMs: Long) -> Int?,
    ): Run {
        val engine = CoachingEngine(setup, config)
        engine.start(0)
        val r = Run(engine)
        var s = 0
        while (s < untilSec && !engine.isFinished) {
            s++
            val t = s * 1_000L
            if (pauseAtSec != null && s == pauseAtSec) engine.pause(t)
            val v = hr(t)
            if (v != null) engine.onSample(HeartRateSample(t, v, listOf(60_000 / v)))
            engine.onTick(t)
            r.states += engine.state.value
            r.smoothed += engine.state.value.smoothedHr
            r.raw += v
        }
        return r
    }

    fun simRun(
        profile: SimProfile,
        untilSec: Int = Int.MAX_VALUE,
        seed: Long = 7L,
        pauseAtSec: Int? = null,
        easyTargetMin: Int? = null,
    ): Run {
        val model = SimulatedHeartModel(profile, HR_MAX, config.fourByFour, seed)
        val mode = if (profile == SimProfile.EASY_WITH_DRIFT) SessionMode.EASY else SessionMode.INTERVAL_4X4
        val setup = SessionSetup(mode, HR_MAX, easyTargetMin?.let { it * MS_PER_MINUTE })
        return run(setup, untilSec, pauseAtSec) { t -> model.step(t).let { if (it.signalLost) null else it.hr } }
    }

    // ---- Synthetic six-week history ---------------------------------------------------

    /**
     * A first-order "athlete": HR follows the effort target with a ramp time constant on the way
     * up and [recoveryTauS] on the way down (smaller = fitter, larger HRR60), plus noise.
     */
    private fun athlete(
        seed: Long,
        target: (tSec: Double) -> Double,
        recoveryTauS: Double,
    ): (Long) -> Int? {
        val rnd = Random(seed)
        var hr = 0.42 * HR_MAX
        var last = 0.0
        return { tMs ->
            val t = tMs / 1000.0
            val goal = target(t) * HR_MAX
            val tau = if (goal > hr) 26.0 else recoveryTauS
            hr += (goal - hr) * (1 - exp(-(t - last) / tau))
            last = t
            (hr + rnd.nextInt(-2, 3)).roundToInt()
        }
    }

    private fun fourByFourTarget(work: List<Double>): (Double) -> Double =
        { t ->
            val p = config.fourByFour
            var s = t * 1000
            when {
                s < p.warmupMs -> 0.66
                else -> {
                    s -= p.warmupMs
                    var f = 0.55
                    for (i in 0 until p.intervalCount) {
                        if (s < p.workMs) { f = work[i]; break }
                        s -= p.workMs
                        if (s < p.recoveryMs) { f = 0.62; break }
                        s -= p.recoveryMs
                    }
                    f
                }
            }
        }

    /** Six weeks of sessions ending [today]; fitness improves gently over the period. */
    fun history(): List<Pair<SessionDigest, SessionResult>> {
        val rnd = Random(42)
        // Day offsets from the first day (0) of the 42-day window; ~2.5/week early, ~3.5 later.
        val plan =
            listOf(
                1 to SessionMode.INTERVAL_4X4, 3 to SessionMode.EASY, 6 to SessionMode.INTERVAL_4X4,
                9 to SessionMode.EASY, 12 to SessionMode.INTERVAL_4X4,
                15 to SessionMode.INTERVAL_4X4, 18 to SessionMode.EASY, 20 to SessionMode.EASY,
                22 to SessionMode.INTERVAL_4X4, 24 to SessionMode.EASY, 26 to SessionMode.INTERVAL_4X4, 27 to SessionMode.EASY,
                29 to SessionMode.INTERVAL_4X4, 31 to SessionMode.EASY, 33 to SessionMode.INTERVAL_4X4, 34 to SessionMode.EASY,
                36 to SessionMode.INTERVAL_4X4, 38 to SessionMode.EASY, 39 to SessionMode.EASY,
            )
        val first = today.minusDays(41)
        val out = mutableListOf<Pair<SessionDigest, SessionResult>>()
        plan.forEachIndexed { i, (day, mode) ->
            val progress = day / 41.0
            val date = first.plusDays(day.toLong())
            val hour = if (date.dayOfWeek.value >= 6) 10 else 18
            val result =
                if (mode == SessionMode.INTERVAL_4X4) {
                    // Early: work intensity a bit low and fading; later: steady in the zone.
                    val base = 0.885 + 0.05 * progress + rnd.nextDouble(-0.012, 0.012)
                    val fade = 0.03 * (1 - progress)
                    val work = List(4) { k -> base - fade * k / 3.0 }
                    val tau = 78.0 - 30.0 * progress + rnd.nextDouble(-5.0, 5.0)
                    run(SessionSetup(mode, HR_MAX), hr = athlete(100L + i, fourByFourTarget(work), tau)).result
                } else {
                    val minutes = listOf(40, 45, 50, 60)[rnd.nextInt(4)]
                    val driftPerHour = 0.075 - 0.045 * progress + rnd.nextDouble(-0.008, 0.008)
                    val r = run(SessionSetup(mode, HR_MAX, minutes * MS_PER_MINUTE), untilSec = minutes * 60, hr = athlete(200L + i, { t -> 0.625 + driftPerHour * t / 3600.0 }, 40.0))
                    r.result
                }
            out += SessionDigest.of("h$i", result, epochAt(date, hour, 5 + i % 40)) to result
        }
        return out
    }
}
