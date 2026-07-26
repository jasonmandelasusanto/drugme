package com.drugme.app.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.drugme.app.MainActivity
import com.drugme.app.data.repo.DoseRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Clock
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Arms the OS alarm for the next dose.
 *
 * **Design: one alarm at a time.** Rather than registering an alarm per dose (which runs
 * into per-app alarm quotas and leaves hundreds of stale registrations to reconcile), a
 * single alarm is armed for the earliest pending dose. When it fires, [DoseAlarmReceiver]
 * arms the next one — a chain.
 *
 * The chain's weakness is that it has exactly one link in flight: if a link is ever
 * dropped (process killed mid-handling, alarm cleared by the OS, an exception in the
 * receiver) reminders stop **permanently and silently**, which is the worst failure this
 * app has. Three independent backstops re-arm unconditionally, and all are safe to run at
 * any time because [rescheduleNext] is idempotent:
 *
 *  1. [BootReceiver] — alarms do not survive a reboot.
 *  2. [RearmWorker] — a daily WorkManager pass that heals a broken chain.
 *  3. App foreground — see MainActivity.
 */
@Singleton
class DoseAlarmScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val doseRepository: DoseRepository,
    private val directBootStore: DirectBootReminderStore,
    private val clock: Clock,
) {

    private val alarmManager = context.getSystemService(AlarmManager::class.java)

    /**
     * Cancels any pending alarm and arms one for the next pending dose, if any.
     *
     * Idempotent: the PendingIntent uses a fixed request code, so re-arming replaces
     * rather than stacks.
     */
    suspend fun rescheduleNext() {
        val next = doseRepository.getNextPending()
        if (next == null) {
            cancel()
            Log.i(TAG, "No pending doses; alarm chain idle.")
            return
        }

        // Keep an anonymous rolling queue outside credential-encrypted storage. AlarmManager
        // registrations disappear at reboot; LOCKED_BOOT_COMPLETED reads this queue to restore
        // generic reminders without exposing any medication data before first unlock.
        runCatching {
            directBootStore.replace(doseRepository.getUpcomingPendingTimes())
            DirectBootReminderAlarm(context).cancel()
        }.onFailure {
            // Direct Boot is a backstop. Its failure must never prevent the normal, more
            // informative credential-unlocked reminder from being armed.
            Log.e(TAG, "Could not refresh the anonymous direct-boot queue", it)
        }
        armAt(next.effectiveAt, next.id)
    }

    private fun armAt(at: Instant, doseId: String) {
        val fireAt = at.toEpochMilli()
        val intent = Intent(context, DoseAlarmReceiver::class.java).apply {
            action = DoseAlarmReceiver.ACTION_DOSE_DUE
            putExtra(DoseAlarmReceiver.EXTRA_DOSE_ID, doseId)
        }
        val pending = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        if (!canScheduleExact()) {
            // Degrade rather than crash. setAndAllowWhileIdle still pierces Doze; it is
            // only inexact (the OS may delay it by minutes). A late reminder beats none,
            // and the UI surfaces a prompt to grant the permission.
            Log.w(TAG, "Exact alarms not permitted; falling back to inexact.")
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAt, pending)
            return
        }

        // setAlarmClock is the strongest guarantee Android offers: it is exempt from Doze
        // and from app-standby deferral, and is what the stock Clock app uses. setExact*
        // variants are weaker and can still be deferred under some OEM policies.
        // The show-intent is what the OS surfaces in the status bar as a pending alarm.
        val showIntent = PendingIntent.getActivity(
            context,
            SHOW_REQUEST_CODE,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        alarmManager.setAlarmClock(AlarmManager.AlarmClockInfo(fireAt, showIntent), pending)
        Log.i(TAG, "Armed alarm for dose=$doseId at ${Instant.ofEpochMilli(fireAt)}")
    }

    fun cancel() {
        runCatching {
            directBootStore.clear()
            DirectBootReminderAlarm(context).cancel()
        }.onFailure { Log.e(TAG, "Could not clear the anonymous direct-boot queue", it) }
        val intent = Intent(context, DoseAlarmReceiver::class.java).apply {
            action = DoseAlarmReceiver.ACTION_DOSE_DUE
        }
        PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )?.let {
            alarmManager.cancel(it)
            it.cancel()
        }
    }

    /**
     * Whether exact alarms are currently permitted.
     *
     * On API 31+ this is a user-revocable special access. The manifest declares
     * USE_EXACT_ALARM (auto-granted for a sideloaded build), so this should normally be
     * true — but a Play-distributed build, or a future policy change, would route through
     * SCHEDULE_EXACT_ALARM instead, where the user can and does say no.
     */
    fun canScheduleExact(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()

    /** Settings screen where the user can grant exact-alarm access. */
    fun exactAlarmSettingsIntent(): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !canScheduleExact()) {
            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))
        } else {
            null
        }

    private companion object {
        const val TAG = "DoseAlarmScheduler"
        const val REQUEST_CODE = 1001
        const val SHOW_REQUEST_CODE = 1002
    }
}
