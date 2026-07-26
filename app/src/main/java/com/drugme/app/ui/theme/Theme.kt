package com.drugme.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightColors = lightColorScheme(
    primary = Blue40,
    onPrimary = Color.White,
    primaryContainer = Blue90,
    onPrimaryContainer = Blue10,
    secondary = BlueGrey30,
    onSecondary = Color.White,
    secondaryContainer = BlueGrey90,
    onSecondaryContainer = Blue10,
    tertiary = Blue30,
    onTertiary = Color.White,
    tertiaryContainer = Blue95,
    onTertiaryContainer = Blue10,
    error = Red40,
    onError = Color.White,
    errorContainer = Red90,
    onErrorContainer = Red10,
    background = Neutral99,
    onBackground = Neutral10,
    surface = Neutral99,
    onSurface = Neutral10,
    surfaceVariant = BlueGrey90,
    onSurfaceVariant = BlueGrey30,
    outline = BlueGrey50,
    outlineVariant = BlueGrey80,
)

private val DarkColors = darkColorScheme(
    primary = Blue80,
    onPrimary = Blue20,
    primaryContainer = Blue30,
    onPrimaryContainer = Blue90,
    secondary = BlueGrey80,
    onSecondary = Blue20,
    secondaryContainer = BlueGrey30,
    onSecondaryContainer = BlueGrey90,
    tertiary = Blue80,
    onTertiary = Blue20,
    tertiaryContainer = Blue30,
    onTertiaryContainer = Blue90,
    error = Red80,
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Red90,
    background = Neutral10,
    onBackground = Neutral90,
    surface = Neutral10,
    onSurface = Neutral90,
    surfaceVariant = BlueGrey30,
    onSurfaceVariant = BlueGrey80,
    outline = BlueGrey60,
    outlineVariant = BlueGrey30,
)

/** Dose-state colors, which live outside the M3 scheme but must still flip with it. */
data class DoseColors(
    val taken: Color,
    val missed: Color,
    val pending: Color,
    val skipped: Color,
)

val LocalDoseColors = staticCompositionLocalOf {
    DoseColors(DoseTakenLight, DoseMissedLight, DosePendingLight, DoseSkippedLight)
}

internal fun drugMeColorScheme(darkTheme: Boolean) =
    if (darkTheme) DarkColors else LightColors

@Composable
fun DrugMeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    // Dynamic color is intentionally not used: the brand is blue-and-white, and
    // wallpaper-derived schemes would let the dose-state accents drift.
    val colors = drugMeColorScheme(darkTheme)
    val doseColors = if (darkTheme) {
        DoseColors(DoseTakenDark, DoseMissedDark, DosePendingDark, DoseSkippedDark)
    } else {
        DoseColors(DoseTakenLight, DoseMissedLight, DosePendingLight, DoseSkippedLight)
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    CompositionLocalProvider(LocalDoseColors provides doseColors) {
        MaterialTheme(
            colorScheme = colors,
            typography = DrugMeTypography,
            content = content,
        )
    }
}
