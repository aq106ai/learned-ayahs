package com.quran.learnedplayer.ui.theme

import androidx.compose.animation.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.quran.learnedplayer.data.AppTheme
import com.quran.learnedplayer.video.BackgroundPlan
import com.quran.learnedplayer.video.NaturePhotos
import com.quran.learnedplayer.video.PlayerBackgrounds
import com.quran.learnedplayer.video.SceneKind
import com.quran.learnedplayer.video.SceneSpec
import com.quran.learnedplayer.video.VideoThemes
import kotlin.math.max
import kotlin.math.min

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

/** A theme change the user just made: long enough to read as one move, short enough not to lag. */
internal const val USER_THEME_MOTION_MS = 280

/** Matches the picture's crossfade, so the chrome and the scene arrive together. */
internal val SCENE_THEME_MOTION_MS = BackgroundPlan.CROSSFADE_MS.toInt()

// Snapshot-state backed so every composable that reads a colour recomposes when the theme
// changes — which lets the 190-odd existing `Bg` / `Panel` / … call sites stay untouched.
// [targetPalette] is the look just chosen. [shownPalette] is the one on screen, eased toward it.
private val targetPalette = mutableStateOf(DarkGreenPalette)
private val shownPalette = mutableStateOf(DarkGreenPalette)
private val themeMotionMs = mutableIntStateOf(USER_THEME_MOTION_MS)

private var plainPalette: Palette = DarkGreenPalette
private var backgroundChoice: String = PlayerBackgrounds.PLAIN
private var liveScene: SceneSpec? = null

private const val TEXT_CONTRAST = 4.5f

/** The full palette on screen, for the places that need more than one role. */
val currentPalette: Palette get() = shownPalette.value

/**
 * The user's Dark or Light palette. While a background is selected the scene look replaces it
 * on every screen; this is what returns when the background goes back to plain.
 */
fun applyAppTheme(theme: AppTheme) {
    plainPalette = if (theme == AppTheme.LIGHT) LightGreenPalette else DarkGreenPalette
    if (backgroundChoice == PlayerBackgrounds.PLAIN) retarget(plainPalette, USER_THEME_MOTION_MS)
}

/**
 * The background just chosen. A named theme recolors at once from its first scene. Matched scenes
 * and photos use the scene the player is showing, or [VideoThemes.FALLBACK] until that arrives.
 */
fun applyPlayerBackground(choice: String) {
    backgroundChoice = choice
    retarget(resolvePalette(), USER_THEME_MOTION_MS)
}

/**
 * The scene the player has decided to show, after its hold. [crossfade] eases the whole app over
 * the same time as the picture; the first scene eases over [USER_THEME_MOTION_MS].
 */
fun applyShownScene(spec: SceneSpec, crossfade: Boolean) {
    if (backgroundChoice == PlayerBackgrounds.PLAIN) return
    if (spec === liveScene) return
    liveScene = spec
    retarget(paletteFor(spec), if (crossfade) SCENE_THEME_MOTION_MS else USER_THEME_MOTION_MS)
}

/** Puts the saved theme and background on screen with no fade from the default dark green. */
fun snapAppearance(theme: AppTheme, choice: String) {
    plainPalette = if (theme == AppTheme.LIGHT) LightGreenPalette else DarkGreenPalette
    backgroundChoice = choice
    liveScene = null
    val next = resolvePalette()
    themeMotionMs.intValue = USER_THEME_MOTION_MS
    targetPalette.value = next
    shownPalette.value = next
}

/** Photos have no per-picture colours; the player uses this same neutral dark for every one. */
private val photoChrome = SceneSpec(
    SceneKind.PHOTO,
    intArrayOf(0xFF101418.toInt(), 0xFF101418.toInt(), 0xFF101418.toInt(), -1),
    emptySet(),
)

private fun resolvePalette(): Palette = when (backgroundChoice) {
    PlayerBackgrounds.PLAIN -> plainPalette
    PlayerBackgrounds.MATCHED, PlayerBackgrounds.PHOTOS ->
        paletteFor(liveScene ?: VideoThemes.variant(VideoThemes.FALLBACK, 0))
    else -> paletteFor(if (NaturePhotos.isPhoto(backgroundChoice)) photoChrome else VideoThemes.variant(backgroundChoice, 0))
}

private fun retarget(next: Palette, motionMs: Int) {
    if (next == targetPalette.value) return
    themeMotionMs.intValue = motionMs
    targetPalette.value = next
}

/**
 * Eases [targetPalette] into [shownPalette] and returns the frame's colours. Called once at the
 * root, before the screens read [Bg] and [Panel], so the whole app moves together. The first
 * frame is whatever was snapped in before the UI existed, so a cold start does not fade in.
 */
