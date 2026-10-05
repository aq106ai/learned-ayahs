package com.quran.learnedplayer.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.quran.learnedplayer.ui.theme.AccentGreen
import com.quran.learnedplayer.ui.theme.Panel
import com.quran.learnedplayer.ui.theme.TextMuted
import com.quran.learnedplayer.ui.theme.TextPrimary
import com.quran.learnedplayer.ui.theme.quranArabicStyle

const val TAG_INTRO_DIALOG = "intro_dialog"
const val TAG_INTRO_NEXT = "intro_next"
const val TAG_INTRO_DISMISS = "intro_dismiss"

private const val PAGE_COUNT = 3

/**
 * Shown once, on first launch: why revising what you have memorised matters, then a short
 * guide to marking ayahs and to revising them. Dismissing at any point (Skip, tapping
 * outside, or Begin on the last page) marks the intro seen — it never reappears.
 */
@Composable
fun IntroDialog(onDismiss: () -> Unit) {
    var page by rememberSaveable { mutableIntStateOf(0) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Panel,
        modifier = Modifier.testTag(TAG_INTRO_DIALOG),
        title = {
            Text(
                text = when (page) {
                    0 -> "Keep what you have learned"
                    1 -> "Mark what you know"
                    else -> "Revise and read"
                },
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary,
            )
        },
        text = {
            Column {
                when (page) {
                    0 -> HadithPage()
                    1 -> MarkingPage()
                    else -> RevisingPage()
                }
                Text(
                    text = "${page + 1} / $PAGE_COUNT",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                )
            }
        },
        confirmButton = {
            if (page < PAGE_COUNT - 1) {
                TextButton(onClick = { page++ }, modifier = Modifier.testTag(TAG_INTRO_NEXT)) {
                    Text("Next", color = AccentGreen)
                }
            } else {
                TextButton(onClick = onDismiss, modifier = Modifier.testTag(TAG_INTRO_DISMISS)) {
                    Text("Begin", color = AccentGreen)
                }
            }
        },
        dismissButton = {
            if (page < PAGE_COUNT - 1) {
                TextButton(onClick = onDismiss) {
                    Text("Skip", color = TextMuted)
                }
            }
        },
    )
}

@Composable
private fun HadithPage() {
    Column {
        Text(
            text = "تَعَاهَدُوا هَذَا الْقُرْآنَ، فَوَالَّذِي نَفْسُ مُحَمَّدٍ بِيَدِهِ " +
                "لَهُوَ أَشَدُّ تَفَلُّتًا مِنَ الْإِبِلِ فِي عُقُلِهَا",
            style = quranArabicStyle(22f),
            color = TextPrimary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = "\"Keep refreshing your knowledge of the Qur'an, for by Him in Whose " +
                "Hand my soul is, it is more liable to escape than camels which are tied.\"",
            style = MaterialTheme.typography.bodyMedium,
            color = TextPrimary,
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(
            text = "Sahih al-Bukhari 5033 · Sahih Muslim 791",
            style = MaterialTheme.typography.bodySmall,
            color = TextMuted,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            text = "Mark the ayahs you have memorised, and this app will help you revise them.",
            style = MaterialTheme.typography.bodySmall,
            color = TextMuted,
            modifier = Modifier.padding(top = 16.dp),
        )
    }
}

@Composable
private fun MarkingPage() {
    TipList(
        "Open any surah and tap the circle beside an ayah you have memorised.",
        "Adding a lot at once? “Add by description” at the bottom of the surah list takes " +
            "refs like 2:255, 36:1-83, or whole surahs like 112.",
        "Already used this app? “Import”, beside it, restores an exported backup.",
    )
}

@Composable
private fun RevisingPage() {
    TipList(
        "Press play and your learned ayahs recite in order — turn on “Repeat ayah” to have " +
            "each one repeat so it settles in.",
        "The player reads along with you: each word lights up as it is recited. Swipe or tap " +
            "to move between ayahs.",
        "Try “Word by word” in the player header — one word at a time with its meaning, " +
            "and Next/Previous (even on headsets) step word by word.",
    )
}

@Composable
private fun TipList(vararg tips: String) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        tips.forEach { tip ->
            Row {
                Text(
                    text = "•",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = AccentGreen,
                )
                Text(
                    text = tip,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextPrimary,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}
