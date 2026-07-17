package com.drugme.app.ui.theme

import androidx.compose.ui.graphics.Color

// Blue-and-white palette, hand-tuned rather than seeded from the M3 default
// (which lands on violet). Primary is anchored near Material Blue 800.
val Blue10 = Color(0xFF001B3D)
val Blue20 = Color(0xFF002F65)
val Blue30 = Color(0xFF00458F)
val Blue40 = Color(0xFF1565C0)
val Blue80 = Color(0xFFA8C8FF)
val Blue90 = Color(0xFFD5E3FF)
val Blue95 = Color(0xFFEAF1FF)
val Blue99 = Color(0xFFFDFCFF)

val BlueGrey30 = Color(0xFF3F4759)
val BlueGrey50 = Color(0xFF707887)
val BlueGrey60 = Color(0xFF8A92A2)
val BlueGrey80 = Color(0xFFC2C7D5)
val BlueGrey90 = Color(0xFFDEE2F1)

val Neutral10 = Color(0xFF1A1C1E)
val Neutral20 = Color(0xFF2F3033)
val Neutral90 = Color(0xFFE2E2E6)
val Neutral95 = Color(0xFFF1F0F4)
val Neutral99 = Color(0xFFFDFCFF)

val Red40 = Color(0xFFBA1A1A)
val Red80 = Color(0xFFFFB4AB)
val Red90 = Color(0xFFFFDAD6)
val Red10 = Color(0xFF410002)

// Dose-state accents. These are deliberately paired with distinct icons and text
// labels at every call site: red/green is the most common color-vision deficiency
// axis, and "did I take this dose?" must never rest on hue alone.
val DoseTakenLight = Color(0xFF186A3B)
val DoseTakenDark = Color(0xFF7BDBA1)
val DoseMissedLight = Color(0xFFBA1A1A)
val DoseMissedDark = Color(0xFFFFB4AB)
val DosePendingLight = Color(0xFF1565C0)
val DosePendingDark = Color(0xFFA8C8FF)
val DoseSkippedLight = Color(0xFF6B5E00)
val DoseSkippedDark = Color(0xFFDCC94E)