@Composable
internal fun animatedPalette(): Palette {
    val target = targetPalette.value
    val motion = themeMotionMs.intValue
    val bg = animateRole(target.bg, motion)
    val panel = animateRole(target.panel, motion)
    val textPrimary = animateRole(target.textPrimary, motion)
    val textMuted = animateRole(target.textMuted, motion)
    val accent = animateRole(target.accent, motion)
    val accentGreen = animateRole(target.accentGreen, motion)
    val border = animateRole(target.border, motion)
    val settled = bg == target.bg && panel == target.panel && textPrimary == target.textPrimary &&
        textMuted == target.textMuted && accent == target.accent &&
        accentGreen == target.accentGreen && border == target.border
    // While the colours are still moving, bar icons follow the colour on screen. At rest, hand
    // back the exact target so a theme check sees Dark or Light itself, not a copy.
    val frame = if (settled) {
        target
    } else {
        Palette(bg, panel, textPrimary, textMuted, accent, accentGreen, border, bg.luminance() > 0.45f)
    }
    val shown = Snapshot.withoutReadObservation { shownPalette.value }
    if (shown != frame) shownPalette.value = frame
    return frame
}

@Composable
private fun animateRole(target: Color, motionMs: Int): Color {
    val anim = remember { Animatable(target) }
    LaunchedEffect(target, motionMs) {
        if (anim.value != target) {
            anim.animateTo(target, tween(durationMillis = motionMs, easing = FastOutSlowInEasing))
        }
        if (anim.value != target) anim.snapTo(target)
    }
    return anim.value
}

/**
 * Chrome taken from a scene: the sky, darkened, for the page and panels, and the scene's light
 * colour for text. Panels stay dark so light text still reads on the picture. Photos share one
 * neutral dark spec, so they land here too.
 */
fun paletteFor(scene: SceneSpec): Palette {
    val sky = Color(scene.colors[0]).copy(alpha = 1f)
    val mid = Color(scene.colors[1]).copy(alpha = 1f)
    val rawLight = Color(scene.colors.getOrElse(3) { -1 }).copy(alpha = 1f)
    val text = if (rawLight.luminance() >= 0.7f) rawLight else Color.White
    val bg = darkenUntil(sky, text, 7.5f)
    var panel = mix(bg, Color.White, 0.08f)
    if (contrastRatio(text, panel) < TEXT_CONTRAST) panel = darkenUntil(panel, text, TEXT_CONTRAST)
    val accent = contrastingAccent(panel, listOf(rawLight, mid, Color(0xFF45D483), Color.White))
    return Palette(
        bg = bg,
        panel = panel,
        textPrimary = text,
        textMuted = mix(text, panel, 0.42f),
        accent = accent,
        accentGreen = accent,
        border = mix(panel, text, 0.18f),
        isLight = false,
    )
}

/** WCAG contrast of [foreground] against [background]. 4.5 is the body-text minimum. */
internal fun contrastRatio(foreground: Color, background: Color): Float {
    val lighter = max(foreground.luminance(), background.luminance())
    val darker = min(foreground.luminance(), background.luminance())
    return (lighter + 0.05f) / (darker + 0.05f)
}

private fun mix(a: Color, b: Color, t: Float) = Color(
    red = a.red + (b.red - a.red) * t,
    green = a.green + (b.green - a.green) * t,
    blue = a.blue + (b.blue - a.blue) * t,
    alpha = 1f,
)

private fun darkenUntil(color: Color, against: Color, minContrast: Float): Color {
    val start = color.copy(alpha = 1f)
    if (contrastRatio(against, start) >= minContrast) return start
    var t = 0f
    var current = start
    while (contrastRatio(against, current) < minContrast && t < 1f) {
        t = (t + 0.04f).coerceAtMost(1f)
        current = mix(start, Color.Black, t)
    }
    return current
}

private fun contrastingAccent(panel: Color, candidates: List<Color>): Color {
    var best = candidates.maxBy { contrastRatio(it.copy(alpha = 1f), panel) }.copy(alpha = 1f)
    if (contrastRatio(best, panel) >= TEXT_CONTRAST) return best
    val aim = if (panel.luminance() > 0.4f) Color.Black else Color.White
    var t = 0f
    var color = best
    while (contrastRatio(color, panel) < TEXT_CONTRAST && t < 1f) {
        t = (t + 0.05f).coerceAtMost(1f)
        color = mix(best, aim, t)
    }
    return color
}

val Bg: Color get() = currentPalette.bg
val Panel: Color get() = currentPalette.panel
val TextPrimary: Color get() = currentPalette.textPrimary
val TextMuted: Color get() = currentPalette.textMuted
val Accent: Color get() = currentPalette.accent
val AccentGreen: Color get() = currentPalette.accentGreen

/**
 * Icons and labels drawn on [accent]: white, or the panel colour when the accent is light (a
 * photo's near-white highlight), so they never vanish into it. Web: palette.js `onAccent`.
 */
fun onAccentFor(accent: Color, panel: Color): Color =
    if (contrastRatio(Color.White, accent) >= contrastRatio(panel, accent)) Color.White else panel

val OnAccent: Color get() = onAccentFor(currentPalette.accent, currentPalette.panel)
val OnAccentGreen: Color get() = onAccentFor(currentPalette.accentGreen, currentPalette.panel)
val Border: Color get() = currentPalette.border
