package com.quran.learnedplayer.ui

import android.os.SystemClock
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.quran.learnedplayer.data.WordSync
import com.quran.learnedplayer.player.AyahOverflowMode
import com.quran.learnedplayer.player.PlayerSettings
import com.quran.learnedplayer.player.PlayerUiState
import com.quran.learnedplayer.ui.common.WordByWordFlow
import com.quran.learnedplayer.ui.common.advanceOnTap
import com.quran.learnedplayer.ui.common.swipeToAdvance
import com.quran.learnedplayer.ui.theme.ARABIC_BASE_SIZE
import com.quran.learnedplayer.ui.theme.AccentGreen
import com.quran.learnedplayer.ui.theme.TextMuted
import com.quran.learnedplayer.ui.theme.TextPrimary
import com.quran.learnedplayer.ui.theme.findActivity
import com.quran.learnedplayer.ui.theme.quranArabicStyle
import kotlinx.coroutines.delay

/** UI-side tick between service progress updates, for smooth word tracking. */
private const val SMOOTH_TICK_MS = 100L

/** Ayah font size (sp) in the immersive reader. */
private const val ARABIC_FULLSCREEN_SIZE = 34f

/** Fit-to-screen never shrinks past this fraction of [ARABIC_FULLSCREEN_SIZE]. */
private const val MIN_FIT_SCALE = 0.4f
private const val FIT_SCALE_STEP = 0.05f

const val TAG_FULLSCREEN_WHOLE_AYAH = "fullscreen_whole_ayah"

/**
 * The player's central reading area: the word-by-word pager or the whole-ayah view, with
 * swipe/tap-to-advance wired in. [wordByWord] is owned by the caller (the toggle lives in
 * the screen header now that this is the only reader — there's no separate inline/fullscreen
 * split anymore).
 */
