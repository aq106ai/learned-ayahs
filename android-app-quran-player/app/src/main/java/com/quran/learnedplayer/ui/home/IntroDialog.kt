package com.quran.learnedplayer.ui.home

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.quran.learnedplayer.data.AppTheme
import com.quran.learnedplayer.player.PlayerSettings
import com.quran.learnedplayer.ui.common.SourcesDialog
import com.quran.learnedplayer.ui.theme.AccentGreen
import com.quran.learnedplayer.ui.theme.Border
import com.quran.learnedplayer.ui.theme.Panel
import com.quran.learnedplayer.ui.theme.TextMuted
import com.quran.learnedplayer.ui.theme.TextPrimary
import com.quran.learnedplayer.ui.theme.quranArabicStyle
import com.quran.learnedplayer.ui.video.ThemeThumbnails
import com.quran.learnedplayer.ui.video.ThemeTile
import com.quran.learnedplayer.video.NaturePhotos
import com.quran.learnedplayer.video.PlayerBackgrounds

const val TAG_INTRO_DIALOG = "intro_dialog"
const val TAG_INTRO_NEXT = "intro_next"
const val TAG_INTRO_DISMISS = "intro_dismiss"
const val TAG_INTRO_SOURCES = "intro_sources"

fun introThemeTag(theme: AppTheme) = "intro_theme_${theme.name}"
fun introBackgroundTag(choice: String) = "intro_background_$choice"

private const val PAGE_COUNT = 4

/**
 * Shown once, on first launch: why revising what you have memorised matters, how the app should
 * look (theme and player background, applied as they are picked), then a short guide to marking
 * ayahs and to revising them. The last page links to the sources and disclaimer, which are also
 * always in Settings; nobody is asked to agree to them first. Dismissing at any point (Skip,
 * tapping outside, or Begin on the last page) marks the intro seen — it never reappears.
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
                    1 -> "Make it yours"
                    2 -> "Mark what you know"
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
                    1 -> LookPage()
                    2 -> MarkingPage()
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

/** Theme and player background, applied as they are picked: the app changes behind the dialog. */
@Composable
private fun LookPage() {
    val context = LocalContext.current
    val theme by PlayerSettings.appThemeFlow.collectAsState()
    val background by PlayerSettings.playerBackgroundFlow.collectAsState()
    val drawnThumbs by ThemeThumbnails.drawn.collectAsState()
    val photoThumbs by ThemeThumbnails.photos.collectAsState()
    val firstSubject = remember { NaturePhotos(context).subjects.firstOrNull() }
    LaunchedEffect(Unit) {
        ThemeThumbnails.loadDrawn()
        if (firstSubject != null) ThemeThumbnails.loadPhotos(context)
    }
    fun pick(next: String) {
        // As in the player's background sheet: a background brings the ayah's translation with it.
        if (background == PlayerBackgrounds.PLAIN && next != PlayerBackgrounds.PLAIN) PlayerSettings.showAyahTranslation = true
        PlayerSettings.playerBackground = next
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "You can change these any time in Settings, or from the background button in the player.",
            style = MaterialTheme.typography.bodySmall,
            color = TextMuted,
        )
        Text("Theme", style = MaterialTheme.typography.labelMedium, color = TextMuted)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppTheme.entries.forEach { option ->
                FilterChip(
                    selected = theme == option,
                    onClick = { PlayerSettings.appTheme = option },
                    label = { Text(option.label) },
                    modifier = Modifier.testTag(introThemeTag(option)),
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = Border,
                        labelColor = TextMuted,
                        selectedContainerColor = AccentGreen,
                        selectedLabelColor = Panel,
                    ),
                )
            }
        }
        Text("Behind the ayah while it plays", style = MaterialTheme.typography.labelMedium, color = TextMuted)
        Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ThemeTile("Plain", null, background == PlayerBackgrounds.PLAIN, null, true, Modifier.testTag(introBackgroundTag(PlayerBackgrounds.PLAIN))) {
                pick(PlayerBackgrounds.PLAIN)
            }
            ThemeTile("Scenes", null, background == PlayerBackgrounds.MATCHED, drawnThumbs["mercy"], true, Modifier.testTag(introBackgroundTag(PlayerBackgrounds.MATCHED))) {
                pick(PlayerBackgrounds.MATCHED)
            }
            if (firstSubject != null) {
                ThemeTile("Nature photos", null, background == PlayerBackgrounds.PHOTOS, photoThumbs[firstSubject.key], true, Modifier.testTag(introBackgroundTag(PlayerBackgrounds.PHOTOS))) {
                    pick(PlayerBackgrounds.PHOTOS)
                }
            }
        }
        Text(
            "Scenes and photos follow what each ayah is about, with its translation underneath.",
            style = MaterialTheme.typography.bodySmall,
            color = TextMuted,
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
    var showSources by remember { mutableStateOf(false) }
    Column {
        TipList(
            "Press play and your learned ayahs recite in order — turn on “Repeat ayah” to have " +
                "each one repeat so it settles in.",
            "The player reads along with you: each word lights up as it is recited. Swipe or tap " +
                "to move between ayahs.",
            "Try “Word by word” in the player header — one word at a time with its meaning, " +
                "and Next/Previous (even on headsets) step word by word.",
        )
        Text(
            "The Qur'an text, meanings and recitations come from public sources, also listed in Settings.",
            style = MaterialTheme.typography.bodySmall,
            color = TextMuted,
            modifier = Modifier.padding(top = 14.dp),
        )
        TextButton(onClick = { showSources = true }, modifier = Modifier.testTag(TAG_INTRO_SOURCES)) {
            Text("Sources and disclaimer", color = AccentGreen)
        }
    }
    if (showSources) SourcesDialog(onDismiss = { showSources = false })
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
