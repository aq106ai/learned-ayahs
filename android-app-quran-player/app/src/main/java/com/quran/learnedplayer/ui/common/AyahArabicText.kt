package com.quran.learnedplayer.ui.common

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import com.quran.learnedplayer.data.AyahWord
import com.quran.learnedplayer.ui.theme.TextPrimary
import com.quran.learnedplayer.ui.theme.quranArabicStyle

/**
 * Renders an ayah's words as a single RTL string in the Qur'anic Naskh face.
 *
 * A single AnnotatedString (rather than one Text per word) is deliberate — Arabic shaping and
 * ligatures only resolve correctly when the run is laid out together.
 */
@Composable
fun AyahArabicText(
    words: List<AyahWord>,
    modifier: Modifier = Modifier,
    fontSize: Float,
    color: Color = TextPrimary,
    textAlign: TextAlign = TextAlign.End,
) {
    val text = remember(words) {
        buildAnnotatedString {
            words.forEach { word ->
                if (word.isEnd) append(" ${word.text}") else append("${word.text} ")
            }
        }
    }
    Text(
        text = text,
        modifier = modifier,
        color = color,
        textAlign = textAlign,
        style = quranArabicStyle(fontSize),
    )
}
