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
import com.drugme.app.data.repo.MedicationRepository
import com.drugme.app.notify.DoseNotifier
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
    private val medicationRepository: MedicationRepository,
    private val notifier: DoseNotifier,
    private val scheduler: DoseAlarmScheduler,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = try {
        val created = doseRepository.materializeWindow()
        val missed = doseRepository.markOverdueAsMissed()
        scheduler.rescheduleNext()

        // Refill checks ride along on the existing daily pass rather than adding a second
        // worker: the schedule simulation is arithmetic over a handful of medications, and
        // one scheduled job is one fewer thing an OEM battery manager can decide to kill.
        val refills = checkRefills()

        Log.i(TAG, "Heal pass: generated=$created markedMissed=${missed.size} refillWarnings=$refills")
        Result.success()
    } catch (t: Throwable) {
        Log.e(TAG, "Heal pass failed", t)
        Result.retry()
    }

    /**
     * Warns about medications about to run out.
     *
     * Failures here are swallowed: a refill warning is a convenience, and it must never
     * cause the worker to retry and thereby delay the alarm re-arming that is the whole
     * reason this job exists.
     */
    private suspend fun checkRefills(): Int = runCatching {
        val due = medicationRepository.dueForRefillWarning()
        for ((item, forecast) in due) {
            notifier.notifyRefill(
                medicationName = item.medication.name,
                daysRemaining = forecast.daysRemaining ?: 0,
                runOutDate = forecast.runOutDate ?: continue,
            )
            medicationRepository.markRefillNotified(item.medication.id)
        }
        due.size
    }.getOrElse {
        Log.e(TAG, "Refill check failed", it)
        0
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
