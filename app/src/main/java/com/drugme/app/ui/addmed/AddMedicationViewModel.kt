package com.drugme.app.ui.addmed

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.drugme.app.data.local.entity.MedicationEntity
import com.drugme.app.data.local.entity.ScheduleEntity
import com.drugme.app.data.medical.AutocompleteOutcome
import com.drugme.app.data.medical.DiseaseAutocompleteSource
import com.drugme.app.data.medical.MedicationAutocompleteSource
import com.drugme.app.data.repo.DrugSuggestion
import com.drugme.app.data.repo.MedicationRepository
import com.drugme.app.domain.model.DiseaseRef
import com.drugme.app.domain.model.DoseUnit
import com.drugme.app.domain.model.FoodRelation
import com.drugme.app.domain.model.ScheduleType
import com.drugme.app.domain.model.WeekdayMask
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID
import javax.inject.Inject

enum class SuggestionState {
    IDLE,
    LOADING,
    RESULTS,
    EMPTY,
    OFFLINE,
    ERROR,
}

data class AddMedicationState(
    val id: String? = null,

    // --- drug ---
    val name: String = "",
    val rxcui: String? = null,
    val drugSuggestions: List<DrugSuggestion> = emptyList(),
    val showDrugSuggestions: Boolean = false,
    val drugFieldFocused: Boolean = false,
    val drugSuggestionState: SuggestionState = SuggestionState.IDLE,
    val drugRemoteUnavailable: Boolean = false,
    val drugSelectionVersion: Long = 0,

    // --- condition ---
    // The user's own statement, typed and searched independently. Deliberately NOT derived
    // from the chosen drug — see MedicationEntity.diseaseId.
    val diseaseQuery: String = "",
    val diseaseSuggestions: List<DiseaseRef> = emptyList(),
    val showDiseaseSuggestions: Boolean = false,
    val diseaseFieldFocused: Boolean = false,
    val selectedDisease: DiseaseRef? = null,
    val diseaseSuggestionState: SuggestionState = SuggestionState.IDLE,
    val diseaseSelectionVersion: Long = 0,

    // --- dose ---
    val doseAmount: String = "1",
    val doseUnit: DoseUnit = DoseUnit.MG,
    val foodRelation: FoodRelation = FoodRelation.ANY,

    // --- schedule ---
    val scheduleType: ScheduleType = ScheduleType.TIMES_PER_DAY,
    val timesOfDay: List<LocalTime> = listOf(LocalTime.of(8, 0)),
    /** Empty entry means this time follows [doseAmount]. */
    val doseAmountByTime: Map<LocalTime, String> = emptyMap(),
    val weekdays: WeekdayMask = WeekdayMask.EVERY_DAY,
    val intervalDays: Int = 2,
    val startDate: LocalDate = LocalDate.now(),
    val endDate: LocalDate? = null,

    // --- stock ---
    val trackStock: Boolean = false,
    val stockAmount: String = "",
    val stockUnit: DoseUnit = DoseUnit.TABLET,
    val stockPerDose: String = "1",
    val refillReminderDays: Int = 7,

    val notes: String = "",
    val saving: Boolean = false,
    val saved: Boolean = false,
    val error: String? = null,
) {
    val doseAmountValue: Double? get() = doseAmount.replace(',', '.').toDoubleOrNull()
    val stockAmountValue: Double? get() = stockAmount.replace(',', '.').toDoubleOrNull()
    val stockPerDoseValue: Double? get() = stockPerDose.replace(',', '.').toDoubleOrNull()

    val canSave: Boolean
        get() = name.isNotBlank() &&
            (doseAmountValue?.let { it > 0 } == true) &&
            timesOfDay.isNotEmpty() &&
            (scheduleType != ScheduleType.DAYS_OF_WEEK || !weekdays.isEmpty) &&
            (scheduleType != ScheduleType.INTERVAL_DAYS || intervalDays >= 1) &&
            (endDate == null || !endDate.isBefore(startDate)) &&
            (!trackStock || (
                stockAmountValue?.let { it >= 0 } == true &&
                    stockPerDoseValue?.let { it > 0 } == true
                )) &&
            doseAmountByTime.values.all { it.replace(',', '.').toDoubleOrNull()?.let { n -> n > 0 } == true }
}

