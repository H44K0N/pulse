package dev.tischbein.pulse.data.export

import dev.tischbein.pulse.coaching.CoachingConfig
import dev.tischbein.pulse.coaching.SessionSetup
import dev.tischbein.pulse.coaching.ZonePercent
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The `settings` object stored with each session ("protocol and zone settings in effect",
 * free-form in the contract). Kept so history stays honest when settings change later.
 * Zones are fractions of HRmax, as in CoachingConfig.
 */
@Serializable
data class SettingsSnapshot(
    @SerialName("age") val age: Int,
    @SerialName("hr_max_learned") val hrMaxLearned: Boolean,
    @SerialName("warmup_ms") val warmupMs: Long,
    @SerialName("work_ms") val workMs: Long,
    @SerialName("recovery_ms") val recoveryMs: Long,
    @SerialName("cooldown_ms") val cooldownMs: Long,
    @SerialName("interval_count") val intervalCount: Int,
    @SerialName("warmup_zone") val warmupZone: List<Double>,
    @SerialName("work_zone") val workZone: List<Double>,
    @SerialName("recovery_zone") val recoveryZone: List<Double>,
    @SerialName("cooldown_zone") val cooldownZone: List<Double>,
    @SerialName("easy_zone") val easyZone: List<Double>,
    @SerialName("easy_target_duration_ms") val easyTargetDurationMs: Long?,
) {
    fun toJson(): String = SessionJson.format.encodeToString(serializer(), this)

    companion object {
        fun of(setup: SessionSetup, config: CoachingConfig, age: Int, hrMaxLearned: Boolean): SettingsSnapshot {
            val p = config.fourByFour
            fun ZonePercent.pair() = listOf(low, high)
            return SettingsSnapshot(
                age = age,
                hrMaxLearned = hrMaxLearned,
                warmupMs = p.warmupMs,
                workMs = p.workMs,
                recoveryMs = p.recoveryMs,
                cooldownMs = p.cooldownMs,
                intervalCount = p.intervalCount,
                warmupZone = p.warmupZone.pair(),
                workZone = p.workZone.pair(),
                recoveryZone = p.recoveryZone.pair(),
                cooldownZone = p.cooldownZone.pair(),
                easyZone = config.easy.zone.pair(),
                easyTargetDurationMs = setup.easyTargetDurationMs,
            )
        }
    }
}
