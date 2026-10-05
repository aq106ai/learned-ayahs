package com.quran.learnedplayer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.quran.learnedplayer.data.AyahTrack
import com.quran.learnedplayer.data.SurahNames
import com.quran.learnedplayer.player.PlayerUiState
import com.quran.learnedplayer.player.PlayerViewModel
import com.quran.learnedplayer.ui.theme.AccentGreen
import com.quran.learnedplayer.ui.theme.Border
import com.quran.learnedplayer.ui.theme.Panel
import com.quran.learnedplayer.ui.theme.TextMuted
import com.quran.learnedplayer.ui.theme.TextPrimary

@Composable
fun CompactPlayerBar(
    state: PlayerUiState,
    viewModel: PlayerViewModel,
    onPlayRequested: (() -> Unit) -> Unit,
) {
    val track = state.currentTrack
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Panel)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = state.introLabel ?: when {
                            track != null -> SurahNames.trackTitle(track.surah, track.ayah)
                            else -> "Ready — press Play"
                        },
                        color = TextPrimary,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                    )
                    Text(
                        text = buildString {
                            if (state.tracks.isNotEmpty()) {
                                append("${state.currentIndex + 1} of ${state.tracks.size}")
                            }
                            append(" · ${state.modeLabel}")
                        },
                        color = TextMuted,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                    )
                }
                TextButton(onClick = { viewModel.previous() }) { Text("Prev") }
                TextButton(onClick = {
                    if (state.isPlaying) viewModel.pause() else viewModel.playOrResume(onPlayRequested)
                }) {
                    Text(if (state.isPlaying) "Pause" else "Play")
                }
                TextButton(onClick = { viewModel.next() }) { Text("Next") }
            }
        }
    }
}

/**
 * Renders playlist entries. Surah headers call [onHeaderClick]; ayah rows call
 * [onTrackClick]. When [expandedSurahs] is non-null, headers show an expand indicator.
 */
fun bookmarkToggleTag(globalId: Int) = "playlist_bookmark_toggle_$globalId"

fun LazyListScope.playlistTrackItems(
    entries: List<PlaylistEntry>,
    activeGlobalId: Int?,
    activeSurah: Int?,
    onTrackClick: (AyahTrack) -> Unit,
    onHeaderClick: (Int) -> Unit,
    expandedSurahs: Set<Int>? = null,
    bookmarkedIds: Set<Int> = emptySet(),
    onToggleBookmark: (AyahTrack) -> Unit = {},
) {
    items(
        count = entries.size,
        key = { index ->
            val entry = entries[index]
            when (entry.kind) {
                PlaylistEntryKind.SURAH_HEADER -> "h-${entry.surah}"
                PlaylistEntryKind.AYAH_ROW -> "a-${entry.track?.globalId}"
            }
        },
    ) { index ->
        val entry = entries[index]
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Panel)
                .padding(horizontal = 12.dp),
        ) {
            when (entry.kind) {
                PlaylistEntryKind.SURAH_HEADER -> SurahHeaderRow(
                    surah = entry.surah ?: 0,
                    ayahCount = entry.ayahCount,
                    active = entry.surah == activeSurah,
                    expanded = expandedSurahs?.contains(entry.surah),
                    onClick = { entry.surah?.let(onHeaderClick) },
                )
                PlaylistEntryKind.AYAH_ROW -> TrackRow(
                    label = "Ayah ${entry.track?.ayah ?: 0}",
                    active = entry.track?.globalId == activeGlobalId,
                    bookmarked = entry.track?.globalId?.let(bookmarkedIds::contains) ?: false,
                    bookmarkTag = entry.track?.globalId?.let(::bookmarkToggleTag),
                    onClick = { entry.track?.let(onTrackClick) },
                    onToggleBookmark = { entry.track?.let(onToggleBookmark) },
                )
            }
        }
    }
    item(key = "playlist-footer") {
        Spacer(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(bottomStart = 14.dp, bottomEnd = 14.dp))
                .background(Panel)
                .height(12.dp),
        )
    }
}

@Composable
private fun SurahHeaderRow(
    surah: Int,
    ayahCount: Int,
    active: Boolean,
    expanded: Boolean?,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(if (active) Border else Panel)
            .padding(horizontal = 10.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (expanded != null) {
                Text(
                    text = if (expanded) "▾" else "▸",
                    color = if (active) AccentGreen else TextMuted,
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
            Text(
                text = SurahNames.title(surah),
                color = if (active) AccentGreen else TextPrimary,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Text("$ayahCount ayahs", color = TextMuted, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun TrackRow(
    label: String,
    active: Boolean,
    bookmarked: Boolean,
    bookmarkTag: String?,
    onClick: () -> Unit,
    onToggleBookmark: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(if (active) Border else Panel)
            .padding(start = 24.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = if (active) AccentGreen else MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        IconButton(
            onClick = onToggleBookmark,
            modifier = Modifier.size(32.dp).let { if (bookmarkTag != null) it.testTag(bookmarkTag) else it },
        ) {
            Icon(
                imageVector = if (bookmarked) Icons.Filled.Star else Icons.Filled.StarBorder,
                contentDescription = if (bookmarked) "Remove bookmark" else "Bookmark this ayah",
                tint = if (bookmarked) AccentGreen else TextMuted,
            )
        }
    }
}
