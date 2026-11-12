package com.quran.learnedplayer.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
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

/**
 * Status bar, navigation bar and cutout, plus the keyboard only while it is actually open.
 * Each bar edge is capped: a stuck inset (seen as ~900px of top padding on a phone whose
 * status bar is 75px) must not shove the whole screen down.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Modifier.appSafePadding(): Modifier {
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val bars = WindowInsets.systemBars.union(WindowInsets.displayCutout)
    val cap = 128.dp
    fun Int.capped() = with(density) { toDp().coerceAtMost(cap) }
    val imeBottom = if (WindowInsets.isImeVisible) {
        with(density) { WindowInsets.ime.getBottom(density).toDp() }
    } else {
        0.dp
    }
    return padding(
        start = (if (direction == LayoutDirection.Ltr) bars.getLeft(density, direction) else bars.getRight(density, direction)).capped(),
        top = bars.getTop(density).capped(),
        end = (if (direction == LayoutDirection.Ltr) bars.getRight(density, direction) else bars.getLeft(density, direction)).capped(),
        bottom = maxOf(bars.getBottom(density).capped(), imeBottom),
    )
}

@Composable
fun LearnedAyahsTheme(content: @Composable () -> Unit) {
    val palette = animatedPalette()
    val colorScheme = if (palette.isLight) {
        lightColorScheme(
            primary = palette.accent,
            secondary = palette.accentGreen,
            background = palette.bg,
            surface = palette.panel,
            // Menus are drawn on this role; without it they fall back to Material's grey.
            surfaceContainer = palette.panel,
            onBackground = palette.textPrimary,
            onSurface = palette.textPrimary,
            onSurfaceVariant = palette.textMuted,
            onPrimary = onAccentFor(palette.accent, palette.panel),
        )
    } else {
        darkColorScheme(
            primary = palette.accent,
            secondary = palette.accentGreen,
            background = palette.bg,
            surface = palette.panel,
            // Menus are drawn on this role; without it they fall back to Material's grey.
            surfaceContainer = palette.panel,
            onBackground = palette.textPrimary,
            onSurface = palette.textPrimary,
            onPrimary = onAccentFor(palette.accent, palette.panel),
        )
    }

    // themes.xml paints the system bars in the dark palette (matching the launch window
    // background); restyle them here so the light theme doesn't sit under dark bars with
    // invisible icons.
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            view.context.findActivity()?.window?.let { window ->
                @Suppress("DEPRECATION") // The effective API below 35; ignored (harmlessly) from 35 on.
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
            color = palette.bg,
            contentColor = palette.textPrimary,
        ) {
            // Android 15+ draws apps targeting 35+ edge to edge. Pad once, for the status bar,
            // navigation bar and cutout only. safeDrawing also includes the IME, and on Android 16
            // a cancelled keyboard inset was applied as a top pad of roughly a keyboard's height,
            // which pushed the player header down and left the ayah slot empty.
            Box(modifier = Modifier.fillMaxSize().appSafePadding()) {
                com.quran.learnedplayer.ui.common.AdaptiveAppFrame { content() }
            }
        }
    }
}
