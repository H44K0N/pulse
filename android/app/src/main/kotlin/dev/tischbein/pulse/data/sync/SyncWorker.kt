package dev.tischbein.pulse.data.sync

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.tischbein.pulse.AppConstants
import dev.tischbein.pulse.PulseApplication
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/**
 * The background job: runs SessionUploader when the phone is online. WorkManager keeps it
 * across app restarts and reboots, waits for a network, and retries with exponential backoff
 * (from [AppConstants.SYNC_BACKOFF_INITIAL_MS], doubling, capped by WorkManager at 5 hours).
 */
class SyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as PulseApplication).container
        return try {
            when (container.uploader.uploadPending()) {
                UploadRun.DONE, UploadRun.NOT_CONFIGURED -> Result.success()
                UploadRun.RETRY -> Result.retry()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // E.g. the database is busy being migrated: not the session's fault, try again.
            Log.w("Pulse/Sync", "sync run failed: ${e.javaClass.simpleName}")
            Result.retry()
        }
    }
}

/** Why sync is being asked to run; decides waiting and what happens to an already queued run. */
enum class SyncTrigger(val policy: ExistingWorkPolicy, val delayMs: Long) {
    /** Catch leftovers (e.g. a session marked incomplete). Keeps a queued run and its backoff. */
    APP_START(ExistingWorkPolicy.KEEP, 0),

    /** A session was saved: upload now (replaces a run that is waiting for its next retry). */
    SESSION_FINISHED(ExistingWorkPolicy.REPLACE, 0),

    /** Settings > Sync > "Sync now". */
    SYNC_NOW(ExistingWorkPolicy.REPLACE, 0),

    /** Server URL or token edited: wait until typing has settled (see AppConstants). */
    SETTINGS_CHANGED(ExistingWorkPolicy.REPLACE, AppConstants.SYNC_SETTINGS_SETTLE_MS),
}

/** Enqueues the one sync job ("unique work": never two at once). */
class SyncScheduler(
    private val context: Context,
) {
    fun request(trigger: SyncTrigger) {
        val work =
            OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, AppConstants.SYNC_BACKOFF_INITIAL_MS, TimeUnit.MILLISECONDS)
                .setInitialDelay(trigger.delayMs, TimeUnit.MILLISECONDS)
                .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, trigger.policy, work)
    }

    private companion object {
        const val WORK_NAME = "pulse-sync"
    }
}
