package com.drugme.app.ui.medications

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.drugme.app.data.local.dao.MedicationWithSchedules
import com.drugme.app.data.medical.MedicationInfoOutcome
import com.drugme.app.data.medical.MedicationInfoRequest
import com.drugme.app.data.medical.MedicationInformationRepository
import com.drugme.app.data.repo.MedicationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class MedicationDetailsState(
    val medication: MedicationWithSchedules? = null,
    val information: MedicationInfoOutcome? = null,
    val loading: Boolean = true,
)

@HiltViewModel
class MedicationDetailsViewModel @Inject constructor(
    private val medications: MedicationRepository,
    private val informationRepository: MedicationInformationRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(MedicationDetailsState())
    val state: StateFlow<MedicationDetailsState> = _state.asStateFlow()
    private var loadedId: String? = null

    fun load(id: String, forceRefresh: Boolean = false) {
        if (!forceRefresh && loadedId == id) return
        loadedId = id
        viewModelScope.launch {
            val medication = medications.getById(id)
            _state.value = MedicationDetailsState(medication = medication, loading = medication != null)
            if (medication == null) return@launch
            val med = medication.medication
            val outcome = informationRepository.get(
                MedicationInfoRequest(rxcui = med.rxcui, name = med.name),
                forceRefresh = forceRefresh,
            )
            _state.value = MedicationDetailsState(
                medication = medication,
                information = outcome,
                loading = false,
            )
        }
    }
}
