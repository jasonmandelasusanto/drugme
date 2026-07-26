package com.drugme.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.drugme.app.data.update.AppUpdateState
import com.drugme.app.ui.settings.AppUpdateSetting
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppUpdateSettingsUiTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `check button displays installed version and invokes manual check`() {
        var checks = 0
        compose.setContent {
            MaterialTheme {
                AppUpdateSetting(
                    state = AppUpdateState(),
                    currentVersion = "1.2.0",
                    updatesSupported = true,
                    onCheck = { checks++ },
                    onInstall = {},
                )
            }
        }

        compose.onNodeWithText("Installed version 1.2.0").assertExists()
        compose.onNodeWithText("Check for updates").assertIsEnabled().performClick()
        assertEquals(1, checks)
    }

    @Test
    fun `checking state is visible and disables duplicate checks`() {
        compose.setContent {
            MaterialTheme {
                AppUpdateSetting(
                    state = AppUpdateState(checking = true),
                    currentVersion = "1.2.0",
                    updatesSupported = true,
                    onCheck = {},
                    onInstall = {},
                )
            }
        }

        compose.onNodeWithText("Checking GitHub Releases…").assertExists()
        compose.onNodeWithText("Check for updates").assertIsNotEnabled()
    }

    @Test
    fun `downloaded update exposes install action`() {
        var installs = 0
        compose.setContent {
            MaterialTheme {
                AppUpdateSetting(
                    state = AppUpdateState(version = "1.3.0", downloaded = true),
                    currentVersion = "1.2.0",
                    updatesSupported = true,
                    onCheck = {},
                    onInstall = { installs++ },
                )
            }
        }

        compose.onNodeWithText("Version 1.3.0 is downloaded and signature-verified.").assertExists()
        compose.onNodeWithText("Review and install").performClick()
        assertEquals(1, installs)
    }

    @Test
    fun `up to date outcome is visible`() {
        compose.setContent {
            MaterialTheme {
                AppUpdateSetting(
                    state = AppUpdateState(upToDate = true),
                    currentVersion = "1.2.0",
                    updatesSupported = true,
                    onCheck = {},
                    onInstall = {},
                )
            }
        }
        compose.onNodeWithText("You’re using the latest version.").assertExists()
    }

    @Test
    fun `update failure is visible`() {
        compose.setContent {
            MaterialTheme {
                AppUpdateSetting(
                    state = AppUpdateState(error = "GitHub returned HTTP 403."),
                    currentVersion = "1.2.0",
                    updatesSupported = true,
                    onCheck = {},
                    onInstall = {},
                )
            }
        }
        compose.onNodeWithText("GitHub returned HTTP 403.").assertExists()
    }
}
