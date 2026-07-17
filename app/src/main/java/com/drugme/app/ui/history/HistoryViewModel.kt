package com.drugme.app.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.drugme.app.data.local.dao.DoseWithMedication
import com.drugme.app.data.repo.DoseRepository
import com.drugme.app.domain.model.DoseStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val doseRepository: DoseRepository,
    private val clock: Clock,
) : ViewModel() {

    private val windowDays = MutableStateFlow(7)

    val state: StateFlow<HistoryState> =
        windowDays.flatMapLatest { days ->
            val today = LocalDate.now(clock)
            val from = today.minusDays(days.toLong() - 1)
            doseRepository.observeHistory(from, today).map { all ->
                // Only past-or-present doses count toward adherence; a dose due tonight is
                // not evidence of anything yet.
                val now = clock.instant()
                val elapsed = all.filter { it.dose.effectiveAt <= now }
                HistoryState(
                    days = days,
                    entries = all,
                    adherence = Adherence(
                        taken = elapsed.count { it.dose.status == DoseStatus.TAKEN },
                        missed = elapsed.count { it.dose.status == DoseStatus.MISSED },
                        skipped = elapsed.count { it.dose.status == DoseStatus.SKIPPED },
                    ),
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
}
