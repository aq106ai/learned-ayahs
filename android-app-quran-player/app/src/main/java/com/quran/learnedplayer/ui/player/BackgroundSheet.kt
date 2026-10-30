package com.quran.learnedplayer.ui.player

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.quran.learnedplayer.player.PlayerSettings
import com.quran.learnedplayer.ui.theme.AccentGreen
import com.quran.learnedplayer.ui.theme.Border
import com.quran.learnedplayer.ui.theme.Panel
import com.quran.learnedplayer.ui.theme.TextMuted
import com.quran.learnedplayer.ui.theme.TextPrimary
import com.quran.learnedplayer.ui.video.ThemeThumbnails
import com.quran.learnedplayer.ui.video.ThemeTile
import com.quran.learnedplayer.video.NaturePhotos
import com.quran.learnedplayer.video.PlayerBackgrounds
import com.quran.learnedplayer.video.VideoThemes

const val TAG_BACKGROUND_SHEET = "background_sheet"
const val TAG_AYAH_TRANSLATION_TOGGLE = "ayah_translation_toggle"
fun backgroundChoiceTag(choice: String) = "background_choice_$choice"

/**
 * Picks what the player shows behind the ayah: the plain theme, a background matched to each
 * ayah (drawn or photographed), or one theme throughout; and whether the translation shows.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackgroundSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val choice by PlayerSettings.playerBackgroundFlow.collectAsState()
    val translation by PlayerSettings.showAyahTranslationFlow.collectAsState()
    val drawnThumbs by ThemeThumbnails.drawn.collectAsState()
    val photoThumbs by ThemeThumbnails.photos.collectAsState()
    val subjects = remember { NaturePhotos(context).subjects }
    LaunchedEffect(Unit) {
        ThemeThumbnails.loadDrawn()
        if (subjects.isNotEmpty()) ThemeThumbnails.loadPhotos(context)
    }

    fun pick(next: String) {
        // A background is the look of a shared video, translation included; turning one on
        // brings the translation with it. It can be switched off again just below.
        if (choice == PlayerBackgrounds.PLAIN && next != PlayerBackgrounds.PLAIN) PlayerSettings.showAyahTranslation = true
        PlayerSettings.playerBackground = next
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Panel,
        modifier = Modifier.testTag(TAG_BACKGROUND_SHEET),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Background", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = TextPrimary)
            Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Choice("Plain", PlayerBackgrounds.PLAIN, choice, ::pick)
                Choice("Matched scenes", PlayerBackgrounds.MATCHED, choice, ::pick)
                if (subjects.isNotEmpty()) Choice("Matched photos", PlayerBackgrounds.PHOTOS, choice, ::pick)
            }
            Text(
                "Matched follows what each ayah is about, as in a shared video. Or choose one for every ayah:",
                color = TextMuted,
                style = MaterialTheme.typography.bodySmall,
            )
            Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                VideoThemes.all.forEach { theme ->
                    ThemeTile(
                        title = theme.title,
                        subtitle = null,
                        selected = choice == theme.key,
                        image = drawnThumbs[theme.key],
                        enabled = true,
                        modifier = Modifier.testTag(backgroundChoiceTag(theme.key)),
                    ) { pick(theme.key) }
                }
            }
            if (subjects.isNotEmpty()) {
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    subjects.forEach { subject ->
                        ThemeTile(
                            title = subject.title,
                            subtitle = null,
                            selected = choice == subject.themeKey,
                            image = photoThumbs[subject.key],
                            enabled = true,
                            modifier = Modifier.testTag(backgroundChoiceTag(subject.themeKey)),
                        ) { pick(subject.themeKey) }
                    }
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Translation", color = TextPrimary, style = MaterialTheme.typography.bodyMedium)
                    Text("Saheeh International, under the whole ayah", color = TextMuted, style = MaterialTheme.typography.bodySmall)
                }
                Switch(
                    checked = translation,
                    onCheckedChange = { PlayerSettings.showAyahTranslation = it },
                    modifier = Modifier.testTag(TAG_AYAH_TRANSLATION_TOGGLE),
                    colors = SwitchDefaults.colors(checkedThumbColor = Panel, checkedTrackColor = AccentGreen),
                )
            }
            Text(
                "Scenes of sky, land, water, light and pattern, and CC0 nature photos from Wikimedia Commons " +
                    "(downloaded when first used), never people or animals. A moving background uses a little more battery.",
                color = TextMuted,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun Choice(label: String, value: String, current: String, onPick: (String) -> Unit) {
    FilterChip(
        selected = current == value,
        onClick = { onPick(value) },
        label = { Text(label) },
        modifier = Modifier.testTag(backgroundChoiceTag(value)),
        colors = FilterChipDefaults.filterChipColors(
            containerColor = Border,
            labelColor = TextMuted,
            selectedContainerColor = AccentGreen,
            selectedLabelColor = Panel,
        ),
    )
}
