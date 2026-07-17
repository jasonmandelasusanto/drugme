package com.drugme.app.alarm

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import android.util.Log
import com.drugme.app.data.repo.DoseRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * The healer: the backstop that makes a broken alarm chain recoverable.
 *
 * [DoseAlarmScheduler] keeps exactly one alarm in flight, each firing arming the next. If
 * any single link is ever lost — process killed mid-handling, an OEM battery manager
 * clearing alarms, an unhandled exception — nothing else would ever re-arm it and the user
 * would stop being reminded *permanently, with no error and no symptom*. For a medication
 * app that is the worst possible failure, so it must not depend on the chain being
 * flawless.
 *
 * This runs daily and re-arms unconditionally. Both operations it performs are idempotent,
 * so healing a chain that was never broken costs nothing.
 *
 * WorkManager is used here rather than another alarm on purpose: its jobs are persisted
 * across reboots and app updates by the OS, so the healer itself does not share the
 * failure mode it exists to cover.
 */
@HiltWorker
class RearmWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val doseRepository: DoseRepository,
    private val scheduler: DoseAlarmScheduler,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = try {
        val created = doseRepository.materializeWindow()
        val missed = doseRepository.markOverdueAsMissed()
        scheduler.rescheduleNext()
        Log.i(TAG, "Heal pass: generated=$created markedMissed=${missed.size}")
        Result.success()
    } catch (t: Throwable) {
        Log.e(TAG, "Heal pass failed", t)
        Result.retry()
    }

    companion object {
        private const val TAG = "RearmWorker"
        private const val WORK_NAME = "dose_rearm_heal"

        fun enqueue(context: Context) {
            val request = PeriodicWorkRequestBuilder<RearmWorker>(1, TimeUnit.DAYS)
                .setInitialDelay(1, TimeUnit.HOURS)
                .build()
            // KEEP, not UPDATE: re-enqueuing on every app start must not reset the period
            // and postpone the heal indefinitely.
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
