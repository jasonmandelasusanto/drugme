package com.drugme.app.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Re-arms the alarm chain after events that silently destroy pending alarms.
 *
 * The OS drops every registered alarm on reboot without telling the app, so without this
 * the chain would simply never resume — the single most likely way for a reminder app to
 * die quietly. Also handles time and timezone changes, which move every wall-clock dose
 * to a different instant.
 *
 * MY_PACKAGE_REPLACED matters too: alarms are cleared when the app is updated, so
 * upgrading the app would otherwise stop all reminders until it was next opened.
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject lateinit var scheduler: DoseAlarmScheduler
    @Inject lateinit var doseRepository: com.drugme.app.data.repo.DoseRepository

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.i(TAG, "Re-arming after $action")

        // Stop the anonymous pre-unlock chain before doing asynchronous Room work. A generic
        // notification that already fired stays visible; only future shadow alarms stop.
        DirectBootReminderAlarm(context).cancel()

        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                // A timezone change moves every dose's instant, and a long power-off may
                // have exhausted the materialised window; regenerate before re-arming.
                doseRepository.materializeWindow()
                doseRepository.markOverdueAsMissed()
                scheduler.rescheduleNext()
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to re-arm after $action", t)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "BootReceiver"
    }
}
