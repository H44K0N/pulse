package dev.tischbein.pulse.update

import android.content.pm.PackageInstaller
import dev.tischbein.pulse.AppConstants
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The update's decisions without Android: what the server's answer means, what is offered, what may be installed. */
class UpdateRulesTest {
    private val sha = "ab".repeat(32)

    private fun body(
        code: Any = 7,
        name: String = "\"0.5.2\"",
        size: Any = 1234,
        hash: String = "\"$sha\"",
        apk: String = "\"/api/app/apk?v=7\"",
        notes: String = "\"Faster sync.\"",
    ) = """{"published": true, "version_code": $code, "version_name": $name, "notes": $notes, "size": $size, "sha256": $hash, "apk": $apk}"""

    // ---- The server's answer ------------------------------------------------------------

    @Test
    fun `a published build is read with what the file must be`() {
        val r = assertIs<ReleaseCheck.Published>(UpdateRules.parse(body())).release
        assertEquals(ServerRelease(7, "0.5.2", "Faster sync.", "/api/app/apk?v=7", ExpectedFile(1234, sha)), r)
    }

    @Test
    fun `nothing published`() {
        assertEquals(ReleaseCheck.NothingPublished, UpdateRules.parse("""{"published": false}"""))
    }

    @Test
    fun `an answer that is not a usable build offers nothing`() {
        val bad =
            listOf(
                "",
                "not json",
                "[]",
                "{}",
                """{"published": "yes"}""",
                body(code = "\"7\""),
                body(code = 0),
                body(code = -1),
                body(size = 0),
                body(size = "\"1234\""),
                body(size = UpdateRules.MAX_SIZE + 1),
                body(hash = "\"abc\""),
                body(hash = "null"),
                body(hash = "\"" + "g".repeat(64) + "\""),
                body(apk = "null"),
                // Only /api/app/apk on the configured server: never another path, host or scheme.
                body(apk = "\"https://elsewhere.example/api/app/apk?v=7\""),
                body(apk = "\"//elsewhere.example/api/app/apk\""),
                body(apk = "\"/api/app/apk/../../sessions\""),
                body(apk = "\"/api/app/apk?v=7&x=1\""),
                body(apk = "\"/api/sessions\""),
            )
        bad.forEach { assertIs<ReleaseCheck.Failed>(UpdateRules.parse(it), it) }
    }

    @Test
    fun `upper-case hash, a missing name and missing notes are fine`() {
        val r = assertIs<ReleaseCheck.Published>(UpdateRules.parse(body(name = "null", notes = "null", hash = "\"${sha.uppercase()}\"", apk = "\"/api/app/apk\""))).release
        assertEquals("version 7", r.versionName)
        assertEquals("", r.notes)
        assertEquals(sha, r.expected.sha256)
        assertEquals("/api/app/apk", r.apkPath)
    }

    // ---- Offer or not -------------------------------------------------------------------

    private val release = ServerRelease(7, "0.5.2", "n", "/api/app/apk?v=7", ExpectedFile(10, sha))

    @Test
    fun `only a higher versionCode is offered, never the same or a lower one`() {
        assertEquals(7, UpdateRules.offer(release, installedVersionCode = 6)?.versionCode)
        assertNull(UpdateRules.offer(release, installedVersionCode = 7))
        assertNull(UpdateRules.offer(release, installedVersionCode = 8))
    }

    // ---- When to ask the server ---------------------------------------------------------

    @Test
    fun `checks at most about once an hour, sooner again after a failure, never during a session`() {
        val hour = AppConstants.UPDATE_CHECK_INTERVAL_MS
        val retry = AppConstants.UPDATE_RETRY_AFTER_FAILURE_MS
        assertTrue(UpdateRules.checkDue(1_000, null, null, sessionRunning = false))
        assertFalse(UpdateRules.checkDue(1_000, null, null, sessionRunning = true))
        assertFalse(UpdateRules.checkDue(hour - 1, lastAnswerMs = 0, lastFailureMs = null, sessionRunning = false))
        assertTrue(UpdateRules.checkDue(hour, lastAnswerMs = 0, lastFailureMs = null, sessionRunning = false))
        assertFalse(UpdateRules.checkDue(retry - 1, lastAnswerMs = null, lastFailureMs = 0, sessionRunning = false))
        assertTrue(UpdateRules.checkDue(retry, lastAnswerMs = null, lastFailureMs = 0, sessionRunning = false))
    }

    // ---- The downloaded file ------------------------------------------------------------

    @Test
    fun `size and hash must both match what the server stated`() {
        val e = ExpectedFile(10, sha)
        assertNull(UpdateRules.fileProblem(e, 10, sha))
        assertNull(UpdateRules.fileProblem(e, 10, sha.uppercase()))
        assertEquals(UpdateTexts.DAMAGED, UpdateRules.fileProblem(e, 11, sha))
        assertEquals(UpdateTexts.DAMAGED, UpdateRules.fileProblem(e, 10, "cd".repeat(32)))
    }

