package com.drugme.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.drugme.app.data.auth.AuthUser
import com.drugme.app.data.repo.DrugSuggestion
import com.drugme.app.ui.addmed.MedicationSuggestionRow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ComposeUiBehaviorTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `complete autocomplete row selects in one tap`() {
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

    @Test
    fun `profile photo fallback exposes initials to accessibility`() {
        compose.setContent {
            MaterialTheme {
                ProfileNavigationIcon(
                    user = AuthUser("1", "jason@example.com", "Jason Susanto", null),
                    selected = true,
                )
            }
        }

        compose.onNodeWithContentDescription("Profile JS").fetchSemanticsNode()
    }

    @Test
    fun `profile without identity exposes person icon fallback`() {
        compose.setContent {
            MaterialTheme {
                ProfileNavigationIcon(user = null, selected = false)
            }
        }

        compose.onNodeWithContentDescription("Profile").fetchSemanticsNode()
    }
}
