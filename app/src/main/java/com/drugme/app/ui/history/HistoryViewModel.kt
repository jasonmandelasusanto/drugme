package com.drugme.app.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.drugme.app.data.local.dao.DoseWithMedication
import com.drugme.app.data.repo.DoseRepository
import com.drugme.app.domain.model.DoseStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Adherence over a window.
 *
 * [taken] / [missed] / [skipped] are kept distinct rather than collapsed into a single
 * percentage. Skipping is a decision the user made; missing is one they didn't — folding
 * them together would report someone who deliberately stopped a drug as non-adherent.
 * Only doses in the past are counted; upcoming ones are neither.
 */
data class Adherence(
    val taken: Int = 0,
    val missed: Int = 0,
    val skipped: Int = 0,
) {
    val decided: Int get() = taken + missed + skipped

    /** Null rather than 0% when nothing has come due — "no data" isn't "you failed". */
    val takenPercent: Int?
        get() = if (decided == 0) null else (taken * 100) / decided
}

data class HistoryState(
    val displayedMonth: YearMonth = YearMonth.of(1970, 1),
    val selectedDate: LocalDate = LocalDate.of(1970, 1, 1),
    /** Only doses on [selectedDate], after applying the user's filters. */
    val entries: List<DoseWithMedication> = emptyList(),
    /** All filtered doses in the displayed month, used by the export action. */
    val exportEntries: List<DoseWithMedication> = emptyList(),
    val adherence: Adherence = Adherence(),
    val query: String = "",
    val medicationId: String? = null,
    val statuses: Set<DoseStatus> = emptySet(),
    val medications: List<Pair<String, String>> = emptyList(),
    val calendar: List<CalendarDay> = emptyList(),
    val today: LocalDate = LocalDate.of(1970, 1, 1),
    val now: Instant = Instant.EPOCH,
)

data class CalendarDay(
    val date: LocalDate,
    val inDisplayedMonth: Boolean,
    /** Distinct medications scheduled that day, not the number of individual dose times. */
    val medicationCount: Int,
)

/**
 * Returns a stable six-week, Monday-first grid. Keeping 42 cells prevents the page from
 * jumping in height as the user moves between months.
 */
internal fun monthGridDates(month: YearMonth): List<LocalDate> {
    val first = month.atDay(1)
    val daysFromMonday = first.dayOfWeek.value - DayOfWeek.MONDAY.value
    val start = first.minusDays(daysFromMonday.toLong())
    return List(42) { start.plusDays(it.toLong()) }
}

