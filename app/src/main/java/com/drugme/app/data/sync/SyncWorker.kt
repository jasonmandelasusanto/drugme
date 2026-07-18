package com.drugme.app.data.sync

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.drugme.app.data.crypto.VaultManager
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * Backs up local data to the cloud in the background.
 *
 * This exists because sync used to run only when the user typed their passphrase to unlock —
 * and once the key is cached in the Keystore that never happens again, so medications added
 * afterwards were never uploaded and were lost on reinstall. Now every data change enqueues
 * this worker, with a periodic pass as a safety net, so a backup no longer depends on an
 * unlock ever recurring.
 *
 * WorkManager (not an inline coroutine) on purpose: the push must survive the app being
 * backgrounded or killed right after an edit, retry when the network returns, and hold its
 * constraint (connected) instead of failing silently offline — none of which a fire-and-
 * forget launch guarantees.
 */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val syncEngine: SyncEngine,
    private val vault: VaultManager,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // A background run has no in-memory key, so restore it from the Keystore cache — the
        // push seals records with it. If there is no cache (never unlocked on this install)
        // there is nothing to do until the next unlock; that is not a failure to retry.
        vault.ensureUnlockedFromCache()

        return when (val result = syncEngine.sync()) {
            is SyncResult.Success -> Result.success()
            // Nothing we can do right now — no account, or no cached key. A later unlock or
            // the periodic pass will pick it up; retrying would just spin.
            SyncResult.NotSignedIn, SyncResult.Locked -> Result.success()
            is SyncResult.Failure -> {
                Log.w(TAG, "Sync failed; will retry: ${result.message}")
                Result.retry()
            }
        }
    }

    companion object {
        private const val TAG = "SyncWorker"
        private const val ONE_TIME_WORK = "data_sync_now"
        private const val PERIODIC_WORK = "data_sync_periodic"

        private val networkConstraint = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        /** Push as soon as there is a network — called after every data change. */
        fun enqueueNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(networkConstraint)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            // REPLACE so a burst of rapid edits collapses into one pending push rather than a
            // queue of redundant full syncs.
            WorkManager.getInstance(context).enqueueUniqueWork(
                ONE_TIME_WORK,
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }

        /**
         * Safety net: catches anything a one-time push missed — offline at the moment of the
         * edit, or the process killed mid-run.
         */
        fun enqueuePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(6, TimeUnit.HOURS)
                .setConstraints(networkConstraint)
                .build()
            // KEEP so re-enqueuing on every app start doesn't reset the interval.
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_WORK,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
