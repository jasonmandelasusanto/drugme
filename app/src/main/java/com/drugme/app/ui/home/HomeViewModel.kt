package com.drugme.app.ui.home

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.drugme.app.alarm.DoseAlarmScheduler
import com.drugme.app.data.auth.AuthRepository
import com.drugme.app.data.local.dao.DoseWithMedication
import com.drugme.app.data.local.entity.MedicationEntity
import com.drugme.app.data.repo.DoseRepository
import com.drugme.app.data.repo.MedicationRepository
import com.drugme.app.data.update.AppUpdateRepository
import com.drugme.app.data.update.AppUpdateState
import com.drugme.app.domain.model.DoseStatus
import com.drugme.app.domain.schedule.Forecast
import com.drugme.app.domain.schedule.StockForecast
import com.drugme.app.notify.DoseNotifier
import com.drugme.app.ui.onboarding.OemBatteryGuidance
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject

data class ReminderHealth(
    val notificationsOk: Boolean = false,
    val exactAlarmsOk: Boolean = false,
    val batteryOptimized: Boolean = true,
    val nextReminder: Instant? = null,
) {
    val healthy: Boolean get() = notificationsOk && exactAlarmsOk && !batteryOptimized
}

data class StockAlert(
    val medication: MedicationEntity,
    val forecast: Forecast,
)

data class HomeState(
    val date: LocalDate,
    val doses: List<DoseWithMedication> = emptyList(),
    val hasMedications: Boolean = false,
    val exactAlarmsBlocked: Boolean = false,
    val loading: Boolean = true,
    /** First name only — a full "Hello, Jason Mandela Susanto" reads like a form letter. */
    val displayName: String? = null,
    val health: ReminderHealth = ReminderHealth(),
    val stockAlerts: List<StockAlert> = emptyList(),
    val testReminderSent: Boolean? = null,
    val appUpdate: AppUpdateState = AppUpdateState(),
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val doseRepository: DoseRepository,
    private val medicationRepository: MedicationRepository,
    private val alarmScheduler: DoseAlarmScheduler,
    private val auth: AuthRepository,
    private val notifier: DoseNotifier,
    private val stockForecast: StockForecast,
    private val appUpdates: AppUpdateRepository,
    @ApplicationContext private val context: Context,
    private val clock: Clock,
) : ViewModel() {

    private val selectedDate = MutableStateFlow(LocalDate.now(clock))
    val date: StateFlow<LocalDate> = selectedDate.asStateFlow()

    private val healthRefresh = MutableStateFlow(0)
    private val testReminderSent = MutableStateFlow<Boolean?>(null)
    private val refreshState = combine(healthRefresh, testReminderSent) { _, sent -> sent }
    private val dashboardRefresh = combine(refreshState, appUpdates.state) { test, update -> test to update }

    val state: StateFlow<HomeState> =
        combine(
            selectedDate.flatMapLatest { d -> doseRepository.observeForDate(d) },
            medicationRepository.observeActive(),
            selectedDate,
            auth.authState,
            dashboardRefresh,
        ) { doses, medications, date, user, refresh ->
            val (testSent, appUpdate) = refresh
            val next = doseRepository.getNextPending()
            val today = LocalDate.now(clock)
            val alerts = medications.mapNotNull { item ->
                stockForecast.forecast(item, today, clock.zone)
                    ?.takeIf { it.needsRefillWarning }
                    ?.let { StockAlert(item.medication, it) }
            }.sortedBy { it.forecast.daysRemaining ?: Int.MAX_VALUE }
            val health = ReminderHealth(
                notificationsOk = notifier.canNotifyReminders(),
                exactAlarmsOk = alarmScheduler.canScheduleExact(),
                batteryOptimized = OemBatteryGuidance.isBatteryOptimized(context),
                nextReminder = next?.effectiveAt,
            )
            HomeState(
                date = date,
                doses = doses,
                hasMedications = medications.isNotEmpty(),
                exactAlarmsBlocked = !health.exactAlarmsOk,
                loading = false,
                displayName = user?.displayName?.trim()?.split(' ')?.firstOrNull()?.takeIf { it.isNotBlank() },
                health = health,
                stockAlerts = alerts,
                testReminderSent = testSent,
                appUpdate = appUpdate,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = HomeState(date = LocalDate.now(clock)),
        )

    /** Re-checked on resume: the user may have granted the permission in Settings. */
    fun refreshPermissionState() {
        healthRefresh.value += 1
        testReminderSent.value = null
    }

    fun sendTestReminder() {
        testReminderSent.value = notifier.notifyTest()
        healthRefresh.value += 1
    }

    fun setMedicationActive(id: String, active: Boolean) = viewModelScope.launch {
        medicationRepository.setActive(id, active)
        healthRefresh.value += 1
    }

    fun setStock(id: String, amount: Double?) = viewModelScope.launch {
        medicationRepository.setStock(id, amount)
        healthRefresh.value += 1
    }

    fun checkForUpdates() = viewModelScope.launch {
        appUpdates.checkAndDownload()
    }

    fun installUpdate(): Boolean = appUpdates.installDownloaded()

    fun unknownSourcesIntent() = appUpdates.unknownSourcesIntent()

    fun showDate(date: LocalDate) {
        selectedDate.value = date
    }

    fun today() {
        selectedDate.value = LocalDate.now(clock)
    }

    fun markTaken(doseId: String) = viewModelScope.launch {
        doseRepository.markTaken(doseId)
        // Marking a dose taken removes it from the pending queue, so the armed alarm may
        // now point at a dose that no longer needs one.
        alarmScheduler.rescheduleNext()
    }

    fun markSkipped(doseId: String) = viewModelScope.launch {
        doseRepository.markSkipped(doseId)
        alarmScheduler.rescheduleNext()
    }

    fun snooze(doseId: String) = viewModelScope.launch {
        doseRepository.snooze(doseId)
        alarmScheduler.rescheduleNext()
    }

    /**
     * Changes an already-decided dose.
     *
     * Re-arms afterwards because moving a dose back to PENDING can make it the next one
     * due, and the alarm chain has no other way to learn that.
     */
    fun setStatus(doseId: String, status: DoseStatus) = viewModelScope.launch {
        doseRepository.setStatus(doseId, status)
        alarmScheduler.rescheduleNext()
    }
}