@Composable
fun AyahReaderContent(
    state: PlayerUiState,
    wordByWord: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onPlayWord: (Int) -> Unit = {},
    wordFileMode: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val swipeToNavigate by PlayerSettings.swipeToNavigateFlow.collectAsState()
    val tapToAdvance by PlayerSettings.tapToAdvanceFlow.collectAsState()
    // Tap/swipe-to-advance would fight the word pager's own swipe/tap handling.
    val tapAdvances = tapToAdvance && !wordByWord
    val swipeAdvances = swipeToNavigate && !wordByWord

    // The word reader pages horizontally and must fill this area exactly; the whole-ayah view
    // instead needs to handle a long ayah (2:282 is the extreme) outgrowing it — either by
    // scrolling or by shrinking, see WholeAyahReader. BoxWithConstraints supplies the viewport
    // height both branches size themselves against.
    BoxWithConstraints(
        modifier = modifier
            .testTag(TAG_FULLSCREEN_WHOLE_AYAH)
            .swipeToAdvance(
                enabled = swipeAdvances,
                onAdvance = onNext,
                onGoBack = onPrevious,
            )
            .advanceOnTap(enabled = tapAdvances, label = "Next ayah", onTap = onNext),
    ) {
        if (wordByWord) {
            WordByWordView(
                state = state,
                onNextAyah = onNext,
                onPreviousAyah = onPrevious,
                onPlayWord = onPlayWord,
                wordFileMode = wordFileMode,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 24.dp),
            )
        } else {
            WholeAyahReader(
                state = state,
                maxHeight = maxHeight,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * The non-word-by-word reader. [PlayerSettings.ayahOverflowMode] picks how it handles an ayah
 * taller than one screen: [AyahOverflowMode.AUTO_SCROLL] scrolls to keep the word being recited
 * centred as it plays; [AyahOverflowMode.FIT_TO_SCREEN] shrinks the font instead so the whole
 * ayah is visible at once, no scrolling.
 */
@Composable
private fun WholeAyahReader(
    state: PlayerUiState,
    maxHeight: Dp,
    modifier: Modifier = Modifier,
) {
    val overflowMode by PlayerSettings.ayahOverflowModeFlow.collectAsState()
    when (overflowMode) {
        AyahOverflowMode.FIT_TO_SCREEN -> {
            val density = LocalDensity.current
            val maxHeightPx = with(density) { (maxHeight - 48.dp).coerceAtLeast(0.dp).roundToPx() }
            Box(
                modifier = modifier
                    .fillMaxWidth()
                    .heightIn(min = maxHeight)
                    .padding(horizontal = 16.dp, vertical = 24.dp),
                contentAlignment = Alignment.Center,
            ) {
                ShrinkToFit(maxHeightPx = maxHeightPx, modifier = Modifier.fillMaxWidth()) { fontScale ->
                    AyahWordsContent(
                        state = state,
                        large = true,
                        fontScale = fontScale,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        AyahOverflowMode.AUTO_SCROLL -> {
            val scrollState = rememberScrollState()
            var containerCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
            Box(
                modifier = modifier
                    .fillMaxWidth()
                    .onGloballyPositioned { containerCoordinates = it }
                    .verticalScroll(scrollState)
                    .heightIn(min = maxHeight)
                    .padding(horizontal = 16.dp, vertical = 24.dp),
                contentAlignment = Alignment.Center,
            ) {
                AyahWordsContent(
                    state = state,
                    large = true,
                    scrollState = scrollState,
                    containerCoordinates = { containerCoordinates },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/**
 * Measures [content] at decreasing font scales (1.0 down to [MIN_FIT_SCALE]) until it fits
 * within [maxHeightPx], then commits to that scale. A real measurement pass at each candidate
 * scale — rather than an approximation — is what makes this work for both the plain-text and
 * word-by-word-with-translations layouts, whose heights aren't simply related to font size.
 */
@Composable
private fun ShrinkToFit(
    maxHeightPx: Int,
    modifier: Modifier = Modifier,
    content: @Composable (fontScale: Float) -> Unit,
) {
    SubcomposeLayout(modifier) { constraints ->
        val loose = constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity)
        var scale = 1f
        var placeables = subcompose(scale) { content(scale) }.map { it.measure(loose) }
        var height = placeables.maxOfOrNull { it.height } ?: 0
        while (height > maxHeightPx && scale > MIN_FIT_SCALE) {
            scale = (scale - FIT_SCALE_STEP).coerceAtLeast(MIN_FIT_SCALE)
            placeables = subcompose(scale) { content(scale) }.map { it.measure(loose) }
            height = placeables.maxOfOrNull { it.height } ?: 0
        }
        val width = (placeables.maxOfOrNull { it.width } ?: 0).coerceAtMost(constraints.maxWidth)
        layout(width, height) {
            placeables.forEach { it.place(0, 0) }
        }
    }
}

/**
 * Hide status/nav bars for true fullscreen. Transient swipe-to-show bars
 * auto-hide again (sticky immersive) so the chrome does not stay visible.
 */
@Composable
fun ImmersiveSystemBars() {
    val view = LocalView.current
    DisposableEffect(Unit) {
        val window = view.context.findActivity()?.window
            ?: return@DisposableEffect onDispose { }
        val controller = WindowCompat.getInsetsController(window, view)
        val previousBehavior = controller.systemBarsBehavior
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            controller.systemBarsBehavior = previousBehavior
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }
}

/**
 * Position interpolated between the service's progress ticks so the word highlight
 * moves smoothly. Resyncs to the real position on every service update.
 */
@Composable
private fun rememberSmoothPositionMs(positionMs: Long, isPlaying: Boolean): Long {
    var smooth by remember { mutableLongStateOf(positionMs) }
    LaunchedEffect(positionMs, isPlaying) {
        smooth = positionMs
        if (!isPlaying) return@LaunchedEffect
        val startedAt = SystemClock.elapsedRealtime()
        while (true) {
            delay(SMOOTH_TICK_MS)
            smooth = positionMs + (SystemClock.elapsedRealtime() - startedAt)
        }
    }
    return smooth
}

/**
 * Scrolls [scrollState] so the point [pointInSource] (in [source]'s own coordinate space) ends
 * up vertically centred inside [container]. Used to keep the word being recited centred rather
 * than merely visible — computed as a relative delta from current on-screen positions, so it
 * keeps working correctly as scrolling itself moves those positions between calls.
 */
private suspend fun centerOn(
    scrollState: ScrollState,
    container: LayoutCoordinates,
    source: LayoutCoordinates,
    pointInSource: Offset,
) {
    if (!container.isAttached || !source.isAttached) return
    val pointInContainer = try {
        container.localPositionOf(source, pointInSource)
    } catch (_: IllegalStateException) {
        return
    }
    val delta = pointInContainer.y - container.size.height / 2f
    scrollState.animateScrollBy(delta)
}

@Composable
private fun AyahWordsContent(
    state: PlayerUiState,
    modifier: Modifier = Modifier,
    large: Boolean = false,
    fontScale: Float = 1f,
    scrollState: ScrollState? = null,
    containerCoordinates: (() -> LayoutCoordinates?)? = null,
) {
    val words = state.ayahWords
    val fontSize = (if (large) ARABIC_FULLSCREEN_SIZE else ARABIC_BASE_SIZE) * fontScale
    val showTranslations by PlayerSettings.showWordTranslationsFlow.collectAsState()
    when {
        state.ayahTextLoading -> Text(
            text = "Loading text…",
            color = TextMuted,
            modifier = modifier,
            textAlign = TextAlign.Center,
        )
        words.isEmpty() -> Text(
            text = "…",
            color = TextMuted,
            modifier = modifier,
            textAlign = TextAlign.Center,
        )
        else -> {
            // No highlight during the A'udhu/Bismillah intro (its position isn't the
            // ayah's) or before the ayah has started playing.
            val highlightEnabled = state.introLabel == null &&
                (state.isPlaying || state.positionMs > 0L)
            val smoothPosition = rememberSmoothPositionMs(state.positionMs, state.isPlaying)
            // Empty when this ayah has no validated timings; the words then simply don't
            // highlight, rather than being lit up from a guess.
            val segments = state.wordSegments
            val contentWordCount = remember(words) { words.count { !it.isEnd } }
            val activeIndex = if (highlightEnabled) {
                WordSync.activeWordIndex(smoothPosition, segments, contentWordCount)
            } else {
                -1
            }

            if (showTranslations) {
                var activeWordCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
                if (scrollState != null && containerCoordinates != null) {
                    LaunchedEffect(activeIndex, state.isPlaying) {
                        if (!state.isPlaying || activeIndex < 0) return@LaunchedEffect
                        val container = containerCoordinates() ?: return@LaunchedEffect
                        val word = activeWordCoordinates ?: return@LaunchedEffect
                        centerOn(
                            scrollState = scrollState,
                            container = container,
                            source = word,
                            pointInSource = Offset(word.size.width / 2f, word.size.height / 2f),
                        )
                    }
                }
                WordByWordFlow(
                    words = words,
                    modifier = modifier.fillMaxWidth(),
                    fontSize = fontSize,
                    activeIndex = activeIndex,
                    onActiveWordPositioned = if (scrollState != null) {
                        { coordinates -> activeWordCoordinates = coordinates }
                    } else {
                        null
                    },
                )
                return
            }
            // Single RTL AnnotatedString keeps Arabic shaping correct — highlighting
            // whole words via spans doesn't break ligatures (unlike per-word layouts).
            // The colours are keys too: the spans bake them in, so a theme switch must
            // rebuild the string or a paused ayah keeps the old theme's highlight colours.
            val text = remember(words, activeIndex, AccentGreen, TextMuted) {
                buildAnnotatedString {
                    var wordIndex = 0
                    words.forEach { word ->
                        if (word.isEnd) {
                            append(" ${word.text}")
                        } else {
                            when {
                                activeIndex < 0 -> append(word.text)
                                wordIndex == activeIndex -> withStyle(
                                    SpanStyle(color = AccentGreen, fontWeight = FontWeight.Bold),
                                ) { append(word.text) }
                                wordIndex > activeIndex -> withStyle(
                                    SpanStyle(color = TextMuted),
                                ) { append(word.text) }
                                else -> append(word.text)
                            }
                            append(" ")
                            wordIndex++
                        }
                    }
                }
            }
            // Character offset of each content word's first glyph within [text], in the exact
            // append order used above — the lookup that turns "which word is active" into
            // "which line is it on" once the real layout is known.
            val wordStartOffsets = remember(words) {
                val offsets = IntArray(contentWordCount)
                var wordIndex = 0
                var charIndex = 0
                words.forEach { word ->
                    if (word.isEnd) {
                        charIndex += 1 + word.text.length
                    } else {
                        offsets[wordIndex] = charIndex
                        charIndex += word.text.length + 1
                        wordIndex++
                    }
                }
                offsets
            }
            var textLayout by remember { mutableStateOf<TextLayoutResult?>(null) }
            var textCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
            if (scrollState != null && containerCoordinates != null) {
                LaunchedEffect(activeIndex, state.isPlaying) {
                    if (!state.isPlaying || activeIndex !in wordStartOffsets.indices) return@LaunchedEffect
                    val layout = textLayout ?: return@LaunchedEffect
                    val container = containerCoordinates() ?: return@LaunchedEffect
                    val textCoords = textCoordinates ?: return@LaunchedEffect
                    val line = layout.getLineForOffset(wordStartOffsets[activeIndex])
                    val lineMidY = (layout.getLineTop(line) + layout.getLineBottom(line)) / 2f
                    centerOn(
                        scrollState = scrollState,
                        container = container,
                        source = textCoords,
                        pointInSource = Offset(0f, lineMidY),
                    )
                }
            }
            Text(
                text = text,
                color = TextPrimary,
                modifier = modifier
                    .fillMaxWidth()
                    .then(
                        if (scrollState != null) {
                            Modifier.onGloballyPositioned { textCoordinates = it }
                        } else {
                            Modifier
                        },
                    ),
                textAlign = TextAlign.Center,
                style = quranArabicStyle(fontSize),
                onTextLayout = { textLayout = it },
            )
        }
    }
}
