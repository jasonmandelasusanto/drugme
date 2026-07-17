package com.drugme.app.ui.addmed

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.drugme.app.data.local.entity.MedicationEntity
import com.drugme.app.data.local.entity.ScheduleEntity
import com.drugme.app.data.repo.DrugCatalogRepository
import com.drugme.app.data.repo.DrugSuggestion
import com.drugme.app.data.repo.MedicationRepository
import com.drugme.app.domain.model.DiseaseRef
import com.drugme.app.domain.model.DoseUnit
import com.drugme.app.domain.model.ScheduleType
import com.drugme.app.domain.model.WeekdayMask
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID
import javax.inject.Inject

data class AddMedicationState(
    val id: String? = null,
    val name: String = "",
    val rxcui: String? = null,
    val suggestions: List<DrugSuggestion> = emptyList(),
    val showSuggestions: Boolean = false,

    /** Indications offered once a catalog drug is picked. Empty for free-text entries. */
    val availableDiseases: List<DiseaseRef> = emptyList(),
    val selectedDisease: DiseaseRef? = null,

    val doseAmount: String = "1",
    val doseUnit: DoseUnit = DoseUnit.MG,

    val scheduleType: ScheduleType = ScheduleType.TIMES_PER_DAY,
    val timesOfDay: List<LocalTime> = listOf(LocalTime.of(8, 0)),
    val weekdays: WeekdayMask = WeekdayMask.EVERY_DAY,
    val intervalDays: Int = 2,

    val startDate: LocalDate = LocalDate.now(),
    val endDate: LocalDate? = null,

    val notes: String = "",
    val saving: Boolean = false,
    val saved: Boolean = false,
    val error: String? = null,
) {
    val doseAmountValue: Double? get() = doseAmount.replace(',', '.').toDoubleOrNull()

    /**
     * Validation gate for the save button. Kept as derived state rather than checked in
     * save() so the button reflects it live.
     */
    val canSave: Boolean
        get() = name.isNotBlank() &&
            (doseAmountValue?.let { it > 0 } == true) &&
            timesOfDay.isNotEmpty() &&
            (scheduleType != ScheduleType.DAYS_OF_WEEK || !weekdays.isEmpty) &&
            (scheduleType != ScheduleType.INTERVAL_DAYS || intervalDays >= 1) &&
            (endDate == null || !endDate.isBefore(startDate))
}

