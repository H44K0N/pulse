package dev.tischbein.pulse.data.sync

import dev.tischbein.pulse.AppConstants
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.IOException
import java.net.HttpURLConnection.HTTP_CREATED
import java.net.HttpURLConnection.HTTP_ENTITY_TOO_LARGE
import java.net.HttpURLConnection.HTTP_FORBIDDEN
import java.net.HttpURLConnection.HTTP_INTERNAL_ERROR
import java.net.HttpURLConnection.HTTP_MULT_CHOICE
import java.net.HttpURLConnection.HTTP_OK
import java.net.HttpURLConnection.HTTP_UNAUTHORIZED

/** What one upload attempt means for the session. */
sealed interface UploadResult {
    /** 201 (stored now) or 200 (was already stored: uploads are idempotent on the session id). */
    data object Stored : UploadResult

    /** The server will never accept this document (413 too large, 422 invalid). Do not retry. */
    data class Rejected(val reason: String) : UploadResult

    /** Try again later: no connection, wrong token, server error, anything unexpected. */
    data class Retry(val reason: String) : UploadResult
}

/** Result of Settings > Sync > "Test connection", worded for the user. */
enum class ConnectionCheck(val message: String) {
    OK("Connected. Server and token work."),
    NOT_CONFIGURED("Enter the server URL and the API token first."),
    NOT_HTTPS(ServerUrl.NOT_HTTPS + "."),
    BAD_URL("That is not a valid server URL."),
    UNREACHABLE("Cannot reach the server. Check the URL and the connection."),
    WRONG_TOKEN("Server reached, but it does not accept this token."),
    SERVER_ERROR("Server reached, but it reports an error. Try again later."),
    NOT_PULSE("Something answered, but it is not a Pulse server. Check the URL."),
}

/**
 * The server's API (server/README.md) on top of [SyncHttp]: which URL, which status code
 * means what. No state, no database: SessionUploader decides what to do with the answers.
 */
class SyncClient(
    private val http: SyncHttp,
    private val allowLocalHttp: Boolean,
) {
    /** POST /api/sessions with one session document. Blocking: call from Dispatchers.IO. */
    fun upload(
        serverUrl: String,
        token: String,
        json: String,
    ): UploadResult {
        val response =
            try {
                http.postJson(ServerUrl.endpoint(serverUrl, SESSIONS), token, json.toByteArray(Charsets.UTF_8))
            } catch (_: IOException) {
                return UploadResult.Retry(UNREACHABLE)
            }
        val code = response.code
        return when {
            code == HTTP_OK || code == HTTP_CREATED -> UploadResult.Stored
            code == HTTP_ENTITY_TOO_LARGE -> UploadResult.Rejected("Too large for the server (HTTP 413)")
            code == HTTP_UNPROCESSABLE -> UploadResult.Rejected(clip("Rejected by the server: " + detailOf(response.body)))
            code == HTTP_UNAUTHORIZED || code == HTTP_FORBIDDEN -> UploadResult.Retry(WRONG_TOKEN)
            code >= HTTP_INTERNAL_ERROR -> UploadResult.Retry("Server error (HTTP $code)")
            code in HTTP_MULT_CHOICE..<400 -> UploadResult.Retry("Server redirects (HTTP $code); check the URL")
            else -> UploadResult.Retry("Unexpected answer (HTTP $code); check the URL")
        }
    }

    /**
     * Settings > "Test connection": GET /healthz (no token: is it there?), then
     * GET /api/sessions?limit=1 with the token (does the token work?). Nothing is written.
     * Blocking: call from Dispatchers.IO.
     */
    fun check(
        serverUrl: String,
        token: String,
    ): ConnectionCheck {
        if (serverUrl.isBlank() || token.isBlank()) return ConnectionCheck.NOT_CONFIGURED
        when (ServerUrl.problem(serverUrl, allowLocalHttp)) {
            null -> Unit
            ServerUrl.NOT_HTTPS -> return ConnectionCheck.NOT_HTTPS
            else -> return ConnectionCheck.BAD_URL
        }
        return try {
            val health = http.get(ServerUrl.endpoint(serverUrl, HEALTH), token = null).code
            when {
                health >= HTTP_INTERNAL_ERROR -> ConnectionCheck.SERVER_ERROR
                health != HTTP_OK -> ConnectionCheck.NOT_PULSE
                else -> {
                    val auth = http.get(ServerUrl.endpoint(serverUrl, "$SESSIONS?limit=1"), token).code
                    when {
                        auth == HTTP_OK -> ConnectionCheck.OK
                        auth == HTTP_UNAUTHORIZED || auth == HTTP_FORBIDDEN -> ConnectionCheck.WRONG_TOKEN
                        auth >= HTTP_INTERNAL_ERROR -> ConnectionCheck.SERVER_ERROR
                        else -> ConnectionCheck.NOT_PULSE
                    }
                }
            }
        } catch (_: IOException) {
            ConnectionCheck.UNREACHABLE
        }
    }

    companion object {
        const val SESSIONS = "/api/sessions"
        const val HEALTH = "/healthz"
        const val UNREACHABLE = "Cannot reach the server"
        const val WRONG_TOKEN = "Wrong API token"
        private const val HTTP_UNPROCESSABLE = 422
        private const val MAX_DETAIL_ITEMS = 3

        /**
         * FastAPI's validation answer is {"detail": [{"loc": ["body", "summary", "avg_hr"],
         * "msg": "..."}, ...]}; made readable as "summary.avg_hr: ...". Anything else is
         * shown as it came (shortened).
         */
        fun detailOf(body: String): String {
            val detail = runCatching { (Json.parseToJsonElement(body) as JsonObject)["detail"] }.getOrNull()
            return when (detail) {
                is JsonPrimitive -> detail.contentOrNull ?: body
                is JsonArray ->
                    detail.take(MAX_DETAIL_ITEMS).joinToString("; ") { item ->
                        val o = item as? JsonObject
                        val loc = (o?.get("loc") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.filter { it != "body" }?.joinToString(".")
                        val msg = (o?.get("msg") as? JsonPrimitive)?.contentOrNull ?: item.toString()
                        if (loc.isNullOrEmpty()) msg else "$loc: $msg"
                    }
                else -> body.ifBlank { "no reason given" }
            }
        }

        fun clip(text: String): String = if (text.length <= AppConstants.SYNC_MAX_ERROR_CHARS) text else text.take(AppConstants.SYNC_MAX_ERROR_CHARS - 1) + "…"
    }
}
