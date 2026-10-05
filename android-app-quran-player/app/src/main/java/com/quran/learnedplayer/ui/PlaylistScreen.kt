package com.quran.learnedplayer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.quran.learnedplayer.data.AyahMapping
import com.quran.learnedplayer.data.PlaylistStore
import com.quran.learnedplayer.data.SurahNames
import com.quran.learnedplayer.player.PlayerUiState
import com.quran.learnedplayer.player.PlayerViewModel
import com.quran.learnedplayer.ui.theme.AccentGreen
import com.quran.learnedplayer.ui.theme.Panel
import com.quran.learnedplayer.ui.theme.TextMuted
import com.quran.learnedplayer.ui.theme.TextPrimary

/** Items in the LazyColumn before the playlist entries (the meta panel, plus the bookmarks
 *  row when there are any bookmarks). */
private const val PLAYLIST_LIST_TOP_ITEMS_BASE = 1
const val TAG_PLAYLIST_BOOKMARKS = "playlist_bookmarks"
fun bookmarkRowTag(globalId: Int) = "playlist_bookmark_$globalId"

@Composable
fun PlaylistScreen(
    state: PlayerUiState,
    viewModel: PlayerViewModel,
    onDismiss: () -> Unit,
    onPlayRequested: (() -> Unit) -> Unit,
) {
    BackHandler { onDismiss() }
    val masterTracks = remember(state.tracks) {
        PlaylistStore.latest?.tracks?.takeIf { it.isNotEmpty() } ?: state.tracks
    }
    val surahs = remember(masterTracks) { playlistSurahs(masterTracks) }
    val currentSurah = state.currentTrack?.surah
    val activeGlobalId = state.currentTrack?.globalId
    val fullSurahMode = state.isSingleSurahQueue
    val bookmarkedIds by viewModel.bookmarkedIds.collectAsState()
    val topItemCount = PLAYLIST_LIST_TOP_ITEMS_BASE + if (bookmarkedIds.isEmpty()) 0 else 1

    var expandedSurahs by remember {
        mutableStateOf(setOfNotNull(currentSurah ?: surahs.firstOrNull()))
    }
    var showSurahPicker by remember { mutableStateOf(false) }
    // Scroll target: set on open, when playback crosses into a new surah, and on picker jumps.
    var pendingScrollSurah by remember { mutableStateOf(currentSurah) }

    val entries = remember(masterTracks, expandedSurahs, state.tracks, currentSurah, fullSurahMode) {
        buildExpandablePlaylistEntries(
            master = masterTracks,
            expandedSurahs = expandedSurahs,
            queue = if (fullSurahMode) state.tracks else emptyList(),
            queueSurah = if (fullSurahMode) currentSurah else null,
        )
    }
    val listState = rememberLazyListState()

    // Follow playback into a new surah: expand it and scroll its header into view.
    // Deliberately not per-ayah so manual browsing isn't yanked around.
    LaunchedEffect(currentSurah, state.isPlaying) {
        if (state.isPlaying && currentSurah != null) {
            expandedSurahs = expandedSurahs + currentSurah
            pendingScrollSurah = currentSurah
        }
    }

    LaunchedEffect(entries, pendingScrollSurah) {
        val target = pendingScrollSurah ?: return@LaunchedEffect
        val index = firstEntryIndexForSurah(entries, target)
        if (index >= 0) {
            listState.scrollToItem(topItemCount + index)
            pendingScrollSurah = null
        }
    }

    if (showSurahPicker) {
        SurahJumpSheet(
            availableSurahs = surahs,
            tracks = masterTracks,
            currentSurah = currentSurah,
            onDismiss = { showSurahPicker = false },
            onSurahSelected = { surah ->
                expandedSurahs = expandedSurahs + surah
                pendingScrollSurah = surah
                viewModel.jumpToSurah(surah)
            },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Playlist",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = onDismiss) {
                Text("Back")
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        CompactPlayerBar(state, viewModel, onPlayRequested)
        Spacer(modifier = Modifier.height(12.dp))
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
        ) {
            if (bookmarkedIds.isNotEmpty()) {
                item(key = "playlist-bookmarks") {
                    BookmarksSection(
                        bookmarkedIds = bookmarkedIds,
                        activeGlobalId = activeGlobalId,
                        onSelect = { viewModel.playBookmark(it) },
                        onRemove = { viewModel.toggleBookmark(it) },
                    )
                }
            }
            item(key = "playlist-meta") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp))
                        .background(Panel)
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "${surahs.size} surahs · ${masterTracks.size} ayahs · ${state.modeLabel}",
                        color = TextMuted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    SurahJumpButton(
                        currentSurah = currentSurah,
                        onClick = { showSurahPicker = true },
                    )
                    Text(
                        text = "Tap a surah to expand · tap an ayah to go there",
                        color = TextMuted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            playlistTrackItems(
                entries = entries,
                activeGlobalId = activeGlobalId,
                activeSurah = currentSurah,
                expandedSurahs = expandedSurahs,
                onTrackClick = viewModel::selectTrack,
                onHeaderClick = { surah ->
                    expandedSurahs = if (surah in expandedSurahs) {
                        expandedSurahs - surah
                    } else {
                        expandedSurahs + surah
                    }
                },
                bookmarkedIds = bookmarkedIds,
                onToggleBookmark = { viewModel.toggleBookmark(it.globalId) },
            )
        }
    }
}

/** Quick access to pinned ayahs, spanning any surah — separate from the surah-grouped list
 *  below, which only shows ayahs from the current queue. */
@Composable
private fun BookmarksSection(
    bookmarkedIds: Set<Int>,
    activeGlobalId: Int?,
    onSelect: (Int) -> Unit,
    onRemove: (Int) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp))
            .background(Panel)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag(TAG_PLAYLIST_BOOKMARKS),
    ) {
        Text(
            text = "Bookmarks",
            color = TextMuted,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(vertical = 6.dp),
        )
        bookmarkedIds.sorted().forEach { globalId ->
            val active = globalId == activeGlobalId
            val (surah, ayah) = AyahMapping.globalToSurahAyah(globalId)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(globalId) }
                    .padding(vertical = 6.dp)
                    .testTag(bookmarkRowTag(globalId)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = SurahNames.trackTitle(surah, ayah),
                    color = if (active) AccentGreen else TextPrimary,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { onRemove(globalId) }) {
                    Icon(Icons.Filled.Star, contentDescription = "Remove bookmark", tint = AccentGreen)
                }
            }
        }
    }
}
