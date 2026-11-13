package dev.tischbein.pulse.data.settings

import dev.tischbein.pulse.coaching.CoachingConfig
import dev.tischbein.pulse.coaching.EasyConfig
import dev.tischbein.pulse.coaching.FourByFourConfig
import dev.tischbein.pulse.coaching.MS_PER_MINUTE
import dev.tischbein.pulse.coaching.ZonePercent
import dev.tischbein.pulse.coaching.estimateHrMax
import dev.tischbein.pulse.sensor.sim.SimProfile
import kotlin.math.roundToInt

/** A zone as whole percent of HRmax, as typed in Settings (e.g. 85..95). */
data class ZoneSetting(val lowPct: Int, val highPct: Int) {
    fun toZonePercent() = ZonePercent(lowPct / PERCENT, highPct / PERCENT)

    val isValid: Boolean get() = lowPct in 1..<highPct && highPct <= MAX_ZONE_PCT

    companion object {
        private const val PERCENT = 100.0
        private const val MAX_ZONE_PCT = 120

        fun of(zone: ZonePercent) = ZoneSetting((zone.low * PERCENT).roundToInt(), (zone.high * PERCENT).roundToInt())
    }
}

/**
 * Everything the user can change in Settings, as stored in DataStore.
 *
 * Defaults come from the coaching engine's [CoachingConfig] (the single place for protocol
 * and zone values), so changing a default there changes it here too.
 *
 * @property hrMaxOverride null = use the Tanaka estimate for [age]. Set either manually in
 *   Settings ([hrMaxLearned] = false) or by a session that observed a higher HRmax
 *   ([hrMaxLearned] = true).
 * @property easyTargetMin default target duration for easy sessions in minutes, 0 = open-ended.
 * @property apiToken credential for the sync server. Never log it ([toString] hides it).
 * @property deviceId the remembered Polar device id (printed on the sensor, e.g. "A1B2C3D4").
 */
data class AppSettings(
    val age: Int = DEFAULT_AGE,
    val hrMaxOverride: Int? = null,
    val hrMaxLearned: Boolean = false,
    val warmupMin: Int = minutes(DEFAULT_4X4.warmupMs),
    val workMin: Int = minutes(DEFAULT_4X4.workMs),
    val recoveryMin: Int = minutes(DEFAULT_4X4.recoveryMs),
    val cooldownMin: Int = minutes(DEFAULT_4X4.cooldownMs),
    val intervalCount: Int = DEFAULT_4X4.intervalCount,
    val warmupZone: ZoneSetting = ZoneSetting.of(DEFAULT_4X4.warmupZone),
    val workZone: ZoneSetting = ZoneSetting.of(DEFAULT_4X4.workZone),
    val recoveryZone: ZoneSetting = ZoneSetting.of(DEFAULT_4X4.recoveryZone),
    val easyZone: ZoneSetting = ZoneSetting.of(EasyConfig().zone),
    val easyTargetMin: Int = 0,
    val serverUrl: String = "",
    val apiToken: String = "",
    val simulatorEnabled: Boolean = false,
    val simulatorProfile: SimProfile = SimProfile.GOOD_4X4,
    val deviceId: String? = null,
) {
    /** HRmax used for zones: the stored value, or the Tanaka estimate (age 26 -> 190). */
    val hrMax: Int get() = hrMaxOverride ?: estimateHrMax(age)

    val easyTargetDurationMs: Long? get() = easyTargetMin.takeIf { it > 0 }?.let { it * MS_PER_MINUTE }

    /** The engine configuration for these settings. Values not in Settings keep their defaults. */
    fun toCoachingConfig(): CoachingConfig {
        val base = CoachingConfig()
        return base.copy(
            fourByFour =
                base.fourByFour.copy(
                    warmupMs = warmupMin * MS_PER_MINUTE,
                    workMs = workMin * MS_PER_MINUTE,
                    recoveryMs = recoveryMin * MS_PER_MINUTE,
                    cooldownMs = cooldownMin * MS_PER_MINUTE,
                    intervalCount = intervalCount,
                    warmupZone = warmupZone.toZonePercent(),
                    workZone = workZone.toZonePercent(),
                    recoveryZone = recoveryZone.toZonePercent(),
                ),
            easy = base.easy.copy(zone = easyZone.toZonePercent()),
        )
    }

    /** Problems that would make a session nonsensical; empty = OK to save. */
    fun validationErrors(): List<String> =
        buildList {
            if (age !in 10..100) add("Age must be 10-100")
            hrMaxOverride?.let { if (it !in 100..230) add("HRmax must be 100-230") }
            if (listOf(warmupMin, workMin, recoveryMin, cooldownMin).any { it !in 0..120 }) add("Durations must be 0-120 min")
            if (workMin < 1) add("Work interval must be at least 1 min")
            if (intervalCount !in 1..10) add("Interval count must be 1-10")
            if (easyTargetMin !in 0..600) add("Easy target must be 0-600 min")
            mapOf("Warmup" to warmupZone, "Work" to workZone, "Recovery" to recoveryZone, "Easy" to easyZone)
                .filterValues { !it.isValid }
                .keys
                .forEach { add("$it zone: low must be below high (percent of HRmax)") }
        }

    // A data class prints every field; the token must never end up in a log or crash report.
    override fun toString(): String =
        "AppSettings(age=$age, hrMax=$hrMax, learned=$hrMaxLearned, 4x4=$warmupMin/$intervalCount" +
            "x($workMin+$recoveryMin)/$cooldownMin, zones=$warmupZone/$workZone/$recoveryZone/$easyZone, " +
            "server=$serverUrl, apiToken=${if (apiToken.isEmpty()) "<none>" else "<hidden>"}, " +
            "simulator=$simulatorEnabled/$simulatorProfile, device=$deviceId)"

    companion object {
        const val DEFAULT_AGE = 26
        private val DEFAULT_4X4 = FourByFourConfig()

        private fun minutes(ms: Long): Int = (ms / MS_PER_MINUTE).toInt()
    }
}
