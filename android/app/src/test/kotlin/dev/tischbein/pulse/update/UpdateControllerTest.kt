package dev.tischbein.pulse.update

import dev.tischbein.pulse.AppConstants
import dev.tischbein.pulse.ui.UpdateButton
import dev.tischbein.pulse.ui.UpdateCard
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The update from the check to Android's installer, with a fake server and a fake Android:
 * when the banner shows, what a tap does, every check of the file, the "Install unknown apps"
 * detour, Android's answers, and above all: nothing while a session runs.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UpdateControllerTest {
    @get:Rule val tmp = TemporaryFolder()

    private val apkBytes = ByteArray(3000) { (it * 7).toByte() }
    private val release = ServerRelease(7, "0.5.2", "Faster sync.", "/api/app/apk?v=7", ExpectedFile(apkBytes.size.toLong(), sha(apkBytes)))
    private val goodFacts = ApkFacts("dev.tischbein.pulse", 7, setOf("cert-a"))

    private class FakeServer(var answer: ReleaseCheck, var body: ByteArray) : UpdateServer {
        var checks = 0
        var downloads = 0
        var beforeDownload: () -> Unit = {}

        override suspend fun check(): ReleaseCheck {
            checks++
            return answer
        }

        override suspend fun download(
            offer: UpdateOffer,
            dest: File,
            progress: (Long) -> Unit,
        ): DownloadResult {
            downloads++
            beforeDownload()
            return HttpApkDownloader.copyHashing(body.inputStream(), dest, offer.expected.size, progress)
        }
    }

    private class FakeAndroid(var facts: ApkFacts?) : UpdatePlatform {
        var canInstall = true
        var installed = InstalledApp("dev.tischbein.pulse", 6, setOf("cert-a"))
        val installs = mutableListOf<File>()
        var installProblem: String? = null
        var abandoned = 0

        override fun canInstallPackages() = canInstall

        override fun installed() = installed

        override fun inspect(file: File) = facts

        override fun install(file: File): String? {
            installs += file
            return installProblem
        }

        override fun abandonPending() {
            abandoned++
        }
    }

    private class Memory : UpdateMemory {
        override var permissionAskedFor = 0L
    }

    private inner class Env(scope: TestScope) {
        val server = FakeServer(ReleaseCheck.Published(release), apkBytes)
        val android = FakeAndroid(goodFacts)
        val memory = Memory()
        val gate = SessionGate()
        var now = 1_000_000L
        val dir = File(tmp.root, "update")
        val controller =
            UpdateController(scope.backgroundScope, StandardTestDispatcher(scope.testScheduler), dir, server, android, memory, gate) { now }
        val phase get() = controller.state.value.phase
        val card get() = UpdateCard.of(controller.state.value, gate.running.value)
    }

    private fun test(block: suspend TestScope.(Env) -> Unit) = runTest { block(Env(this)) }

    // ---- Offer or not -------------------------------------------------------------------

    @Test
    fun `a newer build on the server shows the banner, nothing is downloaded before the tap`() =
        test { e ->
            e.controller.checkIfDue()
            assertEquals(7, e.controller.state.value.offer?.versionCode)
            val card = assertNotNull(e.card)
            assertEquals("Update to 0.5.2", card.title)
            assertEquals("Faster sync.", card.notes)
            assertEquals(UpdateButton.UPDATE, card.button)
            assertEquals(0, e.server.downloads)
            assertEquals(emptyList(), e.android.installs)
        }

    @Test
    fun `the same or an older build is not offered`() =
        test { e ->
            e.android.installed = e.android.installed.copy(versionCode = 7)
            e.controller.checkIfDue()
            assertNull(e.card)
        }

    @Test
    fun `nothing published offers nothing and removes an old offer and its file`() =
        test { e ->
            e.controller.checkIfDue()
            e.dir.mkdirs()
            File(e.dir, "pulse-7.apk").writeBytes(apkBytes)
            e.server.answer = ReleaseCheck.NothingPublished
            e.now += AppConstants.UPDATE_CHECK_INTERVAL_MS
            e.controller.checkIfDue()
            assertNull(e.card)
            assertEquals(emptyList(), e.dir.listFiles()!!.toList())
        }

    @Test
    fun `sync not set up means no banner and no throttling`() =
        test { e ->
            e.server.answer = ReleaseCheck.NotConfigured
            e.controller.checkIfDue()
            assertNull(e.card)
            // Set up a minute later: the next time Pulse comes to the front it asks right away.
            e.server.answer = ReleaseCheck.Published(release)
            e.now += 60_000
            e.controller.checkIfDue()
            assertNotNull(e.card)
        }

    @Test
    fun `offline or a server error changes nothing on screen and is retried a few minutes later`() =
        test { e ->
            e.server.answer = ReleaseCheck.Failed(UpdateTexts.OFFLINE)
            e.controller.checkIfDue()
            assertNull(e.card)
            assertEquals(UpdatePhase.Ready, e.phase)

            e.server.answer = ReleaseCheck.Published(release)
            e.controller.checkIfDue()
            assertEquals(1, e.server.checks, "not again right after a failure")
            e.now += AppConstants.UPDATE_RETRY_AFTER_FAILURE_MS
            e.controller.checkIfDue()
            assertNotNull(e.card)

            e.server.answer = ReleaseCheck.Failed(UpdateTexts.OFFLINE)
            e.now += AppConstants.UPDATE_CHECK_INTERVAL_MS
            e.controller.checkIfDue() // offline again: the offer from before stays, no error shown
            val card = assertNotNull(e.card)
            assertNull(card.status)
            assertEquals(3, e.server.checks)
        }

    @Test
    fun `asks the server at most about once an hour`() =
        test { e ->
            e.controller.checkIfDue()
            e.now += AppConstants.UPDATE_CHECK_INTERVAL_MS - 1
            e.controller.checkIfDue()
            assertEquals(1, e.server.checks)
            e.now += 1
            e.controller.checkIfDue()
            assertEquals(2, e.server.checks)
        }

    @Test
    fun `an edited server forgets the offer and asks again next time`() =
        test { e ->
            e.controller.checkIfDue()
            e.controller.onServerChanged()
            runCurrent()
            assertNull(e.card)
            e.controller.checkIfDue()
            assertEquals(2, e.server.checks)
            assertNotNull(e.card)
        }

    // ---- Never during a session ---------------------------------------------------------

    @Test
    fun `no check, no banner and no download while a session runs`() =
        test { e ->
            e.controller.checkIfDue()
            assertTrue(e.gate.tryStartSession())
            assertNull(e.card, "no banner while the session is being set up")
            e.gate.serviceAlive(true)
            assertNull(e.card)
            e.controller.tap()
            runCurrent()
            assertEquals(0, e.server.downloads)
            assertEquals(UpdatePhase.Ready, e.phase)
            e.now += AppConstants.UPDATE_CHECK_INTERVAL_MS
            e.controller.checkIfDue()
            assertEquals(1, e.server.checks)
            // Session over (service gone): the banner is back.
            e.gate.serviceAlive(false)
            assertNotNull(e.card)
        }

    @Test
    fun `a download running when a session starts finishes quietly and waits for his tap afterwards`() =
        test { e ->
            e.controller.checkIfDue()
            e.server.beforeDownload = {
                e.gate.tryStartSession()
                e.gate.serviceAlive(true)
            }
            e.controller.tap()
            runCurrent()
            assertEquals(UpdatePhase.Downloaded, e.phase)
            assertEquals(emptyList(), e.android.installs, "never installed during a session")
            assertTrue(File(e.dir, "pulse-7.apk").isFile)
            assertNull(e.card)

            e.gate.serviceAlive(false)
            val card = assertNotNull(e.card)
            assertEquals(UpdateTexts.DOWNLOADED, card.status)
            assertEquals(UpdateButton.UPDATE, card.button)
            runCurrent()
            assertEquals(emptyList(), e.android.installs, "not by itself after the session either")

            e.controller.tap()
            runCurrent()
            assertEquals(1, e.server.downloads, "the finished file is not downloaded again")
            assertEquals(1, e.android.installs.size)
        }

    @Test
    fun `Start waits while the install is with Android, and the install never begins while Start is pending`() =
        test { e ->
            e.controller.checkIfDue()
            e.controller.tap()
            runCurrent()
            assertEquals(UpdatePhase.Installing, e.phase)
            assertFalse(e.gate.tryStartSession(), "Start does nothing while Pulse is being replaced")
            assertEquals(UpdateTexts.START_WAITS, UpdateCard.startBlocked(e.controller.state.value))
            e.controller.onSessionStatus(UpdateRules.STATUS_FAILURE_ABORTED, null, null)
            assertTrue(e.gate.tryStartSession())
            assertFalse(e.gate.tryBeginInstall())
        }

    // ---- The download and the file ------------------------------------------------------

    @Test
    fun `tap downloads, checks and hands the file to Android`() =
        test { e ->
            e.controller.checkIfDue()
            e.controller.tap()
            assertEquals(UpdatePhase.Downloading(0), e.phase)
            runCurrent()
            assertEquals(UpdatePhase.Installing, e.phase)
            assertEquals(listOf(File(e.dir, "pulse-7.apk")), e.android.installs)
            assertEquals(apkBytes.toList(), e.android.installs.single().readBytes().toList())
            assertNull(UpdateCard.of(e.controller.state.value, false)!!.button.label)
        }

    @Test
    fun `progress is shown in percent`() =
        test { e ->
            e.controller.checkIfDue()
            val seen = mutableListOf<UpdatePhase>()
            e.server.beforeDownload = { seen += e.phase }
            e.controller.tap()
            runCurrent()
            assertEquals(UpdatePhase.Downloading(0), seen.single())
            assertEquals("Downloading… 42 %", UpdateCard.of(UpdateView(e.controller.state.value.offer, UpdatePhase.Downloading(42)), false)!!.status)
        }

    @Test
    fun `one download at a time`() =
        test { e ->
            e.controller.checkIfDue()
            e.controller.tap()
            e.controller.tap()
            e.controller.tap()
            runCurrent()
            assertEquals(1, e.server.downloads)
        }

    @Test
    fun `a wrong hash deletes the file and says so, Try again starts over`() =
        test { e ->
            e.controller.checkIfDue()
            e.server.body = apkBytes.copyOf().also { it[10] = (it[10] + 1).toByte() }
            e.controller.tap()
            runCurrent()
            assertEquals(UpdatePhase.Failed(UpdateTexts.DAMAGED), e.phase)
            assertEquals(emptyList(), e.dir.listFiles()!!.toList())
            assertEquals(emptyList(), e.android.installs)
            val card = assertNotNull(e.card)
            assertEquals(UpdateButton.TRY_AGAIN, card.button)
            assertTrue(card.problem)

            e.server.body = apkBytes
            e.controller.tap()
            runCurrent()
            assertEquals(UpdatePhase.Installing, e.phase)
        }

    @Test
    fun `a wrong size is refused`() =
        test { e ->
            e.controller.checkIfDue()
            e.server.body = apkBytes + byteArrayOf(1)
            e.controller.tap()
            runCurrent()
            assertEquals(UpdatePhase.Failed(UpdateTexts.DAMAGED), e.phase)
            e.server.body = apkBytes.copyOf(apkBytes.size - 1)
            e.controller.tap()
            runCurrent()
            assertEquals(UpdatePhase.Failed(UpdateTexts.DAMAGED), e.phase)
            assertEquals(emptyList(), e.dir.listFiles()!!.toList())
            assertEquals(emptyList(), e.android.installs)
        }

    @Test
    fun `a file that is another app, not newer, or signed by someone else is deleted`() =
        test { e ->
            e.controller.checkIfDue()
            val cases =
                mapOf(
                    null to UpdateTexts.NOT_AN_APP,
                    goodFacts.copy(packageName = "com.example.other") to UpdateTexts.OTHER_APP,
                    goodFacts.copy(versionCode = 6) to UpdateTexts.NOT_NEWER,
                    goodFacts.copy(versionCode = 5) to UpdateTexts.NOT_NEWER,
                    goodFacts.copy(signers = setOf("cert-b")) to UpdateTexts.OTHER_SIGNER,
                )
            cases.forEach { (facts, reason) ->
                e.android.facts = facts
                e.controller.tap()
                runCurrent()
                assertEquals(UpdatePhase.Failed(reason), e.phase)
                assertEquals(emptyList(), e.dir.listFiles()!!.toList())
            }
            assertEquals(emptyList(), e.android.installs)
        }

    @Test
    fun `unreadable signatures are left to Android`() =
        test { e ->
            e.controller.checkIfDue()
            e.android.facts = goodFacts.copy(signers = emptySet())
            e.controller.tap()
            runCurrent()
            assertEquals(1, e.android.installs.size)
        }

    @Test
    fun `a finished file of the right hash is not downloaded again`() =
        test { e ->
            e.controller.checkIfDue()
            e.dir.mkdirs()
            File(e.dir, "pulse-7.apk").writeBytes(apkBytes)
            File(e.dir, "pulse-5.apk").writeBytes(byteArrayOf(1)) // leftovers go
            e.controller.tap()
            runCurrent()
            assertEquals(0, e.server.downloads)
            assertEquals(1, e.android.installs.size)
            assertEquals(listOf("pulse-7.apk"), e.dir.listFiles()!!.map { it.name })
        }

    @Test
    fun `a failed download says why, in one sentence`() =
        test { e ->
            e.controller.checkIfDue()
            val offline =
                object : UpdateServer {
                    override suspend fun check() = ReleaseCheck.Published(release)

                    override suspend fun download(
                        offer: UpdateOffer,
                        dest: File,
                        progress: (Long) -> Unit,
                    ) = DownloadResult.Failed(UpdateTexts.OFFLINE)
                }
            val c = UpdateController(backgroundScope, StandardTestDispatcher(testScheduler), e.dir, offline, e.android, e.memory, e.gate) { e.now }
            c.checkIfDue()
            c.tap()
            runCurrent()
            assertEquals(UpdatePhase.Failed(UpdateTexts.OFFLINE), c.state.value.phase)
        }

    // ---- "Install unknown apps" ---------------------------------------------------------

    @Test
    fun `permission missing - ask - he allows it - carries on by itself`() =
        test { e ->
            e.controller.checkIfDue()
            e.android.canInstall = false
            e.controller.tap()
            assertEquals(UpdatePhase.AskPermission, e.phase)
            val card = assertNotNull(e.card)
            assertEquals(UpdateTexts.PERMISSION, card.status)
            assertEquals(UpdateButton.OPEN_SETTINGS, card.button)
            assertEquals(0, e.server.downloads)

            // Back without allowing it: still asking, nothing downloaded.
            e.controller.continueAfterPermission()
            runCurrent()
            assertEquals(UpdatePhase.AskPermission, e.phase)

            e.android.canInstall = true
            e.controller.onForeground()
            runCurrent()
            assertEquals(1, e.android.installs.size)
            assertEquals(0L, e.memory.permissionAskedFor)
        }

    @Test
    fun `the ask survives Android stopping Pulse while he is in Settings`() =
        test { e ->
            e.controller.checkIfDue()
            e.android.canInstall = false
            e.controller.tap()
            assertEquals(7L, e.memory.permissionAskedFor)

            // A new process: a new controller, same memory.
            val c2 = UpdateController(backgroundScope, StandardTestDispatcher(testScheduler), e.dir, e.server, e.android, e.memory, e.gate) { e.now }
            c2.checkIfDue()
            assertEquals(UpdatePhase.AskPermission, c2.state.value.phase)
            e.android.canInstall = true
            c2.onForeground()
            runCurrent()
            assertEquals(1, e.android.installs.size)
        }

    @Test
    fun `allowed during a session - waits until it is over`() =
        test { e ->
            e.controller.checkIfDue()
            e.android.canInstall = false
            e.controller.tap()
            e.gate.tryStartSession()
            e.android.canInstall = true
            e.controller.onForeground()
            runCurrent()
            assertEquals(0, e.server.downloads)
            e.gate.startFailed()
            e.controller.onForeground()
            runCurrent()
            assertEquals(1, e.android.installs.size)
        }

    // ---- Android's answers --------------------------------------------------------------

    @Test
    fun `pending user action launches Android's confirmation`() =
        test { e ->
            e.controller.checkIfDue()
            e.controller.tap()
            runCurrent()
            val intent = Any()
            e.controller.onSessionStatus(UpdateRules.STATUS_PENDING_USER_ACTION, null, intent)
            assertEquals(intent, e.controller.confirm.value)
            e.controller.confirmShown()
            assertNull(e.controller.confirm.value)
            assertEquals(UpdatePhase.Installing, e.phase)
        }

    @Test
    fun `pending user action without an intent is a failure`() =
        test { e ->
            e.controller.checkIfDue()
            e.controller.tap()
            runCurrent()
            e.controller.onSessionStatus(UpdateRules.STATUS_PENDING_USER_ACTION, null, null)
            assertEquals(UpdatePhase.Failed(UpdateTexts.NO_CONFIRM), e.phase)
        }

    @Test
    fun `cancelled in Android's dialog - back to the banner, the file is kept`() =
        test { e ->
            e.controller.checkIfDue()
            e.controller.tap()
            runCurrent()
            e.controller.onSessionStatus(UpdateRules.STATUS_FAILURE_ABORTED, null, null)
            assertEquals(UpdatePhase.Ready, e.phase)
            assertEquals(UpdateButton.UPDATE, e.card!!.button)
            assertTrue(File(e.dir, "pulse-7.apk").isFile)
            e.controller.tap()
            runCurrent()
            assertEquals(1, e.server.downloads)
            assertEquals(2, e.android.installs.size)
        }

    @Test
    fun `a failed install says why`() =
        test { e ->
            e.controller.checkIfDue()
            e.controller.tap()
            runCurrent()
            e.controller.onSessionStatus(UpdateRules.STATUS_FAILURE_STORAGE, null, null)
            assertEquals(UpdatePhase.Failed(UpdateTexts.NO_SPACE), e.phase)
            assertEquals(UpdateButton.TRY_AGAIN, e.card!!.button)
        }

    @Test
    fun `a session that could not even be started says so`() =
        test { e ->
            e.controller.checkIfDue()
            e.android.installProblem = UpdateTexts.START_FAILED
            e.controller.tap()
            runCurrent()
            assertEquals(UpdatePhase.Failed(UpdateTexts.START_FAILED), e.phase)
            assertTrue(e.gate.tryStartSession(), "Start is free again")
        }

    @Test
    fun `an install that never answers is abandoned after the timeout, Start is free again`() =
        test { e ->
            e.controller.checkIfDue()
            e.controller.tap()
            runCurrent()
            e.controller.onSessionStatus(UpdateRules.STATUS_PENDING_USER_ACTION, null, Any())
            e.controller.confirmShown()
            advanceTimeBy(AppConstants.UPDATE_INSTALL_TIMEOUT_MS - 1)
            assertEquals(UpdatePhase.Installing, e.phase)
            advanceTimeBy(2)
            assertEquals(UpdatePhase.Downloaded, e.phase)
            assertEquals(1, e.android.abandoned)
            assertTrue(e.gate.tryStartSession())
            // A late answer from Android for the abandoned session is not acted on.
            e.controller.onSessionStatus(UpdateRules.STATUS_PENDING_USER_ACTION, null, Any())
            assertNull(e.controller.confirm.value)
            assertEquals(2, e.android.abandoned)
        }

    @Test
    fun `answers for a session nobody started now are ignored`() =
        test { e ->
            e.controller.checkIfDue()
            e.controller.onSessionStatus(UpdateRules.STATUS_FAILURE_STORAGE, null, null)
            assertEquals(UpdatePhase.Ready, e.phase)
            e.controller.onSessionStatus(UpdateRules.STATUS_PENDING_USER_ACTION, null, Any())
            assertNull(e.controller.confirm.value)
        }

    private fun sha(bytes: ByteArray) = HttpApkDownloader.hex(MessageDigest.getInstance("SHA-256").digest(bytes))
}
