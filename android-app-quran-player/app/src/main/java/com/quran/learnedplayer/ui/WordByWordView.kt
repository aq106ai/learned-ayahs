package com.quran.learnedplayer.ui

import android.os.SystemClock
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.quran.learnedplayer.data.AyahWord
import com.quran.learnedplayer.data.WordSync
import com.quran.learnedplayer.player.PlayerSettings
import com.quran.learnedplayer.player.PlayerUiState
import com.quran.learnedplayer.service.PlayerStateHolder
import com.quran.learnedplayer.ui.theme.AccentGreen
import com.quran.learnedplayer.ui.theme.TextMuted
import com.quran.learnedplayer.ui.theme.TextPrimary
import com.quran.learnedplayer.ui.theme.quranArabicStyle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

const val TAG_WORD_PAGER = "word_by_word_pager"
const val TAG_WORD_ARABIC = "word_arabic"
const val TAG_WORD_TRANSLATION = "word_translation"
const val TAG_WORD_COUNTER = "word_counter"

/** Which ayah boundary the reader was heading for when it crossed an edge page. */
private enum class EdgeIntent { NEXT, PREVIOUS }

/** How long to let an ayah change land before treating an edge page as a dead end. */
private const val EDGE_SETTLE_GRACE_MS = 600L

/** How long after a user's swipe to stop the playback-follow from overriding their choice. */
private const val FOLLOW_GRACE_MS = 800L

