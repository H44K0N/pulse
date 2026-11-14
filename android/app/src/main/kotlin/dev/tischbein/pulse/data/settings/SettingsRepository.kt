package dev.tischbein.pulse.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.tischbein.pulse.sensor.sim.SimProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Reads and writes [AppSettings] in a Preferences DataStore (a small key-value file in the
 * app's private storage). A missing key means "default", so new settings need no migration.
 */
class SettingsRepository(private val dataStore: DataStore<Preferences>) {
    val settings: Flow<AppSettings> = dataStore.data.map { it.toSettings() }

    suspend fun current(): AppSettings = settings.first()

    /** Saves everything the Settings screen edits. Does not touch the remembered device. */
    suspend fun save(new: AppSettings) {
        dataStore.edit { prefs ->
            prefs[AGE] = new.age
            prefs.setOrRemove(HR_MAX, new.hrMaxOverride)
            prefs[HR_MAX_LEARNED] = new.hrMaxLearned
            prefs[WARMUP_MIN] = new.warmupMin
            prefs[WORK_MIN] = new.workMin
            prefs[RECOVERY_MIN] = new.recoveryMin
            prefs[COOLDOWN_MIN] = new.cooldownMin
            prefs[INTERVAL_COUNT] = new.intervalCount
            prefs.putZone(WARMUP_ZONE, new.warmupZone)
            prefs.putZone(WORK_ZONE, new.workZone)
            prefs.putZone(RECOVERY_ZONE, new.recoveryZone)
            prefs.putZone(EASY_ZONE, new.easyZone)
            prefs[EASY_TARGET_MIN] = new.easyTargetMin
            prefs[SERVER_URL] = new.serverUrl
            prefs[API_TOKEN] = new.apiToken
            prefs[SIMULATOR] = new.simulatorEnabled
            prefs[SIM_PROFILE] = new.simulatorProfile.name
        }
    }

    suspend fun setDeviceId(deviceId: String?) {
        dataStore.edit { it.setOrRemove(DEVICE_ID, deviceId) }
    }

    /**
     * Brief: "if a session shows a sustained HR above the current HRmax, update HRmax ... Never
     * auto-lower it." Stores [observed] as the learned HRmax only if it is higher than the HRmax
     * in effect right now. Returns true if it was stored.
     */
    suspend fun applyLearnedHrMax(observed: Int): Boolean {
        var raised = false
        dataStore.edit { prefs ->
            if (observed > prefs.toSettings().hrMax) {
                prefs[HR_MAX] = observed
                prefs[HR_MAX_LEARNED] = true
                raised = true
            }
        }
        return raised
    }

    /**
     * The server (normalised URL) whose upload states the database holds: SessionUploader
     * compares it with the configured URL to notice a server change. Not a user setting.
     */
    suspend fun syncTarget(): String? = dataStore.data.first()[SYNC_TARGET]

    suspend fun setSyncTarget(url: String) {
        dataStore.edit { it[SYNC_TARGET] = url }
    }

    private fun Preferences.toSettings(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            age = this[AGE] ?: d.age,
            hrMaxOverride = this[HR_MAX],
            hrMaxLearned = this[HR_MAX_LEARNED] ?: d.hrMaxLearned,
            warmupMin = this[WARMUP_MIN] ?: d.warmupMin,
            workMin = this[WORK_MIN] ?: d.workMin,
            recoveryMin = this[RECOVERY_MIN] ?: d.recoveryMin,
            cooldownMin = this[COOLDOWN_MIN] ?: d.cooldownMin,
            intervalCount = this[INTERVAL_COUNT] ?: d.intervalCount,
            warmupZone = zone(WARMUP_ZONE, d.warmupZone),
            workZone = zone(WORK_ZONE, d.workZone),
            recoveryZone = zone(RECOVERY_ZONE, d.recoveryZone),
            easyZone = zone(EASY_ZONE, d.easyZone),
            easyTargetMin = this[EASY_TARGET_MIN] ?: d.easyTargetMin,
            serverUrl = this[SERVER_URL] ?: d.serverUrl,
            apiToken = this[API_TOKEN] ?: d.apiToken,
            simulatorEnabled = this[SIMULATOR] ?: d.simulatorEnabled,
            // runCatching: a profile renamed in a later version falls back to the default.
            simulatorProfile = this[SIM_PROFILE]?.let { runCatching { SimProfile.valueOf(it) }.getOrNull() } ?: d.simulatorProfile,
            deviceId = this[DEVICE_ID],
        )
    }

    private fun Preferences.zone(key: ZoneKeys, default: ZoneSetting): ZoneSetting =
        ZoneSetting(this[key.low] ?: default.lowPct, this[key.high] ?: default.highPct)

    private fun MutablePreferences.putZone(key: ZoneKeys, zone: ZoneSetting) {
        this[key.low] = zone.lowPct
        this[key.high] = zone.highPct
    }

    private fun <T> MutablePreferences.setOrRemove(key: Preferences.Key<T>, value: T?) {
        if (value == null) remove(key) else this[key] = value
    }

    private class ZoneKeys(name: String) {
        val low = intPreferencesKey("${name}_low_pct")
        val high = intPreferencesKey("${name}_high_pct")
    }

    // Key names are stored on disk: never rename one without migrating it.
    private companion object {
        val AGE = intPreferencesKey("age")
        val HR_MAX = intPreferencesKey("hr_max")
        val HR_MAX_LEARNED = booleanPreferencesKey("hr_max_learned")
        val WARMUP_MIN = intPreferencesKey("warmup_min")
        val WORK_MIN = intPreferencesKey("work_min")
        val RECOVERY_MIN = intPreferencesKey("recovery_min")
        val COOLDOWN_MIN = intPreferencesKey("cooldown_min")
        val INTERVAL_COUNT = intPreferencesKey("interval_count")
        val WARMUP_ZONE = ZoneKeys("warmup_zone")
        val WORK_ZONE = ZoneKeys("work_zone")
        val RECOVERY_ZONE = ZoneKeys("recovery_zone")
        val EASY_ZONE = ZoneKeys("easy_zone")
        val EASY_TARGET_MIN = intPreferencesKey("easy_target_min")
        val SERVER_URL = stringPreferencesKey("server_url")
        val API_TOKEN = stringPreferencesKey("api_token")
        val SIMULATOR = booleanPreferencesKey("simulator_enabled")
        val SIM_PROFILE = stringPreferencesKey("simulator_profile")
        val DEVICE_ID = stringPreferencesKey("device_id")
        val SYNC_TARGET = stringPreferencesKey("sync_target")
    }
}
