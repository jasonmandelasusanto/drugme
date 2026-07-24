package com.drugme.app.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewModelScope
import com.drugme.app.data.local.dao.DoseWithMedication
import com.drugme.app.data.repo.DoseRepository
import com.drugme.app.domain.model.DoseStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject

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
    val days: Int = 7,
    val entries: List<DoseWithMedication> = emptyList(),
    val adherence: Adherence = Adherence(),
    val query: String = "",
    val medicationId: String? = null,
    val statuses: Set<DoseStatus> = emptySet(),
    val medications: List<Pair<String, String>> = emptyList(),
    val calendar: List<CalendarDay> = emptyList(),
)

data class CalendarDay(
    val date: LocalDate,
    val taken: Int,
    val missed: Int,
    val skipped: Int,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val doseRepository: DoseRepository,
    private val clock: Clock,
) : ViewModel() {

    private val windowDays = MutableStateFlow(7)
    private val query = MutableStateFlow("")
    private val medicationId = MutableStateFlow<String?>(null)
    private val statuses = MutableStateFlow<Set<DoseStatus>>(emptySet())

    val state: StateFlow<HistoryState> =
        kotlinx.coroutines.flow.combine(windowDays, query, medicationId, statuses) {
                days, text, medId, selectedStatuses ->
            Filters(days, text, medId, selectedStatuses)
        }.flatMapLatest { filters ->
            val today = LocalDate.now(clock)
            val from = today.minusDays(filters.days.toLong() - 1)
            doseRepository.observeHistory(from, today).map { all ->
                // Only past-or-present doses count toward adherence; a dose due tonight is
                // not evidence of anything yet.
                val now = clock.instant()
                val elapsed = all.filter { it.dose.effectiveAt <= now }
                val filtered = all.filter { item ->
                    (filters.medicationId == null || item.medication.id == filters.medicationId) &&
                        (filters.statuses.isEmpty() || item.dose.status in filters.statuses) &&
                        (filters.query.isBlank() ||
                            item.medication.name.contains(filters.query, ignoreCase = true) ||
                            item.dose.note.orEmpty().contains(filters.query, ignoreCase = true))
                }
                HistoryState(
                    days = filters.days,
                    entries = filtered,
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
                    calendar = (0 until minOf(filters.days, 14)).map { offset ->
                        val date = today.minusDays(offset.toLong())
                        val entries = all.filter { it.dose.localDate == date }
                        CalendarDay(
                            date = date,
                            taken = entries.count { it.dose.status == DoseStatus.TAKEN },
                            missed = entries.count { it.dose.status == DoseStatus.MISSED },
                            skipped = entries.count { it.dose.status == DoseStatus.SKIPPED },
                        )
                    }.reversed(),
                )
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = HistoryState(),
        )

    fun setWindow(days: Int) {
        windowDays.value = days
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

    fun setNote(doseId: String, note: String?) {
        viewModelScope.launch { doseRepository.setNote(doseId, note) }
    }

    private data class Filters(
        val days: Int,
        val query: String,
        val medicationId: String?,
        val statuses: Set<DoseStatus>,
    )
}
