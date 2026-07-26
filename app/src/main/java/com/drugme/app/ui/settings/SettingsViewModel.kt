package com.drugme.app.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.drugme.app.alarm.DoseAlarmScheduler
import com.drugme.app.data.prefs.SettingsRepository
import com.drugme.app.data.repo.DoseRepository
import com.drugme.app.notify.DoseNotifier
import com.drugme.app.ui.onboarding.OemBatteryGuidance
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ReminderSettingsState(
    val loading: Boolean = true,
    val notificationsOk: Boolean = false,
    val exactAlarmsOk: Boolean = false,
    val batteryOptimized: Boolean = true,
    val nextReminder: Instant? = null,
    val testResult: Boolean? = null,
    val discreetNotifications: Boolean = false,
    /** Null only until the user makes an explicit appearance choice. */
    val darkMode: Boolean? = null,
) {
    val healthy: Boolean
        get() = notificationsOk && exactAlarmsOk && !batteryOptimized
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val notifier: DoseNotifier,
    private val scheduler: DoseAlarmScheduler,
    private val doseRepository: DoseRepository,
    private val settings: SettingsRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    private val refresh = MutableStateFlow(0)
    private val testResult = MutableStateFlow<Boolean?>(null)

    val state: StateFlow<ReminderSettingsState> = combine(
        refresh,
        testResult,
        settings.discreetNotifications,
        settings.darkMode,
    ) { _, test, discreet, darkMode ->
        ReminderSettingsState(
            loading = false,
            notificationsOk = notifier.canNotifyReminders(),
            exactAlarmsOk = scheduler.canScheduleExact(),
            batteryOptimized = OemBatteryGuidance.isBatteryOptimized(context),
            nextReminder = doseRepository.getNextPending()?.effectiveAt,
            testResult = test,
            discreetNotifications = discreet,
            darkMode = darkMode,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        ReminderSettingsState(),
    )

    fun refresh() {
        testResult.value = null
        refresh.value += 1
    }

    fun sendTestReminder() {
        testResult.value = notifier.notifyTest()
        refresh.value += 1
    }

    fun setDiscreetNotifications(enabled: Boolean) {
        viewModelScope.launch { settings.setDiscreetNotifications(enabled) }
    }

    fun setDarkMode(enabled: Boolean) {
        viewModelScope.launch { settings.setDarkMode(enabled) }
    }
}
