package eu.kanade.tachiyomi.data.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.data.backup.restore.BackupRestoreWorker
import eu.kanade.tachiyomi.util.system.isRunning
import eu.kanade.tachiyomi.util.system.workManager
import kotlinx.coroutines.CancellationException
import logcat.LogPriority
import mihon.app.di.AppGraph
import mihon.core.metro.metroGraph
import tachiyomi.core.common.util.system.logcat
import java.io.IOException

/**
 * Runs a Google Drive sync in the background, so it finishes even if the app was just left.
 */
class SyncWorker(private val context: Context, workerParams: WorkerParameters) :
    CoroutineWorker(context, workerParams) {

    private val graph: AppGraph = context.metroGraph()

    @Inject private lateinit var syncManager: SyncManager

    @Inject private lateinit var syncPreferences: SyncPreferences

    override suspend fun doWork(): Result {
        graph.inject(this)

        if (!syncPreferences.enabled.get()) return Result.success()

        // Merging while a backup is being restored by hand would have both writing the library at once
        if (BackupRestoreWorker.isRunning(context.workManager)) return Result.retry()

        return try {
            syncManager.sync()
            Result.success()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            logcat(LogPriority.ERROR, e) { "Google Drive sync failed" }
            // Connection problems are worth another go; anything else would only fail the same way
            if (e is IOException && runAttemptCount < MAX_RETRIES) Result.retry() else Result.failure()
        }
    }

    companion object {
        /**
         * Starts a sync, unless one is already running or waiting for a connection.
         */
        fun start(context: Context) {
            enqueue(context, ExistingWorkPolicy.KEEP)
        }

        /**
         * Like [start], but if a sync is running right now, another one is queued after it. Used when leaving
         * the app, so what was read since the running sync made its backup is uploaded too.
         */
        suspend fun startAfterRunning(context: Context) {
            val policy = if (context.workManager.isRunning(TAG)) {
                ExistingWorkPolicy.APPEND_OR_REPLACE
            } else {
                ExistingWorkPolicy.KEEP
            }
            enqueue(context, policy)
        }

        suspend fun isRunning(context: Context): Boolean {
            return context.workManager.isRunning(TAG)
        }

        fun stop(context: Context) {
            context.workManager.cancelUniqueWork(TAG)
        }

        private fun enqueue(context: Context, policy: ExistingWorkPolicy) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .addTag(TAG)
                .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
                .build()
            context.workManager.enqueueUniqueWork(TAG, policy, request)
        }
    }
}

private const val TAG = "GoogleDriveSync"

private const val MAX_RETRIES = 3
