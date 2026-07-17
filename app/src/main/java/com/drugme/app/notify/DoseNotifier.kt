package com.drugme.app.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.drugme.app.MainActivity
import com.drugme.app.R
import com.drugme.app.alarm.DoseAlarmReceiver
import com.drugme.app.data.local.dao.DoseWithMedication
import com.drugme.app.data.prefs.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DoseNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
) {

    private val manager = NotificationManagerCompat.from(context)

    init {
        createChannel()
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_REMINDERS,
            context.getString(R.string.notif_channel_reminders_name),
            // HIGH so the reminder heads-up and makes sound. A medication reminder that
            // arrives silently in the shade has failed at its only job.
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

    suspend fun notifyDose(item: DoseWithMedication) {
        if (!hasPermission()) return

        val med = item.medication
        val discreet = settings.discreetNotifications.first()

        val doseText = med.doseUnit.format(med.doseAmount)

        val builder = NotificationCompat.Builder(context, CHANNEL_REMINDERS)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(context, R.color.brand_blue))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .setContentIntent(openAppIntent(item.dose.id))
            .addAction(0, context.getString(R.string.action_taken), action(DoseAlarmReceiver.ACTION_TAKEN, item.dose.id, 1))
            .addAction(0, context.getString(R.string.action_snooze), action(DoseAlarmReceiver.ACTION_SNOOZE, item.dose.id, 2))
            .addAction(0, context.getString(R.string.action_skip), action(DoseAlarmReceiver.ACTION_SKIP, item.dose.id, 3))

        if (discreet) {
            // Discreet mode names nothing, anywhere. A drug name is a medical diagnosis in
            // disguise — "time to take [antiretroviral]" outs a condition to whoever is
            // looking at the phone. When the user asks for discretion, the public and
            // private forms must both stay silent about which drug it is; leaking it in
            // the unlocked view would defeat the point.
            builder.setContentTitle(context.getString(R.string.reminder_title_discreet))
                .setContentText(context.getString(R.string.reminder_body_discreet))
                .setVisibility(NotificationCompat.VISIBILITY_SECRET)
        } else {
            builder.setContentTitle(context.getString(R.string.reminder_title, med.name))
                .setContentText(doseText)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                // The public form is what shows on a locked screen. It deliberately omits
                // the drug name while still being useful enough to act on.
                .setPublicVersion(
                    NotificationCompat.Builder(context, CHANNEL_REMINDERS)
                        .setSmallIcon(R.drawable.ic_notification)
                        .setColor(ContextCompat.getColor(context, R.color.brand_blue))
                        .setContentTitle(context.getString(R.string.reminder_title_discreet))
                        .setContentText(context.getString(R.string.reminder_body_discreet))
                        .build()
                )
        }

        manager.notify(item.dose.id.hashCode(), builder.build())
    }

    fun dismiss(doseId: String) = manager.cancel(doseId.hashCode())

    fun hasPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun action(action: String, doseId: String, code: Int): PendingIntent {
        val intent = Intent(context, DoseAlarmReceiver::class.java).apply {
            this.action = action
            putExtra(DoseAlarmReceiver.EXTRA_DOSE_ID, doseId)
        }
        return PendingIntent.getBroadcast(
            context,
            // Request code must be unique per (dose, action) or PendingIntents collide and
            // one dose's "Taken" would silently rewire another's.
            doseId.hashCode() * 8 + code,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun openAppIntent(doseId: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(DoseAlarmReceiver.EXTRA_DOSE_ID, doseId)
        }
        return PendingIntent.getActivity(
            context,
            doseId.hashCode() * 8,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        const val CHANNEL_REMINDERS = "dose_reminders"
    }
}
