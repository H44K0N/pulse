package dev.tischbein.pulse.update

import dev.tischbein.pulse.AppConstants
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/** What the server stated about the file (`GET /api/app`). The download must match both. */
data class ExpectedFile(val size: Long, val sha256: String)

/** The published build as `GET /api/app` describes it (server/README.md). */
data class ServerRelease(
    val versionCode: Long,
    val versionName: String,
    val notes: String,
    val apkPath: String,
    val expected: ExpectedFile,
)

/** One update check against the configured server. */
sealed interface ReleaseCheck {
    /** No server URL or token in Settings: there is no update check at all. */
    data object NotConfigured : ReleaseCheck

    /** The server answered `{"published": false}`. */
    data object NothingPublished : ReleaseCheck

    data class Published(val release: ServerRelease) : ReleaseCheck

    /** Offline, wrong token, server error, an answer that is not ours: try again later, quietly. */
    data class Failed(val reason: String) : ReleaseCheck
}

/** A newer Pulse the server offers. */
data class UpdateOffer(
    val versionCode: Long,
    val versionName: String,
    val notes: String,
    /** `/api/app/apk?v=N`, on the configured server only (see [UpdateRules.apkPathAllowed]). */
    val apkPath: String,
    val expected: ExpectedFile,
)

/** The installed app, as Android knows it. [signers]: certificate SHA-256s, empty when not readable. */
data class InstalledApp(val packageName: String, val versionCode: Long, val signers: Set<String>)

/** What a downloaded file says it is. [signers] empty: the phone did not hand them out. */
data class ApkFacts(val packageName: String, val versionCode: Long, val signers: Set<String>)

/** Every decision of the in-app update, without Android (JVM tests: UpdateRulesTest). */
object UpdateRules {
    /** Larger than any Pulse build will be; a bigger stated size is not believed. */
    const val MAX_SIZE: Long = 200L * 1024 * 1024

    private val SHA256 = Regex("[0-9a-f]{64}")

    /** The only download path: `/api/app/apk`, optionally with `?v=<digits>`. Nothing else. */
    private val APK_PATH = Regex("/api/app/apk(\\?v=[0-9]{1,12})?")

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * The body of `GET /api/app` (status 200). `{"published": false}` -> [ReleaseCheck.NothingPublished];
     * anything that does not describe a usable file -> [ReleaseCheck.Failed] (nothing is offered).
     */
    fun parse(body: String): ReleaseCheck {
        val o = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return ReleaseCheck.Failed(NOT_PULSE)
        val published = (o["published"] as? JsonPrimitive)?.booleanOrNull ?: return ReleaseCheck.Failed(NOT_PULSE)
        if (!published) return ReleaseCheck.NothingPublished
        val code = o.long("version_code")
        val size = o.long("size")
        val sha = o.string("sha256")?.lowercase()
        val apk = o.string("apk")
        val name = o.string("version_name")?.trim()
        if (code == null || code <= 0 || size == null || size !in 1..MAX_SIZE || sha == null || !SHA256.matches(sha) || apk == null || !apkPathAllowed(apk)) {
            return ReleaseCheck.Failed(NOT_PULSE)
        }
        return ReleaseCheck.Published(
            ServerRelease(
                versionCode = code,
                versionName = name?.takeIf { it.isNotEmpty() && it.length <= MAX_NAME } ?: "version $code",
                notes = o.string("notes")?.trim()?.take(MAX_NOTES).orEmpty(),
                apkPath = apk,
                expected = ExpectedFile(size, sha),
            ),
        )
    }

    /** The download path is `/api/app/apk` on the configured server: no other path, host or scheme. */
    fun apkPathAllowed(path: String): Boolean = APK_PATH.matches(path)

    /** The update to offer: only a higher versionCode than the installed one (never a downgrade). */
    fun offer(
        release: ServerRelease,
        installedVersionCode: Long,
    ): UpdateOffer? {
        if (release.versionCode <= installedVersionCode) return null
        return UpdateOffer(release.versionCode, release.versionName, release.notes, release.apkPath, release.expected)
    }

    /**
     * Whether the app should ask the server now (it came to the front). Never during a session,
     * at most about once an hour after an answer, and a few minutes after a failed try (offline).
     */
    fun checkDue(
        nowMs: Long,
        lastAnswerMs: Long?,
        lastFailureMs: Long?,
        sessionRunning: Boolean,
    ): Boolean =
        when {
            sessionRunning -> false
            lastAnswerMs != null && nowMs - lastAnswerMs in 0 until AppConstants.UPDATE_CHECK_INTERVAL_MS -> false
            lastFailureMs != null && nowMs - lastFailureMs in 0 until AppConstants.UPDATE_RETRY_AFTER_FAILURE_MS -> false
            else -> true
        }

    /** Size and SHA-256 of the downloaded file against what the server stated; null when equal. */
    fun fileProblem(
        expected: ExpectedFile,
        size: Long,
        sha256: String,
    ): String? = if (size == expected.size && sha256.equals(expected.sha256, ignoreCase = true)) null else UpdateTexts.DAMAGED

