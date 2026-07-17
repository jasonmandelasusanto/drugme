package com.drugme.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.drugme.app.alarm.DoseAlarmScheduler
import com.drugme.app.data.auth.AuthRepository
import com.drugme.app.data.local.dao.DoseWithMedication
import com.drugme.app.data.repo.DoseRepository
import com.drugme.app.data.repo.MedicationRepository
import com.drugme.app.domain.model.DoseStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject

data class HomeState(
    val date: LocalDate,
    val doses: List<DoseWithMedication> = emptyList(),
    val hasMedications: Boolean = false,
    val exactAlarmsBlocked: Boolean = false,
    val loading: Boolean = true,
    /** First name only — a full "Hello, Jason Mandela Susanto" reads like a form letter. */
    val displayName: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val doseRepository: DoseRepository,
    private val medicationRepository: MedicationRepository,
    private val alarmScheduler: DoseAlarmScheduler,
    private val auth: AuthRepository,
    private val clock: Clock,
) : ViewModel() {

    private val selectedDate = MutableStateFlow(LocalDate.now(clock))
    val date: StateFlow<LocalDate> = selectedDate.asStateFlow()

    private val exactAlarmsBlocked = MutableStateFlow(!alarmScheduler.canScheduleExact())

    val state: StateFlow<HomeState> =
        combine(
            selectedDate.flatMapLatest { d -> doseRepository.observeForDate(d) },
            medicationRepository.observeActive().map { it.isNotEmpty() },
            selectedDate,
            exactAlarmsBlocked,
            auth.authState,
        ) { doses, hasMeds, date, blocked, user ->
            HomeState(
                date = date,
                doses = doses,
                hasMedications = hasMeds,
                exactAlarmsBlocked = blocked,
                loading = false,
                displayName = user?.displayName?.trim()?.split(' ')?.firstOrNull()?.takeIf { it.isNotBlank() },
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = HomeState(date = LocalDate.now(clock)),
        )

    /** Re-checked on resume: the user may have granted the permission in Settings. */
    fun refreshPermissionState() {
        exactAlarmsBlocked.value = !alarmScheduler.canScheduleExact()
    }

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