/**
 * Focused word-by-word reader: one word at a time with its English meaning.
 *
 * Swipe left for the next word and right for the previous one; swiping past the last (or first)
 * word carries on into the next (or previous) ayah, landing on its first (or last) word.
 * Settling on a word seeks the recitation to it, while playback in turn moves the focus — the
 * two are kept from fighting by only seeking for a settle the user caused by dragging.
 *
 * The ayah boundaries are real pages either side of the words rather than an overscroll
 * gesture: the pager's own overscroll effect swallows leftover drag, so a nested-scroll
 * approach never sees it. Paging onto an edge page is unambiguous and testable.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WordByWordView(
    state: PlayerUiState,
    onNextAyah: () -> Unit,
    onPreviousAyah: () -> Unit,
    onPlayWord: (Int) -> Unit = {},
    wordFileMode: Boolean = false,
    modifier: Modifier = Modifier,
) {
    // Declared above the early return below. Changing ayah blanks `ayahWords` while the next
    // one loads, which tears this composable down to the loading branch — anything remembered
    // after that point would be discarded, losing which way we crossed the boundary and
    // landing the reader on the wrong word.
    var pendingEdge by remember { mutableStateOf<EdgeIntent?>(null) }
    val currentOnNext by rememberUpdatedState(onNextAyah)
    val currentOnPrevious by rememberUpdatedState(onPreviousAyah)

    // Also declared above the early return, and for the same reason. The hook that turns
    // Next/Previous into word steps must stay registered for as long as the reader is on screen —
    // including the window where the next ayah's words are still loading, which blanks `ayahWords`
    // and tears everything below the return down. If it lapsed there, a Next from Bluetooth (or
    // the lock screen / Auto) would fall through to a real ayah skip, and since every skip blanks
    // the words again, a run of taps races through ayahs instead of stepping words.
    // The pager below fills in `stepPage`. Until it exists the answer depends on *why* there is
    // no pager: while the words are still loading the press is swallowed (letting it through is
    // the skip cascade above), but once they have settled empty — "Ayah text unavailable", which
    // never resolves by waiting — it must be declined, or every controller's Next/Previous is
    // consumed with no effect and the user is trapped on that ayah until they close the reader.
    val stepPage = remember { mutableStateOf<((Int) -> Unit)?>(null) }
    val textLoading by rememberUpdatedState(state.ayahTextLoading)
    // In WORD_BY_WORD the service owns word steps (the queue is word files). Registering
    // the pager hook would double-step Next from the notification / lock screen.
    DisposableEffect(wordFileMode) {
        if (wordFileMode) {
            PlayerStateHolder.setWordStepHandler(null)
        } else {
            PlayerStateHolder.setWordStepHandler { delta ->
                stepPage.value?.let { step ->
                    step(delta)
                    return@setWordStepHandler true
                }
                textLoading
            }
        }
        onDispose { PlayerStateHolder.setWordStepHandler(null) }
    }

    val contentWords = remember(state.ayahWords) { state.ayahWords.filterNot { it.isEnd } }
    if (contentWords.isEmpty()) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = if (state.ayahTextLoading) "Loading ayah text…" else "Ayah text unavailable.",
                color = TextMuted,
            )
        }
        return
    }

    // Empty when this ayah has no validated timings: swiping still works, but a settle won't
    // seek and playback won't move the focus — better than seeking to a guessed position.
    val segments = state.wordSegments

    // Page 0 and the last page are the ayah boundaries; words occupy 1..contentWords.size.
    val firstWordPage = 1
    val lastWordPage = contentWords.size
    val edgePage = contentWords.size + 1
    val pagerState = rememberPagerState(
        initialPage = firstWordPage,
        pageCount = { contentWords.size + 2 },
    )
    val isDragged by pagerState.interactionSource.collectIsDraggedAsState()
    var userDragged by remember { mutableStateOf(false) }
    var followSuppressedUntilMs by remember { mutableLongStateOf(0L) }

    // A new ayah resets the focus: arriving forwards lands on its first word, backwards on its
    // last, so swiping to and fro across a boundary reads continuously.
    val globalId = state.currentTrack?.globalId
    LaunchedEffect(globalId, contentWords.size) {
        val target = if (pendingEdge == EdgeIntent.PREVIOUS) lastWordPage else firstWordPage
        pendingEdge = null
        userDragged = false
        pagerState.scrollToPage(target)
    }

    LaunchedEffect(isDragged) {
        if (isDragged) userDragged = true
    }

    val highlightEnabled = state.introLabel == null && (state.isPlaying || state.positionMs > 0L)
    val activeIndex = when {
        wordFileMode -> state.currentWordIndex
        highlightEnabled ->
            WordSync.activeWordIndex(state.positionMs, segments, contentWords.size)
        else -> -1
    }

    // Follow the recitation, but only while it is actually playing: when paused the user is
    // the one navigating, and chasing a static position would drag them back. The grace window
    // covers the same race during playback — a swipe seeks, but `activeIndex` still describes
    // the previous word until the player reports its new position, and without this the reader
    // would be yanked back to the word it just left.
    LaunchedEffect(activeIndex, isDragged, state.isPlaying, wordFileMode) {
        if (isDragged) return@LaunchedEffect
        if (!wordFileMode && !state.isPlaying) return@LaunchedEffect
        if (SystemClock.elapsedRealtime() < followSuppressedUntilMs) return@LaunchedEffect
        val targetPage = if (wordFileMode) state.currentWordIndex + 1 else activeIndex + 1
        if (targetPage in firstWordPage..lastWordPage && targetPage != pagerState.currentPage) {
            pagerState.animateScrollToPage(targetPage)
        }
    }

    LaunchedEffect(pagerState.settledPage) {
        val page = pagerState.settledPage
        when {
            page == 0 || page == edgePage -> {
                userDragged = false
                val goingBack = page == 0
                pendingEdge = if (goingBack) EdgeIntent.PREVIOUS else EdgeIntent.NEXT
                // Ask for a real ayah change: the hook this reader registered would otherwise
                // intercept its own request and step a word, trapping us on the edge page.
                PlayerStateHolder.skippingWordStep {
                    if (goingBack) currentOnPrevious() else currentOnNext()
                }
                // A successful move changes the ayah, which repositions via the effect above
                // and cancels this one. Only when there is no ayah that way (the ends of the
                // playlist) does the wait elapse — then step back onto a word rather than
                // stranding the reader on the edge page.
                delay(EDGE_SETTLE_GRACE_MS)
                if (pagerState.currentPage == page) {
                    pendingEdge = null
                    pagerState.animateScrollToPage(if (goingBack) firstWordPage else lastWordPage)
                }
            }
            // Seek / play only for a settle the user caused, so following playback can't re-seek in a loop.
            userDragged -> {
                userDragged = false
                followSuppressedUntilMs = SystemClock.elapsedRealtime() + FOLLOW_GRACE_MS
                val wordIndex = page - 1
                if (wordFileMode) {
                    onPlayWord(wordIndex)
                }
            }
        }
    }

    // Next/Previous step words instead of ayahs — for the on-screen buttons and for the
    // notification / lock screen / Bluetooth / Auto alike, since every controller reaches the
    // transport through PlaybackService.skipNext/skipPrevious. Stepping onto an edge page carries
    // into the neighbouring ayah, same as swiping. This only supplies the pager move; the hook
    // itself is registered above the early return so it cannot lapse between ayahs.
    val scope = rememberCoroutineScope()
    DisposableEffect(pagerState, edgePage) {
        stepPage.value = { delta ->
            scope.launch {
                userDragged = true
                pagerState.animateScrollToPage(
                    (pagerState.currentPage + delta).coerceIn(0, edgePage),
                )
            }
        }
        onDispose { stepPage.value = null }
    }

    val swipeToNavigate by PlayerSettings.swipeToNavigateFlow.collectAsState()
    val tapToAdvance by PlayerSettings.tapToAdvanceFlow.collectAsState()

    Column(modifier = modifier.fillMaxSize()) {
        // The next word lies to the left, so the pager is laid out RTL and a rightward swipe
        // advances — the way an Arabic book turns. The page indices are unchanged; only the
        // direction of travel flips, which also puts the "previous" edge page on the right
        // where it belongs.
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .then(
                        if (tapToAdvance) {
                            Modifier.clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() },
                                onClick = { stepPage.value?.invoke(1) },
                            )
                        } else {
                            Modifier
                        },
                    ),
            ) {
                HorizontalPager(
                    state = pagerState,
                    userScrollEnabled = swipeToNavigate,
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag(TAG_WORD_PAGER),
                ) { page ->
                    when (page) {
                        0 -> EdgePage("Previous ayah →")
                        edgePage -> EdgePage("← Next ayah")
                        else -> WordPage(
                            word = contentWords[page - 1],
                            isActive = page - 1 == activeIndex,
                            onSelect = if (!tapToAdvance && wordFileMode) {
                                {
                                    val wordIndex = page - 1
                                    onPlayWord(wordIndex)
                                }
                            } else {
                                null
                            },
                        )
                    }
                }
            }
        }
        Text(
            text = "${pagerState.currentPage.coerceIn(firstWordPage, lastWordPage)} / " +
                "${contentWords.size}   ·   swipe for the next word",
            color = TextMuted,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp)
                .testTag(TAG_WORD_COUNTER),
        )
    }
}

@Composable
private fun EdgePage(label: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text = label, color = TextMuted, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun WordPage(word: AyahWord, isActive: Boolean, onSelect: (() -> Unit)? = null) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp)
            .then(if (onSelect != null) Modifier.clickable(onClick = onSelect) else Modifier),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = word.text,
            color = if (isActive) AccentGreen else TextPrimary,
            textAlign = TextAlign.Center,
            // The style already carries RTL direction, so a single word shapes correctly
            // without flipping the pager itself (which swipes left-to-right).
            style = quranArabicStyle(64f),
            modifier = Modifier.fillMaxWidth().testTag(TAG_WORD_ARABIC),
        )
        Text(
            text = word.translation ?: "—",
            color = TextMuted,
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 24.dp)
                .testTag(TAG_WORD_TRANSLATION),
        )
    }
}
