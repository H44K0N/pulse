package dev.tischbein.pulse.update

import dev.tischbein.pulse.AppConstants
import dev.tischbein.pulse.data.settings.AppSettings
import dev.tischbein.pulse.data.sync.ServerUrl
import dev.tischbein.pulse.data.sync.SyncHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest

sealed interface DownloadResult {
    /** The whole body is in the file: its size and SHA-256 (lower-case hex). */
    data class Done(val size: Long, val sha256: String) : DownloadResult

    data class Failed(val reason: String) : DownloadResult
}

/**
 * The configured server's update routes (server/README.md), behind an interface for the
 * controller's tests. The only server is the one in Settings › Sync; without it there is no
 * update check at all.
 */
interface UpdateServer {
    /** `GET /api/app`. Never throws. */
    suspend fun check(): ReleaseCheck

    /** `GET /api/app/apk` into [dest]; [progress] gets the bytes so far. Never throws. */
    suspend fun download(
        offer: UpdateOffer,
        dest: File,
        progress: (Long) -> Unit,
    ): DownloadResult
}

/**
 * [UpdateServer] on the sync settings: the same server URL and bearer token as the upload, read
 * fresh for every request (an edited token applies at once), and the same URL rules
 * ([ServerUrl]: HTTPS; plain http only to the developer's own machine in debug builds).
 */
class HttpUpdateServer(
    private val settings: suspend () -> AppSettings,
    private val http: SyncHttp,
    private val downloader: ApkDownloader,
    private val allowLocalHttp: Boolean,
) : UpdateServer {
    override suspend fun check(): ReleaseCheck {
        val s = settings()
        if (!s.syncConfigured(allowLocalHttp)) return ReleaseCheck.NotConfigured
        return withContext(Dispatchers.IO) {
            try {
                val r = http.get(ServerUrl.endpoint(s.serverUrl, APP), s.apiToken)
                when (r.code) {
                    HttpURLConnection.HTTP_OK -> UpdateRules.parse(r.body)
                    // A server from before the update routes: nothing to offer, quietly.
                    HttpURLConnection.HTTP_NOT_FOUND -> ReleaseCheck.NothingPublished
                    HttpURLConnection.HTTP_UNAUTHORIZED, HttpURLConnection.HTTP_FORBIDDEN -> ReleaseCheck.Failed(UpdateTexts.WRONG_TOKEN)
                    else -> ReleaseCheck.Failed(UpdateTexts.serverError(r.code))
                }
            } catch (_: IOException) {
                ReleaseCheck.Failed(UpdateTexts.OFFLINE)
            }
        }
    }

    override suspend fun download(
        offer: UpdateOffer,
        dest: File,
        progress: (Long) -> Unit,
    ): DownloadResult {
        val s = settings()
        if (!s.syncConfigured(allowLocalHttp)) return DownloadResult.Failed(UpdateTexts.NO_SERVER)
        if (!UpdateRules.apkPathAllowed(offer.apkPath)) return DownloadResult.Failed(UpdateTexts.DAMAGED)
        val url = ServerUrl.endpoint(s.serverUrl, offer.apkPath)
        return withContext(Dispatchers.IO) { downloader.download(url, s.apiToken, dest, offer.expected, progress) }
    }

    companion object {
        const val APP = "/api/app"
    }
}

/** Fetches the published APK into a file. */
fun interface ApkDownloader {
    /**
     * Writes [url]'s body to [dest] (replacing it), sending [token] as the bearer token.
     * [progress] gets the bytes so far. Never more than [expected]`.size` bytes are written:
     * a longer body is a failure. Blocking; never throws for network or disk problems.
     */
    fun download(
        url: String,
        token: String,
        dest: File,
        expected: ExpectedFile,
        progress: (Long) -> Unit,
    ): DownloadResult
}

/**
 * [ApkDownloader] on HttpURLConnection, the same rules as the upload (data/sync/SyncHttp.kt):
 * no redirects followed (a redirect could carry the token to another host), no caches, no
 * compression, the network security config's cleartext rules. The bearer token goes only to
 * [url]'s own host, which HttpUpdateServer builds from the configured server URL.
 */
class HttpApkDownloader(
    private val connectTimeoutMs: Int = AppConstants.SYNC_CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Int = AppConstants.SYNC_READ_TIMEOUT_MS,
) : ApkDownloader {
    override fun download(
        url: String,
        token: String,
        dest: File,
        expected: ExpectedFile,
        progress: (Long) -> Unit,
    ): DownloadResult {
        val scheme = runCatching { URI(url).scheme?.lowercase() }.getOrNull()
        if (scheme != "https" && scheme != "http") return DownloadResult.Failed(UpdateTexts.DAMAGED)
        val conn =
            try {
                URL(url).openConnection() as HttpURLConnection
            } catch (_: IOException) {
                return DownloadResult.Failed(UpdateTexts.OFFLINE)
            }
        try {
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            conn.instanceFollowRedirects = false
            conn.useCaches = false
            conn.setRequestProperty("Accept-Encoding", "identity")
            conn.setRequestProperty("Authorization", "Bearer $token")
            val code = conn.responseCode
            when (code) {
                HttpURLConnection.HTTP_OK -> Unit
                HttpURLConnection.HTTP_UNAUTHORIZED, HttpURLConnection.HTTP_FORBIDDEN -> return DownloadResult.Failed(UpdateTexts.WRONG_TOKEN)
                else -> return DownloadResult.Failed(UpdateTexts.serverError(code))
            }
            val length = conn.contentLengthLong
            if (length >= 0 && length != expected.size) return DownloadResult.Failed(UpdateTexts.DAMAGED)
            return conn.inputStream.use { copyHashing(it, dest, expected.size, progress) }
        } catch (_: IOException) {
            return DownloadResult.Failed(UpdateTexts.OFFLINE)
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val BUFFER = 64 * 1024

        /** Copies [input] to [dest] while hashing; stops (failed) past [limit] bytes. */
        fun copyHashing(
            input: InputStream,
            dest: File,
            limit: Long,
            progress: (Long) -> Unit,
        ): DownloadResult {
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            dest.parentFile?.mkdirs()
            val out =
                try {
                    dest.outputStream()
                } catch (_: IOException) {
                    return DownloadResult.Failed(UpdateTexts.NO_SPACE)
                }
            out.use {
                val buffer = ByteArray(BUFFER)
                while (true) {
                    val n = input.read(buffer) // IOException here: the connection (caller says offline)
                    if (n < 0) break
                    total += n
                    if (total > limit) return DownloadResult.Failed(UpdateTexts.DAMAGED)
                    try {
                        out.write(buffer, 0, n)
                    } catch (_: IOException) {
                        return DownloadResult.Failed(UpdateTexts.NO_SPACE)
                    }
                    digest.update(buffer, 0, n)
                    progress(total)
                }
                try {
                    out.fd.sync()
                } catch (_: IOException) {
                    return DownloadResult.Failed(UpdateTexts.NO_SPACE)
                }
            }
            return DownloadResult.Done(total, hex(digest.digest()))
        }

        /** SHA-256 of a file already on disk (a finished earlier download). */
        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(BUFFER)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            }
            return hex(digest.digest())
        }

        fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
    }
}
