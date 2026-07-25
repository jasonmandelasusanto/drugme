package com.drugme.app.ui.addmed

import com.drugme.app.data.medical.AutocompleteOutcome
import com.drugme.app.data.medical.DiseaseAutocompleteSource
import com.drugme.app.data.medical.MedicationAutocompleteSource
import com.drugme.app.data.repo.DrugSuggestion
import com.drugme.app.data.repo.MedicationRepository
import com.drugme.app.domain.model.DiseaseRef
import io.mockk.mockk
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AddMedicationAutocompleteTest {
    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)
    private val medications = mockk<MedicationRepository>(relaxed = true)
    private val clock = Clock.fixed(Instant.parse("2026-07-25T00:00:00Z"), ZoneOffset.UTC)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `one medication tap selects populates and closes suggestions`() {
        val vm = viewModel()
        val suggestion = drug("860975", "metformin 500 MG Oral Tablet")

        vm.onNameChange("met")
        vm.onDrugPicked(suggestion)

        assertEquals(suggestion.name, vm.state.value.name)
        assertEquals("860975", vm.state.value.rxcui)
        assertTrue(vm.state.value.drugSuggestions.isEmpty())
        assertFalse(vm.state.value.showDrugSuggestions)
        assertEquals(1, vm.state.value.drugSelectionVersion)
    }

    @Test
    fun `editing a verified medication changes it to unverified free text`() {
        val vm = viewModel()
        vm.onDrugPicked(drug("1", "metformin"))

        vm.onNameChange("metformin custom")

        assertNull(vm.state.value.rxcui)
    }

    @Test
    fun `one condition tap selects populates and closes suggestions`() {
        val vm = viewModel()
        val disease = DiseaseRef("D003924", "Diabetes Mellitus, Type 2")

        vm.onDiseasePicked(disease)

        assertEquals(disease, vm.state.value.selectedDisease)
        assertEquals(disease.name, vm.state.value.diseaseQuery)
        assertFalse(vm.state.value.showDiseaseSuggestions)
        assertEquals(1, vm.state.value.diseaseSelectionVersion)
    }

    @Test
    fun `a delayed old medication response cannot replace a newer query`() {
        val source = FakeMedicationSource { query ->
            if (query == "met") {
                delay(1_000)
                AutocompleteOutcome.Results(listOf(drug("old", "old result")))
            } else {
                delay(10)
                AutocompleteOutcome.Results(listOf(drug("new", "new result")))
            }
        }
        val vm = viewModel(medicationSource = source)

        vm.onNameChange("met")
        scheduler.advanceTimeBy(221)
        scheduler.runCurrent()
        vm.onNameChange("metf")
        scheduler.advanceTimeBy(221)
        scheduler.runCurrent()
        scheduler.advanceTimeBy(11)
        scheduler.runCurrent()

        assertEquals(listOf("new result"), vm.state.value.drugSuggestions.map { it.name })
        scheduler.advanceUntilIdle()
        assertEquals(listOf("new result"), vm.state.value.drugSuggestions.map { it.name })
    }

    @Test
    fun `empty offline and error medication states are explicit`() {
        listOf(
            AutocompleteOutcome.Results(emptyList<DrugSuggestion>()) to SuggestionState.EMPTY,
            AutocompleteOutcome.Offline to SuggestionState.OFFLINE,
            AutocompleteOutcome.Error("failure") to SuggestionState.ERROR,
        ).forEach { (outcome, expected) ->
            val vm = viewModel(medicationSource = FakeMedicationSource { outcome })
            vm.onNameChange("xyz")
            scheduler.advanceTimeBy(221)
            scheduler.runCurrent()
            assertEquals(expected, vm.state.value.drugSuggestionState)
        }
    }

    @Test
    fun `empty offline and error disease states are explicit`() {
        listOf(
            AutocompleteOutcome.Results(emptyList<DiseaseRef>()) to SuggestionState.EMPTY,
            AutocompleteOutcome.Offline to SuggestionState.OFFLINE,
            AutocompleteOutcome.Error("failure") to SuggestionState.ERROR,
        ).forEach { (outcome, expected) ->
            val vm = viewModel(diseaseSource = FakeDiseaseSource { outcome })
            vm.onDiseaseQueryChange("xyz")
            scheduler.advanceTimeBy(221)
            scheduler.runCurrent()
            assertEquals(expected, vm.state.value.diseaseSuggestionState)
        }
    }

    private fun viewModel(
        medicationSource: MedicationAutocompleteSource = FakeMedicationSource {
            AutocompleteOutcome.Results(emptyList())
        },
        diseaseSource: DiseaseAutocompleteSource = FakeDiseaseSource {
            AutocompleteOutcome.Results(emptyList())
        },
    ) = AddMedicationViewModel(
        medicationRepository = medications,
        medicationAutocomplete = medicationSource,
        diseaseAutocomplete = diseaseSource,
        clock = clock,
    )

    private fun drug(id: String, name: String) = DrugSuggestion(
        rxcui = id,
        name = name,
        diseases = emptyList(),
    )
}

private class FakeMedicationSource(
    private val response: suspend (String) -> AutocompleteOutcome<DrugSuggestion>,
) : MedicationAutocompleteSource {
    override suspend fun popular(limit: Int): List<DrugSuggestion> = emptyList()
    override suspend fun search(query: String, limit: Int): AutocompleteOutcome<DrugSuggestion> =
        response(query)
}

private class FakeDiseaseSource(
    private val response: suspend (String) -> AutocompleteOutcome<DiseaseRef>,
) : DiseaseAutocompleteSource {
    override suspend fun popular(limit: Int): List<DiseaseRef> = emptyList()
    override suspend fun search(query: String, limit: Int): AutocompleteOutcome<DiseaseRef> =
        response(query)
}
