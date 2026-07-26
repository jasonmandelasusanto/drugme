package com.drugme.app.data.update

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

@HiltWorker
class AppUpdateWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val updates: AppUpdateRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result =
        updates.checkIfDue().fold(
            onSuccess = { Result.success() },
            // Periodic work will check again tomorrow. Retrying a 404/malformed release
            // repeatedly would violate the once-daily contract and waste battery/data.
            onFailure = { Result.success() },
        )

    companion object {
        private const val UNIQUE_WORK = "daily-app-update"

        fun enqueue(context: Context) {
            val request = PeriodicWorkRequestBuilder<AppUpdateWorker>(24, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
