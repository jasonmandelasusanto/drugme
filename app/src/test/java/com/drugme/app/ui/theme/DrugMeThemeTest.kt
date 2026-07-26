package com.drugme.app.ui.theme

import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test

class DrugMeThemeTest {

    @Test
    fun `light mode uses a bright background`() {
        assertTrue(drugMeColorScheme(darkTheme = false).background.luminance() > 0.5f)
    }

    @Test
    fun `dark mode uses a dark background`() {
        assertTrue(drugMeColorScheme(darkTheme = true).background.luminance() < 0.5f)
    }
}
