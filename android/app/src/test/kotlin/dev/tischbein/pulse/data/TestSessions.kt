package dev.tischbein.pulse.data

import dev.tischbein.pulse.coaching.CoachingConfig
import dev.tischbein.pulse.coaching.CoachingEngine
import dev.tischbein.pulse.coaching.FourByFourConfig
import dev.tischbein.pulse.coaching.MS_PER_MINUTE
import dev.tischbein.pulse.coaching.SessionMode
import dev.tischbein.pulse.coaching.SessionResult
import dev.tischbein.pulse.coaching.SessionSetup
import dev.tischbein.pulse.data.db.DeviceKind
import dev.tischbein.pulse.data.db.SessionEntity
import dev.tischbein.pulse.data.db.SessionStatus
import dev.tischbein.pulse.data.export.SettingsSnapshot
import dev.tischbein.pulse.sensor.HeartRateSample
import java.io.File

/** Shared fixtures: a short but complete 4x4 session produced by the real coaching engine. */
object TestSessions {
    const val HR_MAX = 190
    const val STARTED_AT = 1_794_675_731_000L // 2026-11-14T17:02:11Z

    /** 2 x (1 min work + 1 min recovery), 1 min warmup and cooldown: every array gets entries. */
    val config =
        CoachingConfig(
            fourByFour =
                FourByFourConfig(
                    warmupMs = MS_PER_MINUTE,
                    workMs = MS_PER_MINUTE,
                    recoveryMs = MS_PER_MINUTE,
                    cooldownMs = MS_PER_MINUTE,
                    intervalCount = 2,
                ),
        )
    val setup = SessionSetup(SessionMode.INTERVAL_4X4, HR_MAX)

    /** HR the "athlete" produces at time t (ms since start). */
    private fun hrAt(tMs: Long): Int =
        when ((tMs / MS_PER_MINUTE).toInt()) {
            0 -> 120
            1, 3 -> 172
            else -> 130
        }

    /**
     * Runs the engine over 1 Hz samples with a 15 s dropout in the first recovery (a gap),
     * one invalid reading (hr null) and a 10 s pause. Returns the finished result.
     */
    fun fourByFourResult(): SessionResult {
        val engine = CoachingEngine(setup, config)
        engine.start(0)
        var t = 0L
        while (!engine.isFinished) {
            t += 1_000
            when {
                t in 130_000..145_000 -> engine.onTick(t) // dropout: no samples at all
                t == 20_000L -> engine.onSample(HeartRateSample(t, null))
                else -> engine.onSample(HeartRateSample(t, hrAt(t), listOf(60_000 / hrAt(t))))
            }
            if (t == 40_000L) engine.pause(t)
            if (t == 50_000L) engine.resume(t)
            check(t < 60 * MS_PER_MINUTE) { "engine never finished" }
        }
        return engine.result!!
    }

    fun sessionRow(id: String = "6f1c1e8a-2d6b-4a8e-9a37-1c0d3f8e2b11") =
        SessionEntity(
            id = id,
            mode = SessionMode.INTERVAL_4X4.wireName,
            status = SessionStatus.RECORDING,
            startedAtEpochMs = STARTED_AT,
            endedAtEpochMs = null,
            hrMaxUsed = HR_MAX,
            deviceKind = DeviceKind.POLAR_H10,
            deviceId = "A1B2C3D4",
            settingsJson = SettingsSnapshot.of(setup, config, age = 26, hrMaxLearned = false).toJson(),
        )

    /** The ```json example of docs/session-format.md (found by walking up from the module dir). */
    fun contractExample(): String {
        var dir: File? = File("").absoluteFile // the module directory when Gradle runs tests
        while (dir != null && !File(dir, "docs/session-format.md").exists()) dir = dir.parentFile
        val doc = File(checkNotNull(dir) { "docs/session-format.md not found" }, "docs/session-format.md").readText()
        return doc.substringAfter("```json").substringBefore("```")
    }
}
