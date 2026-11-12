package com.quran.learnedplayer.ui.player

import com.quran.learnedplayer.ui.i18n.tx
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
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.quran.learnedplayer.data.AppTheme
import com.quran.learnedplayer.player.PlayerSettings
import com.quran.learnedplayer.ui.settings.appThemeTag
import com.quran.learnedplayer.ui.theme.AccentGreen
import com.quran.learnedplayer.ui.theme.Border
import com.quran.learnedplayer.ui.theme.Panel
import com.quran.learnedplayer.ui.theme.TextMuted
import com.quran.learnedplayer.ui.theme.TextPrimary
import com.quran.learnedplayer.ui.video.ThemeThumbnails
import com.quran.learnedplayer.ui.video.ThemeTile
import com.quran.learnedplayer.ui.video.photoTileColor
import com.quran.learnedplayer.ui.video.tileColor
import com.quran.learnedplayer.video.NaturePhotos
import com.quran.learnedplayer.video.PlayerBackgrounds
import com.quran.learnedplayer.video.VideoThemes

const val TAG_BACKGROUND_SHEET = "background_sheet"
const val TAG_AYAH_TRANSLATION_TOGGLE = "ayah_translation_toggle"
fun backgroundChoiceTag(choice: String) = "background_choice_$choice"

/**
 * Picks how the app looks: Dark or Light, and what the player shows behind the ayah: the plain
 * theme, a background matched to each ayah (drawn or photographed), or one theme throughout.
 * Dark or Light colours a scene too. The translation switch is in the player's three-dot menu.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackgroundSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val choice by PlayerSettings.playerBackgroundFlow.collectAsState()
    val theme by PlayerSettings.appThemeFlow.collectAsState()
    val drawnThumbs by ThemeThumbnails.drawn.collectAsState()
    val photoThumbs by ThemeThumbnails.photos.collectAsState()
    val subjects = remember { NaturePhotos(context).subjects }
    LaunchedEffect(Unit) {
        ThemeThumbnails.loadDrawn()
        if (subjects.isNotEmpty()) ThemeThumbnails.loadPhotos(context)
    }

    fun pick(next: String) {
        // A background is the look of a shared video, translation included; turning one on
        // brings the translation with it. It can be switched off again from the player's menu.
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
            Text(tx("Theme"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = TextPrimary)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppTheme.entries.forEach { option ->
                    Choice(option.label, theme == option, appThemeTag(option)) { PlayerSettings.appTheme = option }
                }
            }
            Text(tx("Background"), style = MaterialTheme.typography.labelMedium, color = TextMuted)
            Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BackgroundChoice(tx("Plain"), PlayerBackgrounds.PLAIN, choice, ::pick)
                BackgroundChoice(tx("Matched scenes"), PlayerBackgrounds.MATCHED, choice, ::pick)
                if (subjects.isNotEmpty()) BackgroundChoice(tx("Matched photos"), PlayerBackgrounds.PHOTOS, choice, ::pick)
            }
            Text(
                tx("Matched follows what each ayah is about, as in a shared video. Or choose one for every ayah:"),
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
                        placeholder = tileColor(theme),
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
                            placeholder = photoTileColor,
                        ) { pick(subject.themeKey) }
                    }
                }
            }
            Text(
                tx("Dark or Light also colours a scene or photo."),
                color = TextMuted,
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                tx("Scenes of sky, land, water, light and pattern, and CC0 nature photos from Wikimedia Commons (downloaded when first used), never people or animals. A moving background uses a little more battery."),
                color = TextMuted,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun BackgroundChoice(label: String, value: String, current: String, onPick: (String) -> Unit) =
    Choice(label, current == value, backgroundChoiceTag(value)) { onPick(value) }

@Composable
private fun Choice(label: String, selected: Boolean, tag: String, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        modifier = Modifier.testTag(tag),
        colors = FilterChipDefaults.filterChipColors(
            containerColor = Border,
            labelColor = TextMuted,
            selectedContainerColor = AccentGreen,
            selectedLabelColor = Panel,
        ),
    )
}