/** Counts medications rather than dose times, so one drug always contributes one dot per day. */
internal fun medicationCountsByDate(
    scheduledMedications: List<Pair<LocalDate, String>>,
): Map<LocalDate, Int> = scheduledMedications
    .groupBy(keySelector = { it.first }, valueTransform = { it.second })
    .mapValues { (_, medicationIds) -> medicationIds.distinct().size }

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val doseRepository: DoseRepository,
    private val clock: Clock,
) : ViewModel() {

    private val initialToday = LocalDate.now(clock)
    private val displayedMonth = MutableStateFlow(YearMonth.from(initialToday))
    private val selectedDate = MutableStateFlow(initialToday)
    private val query = MutableStateFlow("")
    private val medicationId = MutableStateFlow<String?>(null)
    private val statuses = MutableStateFlow<Set<DoseStatus>>(emptySet())

    init {
        ensureMonthMaterialized(displayedMonth.value)
    }

    val state: StateFlow<HistoryState> =
        combine(displayedMonth, selectedDate, query, medicationId, statuses) {
                month, date, text, medId, selectedStatuses ->
            Filters(month, date, text, medId, selectedStatuses)
        }.flatMapLatest { filters ->
            val gridDates = monthGridDates(filters.month)
            doseRepository.observeHistory(gridDates.first(), gridDates.last()).map { all ->
                val now = clock.instant()
                val today = LocalDate.now(clock)
                val monthEntries = all.filter {
                    YearMonth.from(it.dose.localDate) == filters.month
                }
                val matchingMonthEntries = monthEntries.filter { it.matches(filters) }
                val selectedEntries = matchingMonthEntries.filter {
                    it.dose.localDate == filters.selectedDate
                }
                val elapsed = monthEntries.filter { it.dose.effectiveAt <= now }
                val medicationCounts = medicationCountsByDate(
                    all.map { it.dose.localDate to it.medication.id }
                )

                HistoryState(
                    displayedMonth = filters.month,
                    selectedDate = filters.selectedDate,
                    entries = selectedEntries,
                    exportEntries = matchingMonthEntries,
                    adherence = Adherence(
                        taken = elapsed.count { it.dose.status == DoseStatus.TAKEN },
                        missed = elapsed.count { it.dose.status == DoseStatus.MISSED },
                        skipped = elapsed.count { it.dose.status == DoseStatus.SKIPPED },
                    ),
                    query = filters.query,
                    medicationId = filters.medicationId,
                    statuses = filters.statuses,
                    medications = all.distinctBy { it.medication.id }
                        .map { it.medication.id to it.medication.name }
                        .sortedBy { it.second.lowercase() },
                    calendar = gridDates.map { date ->
                        CalendarDay(
                            date = date,
                            inDisplayedMonth = YearMonth.from(date) == filters.month,
                            medicationCount = medicationCounts[date] ?: 0,
                        )
                    },
                    today = today,
                    now = now,
                )
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = HistoryState(
                displayedMonth = displayedMonth.value,
                selectedDate = selectedDate.value,
                today = initialToday,
            ),
        )

    fun showPreviousMonth() {
        showMonth(displayedMonth.value.minusMonths(1))
    }

    fun showNextMonth() {
        showMonth(displayedMonth.value.plusMonths(1))
    }

    fun showToday() {
        val today = LocalDate.now(clock)
        displayedMonth.value = YearMonth.from(today)
        selectedDate.value = today
        ensureMonthMaterialized(displayedMonth.value)
    }

    fun selectDate(date: LocalDate) {
        val selectedMonth = YearMonth.from(date)
        if (selectedMonth != displayedMonth.value) {
            displayedMonth.value = selectedMonth
            ensureMonthMaterialized(selectedMonth)
        }
        selectedDate.value = date
    }

    fun setQuery(value: String) {
        query.value = value
    }

    fun setMedication(id: String?) {
        medicationId.value = id
    }

    fun toggleStatus(status: DoseStatus) {
        statuses.value = statuses.value.toMutableSet().apply {
            if (!add(status)) remove(status)
        }
    }

    fun clearFilters() {
        query.value = ""
        medicationId.value = null
        statuses.value = emptySet()
    }

    fun setNote(doseId: String, note: String?) {
        viewModelScope.launch { doseRepository.setNote(doseId, note) }
    }

    private fun showMonth(month: YearMonth) {
        displayedMonth.value = month
        val today = LocalDate.now(clock)
        selectedDate.value = if (YearMonth.from(today) == month) today else month.atDay(1)
        ensureMonthMaterialized(month)
    }

    /**
     * The normal background window is deliberately short. A calendar needs every future
     * date it displays, so extend the idempotent generation window through this grid's end.
     */
    private fun ensureMonthMaterialized(month: YearMonth) {
        val today = LocalDate.now(clock)
        val through = monthGridDates(month).last()
        if (through < today) return
        val days = ChronoUnit.DAYS.between(today, through).coerceAtLeast(0)
        viewModelScope.launch { doseRepository.materializeWindow(days) }
    }

    private fun DoseWithMedication.matches(filters: Filters): Boolean =
        (filters.medicationId == null || medication.id == filters.medicationId) &&
            (filters.statuses.isEmpty() || dose.status in filters.statuses) &&
            (filters.query.isBlank() ||
                medication.name.contains(filters.query, ignoreCase = true) ||
                dose.note.orEmpty().contains(filters.query, ignoreCase = true))

    private data class Filters(
        val month: YearMonth,
        val selectedDate: LocalDate,
        val query: String,
        val medicationId: String?,
        val statuses: Set<DoseStatus>,
    )
}