    private val installed = InstalledApp("dev.tischbein.pulse", 6, setOf("cert-a"))

    @Test
    fun `the file must be this app, newer, and signed the same`() {
        assertNull(UpdateRules.apkProblem(ApkFacts("dev.tischbein.pulse", 7, setOf("cert-a")), installed))
        assertEquals(UpdateTexts.NOT_AN_APP, UpdateRules.apkProblem(null, installed))
        assertEquals(UpdateTexts.OTHER_APP, UpdateRules.apkProblem(ApkFacts("com.example.other", 7, setOf("cert-a")), installed))
        assertEquals(UpdateTexts.NOT_NEWER, UpdateRules.apkProblem(ApkFacts("dev.tischbein.pulse", 6, setOf("cert-a")), installed))
        assertEquals(UpdateTexts.NOT_NEWER, UpdateRules.apkProblem(ApkFacts("dev.tischbein.pulse", 5, setOf("cert-a")), installed))
        assertEquals(UpdateTexts.OTHER_SIGNER, UpdateRules.apkProblem(ApkFacts("dev.tischbein.pulse", 7, setOf("cert-b")), installed))
        assertEquals(UpdateTexts.OTHER_SIGNER, UpdateRules.apkProblem(ApkFacts("dev.tischbein.pulse", 7, setOf("cert-a", "cert-b")), installed))
    }

    @Test
    fun `an unreadable signer on either side is left to Android`() {
        assertNull(UpdateRules.apkProblem(ApkFacts("dev.tischbein.pulse", 7, emptySet()), installed))
        assertNull(UpdateRules.apkProblem(ApkFacts("dev.tischbein.pulse", 7, setOf("cert-b")), installed.copy(signers = emptySet())))
    }

    // ---- Android's answer ---------------------------------------------------------------

    @Test
    fun `session status codes`() {
        assertEquals(SessionOutcome.Confirm, UpdateRules.sessionOutcome(UpdateRules.STATUS_PENDING_USER_ACTION, null))
        assertEquals(SessionOutcome.Installed, UpdateRules.sessionOutcome(UpdateRules.STATUS_SUCCESS, null))
        assertEquals(SessionOutcome.Cancelled, UpdateRules.sessionOutcome(UpdateRules.STATUS_FAILURE_ABORTED, null))
        assertEquals(SessionOutcome.Failed(UpdateTexts.NO_SPACE), UpdateRules.sessionOutcome(UpdateRules.STATUS_FAILURE_STORAGE, "x"))
        assertEquals(SessionOutcome.Failed("Android blocked the install: Policy says no."), UpdateRules.sessionOutcome(UpdateRules.STATUS_FAILURE_BLOCKED, "Policy says no"))
        assertEquals(SessionOutcome.Failed("Android blocked the install."), UpdateRules.sessionOutcome(UpdateRules.STATUS_FAILURE_BLOCKED, "x".repeat(200)))
        listOf(
            UpdateRules.STATUS_FAILURE,
            UpdateRules.STATUS_FAILURE_INVALID,
            UpdateRules.STATUS_FAILURE_CONFLICT,
            UpdateRules.STATUS_FAILURE_INCOMPATIBLE,
            UpdateRules.STATUS_FAILURE_TIMEOUT,
            99,
        ).forEach { status ->
            val failed = assertIs<SessionOutcome.Failed>(UpdateRules.sessionOutcome(status, null))
            assertTrue(failed.reason.endsWith("."), failed.reason)
        }
    }

    /** The plain ints are Android's constants (compile-time constants, so readable in a JVM test). */
    @Test
    fun `status constants are Android's`() {
        assertEquals(PackageInstaller.STATUS_PENDING_USER_ACTION, UpdateRules.STATUS_PENDING_USER_ACTION)
        assertEquals(PackageInstaller.STATUS_SUCCESS, UpdateRules.STATUS_SUCCESS)
        assertEquals(PackageInstaller.STATUS_FAILURE, UpdateRules.STATUS_FAILURE)
        assertEquals(PackageInstaller.STATUS_FAILURE_BLOCKED, UpdateRules.STATUS_FAILURE_BLOCKED)
        assertEquals(PackageInstaller.STATUS_FAILURE_ABORTED, UpdateRules.STATUS_FAILURE_ABORTED)
        assertEquals(PackageInstaller.STATUS_FAILURE_INVALID, UpdateRules.STATUS_FAILURE_INVALID)
        assertEquals(PackageInstaller.STATUS_FAILURE_CONFLICT, UpdateRules.STATUS_FAILURE_CONFLICT)
        assertEquals(PackageInstaller.STATUS_FAILURE_STORAGE, UpdateRules.STATUS_FAILURE_STORAGE)
        assertEquals(PackageInstaller.STATUS_FAILURE_INCOMPATIBLE, UpdateRules.STATUS_FAILURE_INCOMPATIBLE)
        assertEquals(PackageInstaller.STATUS_FAILURE_TIMEOUT, UpdateRules.STATUS_FAILURE_TIMEOUT)
    }
}
