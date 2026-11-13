package dev.tischbein.pulse.data.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import dev.tischbein.pulse.sensor.sim.SimProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** DataStore works on the plain JVM, so this runs against a real preferences file. */
class SettingsRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val repo by lazy {
        SettingsRepository(PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "settings.preferences_pb") })
    }

    @After fun tearDown() = scope.cancel()

    @Test
    fun `empty store gives defaults`() =
        runBlocking {
            assertEquals(AppSettings(), repo.current())
        }

    @Test
    fun `save and load round trip`() =
        runBlocking {
            val edited =
                AppSettings(
                    age = 30,
                    hrMaxOverride = 192,
                    workMin = 5,
                    workZone = ZoneSetting(86, 93),
                    serverUrl = "https://pulse.example.org",
                    apiToken = "token",
                    simulatorEnabled = true,
                    simulatorProfile = SimProfile.FADING_4X4,
                )
            repo.save(edited)
            assertEquals(edited, repo.current())
        }

    @Test
    fun `device id is stored separately and survives a settings save`() =
        runBlocking {
            repo.setDeviceId("A1B2C3D4")
            repo.save(repo.current().copy(age = 40))
            assertEquals("A1B2C3D4", repo.current().deviceId)
            repo.setDeviceId(null)
            assertEquals(null, repo.current().deviceId)
        }

    @Test
    fun `learned hrMax raises but never lowers`() =
        runBlocking {
            assertEquals(190, repo.current().hrMax)
            assertFalse(repo.applyLearnedHrMax(185)) // below the estimate: ignored
            assertEquals(190, repo.current().hrMax)
            assertTrue(repo.applyLearnedHrMax(194))
            assertEquals(194, repo.current().hrMax)
            assertTrue(repo.current().hrMaxLearned)
            assertFalse(repo.applyLearnedHrMax(192))
            assertEquals(194, repo.current().hrMax)
        }
}