@HiltViewModel
class AddMedicationViewModel @Inject constructor(
    private val medicationRepository: MedicationRepository,
    private val catalogRepository: DrugCatalogRepository,
    private val clock: Clock,
) : ViewModel() {

    private val _state = MutableStateFlow(AddMedicationState(startDate = LocalDate.now(clock)))
    val state: StateFlow<AddMedicationState> = _state.asStateFlow()

    private val queryFlow = MutableStateFlow("")

    init {
        observeQuery()
    }

    @OptIn(FlowPreview::class)
    private fun observeQuery() {
        viewModelScope.launch {
            queryFlow
                .debounce(180) // A catalog query per keystroke would thrash SQLite for results nobody reads.
                .distinctUntilChanged()
                .map { q -> if (q.length < 2) emptyList() else catalogRepository.search(q, limit = 12) }
                .collect { results ->
                    _state.value = _state.value.copy(
                        suggestions = results,
                        showSuggestions = results.isNotEmpty(),
                    )
                }
        }
    }

    /** Loads an existing medication for editing. */
    fun load(medicationId: String) {
        viewModelScope.launch {
            val existing = medicationRepository.getById(medicationId) ?: return@launch
            val med = existing.medication
            val schedule = existing.schedules.firstOrNull()

            val diseases = med.rxcui?.let { catalogRepository.getByRxcui(it)?.diseases } ?: emptyList()

            _state.value = _state.value.copy(
                id = med.id,
                name = med.name,
                rxcui = med.rxcui,
                availableDiseases = diseases,
                selectedDisease = med.diseaseId?.let { id ->
                    DiseaseRef(id, med.diseaseName ?: "")
                },
                doseAmount = formatAmount(med.doseAmount),
                doseUnit = med.doseUnit,
                notes = med.notes.orEmpty(),
                scheduleType = schedule?.type ?: ScheduleType.TIMES_PER_DAY,
                timesOfDay = schedule?.timesOfDay ?: listOf(LocalTime.of(8, 0)),
                weekdays = schedule?.weekdays ?: WeekdayMask.EVERY_DAY,
                intervalDays = schedule?.intervalDays ?: 2,
                startDate = schedule?.startDate ?: LocalDate.now(clock),
                endDate = schedule?.endDate,
            )
        }
    }

    fun onNameChange(value: String) {
        // Typing after picking a catalog entry detaches the rxcui: the stored concept must
        // never disagree with the name on screen.
        _state.value = _state.value.copy(
            name = value,
            rxcui = if (value != _state.value.name) null else _state.value.rxcui,
        )
        queryFlow.value = value
    }

    fun onSuggestionPicked(suggestion: DrugSuggestion) {
        _state.value = _state.value.copy(
            name = suggestion.name,
            rxcui = suggestion.rxcui,
            availableDiseases = suggestion.diseases,
            // Preselect only when unambiguous. Guessing among several indications would
            // put a condition on the user's record that they never chose.
            selectedDisease = suggestion.diseases.singleOrNull(),
            suggestions = emptyList(),
            showSuggestions = false,
        )
        queryFlow.value = suggestion.name
    }

    fun dismissSuggestions() {
        _state.value = _state.value.copy(showSuggestions = false)
    }

    fun onDiseaseSelected(disease: DiseaseRef?) {
        _state.value = _state.value.copy(selectedDisease = disease)
    }

    fun onDoseAmountChange(value: String) {
        // Permit only digits and one separator so the field can't hold something
        // unparseable by the time save runs.
        val cleaned = value.filter { it.isDigit() || it == '.' || it == ',' }
        _state.value = _state.value.copy(doseAmount = cleaned)
    }

    fun onDoseUnitChange(unit: DoseUnit) {
        _state.value = _state.value.copy(doseUnit = unit)
    }

    fun onScheduleTypeChange(type: ScheduleType) {
        _state.value = _state.value.copy(scheduleType = type)
    }

    fun onTimeChanged(index: Int, time: LocalTime) {
        val times = _state.value.timesOfDay.toMutableList()
        if (index in times.indices) times[index] = time
        _state.value = _state.value.copy(timesOfDay = times.sorted())
    }

    fun onAddTime() {
        val times = _state.value.timesOfDay
        // Offer a sensible next slot rather than a duplicate of the last one, which the
        // generator would collapse anyway.
        val next = (times.maxOrNull() ?: LocalTime.of(8, 0)).plusHours(4)
        _state.value = _state.value.copy(timesOfDay = (times + next).distinct().sorted())
    }

    fun onRemoveTime(index: Int) {
        val times = _state.value.timesOfDay.toMutableList()
        if (times.size <= 1) return // A schedule with no times generates no reminders at all.
        times.removeAt(index)
        _state.value = _state.value.copy(timesOfDay = times)
    }

    fun onWeekdayToggled(day: DayOfWeek) {
        val current = _state.value.weekdays
        _state.value = _state.value.copy(weekdays = current.with(day, day !in current))
    }

    fun onIntervalChange(days: Int) {
        _state.value = _state.value.copy(intervalDays = days.coerceAtLeast(1))
    }

    fun onStartDateChange(date: LocalDate) {
        val s = _state.value
        _state.value = s.copy(
            startDate = date,
            // Keep the "until" date from silently preceding the start.
            endDate = s.endDate?.takeIf { !it.isBefore(date) },
        )
    }

    fun onEndDateChange(date: LocalDate?) {
        _state.value = _state.value.copy(endDate = date)
    }

    fun onNotesChange(value: String) {
        _state.value = _state.value.copy(notes = value)
    }

    fun save() {
        val s = _state.value
        if (!s.canSave || s.saving) return
        val amount = s.doseAmountValue ?: return

        _state.value = s.copy(saving = true, error = null)
        viewModelScope.launch {
            runCatching {
                val now = clock.instant()
                val medId = s.id ?: UUID.randomUUID().toString()
                val existing = s.id?.let { medicationRepository.getById(it) }

                val medication = MedicationEntity(
                    id = medId,
                    name = s.name.trim(),
                    rxcui = s.rxcui,
                    doseAmount = amount,
                    doseUnit = s.doseUnit,
                    diseaseId = s.selectedDisease?.id,
                    diseaseName = s.selectedDisease?.name,
                    notes = s.notes.trim().ifBlank { null },
                    isActive = existing?.medication?.isActive ?: true,
                    createdAt = existing?.medication?.createdAt ?: now,
                    updatedAt = now,
                )

                val schedule = ScheduleEntity(
                    // Reuse the schedule id when editing so its existing doses are updated
                    // in place rather than orphaned.
                    id = existing?.schedules?.firstOrNull()?.id ?: UUID.randomUUID().toString(),
                    medicationId = medId,
                    type = s.scheduleType,
                    timesOfDay = s.timesOfDay,
                    weekdays = s.weekdays,
                    intervalDays = s.intervalDays,
                    startDate = s.startDate,
                    endDate = s.endDate,
                    createdAt = existing?.schedules?.firstOrNull()?.createdAt ?: now,
                    updatedAt = now,
                )

                medicationRepository.save(medication, listOf(schedule))
            }.onSuccess {
                _state.value = _state.value.copy(saving = false, saved = true)
            }.onFailure { t ->
                _state.value = _state.value.copy(saving = false, error = t.message ?: "Could not save")
            }
        }
    }

    private fun formatAmount(v: Double): String =
        if (v % 1.0 == 0.0) v.toLong().toString() else v.toString()
}
