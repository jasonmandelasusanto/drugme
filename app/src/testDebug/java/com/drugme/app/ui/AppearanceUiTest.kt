package com.drugme.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.drugme.app.ui.onboarding.AppearanceStep
import com.drugme.app.ui.settings.AppearanceSetting
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppearanceUiTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `settings toggle changes from light to dark`() {
        var selected: Boolean? = null
        compose.setContent {
            MaterialTheme {
                AppearanceSetting(
                    darkMode = false,
                    onDarkModeChange = { selected = it },
                )
            }
        }

        compose.onNodeWithContentDescription("Dark mode").performClick()

        assertEquals(true, selected)
    }

    @Test
    fun `onboarding requires an appearance choice`() {
        var selected: Boolean? = null
        compose.setContent {
            MaterialTheme {
                AppearanceStep(
                    savedDarkMode = null,
                    onDarkModeSelected = { selected = it },
                    onNext = {},
                )
            }
        }

        compose.onNodeWithText("Continue").assertIsNotEnabled()
        compose.onNodeWithText("Dark").performClick()

        assertEquals(true, selected)
        compose.onNodeWithText("Continue").assertIsEnabled()
    }
}
