package com.drugme.app

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import com.drugme.app.alarm.DoseAlarmScheduler
import com.drugme.app.alarm.RearmWorker
import com.drugme.app.data.repo.DoseRepository
import com.drugme.app.data.repo.DiseaseCatalogRepository
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        RearmWorker.enqueue(this)
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
            DrugMeTheme {
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
