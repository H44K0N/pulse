package dev.tischbein.pulse.data.sync

import dev.tischbein.pulse.AppConstants
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** An HTTP answer: the status code and (the start of) the body. */
data class HttpResponse(val code: Int, val body: String)

/**
 * The whole HTTP layer of the app: two calls, behind an interface so the sync logic is tested
 * with a fake (SessionUploaderTest) and the real one is tested against a local server
 * (UrlConnectionHttpTest).
 *
 * Both throw [IOException] when the server cannot be reached (no network, DNS, TLS, timeout).
 * Implementations must never log [token] or the Authorization header.
 */
interface SyncHttp {
    /** POST [body] (UTF-8 JSON) to [url] with `Authorization: Bearer <token>`. */
    fun postJson(
        url: String,
        token: String,
        body: ByteArray,
    ): HttpResponse

    /** GET [url], with the bearer token if [token] is not null. */
    fun get(
        url: String,
        token: String?,
    ): HttpResponse
}

/**
 * [SyncHttp] on java.net.HttpURLConnection, which is part of Android: no HTTP library needed
 * for two requests. (OkHttp would also do; the Polar SDK happens to bring an old 3.x
 * transitively, which must not be relied on, and a direct dependency is not worth it here.)
 *
 * - Timeouts: [AppConstants.SYNC_CONNECT_TIMEOUT_MS] / [AppConstants.SYNC_READ_TIMEOUT_MS].
 * - Redirects are NOT followed: a redirect would carry the token somewhere else (or to http).
 *   The caller sees the 3xx code and reports it.
 * - The request body is sent as is (no gzip), with a fixed length.
 * - Cleartext is refused by the network security config in release builds; ServerUrl decides
 *   beforehand which URLs are allowed at all.
 */
class UrlConnectionHttp(
    private val connectTimeoutMs: Int = AppConstants.SYNC_CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Int = AppConstants.SYNC_READ_TIMEOUT_MS,
) : SyncHttp {
    override fun postJson(
        url: String,
        token: String,
        body: ByteArray,
    ): HttpResponse =
        request(url, "POST", token) { conn ->
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setFixedLengthStreamingMode(body.size)
            conn.outputStream.use { it.write(body) }
        }

    override fun get(
        url: String,
        token: String?,
    ): HttpResponse = request(url, "GET", token) {}

    private fun request(
        url: String,
        method: String,
        token: String?,
        send: (HttpURLConnection) -> Unit,
    ): HttpResponse {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            conn.instanceFollowRedirects = false
            conn.useCaches = false
            conn.requestMethod = method
            conn.setRequestProperty("Accept", "application/json")
            if (token != null) conn.setRequestProperty("Authorization", "Bearer $token")
            send(conn)
            val code = conn.responseCode
            // Error answers (4xx/5xx) come on the error stream; it may be null (no body).
            val stream = if (code >= HttpURLConnection.HTTP_BAD_REQUEST) conn.errorStream else conn.inputStream
            return HttpResponse(code, stream?.use { readCapped(it) } ?: "")
        } finally {
            conn.disconnect()
        }
    }

    /** At most [AppConstants.SYNC_MAX_RESPONSE_CHARS]: answers are tiny; a huge one is not ours. */
    private fun readCapped(input: InputStream): String {
        val reader = input.bufferedReader(Charsets.UTF_8)
        val buffer = CharArray(AppConstants.SYNC_MAX_RESPONSE_CHARS)
        var n = 0
        while (n < buffer.size) {
            val read = reader.read(buffer, n, buffer.size - n)
            if (read < 0) break
            n += read
        }
        return String(buffer, 0, n)
    }
}
