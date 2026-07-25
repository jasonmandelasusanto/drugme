package com.drugme.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import com.drugme.app.data.auth.AuthUser
import org.junit.Rule
import org.junit.Test

class ProfileNavigationIconUiTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun missingPhotoFallsBackToInitials() {
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
    fun missingIdentityFallsBackToPersonIcon() {
        compose.setContent {
            MaterialTheme {
                ProfileNavigationIcon(user = null, selected = false)
            }
        }

        compose.onNodeWithContentDescription("Profile").fetchSemanticsNode()
    }
}
