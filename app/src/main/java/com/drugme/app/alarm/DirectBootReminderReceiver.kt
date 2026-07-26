package com.drugme.app.alarm

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.drugme.app.MainActivity
import com.drugme.app.R
import com.drugme.app.data.repo.DoseRepository
import com.drugme.app.notify.DoseNotifier

/**
 * Restores generic reminders during Direct Boot, before Room can be opened.
 *
 * This receiver intentionally has no Hilt injection: resolving the normal object graph would
 * construct Room from credential-encrypted storage, which is unavailable at this point. Its
 * only input is [DirectBootReminderStore]'s anonymous timestamp queue.
 */
class DirectBootReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        runCatching {
            when (intent.action) {
                Intent.ACTION_LOCKED_BOOT_COMPLETED -> resumeQueue(context)
                ACTION_REMINDER_DUE -> fireReminder(context, intent)
                else -> Log.w(TAG, "Ignoring unexpected action ${intent.action}")
            }
        }.onFailure { Log.e(TAG, "Direct-boot reminder handling failed", it) }
    }

    private fun resumeQueue(context: Context) {
        val now = System.currentTimeMillis()
        val store = DirectBootReminderStore(context)
        val alarm = DirectBootReminderAlarm(context)
        val next = store.nextAtOrAfter(now - DoseRepository.GRACE.toMillis())

        when {
            next == null -> alarm.cancel()
            next <= now -> {
                // The phone may have been powered off when a recent dose became due. Alert once
                // at locked boot, consume every elapsed timestamp, then continue with the future.
                if (store.consumeDue(now)) DirectBootReminderNotifier(context).notifyReminder()
                armNext(store, alarm, now)
            }
            else -> alarm.arm(next)
        }
    }

    private fun fireReminder(context: Context, intent: Intent) {
        val now = System.currentTimeMillis()
        val scheduledAt = intent.getLongExtra(EXTRA_SCHEDULED_AT, now)
        val store = DirectBootReminderStore(context)

        // Use the intended timestamp as the lower bound too. It prevents a millisecond-level
        // early delivery from leaving the queue entry behind and scheduling it a second time.
        if (store.consumeDue(maxOf(now, scheduledAt))) {
            DirectBootReminderNotifier(context).notifyReminder()
        }
        armNext(store, DirectBootReminderAlarm(context), now)
    }

    private fun armNext(
        store: DirectBootReminderStore,
        alarm: DirectBootReminderAlarm,
        now: Long,
    ) {
        val next = store.nextAtOrAfter(now - DoseRepository.GRACE.toMillis())
        if (next == null) alarm.cancel() else alarm.arm(maxOf(next, now))
    }

    companion object {
        private const val TAG = "DirectBootReceiver"
        const val ACTION_REMINDER_DUE = "com.drugme.app.action.DIRECT_BOOT_REMINDER_DUE"
        const val EXTRA_SCHEDULED_AT = "scheduled_at"
    }
}

/** A dependency-free AlarmManager adapter usable before credential storage unlocks. */
internal class DirectBootReminderAlarm(private val context: Context) {
    private val alarmManager = context.getSystemService(AlarmManager::class.java)

    fun arm(atMillis: Long) {
        val alarmIntent = Intent(context, DirectBootReminderReceiver::class.java).apply {
            action = DirectBootReminderReceiver.ACTION_REMINDER_DUE
            putExtra(DirectBootReminderReceiver.EXTRA_SCHEDULED_AT, atMillis)
        }
        val operation = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            alarmIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            !alarmManager.canScheduleExactAlarms()
        ) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, operation)
            Log.w(TAG, "Exact alarms unavailable; direct-boot reminder is inexact")
            return
        }

        val showIntent = PendingIntent.getActivity(
            context,
            SHOW_REQUEST_CODE,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        alarmManager.setAlarmClock(AlarmManager.AlarmClockInfo(atMillis, showIntent), operation)
        Log.i(TAG, "Armed anonymous direct-boot reminder at $atMillis")
    }

    fun cancel() {
        val intent = Intent(context, DirectBootReminderReceiver::class.java).apply {
            action = DirectBootReminderReceiver.ACTION_REMINDER_DUE
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

    private companion object {
        const val TAG = "DirectBootAlarm"
        const val REQUEST_CODE = 1101
        const val SHOW_REQUEST_CODE = 1102
    }
}

/**
 * Posts a notification without reading settings or medication data.
 *
 * There are deliberately no Taken/Skip/Snooze actions: those require Room and must wait for
 * first unlock. Tapping the notification prompts unlock and opens DrugMe.
 */
private class DirectBootReminderNotifier(private val context: Context) {
    private val manager = NotificationManagerCompat.from(context)

    @SuppressLint("MissingPermission")
    fun notifyReminder() {
        ensureChannel()
        if (!hasPermission() || !manager.areNotificationsEnabled()) return

        val notification = NotificationCompat.Builder(context, DoseNotifier.CHANNEL_REMINDERS)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(context, R.color.brand_blue))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .setContentTitle(context.getString(R.string.reminder_title_discreet))
            .setContentText(context.getString(R.string.direct_boot_reminder_body))
            .setContentIntent(openAppIntent())
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .build()

        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun ensureChannel() {
        val channel = NotificationChannel(
            DoseNotifier.CHANNEL_REMINDERS,
            context.getString(R.string.notif_channel_reminders_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.notif_channel_reminders_desc)
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 400, 200, 400)
            setSound(
                Settings.System.DEFAULT_NOTIFICATION_URI,
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            setBypassDnd(false)
            lockscreenVisibility = NotificationCompat.VISIBILITY_PRIVATE
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun hasPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun openAppIntent(): PendingIntent =
        PendingIntent.getActivity(
            context,
            OPEN_REQUEST_CODE,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private companion object {
        const val NOTIFICATION_ID = 899_998
        const val OPEN_REQUEST_CODE = 1103
    }
}
