package com.drugme.app.ui.addmed

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.drugme.app.data.repo.DrugSuggestion
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class AutocompleteUiTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun completeSuggestionRowSelectsWithOneTap() {
        var taps = 0
        compose.setContent {
            MaterialTheme {
                MedicationSuggestionRow(
                    suggestion = DrugSuggestion(
                        rxcui = "860975",
                        name = "metformin",
                        diseases = emptyList(),
                    ),
                    query = "met",
                    onClick = { taps += 1 },
                )
            }
        }

        compose.onNodeWithText("metformin", substring = true).performClick()

        assertEquals(1, taps)
    }
}
