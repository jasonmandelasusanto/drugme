package com.drugme.app.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.drugme.app.data.repo.DoseRepository
import com.drugme.app.notify.DoseNotifier
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Fires when a dose is due: posts the reminder, then arms the next link in the chain.
 *
 * Nothing here touches the sync encryption key. Doses live in plaintext Room precisely so
 * this path works on a *locked* device and whether or not the user has ever entered their
 * passphrase on this boot — a reminder engine gated on decryption would go dark every
 * night, exactly when people are asleep and relying on it.
 *
 * After a reboot with no subsequent unlock, Room is still unavailable. That narrow period is
 * covered by [DirectBootReminderReceiver], which knows only anonymous reminder timestamps and
 * posts a generic notification until this full receiver can take over after first unlock.
 */
@AndroidEntryPoint
class DoseAlarmReceiver : BroadcastReceiver() {

    @Inject lateinit var doseRepository: DoseRepository
    @Inject lateinit var notifier: DoseNotifier
    @Inject lateinit var scheduler: DoseAlarmScheduler

    override fun onReceive(context: Context, intent: Intent) {
        val doseId = intent.getStringExtra(EXTRA_DOSE_ID)
        Log.i(TAG, "Alarm fired: action=${intent.action} dose=$doseId")

        // onReceive runs on the main thread and is killed after ~10s. goAsync buys a
        // window for the database work while keeping the process alive.
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                when (intent.action) {
                    ACTION_DOSE_DUE -> handleDoseDue(doseId)
                    ACTION_TAKEN -> doseId?.let { doseRepository.markTaken(it); notifier.dismiss(it) }
                    ACTION_SKIP -> doseId?.let { doseRepository.markSkipped(it); notifier.dismiss(it) }
                    ACTION_SNOOZE -> doseId?.let { doseRepository.snooze(it); notifier.dismiss(it) }
                    else -> Log.w(TAG, "Unknown action ${intent.action}")
                }
            } catch (t: Throwable) {
                // Never let a failure here end the chain. An exception thrown while
                // posting one notification must not cost the user every future reminder.
                Log.e(TAG, "Error handling ${intent.action}", t)
            } finally {
                try {
                    scheduler.rescheduleNext()
                } catch (t: Throwable) {
                    Log.e(TAG, "Failed to re-arm alarm chain; RearmWorker will heal it.", t)
                }
                pending.finish()
            }
        }
    }

    private suspend fun handleDoseDue(doseId: String?) {
        doseRepository.markOverdueAsMissed()

        // Notify every dose that is due now, not just the one the alarm was armed for. The
        // chain arms a single dose, but medications sharing a time all fall due together;
        // firing on only the armed dose is why simultaneous reminders showed just one drug.
        // getDuePending already filters to PENDING, so anything the user handled between
        // arming and firing is excluded.
        val due = doseRepository.getDuePending()
        if (due.isEmpty()) {
            Log.i(TAG, "Alarm fired for $doseId but nothing is due; likely already handled.")
            return
        }
        due.forEach { notifier.notifyDose(it) }
    }

    companion object {
        private const val TAG = "DoseAlarmReceiver"
        const val ACTION_DOSE_DUE = "com.drugme.app.action.DOSE_DUE"
        const val ACTION_TAKEN = "com.drugme.app.action.TAKEN"
        const val ACTION_SKIP = "com.drugme.app.action.SKIP"
        const val ACTION_SNOOZE = "com.drugme.app.action.SNOOZE"
        const val EXTRA_DOSE_ID = "dose_id"
    }
}
