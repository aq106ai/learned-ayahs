package com.quran.learnedplayer.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.quran.learnedplayer.data.AyahWord
import com.quran.learnedplayer.ui.theme.AccentGreen
import com.quran.learnedplayer.ui.theme.Border
import com.quran.learnedplayer.ui.theme.TextMuted
import com.quran.learnedplayer.ui.theme.TextPrimary
import com.quran.learnedplayer.ui.theme.quranArabicStyle

const val TAG_WORD_FLOW = "word_by_word_flow"

/**
 * An ayah laid out word by word: each Arabic word with its English meaning underneath, wrapping
 * right-to-left across lines.
 *
 * Rendering one Text per word is safe for Arabic here because letters never join across a space,
 * so a word's internal shaping and ligatures are unaffected — only the whole-ayah *justification*
 * differs from the single-run layout used when translations are hidden.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WordByWordFlow(
    words: List<AyahWord>,
    modifier: Modifier = Modifier,
    fontSize: Float,
    activeIndex: Int = -1,
    onActiveWordPositioned: ((LayoutCoordinates) -> Unit)? = null,
    onWordClick: ((Int) -> Unit)? = null,
) {
    // The end-of-ayah glyph has no meaning of its own; keep it inline as a plain marker.
    val entries = remember(words) {
        var contentIndex = 0
        words.map { word ->
            val index = if (word.isEnd) -1 else contentIndex++
            word to index
        }
    }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        FlowRow(
            modifier = modifier.fillMaxWidth().testTag(TAG_WORD_FLOW),
            horizontalArrangement = Arrangement.Center,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            entries.forEach { (word, index) ->
                val isActive = index >= 0 && index == activeIndex
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .padding(horizontal = 3.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (isActive) Border else androidx.compose.ui.graphics.Color.Transparent)
                        .then(
                            if (onWordClick != null && index >= 0) {
                                Modifier.clickable { onWordClick(index) }
                            } else {
                                Modifier
                            },
                        )
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                        .then(
                            if (isActive && onActiveWordPositioned != null) {
                                Modifier.onGloballyPositioned(onActiveWordPositioned)
                            } else {
                                Modifier
                            },
                        ),
                ) {
                    Text(
                        text = word.text,
                        color = if (isActive) AccentGreen else TextPrimary,
                        style = quranArabicStyle(fontSize).copy(
                            fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                        ),
                        textAlign = TextAlign.Center,
                    )
                    if (!word.isEnd) {
                        Text(
                            text = word.translation ?: "",
                            color = if (isActive) AccentGreen else TextMuted,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = (fontSize * 0.42f).sp,
                            ),
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}
