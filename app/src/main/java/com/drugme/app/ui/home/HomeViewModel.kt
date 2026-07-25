package com.drugme.app.ui.home

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
import java.time.LocalDate
import javax.inject.Inject

data class StockAlert(
    val medication: MedicationEntity,
    val forecast: Forecast,
)

data class HomeState(
    val date: LocalDate,
    val doses: List<DoseWithMedication> = emptyList(),
    val overdueDoses: Int = 0,
    val hasMedications: Boolean = false,
    val loading: Boolean = true,
    /** First name only — a full "Hello, Jason Mandela Susanto" reads like a form letter. */
    val displayName: String? = null,
    val stockAlerts: List<StockAlert> = emptyList(),
    val appUpdate: AppUpdateState = AppUpdateState(),
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val doseRepository: DoseRepository,
    private val medicationRepository: MedicationRepository,
    private val alarmScheduler: DoseAlarmScheduler,
    private val auth: AuthRepository,
    private val stockForecast: StockForecast,
    private val appUpdates: AppUpdateRepository,
    private val clock: Clock,
) : ViewModel() {

    private val selectedDate = MutableStateFlow(LocalDate.now(clock))
    val date: StateFlow<LocalDate> = selectedDate.asStateFlow()

    val state: StateFlow<HomeState> =
        combine(
            selectedDate.flatMapLatest { d -> doseRepository.observeForDate(d) },
            medicationRepository.observeActive(),
            selectedDate,
            auth.authState,
            appUpdates.state,
        ) { doses, medications, date, user, appUpdate ->
            val today = LocalDate.now(clock)
            val alerts = medications.mapNotNull { item ->
                stockForecast.forecast(item, today, clock.zone)
                    ?.takeIf { it.needsRefillWarning }
                    ?.let { StockAlert(item.medication, it) }
            }.sortedBy { it.forecast.daysRemaining ?: Int.MAX_VALUE }
            HomeState(
                date = date,
                doses = doses,
                overdueDoses = doses.count {
                    it.dose.status == DoseStatus.PENDING && it.dose.effectiveAt.isBefore(clock.instant())
                },
                hasMedications = medications.isNotEmpty(),
                loading = false,
                displayName = user?.displayName?.trim()?.split(' ')?.firstOrNull()?.takeIf { it.isNotBlank() },
                stockAlerts = alerts,
                appUpdate = appUpdate,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = HomeState(date = LocalDate.now(clock)),
        )

    fun setMedicationActive(id: String, active: Boolean) = viewModelScope.launch {
        medicationRepository.setActive(id, active)
    }

    fun setStock(id: String, amount: Double?) = viewModelScope.launch {
        medicationRepository.setStock(id, amount)
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
