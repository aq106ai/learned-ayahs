package com.quran.learnedplayer.ui.surah

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.quran.learnedplayer.data.QuranConstants
import com.quran.learnedplayer.data.SurahNames
import com.quran.learnedplayer.player.PlayerViewModel
import com.quran.learnedplayer.player.SurahAyah
import com.quran.learnedplayer.ui.common.AyahArabicText
import com.quran.learnedplayer.ui.common.WordByWordFlow
import com.quran.learnedplayer.ui.theme.AccentGreen
import com.quran.learnedplayer.ui.theme.Bg
import com.quran.learnedplayer.ui.theme.Border
import com.quran.learnedplayer.ui.theme.Panel
import com.quran.learnedplayer.ui.theme.TextMuted

const val TAG_SURAH_DETAIL = "surah_detail"
const val TAG_SURAH_DETAIL_LOADING = "surah_detail_loading"
const val TAG_SELECT_ALL_AYAHS = "surah_detail_select_all"
const val TAG_BACK_TO_MAIN = "surah_detail_back_to_main"
fun ayahRowTag(ayah: Int) = "ayah_row_$ayah"
fun markLearnedTag(ayah: Int) = "mark_learned_$ayah"

/**
 * Every ayah of one surah, for marking learned — a pure selection screen; playback lives in the
 * player. The header's single leading control does double duty rather than adding a second
 * button next to it: normally it's the plain "back to the surah list" arrow, but while
 * [isOnboarding] (the player hasn't been reached yet this session, so there's nothing to step
 * back *to* besides more onboarding) it instead reads "Proceed" and jumps straight to the player
 * — mirroring [com.quran.learnedplayer.ui.home.SurahListScreen]'s header button. The hardware/
 * gesture back action always steps back to the surah list regardless, via [onBack].
 */
@Composable
fun SurahDetailScreen(
    surah: Int,
    viewModel: PlayerViewModel,
    onBack: () -> Unit,
    isOnboarding: Boolean,
    onBackToMain: () -> Unit,
    onPlayRequested: ((() -> Unit) -> Unit),
) {
    BackHandler(onBack = onBack)
    val state by viewModel.uiState.collectAsState()
    val learnedIds by viewModel.learnedIds.collectAsState()
    val showTranslations by viewModel.showWordTranslations.collectAsState()

    var ayahs by remember(surah) { mutableStateOf(emptyList<SurahAyah>()) }
    LaunchedEffect(surah) { ayahs = viewModel.loadSurahWords(surah) }
    val learnedHere = ayahs.count { it.globalId in learnedIds }

    Column(modifier = Modifier.fillMaxSize().background(Bg)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!isOnboarding) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.testTag(TAG_BACK_TO_MAIN),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = TextMuted,
                    )
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "$surah · ${SurahNames.name(surah)}",
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    text = "${QuranConstants.VERSE_COUNTS[surah - 1]} ayahs · $learnedHere learned",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted,
                )
            }

            if (isOnboarding) {
                TextButton(
                    onClick = onBackToMain,
                    modifier = Modifier.testTag(TAG_BACK_TO_MAIN),
                ) {
                    Text(
                        text = "Proceed",
                        color = AccentGreen,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(end = 4.dp),
                    )
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = null,
                        tint = AccentGreen,
                    )
                }
            }
        }

        // Marking a whole surah from inside it, next to the count it changes. The same action is
        // on the surah list; someone who has opened the surah to check what is in it should not
        // have to go back out to mark all of it.
        if (ayahs.isNotEmpty()) {
            val allLearned = learnedHere == ayahs.size
            TextButton(
                onClick = { viewModel.setSurahLearned(surah, !allLearned) },
                modifier = Modifier
                    .padding(start = 12.dp)
                    .testTag(TAG_SELECT_ALL_AYAHS),
            ) {
                Icon(
                    imageVector = if (allLearned) Icons.Filled.Clear else Icons.Filled.DoneAll,
                    contentDescription = null,
                    tint = AccentGreen,
                    modifier = Modifier.padding(end = 6.dp),
                )
                Text(
                    text = if (allLearned) "Unmark all ayahs" else "Mark all ayahs learned",
                    color = AccentGreen,
                )
            }
        }

        Box(modifier = Modifier.weight(1f)) {
            if (ayahs.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize().testTag(TAG_SURAH_DETAIL_LOADING),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(color = AccentGreen)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().testTag(TAG_SURAH_DETAIL),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(ayahs, key = { it.ayah }) { item ->
                        AyahRow(
                            item = item,
                            isLearned = item.globalId in learnedIds,
                            isPlaying = state.currentTrack?.globalId == item.globalId,
                            showTranslations = showTranslations,
                            onToggleLearned = { viewModel.toggleLearned(item.globalId) },
                            onPlay = { onPlayRequested { viewModel.playSurahFrom(surah, item.ayah) } },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AyahRow(
    item: SurahAyah,
    isLearned: Boolean,
    isPlaying: Boolean,
    showTranslations: Boolean,
    onToggleLearned: () -> Unit,
    onPlay: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (isPlaying) Border else Panel)
            .clickable(onClick = onPlay)
            .padding(12.dp)
            .testTag(ayahRowTag(item.ayah)),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier.size(28.dp).clip(CircleShape).background(Border),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = item.ayah.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isPlaying) AccentGreen else TextMuted,
                )
            }
            Box(modifier = Modifier.weight(1f))
            IconButton(
                onClick = onToggleLearned,
                modifier = Modifier.testTag(markLearnedTag(item.ayah)),
            ) {
                Icon(
                    imageVector = if (isLearned) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
                    contentDescription = if (isLearned) "Marked learned" else "Mark learned",
                    tint = if (isLearned) AccentGreen else TextMuted,
                )
            }
        }
        if (showTranslations) {
            WordByWordFlow(
                words = item.words,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                fontSize = 24f,
            )
        } else {
            AyahArabicText(
                words = item.words,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                fontSize = 24f,
                textAlign = TextAlign.End,
            )
        }
    }
}
