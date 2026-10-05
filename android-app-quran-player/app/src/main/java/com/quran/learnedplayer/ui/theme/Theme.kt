package com.quran.learnedplayer.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * Unwraps ContextWrappers (e.g. ContextThemeWrapper) to the hosting Activity. A plain
 * `context as? Activity` silently returns null under a wrapped Compose host, which would
 * skip system-bar styling and leave invisible icons on the light theme.
 */
internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
fun LearnedAyahsTheme(content: @Composable () -> Unit) {
    val palette = currentPalette
    val colorScheme = if (palette.isLight) {
        lightColorScheme(
            primary = palette.accent,
            secondary = palette.accentGreen,
            background = palette.bg,
            surface = palette.panel,
            onBackground = palette.textPrimary,
            onSurface = palette.textPrimary,
            onSurfaceVariant = palette.textMuted,
            onPrimary = Color.White,
        )
    } else {
        darkColorScheme(
            primary = palette.accent,
            secondary = palette.accentGreen,
            background = palette.bg,
            surface = palette.panel,
            onBackground = palette.textPrimary,
            onSurface = palette.textPrimary,
            onPrimary = Color.White,
        )
    }

    // themes.xml paints the system bars in the dark palette (matching the launch window
    // background); restyle them here so the light theme doesn't sit under dark bars with
    // invisible icons.
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            view.context.findActivity()?.window?.let { window ->
                @Suppress("DEPRECATION") // Still the effective API below 35; fine for targetSdk 34.
                window.statusBarColor = palette.bg.toArgb()
                @Suppress("DEPRECATION")
                window.navigationBarColor = palette.bg.toArgb()
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = palette.isLight
                    isAppearanceLightNavigationBars = palette.isLight
                }
            }
        }
    }

    MaterialTheme(colorScheme = colorScheme) {
        // Surface sets the default content (text) color so headings/titles without an
        // explicit color are readable on the background instead of falling back to black.
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Bg,
            contentColor = TextPrimary,
            content = content,
        )
    }
}
