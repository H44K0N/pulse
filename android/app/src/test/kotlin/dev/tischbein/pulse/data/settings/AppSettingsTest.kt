package dev.tischbein.pulse.data.settings

import dev.tischbein.pulse.coaching.CoachingConfig
import dev.tischbein.pulse.coaching.MS_PER_MINUTE
import dev.tischbein.pulse.coaching.ZonePercent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppSettingsTest {
    @Test
    fun `defaults match the brief and the engine's config`() {
        val s = AppSettings()
        assertEquals(26, s.age)
        assertEquals(190, s.hrMax) // Tanaka: 208 - 0.7 * 26 = 189.8
        assertFalse(s.hrMaxLearned)
        assertEquals(CoachingConfig(), s.toCoachingConfig()) // untouched settings = engine defaults
        assertNull(s.easyTargetDurationMs)
        assertTrue(s.validationErrors().isEmpty())
    }

    @Test
    fun `hrMax uses the stored value, otherwise the estimate for the age`() {
        assertEquals(181, AppSettings(age = 38).hrMax)
        assertEquals(195, AppSettings(age = 38, hrMaxOverride = 195).hrMax)
    }

    @Test
    fun `settings map onto the coaching config`() {
        val config =
            AppSettings(
                warmupMin = 12,
                workMin = 5,
                recoveryMin = 2,
                cooldownMin = 6,
                intervalCount = 5,
                workZone = ZoneSetting(88, 94),
                easyZone = ZoneSetting(65, 75),
            ).toCoachingConfig()
        assertEquals(12 * MS_PER_MINUTE, config.fourByFour.warmupMs)
        assertEquals(5 * MS_PER_MINUTE, config.fourByFour.workMs)
        assertEquals(2 * MS_PER_MINUTE, config.fourByFour.recoveryMs)
        assertEquals(6 * MS_PER_MINUTE, config.fourByFour.cooldownMs)
        assertEquals(5, config.fourByFour.intervalCount)
        assertEquals(ZonePercent(0.88, 0.94), config.fourByFour.workZone)
        assertEquals(ZonePercent(0.65, 0.75), config.easy.zone)
        // Values that are not settings keep the engine's defaults.
        assertEquals(CoachingConfig().fourByFour.rampGraceMs, config.fourByFour.rampGraceMs)
    }

    @Test
    fun `easy target duration of 0 means open-ended`() {
        assertNull(AppSettings(easyTargetMin = 0).easyTargetDurationMs)
        assertEquals(45 * MS_PER_MINUTE, AppSettings(easyTargetMin = 45).easyTargetDurationMs)
    }

    @Test
    fun `validation catches nonsense`() {
        assertTrue(AppSettings(workZone = ZoneSetting(95, 85)).validationErrors().isNotEmpty())
        assertTrue(AppSettings(age = -1).validationErrors().isNotEmpty())
        assertTrue(AppSettings(hrMaxOverride = 50).validationErrors().isNotEmpty())
        assertTrue(AppSettings(workMin = 0).validationErrors().isNotEmpty())
        assertTrue(AppSettings(intervalCount = -1).validationErrors().isNotEmpty())
    }

    @Test
    fun `toString never contains the api token`() {
        val text = AppSettings(apiToken = "s3cr3t-token").toString()
        assertFalse("s3cr3t" in text)
        assertTrue("<hidden>" in text)
    }
}
