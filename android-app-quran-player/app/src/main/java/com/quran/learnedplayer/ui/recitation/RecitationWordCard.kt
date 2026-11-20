package com.quran.learnedplayer.ui.recitation

import com.quran.learnedplayer.ui.i18n.tx
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.quran.learnedplayer.data.AyahWord
import com.quran.learnedplayer.ui.theme.AccentGreen
import com.quran.learnedplayer.ui.theme.Bg
import com.quran.learnedplayer.ui.theme.Border
import com.quran.learnedplayer.ui.theme.TextMuted
import com.quran.learnedplayer.ui.theme.TextPrimary
import com.quran.learnedplayer.ui.theme.quranArabicStyle
import kotlinx.coroutines.delay

const val TAG_RECITATION_WORD_CURRENT = "recitation_word_current"
const val TAG_RECITATION_WORD_DONE = "recitation_word_done"

private val MistakeRed = Color(0xFFEF5350)

/**
 * Word-by-word Recite: the word to say now, large, with the words already said above it.
 *
 * Upcoming words are never shown, because reciting from memory is the point. The word on screen
 * is the ViewModel's frontier, which moves only once a word has been recited correctly. Nothing
 * here animates the change: the next word must be there the moment the recogniser has heard the
 * last one, and a student who says three words in one breath should see the fourth straight away,
 * not three transitions.
 */
@Composable
fun RecitationWordCard(
    words: List<AyahWord>,
    frontier: Int,
    believedMistakes: Set<Int>,
    confirmedMistakeIndex: Int?,
    surah: Int,
    ayah: Int,
    onPlayWord: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val contentWords = remember(words) { words.filter { !it.isEnd } }

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (contentWords.isEmpty()) {
            // Moving between ayahs blanks the words for a moment while the next ayah's text
            // loads (usually from memory, in a few ms). A spinner flashing at every ayah would
            // read as lag, so only show one if the wait is real.
            var showSpinner by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                delay(SPINNER_DELAY_MS)
                showSpinner = true
            }
            if (showSpinner) CircularProgressIndicator(color = AccentGreen)
            return@Box
        }

        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            DoneWordsRow(
                contentWords = contentWords,
                frontier = frontier,
                believedMistakes = believedMistakes,
                onPlayWord = onPlayWord,
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                key(frontier) {
                    val word = contentWords.getOrNull(frontier)
                    if (word == null) {
                        // Every word said: the run moves on by itself in a moment.
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = tx("Ayah complete"),
                            tint = AccentGreen,
                            modifier = Modifier.size(64.dp),
                        )
                    } else {
                        CurrentWord(
                            word = word,
                            isMistake = confirmedMistakeIndex == frontier,
                            onClick = { onPlayWord(frontier) },
                        )
                    }
                }
            }

            Text(
                text = tx(
                    "Word {0} / {1} · {2}:{3}",
                    (frontier + 1).coerceAtMost(contentWords.size),
                    contentWords.size,
                    surah,
                    ayah,
                ),
                style = MaterialTheme.typography.labelMedium,
                color = TextMuted,
            )
        }
    }
}

@Composable
private fun CurrentWord(word: AyahWord, isMistake: Boolean, onClick: () -> Unit) {
    val borderColor = if (isMistake) MistakeRed else Border
    Column(
        modifier = Modifier
            .testTag(TAG_RECITATION_WORD_CURRENT)
            .clip(RoundedCornerShape(16.dp))
            .background(if (isMistake) Color(0x22EF5350) else Bg)
            .border(1.dp, borderColor, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 28.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = word.text,
            style = quranArabicStyle(CURRENT_WORD_SP),
            color = if (isMistake) Color(0xFFFF8A80) else TextPrimary,
            textAlign = TextAlign.Center,
        )
        word.translation?.let { meaning ->
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = meaning,
                style = MaterialTheme.typography.bodyMedium,
                color = TextMuted,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** The words already recited, newest nearest the reading edge, kept scrolled to the newest. */
@Composable
private fun DoneWordsRow(
    contentWords: List<AyahWord>,
    frontier: Int,
    believedMistakes: Set<Int>,
    onPlayWord: (Int) -> Unit,
) {
    val done = contentWords.take(frontier.coerceIn(0, contentWords.size))
    val listState = rememberLazyListState()
    LaunchedEffect(done.size) {
        if (done.isNotEmpty()) listState.scrollToItem(done.lastIndex)
    }
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        LazyRow(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .height(DONE_ROW_HEIGHT),
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            itemsIndexed(done) { index, word ->
                // Green once said; red if the coach believed a mistake on it before it was said
                // right, so the student can still see where they slipped.
                val slipped = index in believedMistakes
                Text(
                    text = word.text,
                    style = quranArabicStyle(DONE_WORD_SP),
                    color = if (slipped) Color(0xFFFF8A80) else TextPrimary,
                    modifier = Modifier
                        .testTag(TAG_RECITATION_WORD_DONE)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (slipped) Color(0x33EF5350) else Color(0x3345D483))
                        .border(
                            1.dp,
                            if (slipped) MistakeRed else AccentGreen,
                            RoundedCornerShape(8.dp),
                        )
                        .clickable { onPlayWord(index) }
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }
    }
}

private const val CURRENT_WORD_SP = 52f
private const val DONE_WORD_SP = 20f
private val DONE_ROW_HEIGHT = 56.dp
private const val SPINNER_DELAY_MS = 250L
