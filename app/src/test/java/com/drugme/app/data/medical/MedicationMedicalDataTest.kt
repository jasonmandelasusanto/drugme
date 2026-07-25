package com.drugme.app.data.medical

import com.drugme.app.data.repo.DrugSuggestion
import com.drugme.app.data.repo.fuzzyDiseaseDistance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MedicationMedicalDataTest {
    @Test
    fun `RxNorm display name is separated into ingredient strength form and brand`() {
        val parsed = parseRxNormSuggestion(
            "123",
            "acetaminophen 500 MG Oral Tablet [Tylenol]",
        )

        assertEquals("acetaminophen", parsed.activeIngredient)
        assertEquals("500 MG", parsed.strength)
        assertEquals("Oral tablet", parsed.dosageForm)
        assertEquals("Tylenol", parsed.brandName)
    }

    @Test
    fun `duplicate medication results merge by ingredient strength form and brand`() {
        val first = suggestion("1", "Metformin 500 MG Oral Tablet")
        val duplicate = suggestion("2", "metformin 500 mg oral tablet")

        val merged = mergeMedicationSuggestions(listOf(first), listOf(duplicate))

        assertEquals(1, merged.size)
    }

    @Test
    fun `information from independent providers merges without inventing fields`() {
        val merged = mergeInformation(
            listOf(
                MedicationInformation(
                    title = "Medicine",
                    commonUses = "Official use",
                    sources = listOf(source("MedlinePlus")),
                ),
                MedicationInformation(
                    title = "Medicine",
                    seriousWarnings = "Official warning",
                    sources = listOf(source("FDA")),
                ),
            ),
            cachedAtMillis = 42,
        )

        requireNotNull(merged)
        assertEquals("Official use", merged.commonUses)
        assertEquals("Official warning", merged.seriousWarnings)
        assertNull(merged.commonSideEffects)
        assertEquals(2, merged.sources.size)
        assertEquals(42, merged.cachedAtMillis)
    }

    @Test
    fun `no provider match stays unavailable`() {
        assertNull(mergeInformation(emptyList(), cachedAtMillis = 1))
    }

    @Test
    fun `small spelling differences still match a condition name`() {
        assertTrue(fuzzyDiseaseDistance("diabetse", "Diabetes Mellitus, Type 2") <= 2)
        assertTrue(fuzzyDiseaseDistance("hypertenson", "Hypertension") <= 2)
    }

    private fun suggestion(id: String, name: String): DrugSuggestion =
        parseRxNormSuggestion(id, name)

    private fun source(name: String) = MedicationInfoSource(
        organization = name,
        country = "United States",
        url = "https://example.test/$name",
        scope = "Test",
    )
}
