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
            if (age !in AGE_RANGE) add("Age must be ${AGE_RANGE.first}-${AGE_RANGE.last}")
            hrMaxOverride?.let { if (it !in HR_MAX_RANGE) add("HRmax must be ${HR_MAX_RANGE.first}-${HR_MAX_RANGE.last}") }
            if (listOf(warmupMin, recoveryMin, cooldownMin).any { it !in PHASE_MIN_RANGE }) add("Durations must be ${PHASE_MIN_RANGE.first}-${PHASE_MIN_RANGE.last} min")
            if (workMin !in WORK_MIN_RANGE) add("Work interval must be ${WORK_MIN_RANGE.first}-${WORK_MIN_RANGE.last} min")
            if (intervalCount !in INTERVAL_COUNT_RANGE) add("Interval count must be ${INTERVAL_COUNT_RANGE.first}-${INTERVAL_COUNT_RANGE.last}")
            if (easyTargetMin !in EASY_TARGET_RANGE) add("Easy target must be ${EASY_TARGET_RANGE.first}-${EASY_TARGET_RANGE.last} min")
            mapOf("Warmup" to warmupZone, "Work" to workZone, "Recovery" to recoveryZone, "Easy" to easyZone)
                .filterValues { !it.isValid }
                .keys
                .forEach { add("$it zone: low must be below high (percent of HRmax)") }
            serverUrlError(serverUrl)?.let { add(it) }
        }

    // A data class prints every field; the token must never end up in a log or crash report.
    override fun toString(): String =
        "AppSettings(age=$age, hrMax=$hrMax, learned=$hrMaxLearned, 4x4=$warmupMin/$intervalCount" +
            "x($workMin+$recoveryMin)/$cooldownMin, zones=$warmupZone/$workZone/$recoveryZone/$easyZone, " +
            "server=$serverUrl, apiToken=${if (apiToken.isEmpty()) "<none>" else "<hidden>"}, " +
            "simulator=$simulatorEnabled/$simulatorProfile, device=$deviceId)"

    companion object {
        const val DEFAULT_AGE = 26

        // Limits of what Settings accepts (the steppers stop there, validation checks them).
        val AGE_RANGE = 10..100
        val HR_MAX_RANGE = 100..230
        val PHASE_MIN_RANGE = 0..120
        val WORK_MIN_RANGE = 1..120
        val INTERVAL_COUNT_RANGE = 1..10
        val EASY_TARGET_RANGE = 0..600

        /** Easy target duration changes in 5-minute steps. */
        const val EASY_TARGET_STEP_MIN = 5

        /** Zone sliders cover this much of HRmax, in %. */
        val ZONE_PCT_RANGE = 40..100

        /** Empty (sync off) or an http(s) URL; null = OK. */
        fun serverUrlError(url: String): String? =
            if (url.isEmpty() || url.startsWith("https://") || url.startsWith("http://")) null else "Server URL must start with https://"
        private val DEFAULT_4X4 = FourByFourConfig()

        private fun minutes(ms: Long): Int = (ms / MS_PER_MINUTE).toInt()
    }
}
