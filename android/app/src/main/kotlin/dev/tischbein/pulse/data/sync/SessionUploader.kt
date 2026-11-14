package dev.tischbein.pulse.data.sync

import android.util.Log
import dev.tischbein.pulse.data.SessionRepository
import dev.tischbein.pulse.data.settings.SettingsRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** How an upload run ended (SyncWorker turns it into a WorkManager result). */
enum class UploadRun {
    /** Every finished session is on the server or rejected for good. */
    DONE,

    /** Something is in the way (offline, wrong token, server down): run again later, with backoff. */
    RETRY,

    /** No server URL / token (or an unusable URL): sync is off, nothing to do. */
    NOT_CONFIGURED,
}

/**
 * Uploads the finished sessions that are not on the server yet, oldest first, one request
 * per session, and records the outcome per session in Room (SyncState):
 *
 * - 201 / 200 -> "synced" (200 = already there; uploads are idempotent on the session UUID,
 *   so a retry after a lost answer is harmless).
 * - 413 / 422 -> "failed" with the server's reason; the next session is tried. Not retried
 *   automatically ("Sync now" gives rejected sessions one more try).
 * - anything else (no connection, 401 wrong token, 5xx, ...) -> stays "pending" with the
 *   reason, and the run stops: the next session would fail the same way. WorkManager retries.
 *
 * Never uploads a session that is still being recorded (the query excludes it). The JSON is
 * the contract document from SessionRepository.exportJson (data/export/SessionDocument.kt).
 *
 * The synced states refer to one server: when the URL changes, everything becomes pending again
 * so the new server gets the whole history (the old one keeps its copy; there is no delete).
 */
class SessionUploader(
    private val sessions: SessionRepository,
    private val settings: SettingsRepository,
    private val client: SyncClient,
    private val allowLocalHttp: Boolean,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    // One run at a time, even if WorkManager replaces a run that is still waiting for an answer.
    private val running = Mutex()

    suspend fun uploadPending(): UploadRun =
        running.withLock {
            val s = settings.current()
            if (!s.syncConfigured(allowLocalHttp)) return UploadRun.NOT_CONFIGURED
            val url = ServerUrl.normalize(s.serverUrl)
            if (settings.syncTarget() != url) {
                val reset = sessions.resetAllSync()
                settings.setSyncTarget(url)
                if (reset > 0) Log.i(TAG, "server changed: $reset session(s) will be uploaded again")
            }
            var uploaded = 0
            for (id in sessions.pendingUploads()) {
                val json = sessions.exportJson(id) ?: continue // deleted meanwhile
                val result = withContext(io) { client.upload(url, s.apiToken, json) }
                val now = nowMs()
                when (result) {
                    UploadResult.Stored -> {
                        sessions.markSynced(id, now)
                        uploaded++
                    }
                    is UploadResult.Rejected -> {
                        sessions.markUploadRejected(id, result.reason, now)
                        Log.w(TAG, "session $id rejected by the server: ${result.reason}")
                    }
                    is UploadResult.Retry -> {
                        sessions.markUploadRetry(id, result.reason, now)
                        Log.i(TAG, "upload paused after $uploaded session(s): ${result.reason}")
                        return UploadRun.RETRY
                    }
                }
            }
            if (uploaded > 0) Log.i(TAG, "$uploaded session(s) uploaded")
            UploadRun.DONE
        }

    private companion object {
        const val TAG = "Pulse/Sync"
    }
}