    /**
     * The file as an app: this package, a higher versionCode than the installed one, signed with
     * the installed app's certificate(s). Null when it may be handed to Android.
     *
     * Where the phone does not hand out the certificates of the file or of the installed app
     * (an empty set), the file goes on to Android, which refuses an update signed by anyone else
     * by itself.
     */
    fun apkProblem(
        facts: ApkFacts?,
        installed: InstalledApp,
    ): String? =
        when {
            facts == null -> UpdateTexts.NOT_AN_APP
            facts.packageName != installed.packageName -> UpdateTexts.OTHER_APP
            facts.versionCode <= installed.versionCode -> UpdateTexts.NOT_NEWER
            facts.signers.isNotEmpty() && installed.signers.isNotEmpty() && facts.signers != installed.signers -> UpdateTexts.OTHER_SIGNER
            else -> null
        }

    /** What a PackageInstaller session status (PackageInstaller.STATUS_*) means for the banner. */
    fun sessionOutcome(
        status: Int,
        message: String?,
    ): SessionOutcome =
        when (status) {
            STATUS_PENDING_USER_ACTION -> SessionOutcome.Confirm
            STATUS_SUCCESS -> SessionOutcome.Installed
            STATUS_FAILURE_ABORTED -> SessionOutcome.Cancelled
            STATUS_FAILURE_STORAGE -> SessionOutcome.Failed(UpdateTexts.NO_SPACE)
            STATUS_FAILURE_INCOMPATIBLE -> SessionOutcome.Failed("This phone cannot run this version, so Android did not install it.")
            STATUS_FAILURE_CONFLICT -> SessionOutcome.Failed("Android refused it because it conflicts with the Pulse you have.")
            STATUS_FAILURE_INVALID -> SessionOutcome.Failed("Android found the file invalid and did not install it.")
            STATUS_FAILURE_BLOCKED ->
                SessionOutcome.Failed("Android blocked the install" + (message?.trim()?.takeIf { it.isNotEmpty() && it.length <= 80 }?.let { ": $it." } ?: "."))
            STATUS_FAILURE_TIMEOUT -> SessionOutcome.Failed("Android took too long to install it.")
            else -> SessionOutcome.Failed("Android could not install it.")
        }

    // PackageInstaller.STATUS_* values (stable platform constants; plain ints so JVM tests need no Android).
    const val STATUS_PENDING_USER_ACTION = -1
    const val STATUS_SUCCESS = 0
    const val STATUS_FAILURE = 1
    const val STATUS_FAILURE_BLOCKED = 2
    const val STATUS_FAILURE_ABORTED = 3
    const val STATUS_FAILURE_INVALID = 4
    const val STATUS_FAILURE_CONFLICT = 5
    const val STATUS_FAILURE_STORAGE = 6
    const val STATUS_FAILURE_INCOMPATIBLE = 7
    const val STATUS_FAILURE_TIMEOUT = 8

    private const val NOT_PULSE = "The server's answer is not a Pulse update."
    private const val MAX_NAME = 40
    private const val MAX_NOTES = 500

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
}

sealed interface SessionOutcome {
    /** Android needs his confirmation: launch its activity. */
    data object Confirm : SessionOutcome

    /** Installed (rarely seen: Android stops the old app first). */
    data object Installed : SessionOutcome

    /** He said no in Android's dialog: back to the banner, the file is kept. */
    data object Cancelled : SessionOutcome

    data class Failed(val reason: String) : SessionOutcome
}

/** The sentences the banner shows. */
object UpdateTexts {
    const val DAMAGED = "The download was damaged or changed on the way, so it was deleted."
    const val NOT_AN_APP = "The file is not an Android app, so it was deleted."
    const val OTHER_APP = "The file is a different app, not Pulse, so it was deleted."
    const val NOT_NEWER = "The file is not newer than the Pulse you have, so it was deleted."
    const val OTHER_SIGNER = "The file is not signed like your Pulse, so it was deleted."
    const val NO_SPACE = "There is not enough free space on the phone for the update."
    const val OFFLINE = "The download stopped: no connection to the server."
    const val WRONG_TOKEN = "The server did not accept the API token. Check it in Settings › Sync."
    const val NO_SERVER = "Sync is not set up any more, so there is nothing to download from."
    const val NO_CONFIRM = "Android did not show its confirmation."
    const val START_FAILED = "Android could not start the install."
    const val PERMISSION =
        "Android asks once whether Pulse may install apps. Pulse uses it only for its own updates from your server."
    const val DOWNLOADING = "Downloading…"
    const val CHECKING = "Checking the file…"
    const val INSTALLING = "Installing. Android may ask you to confirm; then Pulse closes and the new version is in place."
    const val DOWNLOADED = "Downloaded. Tap Update to install it."

    /** Under Train's Start while the update is being installed (Start waits). */
    const val START_WAITS = "Pulse is installing its update and closes in a moment. Start a session after that."

    fun serverError(code: Int) = "The server did not hand out the file (error $code)."
}
