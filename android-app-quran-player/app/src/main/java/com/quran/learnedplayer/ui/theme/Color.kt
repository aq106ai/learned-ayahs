package com.quran.learnedplayer.ui.theme

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import com.quran.learnedplayer.data.AppTheme

/**
 * One complete set of the app's colours. Every screen styles itself from these seven roles,
 * so a theme is exactly one [Palette].
 */
data class Palette(
    val bg: Color,
    val panel: Color,
    val textPrimary: Color,
    val textMuted: Color,
    val accent: Color,
    val accentGreen: Color,
    val border: Color,
    /** Drives light/dark Material defaults and the system-bar icon colour. */
    val isLight: Boolean,
)

/** The original dark green look — the default theme. */
val DarkGreenPalette = Palette(
    bg = Color(0xFF0A1710),
    panel = Color(0xFF112A1C),
    textPrimary = Color(0xFFE6F4EB),
    textMuted = Color(0xFF8DB29B),
    accent = Color(0xFF27AE60),
    accentGreen = Color(0xFF45D483),
    border = Color(0xFF1F4030),
    isLight = false,
)

/**
 * Light theme: white panels on a green-tinted off-white, with the greens darkened so they
 * keep contrast in their existing roles — [Palette.accentGreen] is body-size text on white
 * and on [Palette.border] chips, so it must stay ≥4.5:1 against both.
 */
val LightGreenPalette = Palette(
    bg = Color(0xFFF1F8F3),
    panel = Color(0xFFFFFFFF),
    textPrimary = Color(0xFF13281B),
    textMuted = Color(0xFF4E6B5B),
    accent = Color(0xFF1B8A4C),
    accentGreen = Color(0xFF0E7C43),
    border = Color(0xFFDBEDE2),
    isLight = true,
)

// Snapshot-state backed so every composable that reads a colour recomposes when the theme
// changes — which lets the 190-odd existing `Bg` / `Panel` / … call sites stay untouched.
private val activePalette = mutableStateOf(DarkGreenPalette)

/** Set while the player shows a background, whatever the theme: see [overridePalette]. */
private val paletteOverride = mutableStateOf<Palette?>(null)

/** The full active palette, for the places that need more than one role (Theme.kt). */
val currentPalette: Palette get() = paletteOverride.value ?: activePalette.value

/** Applies [theme] app-wide. Called by [com.quran.learnedplayer.player.PlayerSettings]. */
fun applyAppTheme(theme: AppTheme) {
    activePalette.value = if (theme == AppTheme.LIGHT) LightGreenPalette else DarkGreenPalette
}

/**
 * Puts [palette] in place of the chosen theme until it's called again with null. The player uses
 * it while a background is showing: light text on a dark scene reads whatever the theme, and
 * the light theme's dark text would not.
 */
fun overridePalette(palette: Palette?) {
    paletteOverride.value = palette
}

val Bg: Color get() = currentPalette.bg
val Panel: Color get() = currentPalette.panel
val TextPrimary: Color get() = currentPalette.textPrimary
val TextMuted: Color get() = currentPalette.textMuted
val Accent: Color get() = currentPalette.accent
val AccentGreen: Color get() = currentPalette.accentGreen
val Border: Color get() = currentPalette.border
