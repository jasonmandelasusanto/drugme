package com.drugme.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
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

    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            // Denial is survivable: doses are still tracked and the in-app list stays
            // correct. It does gut the core feature, so onboarding explains the stakes
            // rather than re-prompting here.
            Log.i(TAG, "POST_NOTIFICATIONS granted=$granted")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        RearmWorker.enqueue(this)
        maybeRequestNotificationPermission()

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

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private companion object {
        const val TAG = "MainActivity"
    }
}
