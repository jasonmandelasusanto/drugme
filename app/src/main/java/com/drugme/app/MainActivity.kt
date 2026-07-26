package com.drugme.app

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.drugme.app.alarm.DoseAlarmScheduler
import com.drugme.app.alarm.RearmWorker
import com.drugme.app.data.prefs.SettingsRepository
import com.drugme.app.data.sync.SyncWorker
import com.drugme.app.data.repo.DoseRepository
import com.drugme.app.data.repo.DiseaseCatalogRepository
import com.drugme.app.data.update.AppUpdateWorker
import com.drugme.app.data.repo.DrugCatalogRepository
import com.drugme.app.ui.DrugMeApp
import com.drugme.app.ui.theme.DrugMeTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var scheduler: DoseAlarmScheduler
    @Inject lateinit var doseRepository: DoseRepository
    @Inject lateinit var catalogRepository: DrugCatalogRepository
    @Inject lateinit var diseaseCatalogRepository: DiseaseCatalogRepository
    @Inject lateinit var settingsRepository: SettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        RearmWorker.enqueue(this)
        // Periodic backup safety net, so a push doesn't depend on the user ever unlocking
        // again (the key is cached, so unlock rarely recurs). Per-change pushes are requested
        // from MedicationRepository; this catches anything they missed while offline.
        SyncWorker.enqueuePeriodic(this)
        AppUpdateWorker.enqueue(this)
        // POST_NOTIFICATIONS is requested from the onboarding Reminders step, where the
        // rationale is shown, rather than blindly here on launch (which surfaced the system
        // dialog over the disclaimer).

        lifecycleScope.launch {
            // First-launch import of the bundled catalog. Failure only costs autosuggest,
            // never the ability to add a medication by hand.
            runCatching { catalogRepository.ensureLoaded() }
                .onFailure { Log.e(TAG, "Drug catalog load failed; autosuggest unavailable", it) }
            runCatching { diseaseCatalogRepository.ensureLoaded() }
                .onFailure { Log.e(TAG, "Condition catalog load failed; suggestions unavailable", it) }
        }

        setContent {
            val savedDarkMode by settingsRepository.darkMode.collectAsStateWithLifecycle(
                initialValue = null,
            )
            DrugMeTheme(darkTheme = savedDarkMode ?: isSystemInDarkTheme()) {
                // DrugMeApp gates on onboarding / sign-in / vault unlock before handing
                // over to the nav graph.
                DrugMeApp(
                    onFixExactAlarms = {
                        scheduler.exactAlarmSettingsIntent()?.let { startActivity(it) }
                    },
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Push any local changes on every foreground: if a per-change push was dropped
        // (offline, process killed right after an edit), this is the reliable catch-up while
        // the app is open and the key is available.
        SyncWorker.enqueueNow(this)
        // Backstop #3 for the alarm chain. Both calls are idempotent, so running them on
        // every foreground is cheap and catches a chain that broke while the app was closed.
        lifecycleScope.launch {
            runCatching {
                doseRepository.materializeWindow()
                doseRepository.markOverdueAsMissed()
                scheduler.rescheduleNext()
            }.onFailure { Log.e(TAG, "Foreground re-arm failed", it) }
        }
    }

    private companion object {
        const val TAG = "MainActivity"
    }
}
