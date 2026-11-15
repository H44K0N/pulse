package dev.tischbein.pulse.update

import dev.tischbein.pulse.data.settings.AppSettings
import dev.tischbein.pulse.data.sync.UrlConnectionHttp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.junit.Before
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The update against a REAL running Pulse server, through the app's own HTTP and download code.
 * Runs only when PULSE_E2E_URL and PULSE_E2E_TOKEN are set (skipped otherwise), e.g.:
 *
 *   PULSE_E2E_URL=http://127.0.0.1:8461 PULSE_E2E_TOKEN=... PULSE_E2E_APK_SHA256=... \
 *     ./gradlew :app:testDebugUnitTest --tests '*UpdateE2ETest*'
 *
 * The server must publish a build with a versionCode above this app's. The published test file
 * is random bytes, not a real APK: the test checks size and hash and then expects the app to
 * refuse it as "not an Android app" (and delete it), which is as far as a JVM test can go.
 */
class UpdateE2ETest {
    private val url = System.getenv("PULSE_E2E_URL")?.trim()?.trimEnd('/').orEmpty()
    private val token = System.getenv("PULSE_E2E_TOKEN")?.trim().orEmpty()
    private val expectedSha = System.getenv("PULSE_E2E_APK_SHA256")?.trim()?.lowercase()

    @Before
    fun onlyWithAServer() {
        assumeTrue("set PULSE_E2E_URL and PULSE_E2E_TOKEN to run against a real server", url.isNotEmpty() && token.isNotEmpty())
    }

    private val settings get() = AppSettings(serverUrl = url, apiToken = token)
    private val server get() = HttpUpdateServer({ settings }, UrlConnectionHttp(), HttpApkDownloader(), allowLocalHttp = true)

    @Test
    fun `the published update downloads through the app and matches the server's size and hash`() =
        runBlocking(Dispatchers.IO) {
            val release = assertIs<ReleaseCheck.Published>(server.check()).release
            val offer = assertNotNull(UpdateRules.offer(release, installedVersionCode = 6), "the server must publish a build newer than versionCode 6")
            expectedSha?.let { assertEquals(it, offer.expected.sha256, "the server's hash is the file's") }

            // The download code itself, straight to a file.
            val dir = File("build/e2e-update").apply { deleteRecursively() }
            val file = File(dir, "direct.part")
            val r = assertIs<DownloadResult.Done>(server.download(offer, file) {})
            assertEquals(offer.expected.size, file.length())
            assertEquals(offer.expected.size, r.size)
            assertEquals(offer.expected.sha256, r.sha256)
            assertEquals(offer.expected.sha256, HttpApkDownloader.sha256(file))
            expectedSha?.let { assertEquals(it, r.sha256) }
            assertNull(UpdateRules.fileProblem(offer.expected, r.size, r.sha256))

            // The whole flow from the tap: download, size and hash pass, then the file is not an app.
            val android =
                object : UpdatePlatform {
                    val installs = mutableListOf<File>()

                    override fun canInstallPackages() = true

                    override fun installed() = InstalledApp("dev.tischbein.pulse", 6, emptySet())

                    override fun inspect(file: File): ApkFacts? = null // random bytes: no package in it

                    override fun install(file: File): String? {
                        installs += file
                        return null
                    }

                    override fun abandonPending() = Unit
                }
            val memory =
                object : UpdateMemory {
                    override var permissionAskedFor = 0L
                }
            val updateDir = File(dir, "update")
            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            try {
                val controller = UpdateController(scope, Dispatchers.IO, updateDir, server, android, memory, SessionGate()) { 0L }
                controller.checkIfDue()
                assertEquals(release.versionCode, controller.state.value.offer?.versionCode)
                controller.tap()
                withTimeout(30_000) {
                    while (controller.state.value.phase !is UpdatePhase.Failed) delay(50)
                }
                assertEquals(UpdatePhase.Failed(UpdateTexts.NOT_AN_APP), controller.state.value.phase)
            } finally {
                scope.cancel()
            }
            assertEquals(emptyList(), android.installs)
            assertEquals(emptyList(), updateDir.listFiles()!!.toList(), "the refused file is deleted")
        }
}
