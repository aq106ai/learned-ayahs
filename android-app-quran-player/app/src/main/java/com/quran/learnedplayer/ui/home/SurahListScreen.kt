package com.quran.learnedplayer.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.quran.learnedplayer.data.AyahMapping
import com.quran.learnedplayer.data.QuranConstants
import com.quran.learnedplayer.data.SurahNames
import com.quran.learnedplayer.player.PlayerViewModel
import com.quran.learnedplayer.ui.theme.AccentGreen
import com.quran.learnedplayer.ui.theme.Bg
import com.quran.learnedplayer.ui.theme.Border
import com.quran.learnedplayer.ui.theme.Panel
import com.quran.learnedplayer.ui.theme.TextMuted

const val TAG_SURAH_LIST = "surah_list"
const val TAG_SEARCH_FIELD = "surah_search"
const val TAG_ADD_BY_DESCRIPTION = "add_by_description"
const val TAG_EXPORT = "export_learned"
const val TAG_IMPORT = "import_learned"
const val TAG_BACK_TO_MAIN = "home_back_to_main"
fun surahSelectAllTag(surah: Int) = "surah_select_all_$surah"
fun surahRowTag(surah: Int) = "surah_row_$surah"

/** Home screen: every surah, with how many ayahs are marked learned in each, plus a persistent
 *  bottom banner for managing the learned selection itself (add/export/import) — this is the
 *  one screen that's about the learned list, so those controls live here now, not in Settings.
 *  [isOnboarding] is true while the player hasn't been reached yet this session (a fresh
 *  install starts here with nothing to go "back" to): the header button reads "Proceed" and
 *  moves forward into the player, rather than "Back" popping to it, and export is hidden since
 *  there is nothing to export until something has been marked. */
@Composable
fun SurahListScreen(
    viewModel: PlayerViewModel,
    onOpenSurah: (Int) -> Unit,
    onAddByDescription: () -> Unit,
    onExportLearnedAyahs: () -> Unit,
    onImportLearnedAyahs: () -> Unit,
    isOnboarding: Boolean,
    onBackToMain: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    val learnedIds by viewModel.learnedIds.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }

    // The first-run IntroDialog is hoisted to AppNav — it has to be able to show over the
    // player too, not just here.

    // Learned counts per surah, recomputed only when the selection changes.
    val countsBySurah = remember(learnedIds) {
        learnedIds.groupingBy { AyahMapping.globalToSurahAyah(it).first }.eachCount()
    }

    Column(modifier = Modifier.fillMaxSize().background(Bg)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 8.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (isOnboarding) Arrangement.End else Arrangement.Start,
        ) {
            TextButton(onClick = onBackToMain, modifier = Modifier.testTag(TAG_BACK_TO_MAIN)) {
                if (isOnboarding) {
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
                } else {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = null,
                        tint = TextMuted,
                    )
                    Text(
                        text = "Back",
                        color = TextMuted,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
            }
        }
        Column(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp)) {
            Text(
                text = "Learned Ayahs",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                // The reciter belongs to playback, not browsing — it stays on the player
                // screen header and in Settings, where the choice is actually made.
                text = "${learnedIds.size} ayahs marked",
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
            )
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .testTag(TAG_SEARCH_FIELD),
            singleLine = true,
            placeholder = { Text("Search surah") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = TextMuted) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        )

        Box(modifier = Modifier.weight(1f)) {
            SurahList(query, countsBySurah, onOpenSurah, viewModel::setSurahLearned)
        }

        LearnedAyahsBanner(
            error = state.error,
            showExport = !isOnboarding,
            onAddByDescription = onAddByDescription,
            onExportLearnedAyahs = onExportLearnedAyahs,
            onImportLearnedAyahs = onImportLearnedAyahs,
        )
    }
}

/** Always-on bottom banner for managing the learned selection: add by description, export,
 *  import. Lives here rather than Settings since this screen is specifically for browsing and
 *  marking — the natural place to reach for these while you're already doing that. Export is
 *  hidden while [showExport] is false (onboarding, before anything is marked) — there's nothing
 *  to export yet. */
@Composable
private fun LearnedAyahsBanner(
    error: String?,
    showExport: Boolean,
    onAddByDescription: () -> Unit,
    onExportLearnedAyahs: () -> Unit,
    onImportLearnedAyahs: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Panel)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = onAddByDescription,
                modifier = Modifier.weight(1f).testTag(TAG_ADD_BY_DESCRIPTION),
            ) {
                Text("Description", maxLines = 1)
            }
            if (showExport) {
                OutlinedButton(
                    onClick = onExportLearnedAyahs,
                    modifier = Modifier.testTag(TAG_EXPORT),
                ) {
                    Text("Export", maxLines = 1)
                }
            }
            OutlinedButton(
                onClick = onImportLearnedAyahs,
                modifier = Modifier.testTag(TAG_IMPORT),
            ) {
                Text("Import", maxLines = 1)
            }
        }
        error?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun SurahList(
    query: String,
    countsBySurah: Map<Int, Int>,
    onOpenSurah: (Int) -> Unit,
    onSetSurahLearned: (Int, Boolean) -> Unit,
) {
    val surahs = remember(query) {
        (1..114).filter { matchesSurah(it, query) }
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag(TAG_SURAH_LIST),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 16.dp, end = 16.dp, bottom = 16.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(surahs, key = { it }) { surah ->
            SurahRow(
                surah = surah,
                learnedCount = countsBySurah[surah] ?: 0,
                onClick = { onOpenSurah(surah) },
                onSetLearned = { learned -> onSetSurahLearned(surah, learned) },
            )
        }
    }
}

@Composable
private fun SurahRow(
    surah: Int,
    learnedCount: Int,
    onClick: () -> Unit,
    onSetLearned: (Boolean) -> Unit,
) {
    val total = QuranConstants.VERSE_COUNTS[surah - 1]
    val allLearned = learnedCount >= total
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Panel)
            .clickable(onClick = onClick)
            .padding(12.dp)
            .testTag(surahRowTag(surah)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier.size(36.dp).clip(CircleShape).background(Border),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = surah.toString(),
                style = MaterialTheme.typography.labelLarge,
                color = AccentGreen,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(SurahNames.name(surah), style = MaterialTheme.typography.titleMedium)
            Text(
                text = "$total ayahs",
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
            )
        }
        if (learnedCount > 0) {
            LearnedBadge(learnedCount)
        }
        // Marks the whole surah without opening it. A partly-marked surah completes on the first
        // tap rather than clearing, so this can never silently discard ayahs already marked —
        // clearing takes a second, deliberate tap on a fully marked surah.
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(if (allLearned) AccentGreen else Border)
                .clickable { onSetLearned(!allLearned) }
                .semantics {
                    contentDescription = if (allLearned) {
                        "Unmark all of ${SurahNames.name(surah)}"
                    } else {
                        "Mark all of ${SurahNames.name(surah)} learned"
                    }
                }
                .testTag(surahSelectAllTag(surah)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = if (allLearned) Panel else TextMuted,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun LearnedBadge(count: Int) {
    Text(
        text = "$count learned",
        style = MaterialTheme.typography.labelSmall,
        color = AccentGreen,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Border)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

private fun matchesSurah(surah: Int, query: String): Boolean {
    if (query.isBlank()) return true
    val q = query.trim()
    return surah.toString() == q ||
        SurahNames.name(surah).contains(q, ignoreCase = true)
}
