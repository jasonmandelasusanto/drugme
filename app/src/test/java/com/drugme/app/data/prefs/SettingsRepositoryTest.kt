package com.drugme.app.data.prefs

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsRepositoryTest {

    @Test
    fun `dark mode choice is persisted and can switch back to light`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = SettingsRepository(context)

        repository.setDarkMode(true)
        assertTrue(repository.darkMode.first() == true)

        repository.setDarkMode(false)
        assertFalse(repository.darkMode.first() == true)
    }
}