@HiltViewModel
class AddMedicationViewModel @Inject constructor(
    private val medicationRepository: MedicationRepository,
    private val medicationAutocomplete: MedicationAutocompleteSource,
    private val diseaseAutocomplete: DiseaseAutocompleteSource,
    private val clock: Clock,
) : ViewModel() {

    private val _state = MutableStateFlow(AddMedicationState(startDate = LocalDate.now(clock)))
    val state: StateFlow<AddMedicationState> = _state.asStateFlow()

    private val drugQuery = MutableStateFlow("")
    private val diseaseQueryFlow = MutableStateFlow("")

    init {
        observeDrugQuery()
        observeDiseaseQuery()
    }

    @OptIn(FlowPreview::class)
    private fun observeDrugQuery() {
        viewModelScope.launch {
            drugQuery
                .debounce(220)
                .distinctUntilChanged()
                .collectLatest { raw ->
                    val q = raw.trim()
                    if (q.isBlank()) {
                        val popular = medicationAutocomplete.popular()
                        if (_state.value.name.isBlank()) {
                            _state.value = _state.value.copy(
                                drugSuggestions = popular,
                                drugSuggestionState = SuggestionState.IDLE,
                                showDrugSuggestions = _state.value.drugFieldFocused,
                            )
                        }
                        return@collectLatest
                    }
                    if (q.length < 2) {
                        if (_state.value.name == raw) {
                            _state.value = _state.value.copy(
                                drugSuggestions = emptyList(),
                                showDrugSuggestions = false,
                                drugSuggestionState = SuggestionState.IDLE,
                            )
                        }
                        return@collectLatest
                    }

                    if (_state.value.name == raw) {
                        _state.value = _state.value.copy(
                            drugSuggestionState = SuggestionState.LOADING,
                            showDrugSuggestions = true,
                            drugRemoteUnavailable = false,
                        )
                    }
                    val outcome = medicationAutocomplete.search(q, limit = 12)
                    if (_state.value.name != raw) return@collectLatest
                    applyDrugOutcome(outcome)
                }
        }
    }

    @OptIn(FlowPreview::class)
    private fun observeDiseaseQuery() {
        viewModelScope.launch {
            diseaseQueryFlow
                .debounce(220)
                .distinctUntilChanged()
                .collectLatest { raw ->
                    val q = raw.trim()
                    if (q.isBlank()) {
                        val popular = diseaseAutocomplete.popular()
                        if (_state.value.diseaseQuery.isBlank()) {
                            _state.value = _state.value.copy(
                                diseaseSuggestions = popular,
                                diseaseSuggestionState = SuggestionState.IDLE,
                                showDiseaseSuggestions = _state.value.diseaseFieldFocused,
                            )
                        }
                        return@collectLatest
                    }
                    if (q.length < 2) {
                        if (_state.value.diseaseQuery == raw) {
                            _state.value = _state.value.copy(
                                diseaseSuggestions = emptyList(),
                                showDiseaseSuggestions = false,
                                diseaseSuggestionState = SuggestionState.IDLE,
                            )
                        }
                        return@collectLatest
                    }

                    if (_state.value.diseaseQuery == raw) {
                        _state.value = _state.value.copy(
                            diseaseSuggestionState = SuggestionState.LOADING,
                            showDiseaseSuggestions = true,
                        )
                    }
                    val outcome = diseaseAutocomplete.search(q, limit = 12)
                    if (_state.value.diseaseQuery != raw) return@collectLatest
                    applyDiseaseOutcome(outcome)
                }
        }
    }

    private fun applyDrugOutcome(outcome: AutocompleteOutcome<DrugSuggestion>) {
        _state.value = when (outcome) {
            is AutocompleteOutcome.Results -> _state.value.copy(
                drugSuggestions = outcome.items,
                showDrugSuggestions = true,
                drugSuggestionState = if (outcome.items.isEmpty()) SuggestionState.EMPTY
                else SuggestionState.RESULTS,
                drugRemoteUnavailable = outcome.remoteUnavailable,
            )
            AutocompleteOutcome.Offline -> _state.value.copy(
                drugSuggestions = emptyList(),
                showDrugSuggestions = true,
                drugSuggestionState = SuggestionState.OFFLINE,
            )
            is AutocompleteOutcome.Error -> _state.value.copy(
                drugSuggestions = emptyList(),
                showDrugSuggestions = true,
                drugSuggestionState = SuggestionState.ERROR,
            )
        }
    }

    private fun applyDiseaseOutcome(outcome: AutocompleteOutcome<DiseaseRef>) {
        _state.value = when (outcome) {
            is AutocompleteOutcome.Results -> _state.value.copy(
                diseaseSuggestions = outcome.items,
                showDiseaseSuggestions = true,
                diseaseSuggestionState = if (outcome.items.isEmpty()) SuggestionState.EMPTY
                else SuggestionState.RESULTS,
            )
            AutocompleteOutcome.Offline -> _state.value.copy(
                diseaseSuggestions = emptyList(),
                showDiseaseSuggestions = true,
                diseaseSuggestionState = SuggestionState.OFFLINE,
            )
            is AutocompleteOutcome.Error -> _state.value.copy(
                diseaseSuggestions = emptyList(),
                showDiseaseSuggestions = true,
                diseaseSuggestionState = SuggestionState.ERROR,
            )
        }
    }

    fun load(medicationId: String) {
        viewModelScope.launch {
            val existing = medicationRepository.getById(medicationId) ?: return@launch
            val med = existing.medication
            val schedule = existing.schedules.firstOrNull()
            val amountsByTime = existing.schedules.flatMap { s ->
                s.timesOfDay.map { time ->
                    time to formatAmount(s.doseAmount ?: med.doseAmount)
                }
            }.toMap()

            _state.value = _state.value.copy(
                id = med.id,
                name = med.name,
                rxcui = med.rxcui,
                selectedDisease = med.diseaseId?.let { DiseaseRef(it, med.diseaseName.orEmpty()) },
                diseaseQuery = med.diseaseName.orEmpty(),
                doseAmount = formatAmount(med.doseAmount),
                doseUnit = med.doseUnit,
                foodRelation = med.foodRelation,
                trackStock = med.stockAmount != null,
                stockAmount = med.stockAmount?.let(::formatAmount).orEmpty(),
                stockUnit = med.stockUnit ?: med.doseUnit,
                stockPerDose = formatAmount(med.stockPerDose ?: med.doseAmount),
                refillReminderDays = med.refillReminderDays,
                notes = med.notes.orEmpty(),
                scheduleType = schedule?.type ?: ScheduleType.TIMES_PER_DAY,
                timesOfDay = existing.schedules.flatMap { it.timesOfDay }.distinct().sorted()
                    .ifEmpty { listOf(LocalTime.of(8, 0)) },
                doseAmountByTime = amountsByTime,
                weekdays = schedule?.weekdays ?: WeekdayMask.EVERY_DAY,
                intervalDays = schedule?.intervalDays ?: 2,
                startDate = schedule?.startDate ?: LocalDate.now(clock),
                endDate = schedule?.endDate,
            )
        }
    }

    // --- drug ---

    fun onNameChange(value: String) {
        // Typing after picking a catalog entry detaches the rxcui: the stored concept must
        // never disagree with the name on screen.
        _state.value = _state.value.copy(
            name = value,
            rxcui = if (value != _state.value.name) null else _state.value.rxcui,
            showDrugSuggestions = true,
        )
        drugQuery.value = value
    }

    fun onDrugPicked(suggestion: DrugSuggestion) {
        // Note what does NOT happen here: the drug's RxNorm indications are not used to
        // guess the user's condition. The condition is their own statement.
        _state.value = _state.value.copy(
            name = suggestion.name,
            rxcui = suggestion.rxcui,
            drugSuggestions = emptyList(),
            showDrugSuggestions = false,
            drugSuggestionState = SuggestionState.IDLE,
            drugSelectionVersion = _state.value.drugSelectionVersion + 1,
        )
    }

    fun clearDrug() {
        _state.value = _state.value.copy(
            name = "",
            rxcui = null,
            showDrugSuggestions = false,
            drugSuggestionState = SuggestionState.IDLE,
        )
        drugQuery.value = ""
    }

    fun onDrugFocusChanged(focused: Boolean) {
        _state.value = _state.value.copy(
            drugFieldFocused = focused,
            showDrugSuggestions = focused && _state.value.rxcui == null &&
                (_state.value.drugSuggestions.isNotEmpty() || _state.value.name.length >= 2),
        )
    }

    fun dismissDrugSuggestions() {
        _state.value = _state.value.copy(showDrugSuggestions = false)
    }

    // --- condition ---

    fun onDiseaseQueryChange(value: String) {
        _state.value = _state.value.copy(
            diseaseQuery = value,
            // Editing the text detaches the selection, so a stored MeSH id can't drift away
            // from the words on screen.
            selectedDisease = if (value != _state.value.selectedDisease?.name) null else _state.value.selectedDisease,
            showDiseaseSuggestions = true,
        )
        diseaseQueryFlow.value = value
    }

    fun onDiseasePicked(disease: DiseaseRef) {
        _state.value = _state.value.copy(
            selectedDisease = disease,
            diseaseQuery = disease.name,
            diseaseSuggestions = emptyList(),
            showDiseaseSuggestions = false,
            diseaseSuggestionState = SuggestionState.IDLE,
            diseaseSelectionVersion = _state.value.diseaseSelectionVersion + 1,
        )
    }

    fun clearDisease() {
        _state.value = _state.value.copy(
            selectedDisease = null,
            diseaseQuery = "",
            showDiseaseSuggestions = false,
            diseaseSuggestionState = SuggestionState.IDLE,
        )
        diseaseQueryFlow.value = ""
    }

    fun onDiseaseFocusChanged(focused: Boolean) {
        _state.value = _state.value.copy(
            diseaseFieldFocused = focused,
            showDiseaseSuggestions = focused && _state.value.selectedDisease == null &&
                (_state.value.diseaseSuggestions.isNotEmpty() || _state.value.diseaseQuery.length >= 2),
        )
    }

    fun dismissDiseaseSuggestions() {
        _state.value = _state.value.copy(showDiseaseSuggestions = false)
    }

    // --- dose ---

    fun onDoseAmountChange(value: String) {
        _state.value = _state.value.copy(doseAmount = value.filter { it.isDigit() || it == '.' || it == ',' })
    }

    fun onDoseUnitChange(unit: DoseUnit) {
        _state.value = _state.value.copy(doseUnit = unit)
    }

    fun onFoodRelationChange(value: FoodRelation) {
        _state.value = _state.value.copy(foodRelation = value)
    }

    // --- stock ---

    fun onTrackStockChange(enabled: Boolean) {
        _state.value = _state.value.copy(trackStock = enabled)
    }

    fun onStockAmountChange(value: String) {
        _state.value = _state.value.copy(stockAmount = value.filter { it.isDigit() || it == '.' || it == ',' })
    }

    fun onStockUnitChange(unit: DoseUnit) {
        _state.value = _state.value.copy(stockUnit = unit)
    }

    fun onStockPerDoseChange(value: String) {
        _state.value = _state.value.copy(
            stockPerDose = value.filter { it.isDigit() || it == '.' || it == ',' }
        )
    }

    fun onRefillDaysChange(days: Int) {
        _state.value = _state.value.copy(refillReminderDays = days.coerceIn(1, 90))
    }

    // --- schedule ---

    fun onScheduleTypeChange(type: ScheduleType) {
        _state.value = _state.value.copy(scheduleType = type)
    }

    fun onTimeChanged(index: Int, time: LocalTime) {
        val times = _state.value.timesOfDay.toMutableList()
        if (index !in times.indices) return
        val old = times[index]
        times[index] = time
        val amounts = _state.value.doseAmountByTime.toMutableMap()
        amounts.remove(old)?.let { amounts[time] = it }
        _state.value = _state.value.copy(timesOfDay = times.distinct().sorted(), doseAmountByTime = amounts)
    }

    fun onTimeDoseAmountChange(time: LocalTime, value: String) {
        _state.value = _state.value.copy(
            doseAmountByTime = _state.value.doseAmountByTime + (
                time to value.filter { it.isDigit() || it == '.' || it == ',' }
                )
        )
    }

    fun onAddTime() {
        val times = _state.value.timesOfDay
        val next = (times.maxOrNull() ?: LocalTime.of(8, 0)).plusHours(4)
        _state.value = _state.value.copy(timesOfDay = (times + next).distinct().sorted())
    }

    fun onRemoveTime(index: Int) {
        val times = _state.value.timesOfDay.toMutableList()
        if (times.size <= 1) return // A schedule with no times produces no reminders at all.
        val removed = times.removeAt(index)
        _state.value = _state.value.copy(
            timesOfDay = times,
            doseAmountByTime = _state.value.doseAmountByTime - removed,
        )
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
                    foodRelation = s.foodRelation,
                    stockAmount = if (s.trackStock) s.stockAmountValue else null,
                    stockUnit = if (s.trackStock) s.stockUnit else null,
                    stockPerDose = if (s.trackStock) s.stockPerDoseValue else null,
                    refillReminderDays = s.refillReminderDays,
                    // Editing keeps any existing warning flag unless stock tracking was
                    // turned off, in which case it is meaningless.
                    refillNotifiedAt = if (s.trackStock) existing?.medication?.refillNotifiedAt else null,
                    diseaseId = s.selectedDisease?.id,
                    diseaseName = s.selectedDisease?.name
                        ?: s.diseaseQuery.trim().takeIf(String::isNotBlank),
                    notes = s.notes.trim().ifBlank { null },
                    isActive = existing?.medication?.isActive ?: true,
                    createdAt = existing?.medication?.createdAt ?: now,
                    updatedAt = now,
                )

                val reusedScheduleIds = mutableSetOf<String>()
                val schedules = s.timesOfDay.map { time ->
                    // An older schedule can contain several times. Reuse it for the first
                    // matching time only, then give every additional time its own id.
                    // Reusing one primary key twice would make Room upsert only the final
                    // row and silently drop the earlier time.
                    val old = existing?.schedules?.firstOrNull {
                        it.id !in reusedScheduleIds && time in it.timesOfDay
                    }
                    old?.let { reusedScheduleIds += it.id }
                    ScheduleEntity(
                        id = old?.id ?: UUID.randomUUID().toString(),
                        medicationId = medId,
                        type = s.scheduleType,
                        timesOfDay = listOf(time),
                        doseAmount = s.doseAmountByTime[time]
                            ?.replace(',', '.')
                            ?.toDoubleOrNull()
                            ?: amount,
                        doseUnit = s.doseUnit,
                        weekdays = s.weekdays,
                        intervalDays = s.intervalDays,
                        startDate = s.startDate,
                        endDate = s.endDate,
                        createdAt = old?.createdAt ?: now,
                        updatedAt = now,
                    )
                }

                medicationRepository.save(medication, schedules)
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
