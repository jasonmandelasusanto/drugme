package com.drugme.app.ui.medications

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.drugme.app.data.local.dao.MedicationWithSchedules
import com.drugme.app.data.repo.MedicationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class MedicationFilter { ALL, ACTIVE, DISCONTINUED }

data class MedicationsState(
    val query: String = "",
    val filter: MedicationFilter = MedicationFilter.ALL,
    val allCount: Int = 0,
    val medications: List<MedicationWithSchedules> = emptyList(),
)

@HiltViewModel
class MedicationsViewModel @Inject constructor(
    private val repository: MedicationRepository,
) : ViewModel() {
    private val query = MutableStateFlow("")
    private val filter = MutableStateFlow(MedicationFilter.ALL)

    val state: StateFlow<MedicationsState> = combine(
        repository.observeAll(),
        query,
        filter,
    ) { all, text, selectedFilter ->
        MedicationsState(
            query = text,
            filter = selectedFilter,
            allCount = all.size,
            medications = filterMedications(all, text, selectedFilter),
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        MedicationsState(),
    )

    fun setQuery(value: String) {
        query.value = value
    }

    fun setFilter(value: MedicationFilter) {
        filter.value = value
    }

    fun setActive(id: String, active: Boolean) {
        viewModelScope.launch { repository.setActive(id, active) }
    }
}

internal fun filterMedications(
    all: List<MedicationWithSchedules>,
    query: String,
    filter: MedicationFilter,
): List<MedicationWithSchedules> = all.filter { item ->
    (query.isBlank() ||
        item.medication.name.contains(query, ignoreCase = true) ||
        item.medication.diseaseName.orEmpty().contains(query, ignoreCase = true)) &&
        when (filter) {
            MedicationFilter.ALL -> true
            MedicationFilter.ACTIVE -> item.medication.isActive
            MedicationFilter.DISCONTINUED -> !item.medication.isActive
        }
}
