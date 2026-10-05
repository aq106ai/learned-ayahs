package com.quran.learnedplayer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.quran.learnedplayer.data.AyahMapping
import com.quran.learnedplayer.data.QuranConstants
import com.quran.learnedplayer.data.SurahNames
import com.quran.learnedplayer.player.PlaybackMode
import com.quran.learnedplayer.player.PlayerSettings
import com.quran.learnedplayer.player.PlayerUiState
import com.quran.learnedplayer.player.PlayerViewModel
import com.quran.learnedplayer.player.RepeatMode
import com.quran.learnedplayer.ui.common.SidePanel
import com.quran.learnedplayer.ui.common.SidePanelEdge
import com.quran.learnedplayer.ui.theme.AccentGreen
import com.quran.learnedplayer.ui.theme.Border
import com.quran.learnedplayer.ui.theme.Panel
import com.quran.learnedplayer.ui.theme.TextMuted
import com.quran.learnedplayer.ui.theme.TextPrimary

const val TAG_EMPTY_BROWSE = "empty_browse_surahs"
const val TAG_TOGGLE_WORD_BY_WORD = "toggle_word_by_word"
const val TAG_FULLSCREEN_NEXT = "fullscreen_next"
const val TAG_FULLSCREEN_PREVIOUS = "fullscreen_previous"
const val TAG_OPEN_SURAH_PANEL = "open_surah_panel"
const val TAG_OPEN_PLAYLIST_PANEL = "open_playlist_panel"
const val TAG_SURAH_PANEL_SEARCH = "surah_panel_search"
const val TAG_SURAH_PANEL_LIST = "surah_panel_list"
const val TAG_MANAGE_AYAHS = "manage_ayahs"
const val TAG_OPEN_SETTINGS = "open_settings_from_player"
const val TAG_EXPAND_PANEL = "expand_playback_panel"
const val TAG_OPEN_RECITATION = "open_recitation"
fun surahPanelRowTag(surah: Int) = "surah_panel_row_$surah"
fun revisionDelayTag(seconds: Int) = "revision_delay_$seconds"
fun playbackModeTag(mode: PlaybackMode) = "playback_mode_${mode.name}"
fun repeatModeTag(repeat: RepeatMode) = "repeat_mode_${repeat.name}"

/**
 * The whole player: an immersive ayah reader fills the screen, with surah navigation and the
 * playlist as button-triggered side panels and playback controls in an expandable bottom panel.
 * There is no separate "Now Playing" screen distinct from the fullscreen reader any more — this
 * *is* both.
 */
@Composable
fun PlayerScreen(
    viewModel: PlayerViewModel,
    onPlayRequested: (() -> Unit) -> Unit,
    onBrowseSurahs: () -> Unit,
    onOpenSettings: () -> Unit = {},
    onOpenRecitation: (Int) -> Unit = {},
) {
    val state by viewModel.uiState.collectAsState()
    val reciteBetaEnabled by PlayerSettings.reciteBetaEnabledFlow.collectAsState()
    val wordFileMode = state.mode == PlaybackMode.WORD_BY_WORD
    var wordByWord by rememberSaveable { mutableStateOf(false) }
    var panelExpanded by rememberSaveable { mutableStateOf(false) }
    var showSurahPanel by remember { mutableStateOf(false) }
    var showPlaylistPanel by remember { mutableStateOf(false) }

    ImmersiveSystemBars()
    // Keep the screen on for as long as there's something to read, not only while playing —
    // this screen is now always the reader, so a paused reading session should not sleep either.
    KeepScreenOn(enabled = state.isPlaying || state.tracks.isNotEmpty())

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Panel)
            // Keep controls clear of cutouts / gesture bars while immersive.
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            ReaderHeader(
                state = state,
                wordByWord = wordByWord || wordFileMode,
                showWordToggle = !wordFileMode,
                onToggleWordByWord = { wordByWord = !wordByWord },
                onOpenSurahPanel = { showSurahPanel = true },
                onOpenPlaylistPanel = { showPlaylistPanel = true },
                onOpenSettings = onOpenSettings,
            )

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when {
                    state.isLoading -> CenteredMessage { LoadingContent(state) }
                    state.error != null -> CenteredMessage { ErrorContent(state, onBrowseSurahs) }
                    state.tracks.isEmpty() -> CenteredMessage { EmptyContent(onBrowseSurahs) }
                    else -> AyahReaderContent(
                        state = state,
                        wordByWord = wordByWord || wordFileMode,
                        onPrevious = {
                            if (wordFileMode) viewModel.skipAyah(false) else viewModel.previous()
                        },
                        onNext = {
                            if (wordFileMode) viewModel.skipAyah(true) else viewModel.next()
                        },
                        onPlayWord = { viewModel.playWord(it) },
                        wordFileMode = wordFileMode,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            // Recite & review bar placed right above the bottom play panel. Hidden entirely until
            // the user opts in to the beta from Settings — an unfinished coach should not be the
            // most prominent button on the player for someone who never asked for it.
            if (reciteBetaEnabled) Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    onClick = {
                        viewModel.pause()
                        onOpenRecitation(state.currentTrack?.globalId ?: 1)
                    },
                    modifier = Modifier.testTag(TAG_OPEN_RECITATION),
                    shape = RoundedCornerShape(24.dp),
                    color = AccentGreen,
                    shadowElevation = 4.dp,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Mic,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Recite & review",
                            color = Color.White,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }

            BottomPlaybackPanel(
                state = state,
                viewModel = viewModel,
                onPlayRequested = onPlayRequested,
                expanded = panelExpanded,
                onToggleExpanded = { panelExpanded = !panelExpanded },
            )
        }

        SidePanel(
            visible = showSurahPanel,
            edge = SidePanelEdge.START,
            onDismiss = { showSurahPanel = false },
        ) {
            SurahNavPanel(
                viewModel = viewModel,
                currentSurah = state.currentTrack?.surah,
                onDismiss = { showSurahPanel = false },
                onBrowseSurahs = onBrowseSurahs,
            )
        }

        SidePanel(
            visible = showPlaylistPanel,
            edge = SidePanelEdge.END,
            onDismiss = { showPlaylistPanel = false },
        ) {
            if (state.tracks.isNotEmpty()) {
                PlaylistScreen(
                    state = state,
                    viewModel = viewModel,
                    onDismiss = { showPlaylistPanel = false },
                    onPlayRequested = onPlayRequested,
                )
            }
        }
    }
}

@Composable
private fun ReaderHeader(
    state: PlayerUiState,
    wordByWord: Boolean,
    showWordToggle: Boolean,
    onToggleWordByWord: () -> Unit,
    onOpenSurahPanel: () -> Unit,
    onOpenPlaylistPanel: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Border)
                .padding(start = 4.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onOpenSurahPanel, modifier = Modifier.testTag(TAG_OPEN_SURAH_PANEL)) {
                Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Browse surahs", tint = TextMuted)
            }
            Text(
                text = state.fullscreenMeta,
                color = TextMuted,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
            )
            IconButton(onClick = onOpenPlaylistPanel, modifier = Modifier.testTag(TAG_OPEN_PLAYLIST_PANEL)) {
                Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = "Playlist", tint = TextMuted)
            }
            if (showWordToggle) {
                OutlinedButton(
                    onClick = onToggleWordByWord,
                    modifier = Modifier.testTag(TAG_TOGGLE_WORD_BY_WORD),
                ) {
                    Text(if (wordByWord) "Whole ayah" else "Word by word")
                }
            }
            IconButton(onClick = onOpenSettings, modifier = Modifier.testTag(TAG_OPEN_SETTINGS)) {
                Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = TextMuted)
            }
        }
    }
}

@Composable
private fun CenteredMessage(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            content()
        }
    }
}

@Composable
private fun LoadingContent(state: PlayerUiState) {
    CircularProgressIndicator()
    Text(state.loadingMessage, color = TextMuted)
}

@Composable
private fun EmptyContent(onBrowseSurahs: () -> Unit) {
    Text(
        text = "No ayahs marked yet.",
        color = TextPrimary,
        style = MaterialTheme.typography.titleMedium,
        textAlign = TextAlign.Center,
    )
    Text(
        text = "Open a surah and tap the circle beside an ayah to add it to your revision. " +
            "You can also add several at once from Settings.",
        color = TextMuted,
        style = MaterialTheme.typography.bodySmall,
        textAlign = TextAlign.Center,
    )
    Button(
        onClick = onBrowseSurahs,
        modifier = Modifier.testTag(TAG_EMPTY_BROWSE),
    ) {
        Text("Browse surahs")
    }
}

@Composable
private fun ErrorContent(state: PlayerUiState, onBrowseSurahs: () -> Unit) {
    Text(
        state.error ?: "Something went wrong",
        color = MaterialTheme.colorScheme.error,
        textAlign = TextAlign.Center,
    )
    OutlinedButton(onClick = onBrowseSurahs) {
        Text("Browse surahs")
    }
}

@Composable
private fun KeepScreenOn(enabled: Boolean) {
    val view = LocalView.current
    DisposableEffect(enabled) {
        view.keepScreenOn = enabled
        onDispose { view.keepScreenOn = false }
    }
}

@Composable
private fun BottomPlaybackPanel(
    state: PlayerUiState,
    viewModel: PlayerViewModel,
    onPlayRequested: (() -> Unit) -> Unit,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().background(Border)) {
        // Peek row — always visible; tap anywhere on it to expand/collapse the controls below.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggleExpanded)
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .testTag(TAG_EXPAND_PANEL),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = state.introLabel ?: state.currentTrack?.let { "${it.index}. ${it.label}" }
                        ?: "Ready — press Play",
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
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
            Icon(
                imageVector = if (expanded) Icons.Filled.ExpandMore else Icons.Filled.ExpandLess,
                contentDescription = if (expanded) "Collapse controls" else "Expand controls",
                tint = TextMuted,
            )
        }

        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = { viewModel.previous() },
                    modifier = Modifier.weight(1f).testTag(TAG_FULLSCREEN_PREVIOUS),
                ) {
                    Text("Prev")
                }
                Button(
                    onClick = {
                        if (state.isPlaying) viewModel.pause() else viewModel.playOrResume(onPlayRequested)
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentGreen, contentColor = Panel),
                ) {
                    Text(if (state.isPlaying) "Pause" else "Play")
                }
                OutlinedButton(
                    onClick = { viewModel.next() },
                    modifier = Modifier.weight(1f).testTag(TAG_FULLSCREEN_NEXT),
                ) {
                    Text("Next")
                }
            }
        }

        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (state.durationMs > 0) {
                    SeekSlider(state, viewModel)
                }
                ModeSelector(state.mode) { viewModel.setMode(it) }
                RepeatSelector(state.mode, state.repeatMode) { viewModel.setRepeatMode(it) }
                if (state.repeatMode == RepeatMode.AYAH) {
                    val revisionDelay by PlayerSettings.revisionDelay.collectAsState()
                    RevisionDelaySelector(revisionDelay) { viewModel.setRevisionDelay(it) }
                }
                if (state.mode == PlaybackMode.WORD_BY_WORD) {
                    val useWordClips by viewModel.useWordClips.collectAsState()
                    WordClipsToggle(useWordClips) { viewModel.setUseWordClips(it) }
                }
            }
        }
    }
}

@Composable
private fun SeekSlider(state: PlayerUiState, viewModel: PlayerViewModel) {
    // Seek once on release — seeking on every drag pixel makes playback stutter.
    var dragPositionMs by remember { mutableStateOf<Float?>(null) }
    val shownPositionMs = dragPositionMs?.toLong() ?: state.positionMs.coerceIn(0L, state.durationMs)
    Column {
        Slider(
            value = shownPositionMs.toFloat(),
            onValueChange = { dragPositionMs = it },
            onValueChangeFinished = {
                dragPositionMs?.let { viewModel.seekTo(it.toLong()) }
                dragPositionMs = null
            },
            valueRange = 0f..state.durationMs.toFloat(),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(formatTime(shownPositionMs), color = TextMuted, style = MaterialTheme.typography.bodySmall)
            Text(formatTime(state.durationMs), color = TextMuted, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun formatTime(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

@Composable
private fun RevisionDelaySelector(activeSeconds: Int, onSelect: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = "Pause before repeat — time to revise or press Next",
            color = TextMuted,
            style = MaterialTheme.typography.bodySmall,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PlayerSettings.REVISION_DELAY_CHOICES.forEach { seconds ->
                ModeChip(
                    label = if (seconds == 0) "Off" else "${seconds}s",
                    selected = seconds == activeSeconds,
                    modifier = Modifier.weight(1f).testTag(revisionDelayTag(seconds)),
                    onClick = { onSelect(seconds) },
                )
            }
        }
    }
}

@Composable
private fun ModeSelector(active: PlaybackMode, onSelect: (PlaybackMode) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = "Play",
            color = TextMuted,
            style = MaterialTheme.typography.bodySmall,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PlaybackMode.entries.forEach { mode ->
                ModeChip(
                    label = mode.label,
                    selected = mode == active,
                    modifier = Modifier.weight(1f).testTag(playbackModeTag(mode)),
                    onClick = { onSelect(mode) },
                )
            }
        }
    }
}

@Composable
private fun RepeatSelector(
    playbackMode: PlaybackMode,
    active: RepeatMode,
    onSelect: (RepeatMode) -> Unit,
) {
    val options = if (playbackMode == PlaybackMode.WORD_BY_WORD) {
        listOf(RepeatMode.OFF, RepeatMode.AYAH)
    } else {
        RepeatMode.entries.toList()
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = "Repeat",
            color = TextMuted,
            style = MaterialTheme.typography.bodySmall,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.forEach { repeat ->
                ModeChip(
                    label = repeat.labelFor(playbackMode),
                    selected = repeat == active,
                    modifier = Modifier.weight(1f).testTag(repeatModeTag(repeat)),
                    onClick = { onSelect(repeat) },
                )
            }
        }
    }
}

@Composable
private fun WordClipsToggle(enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChange(!enabled) }
            .testTag(com.quran.learnedplayer.ui.settings.TAG_USE_WORD_CLIPS),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text("Word clips (Quran.com)", color = TextPrimary, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = if (enabled) {
                    "Isolated pronunciation clips. Recite & review always uses these."
                } else {
                    "Off: play each word in your ayah reciter by seeking the ayah audio."
                },
                color = TextMuted,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Switch(
            checked = enabled,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Panel,
                checkedTrackColor = AccentGreen,
            ),
        )
    }
}

@Composable
private fun ModeChip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    if (selected) {
        Button(
            onClick = onClick,
            modifier = modifier,
            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AccentGreen, contentColor = Panel),
        ) {
            Text(
                label,
                maxLines = 2,
                overflow = TextOverflow.Clip,
                textAlign = TextAlign.Center,
                fontSize = 12.sp,
                lineHeight = 14.sp,
            )
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            modifier = modifier,
            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp),
        ) {
            Text(
                label,
                maxLines = 2,
                overflow = TextOverflow.Clip,
                textAlign = TextAlign.Center,
                fontSize = 12.sp,
                lineHeight = 14.sp,
            )
        }
    }
}

/** All 114 surahs, searchable, opened from the header. Picking one jumps within the current
 *  queue if it has learned ayahs, otherwise switches to streaming it in full — the only way to
 *  actually hear a surah with nothing marked in it yet. */
@Composable
private fun SurahNavPanel(
    viewModel: PlayerViewModel,
    currentSurah: Int?,
    onDismiss: () -> Unit,
    onBrowseSurahs: () -> Unit,
) {
    val learnedIds by viewModel.learnedIds.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    val countsBySurah = remember(learnedIds) {
        learnedIds.groupingBy { AyahMapping.globalToSurahAyah(it).first }.eachCount()
    }
    val surahs = remember(query) { (1..114).filter { matchesSurahQuery(it, query) } }

    fun selectSurah(surah: Int) {
        if (!viewModel.jumpToSurah(surah)) {
            viewModel.playSurahFrom(surah, 1, persistMode = true, startPlayback = false)
        }
        onDismiss()
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Surahs",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onDismiss) {
                Icon(Icons.Filled.Close, contentDescription = "Close", tint = TextMuted)
            }
        }
        OutlinedButton(
            onClick = onBrowseSurahs,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp).testTag(TAG_MANAGE_AYAHS),
        ) {
            Text("Manage learned ayahs")
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
                .testTag(TAG_SURAH_PANEL_SEARCH),
            singleLine = true,
            placeholder = { Text("Search surah") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = TextMuted) },
            keyboardOptions = KeyboardOptions.Default,
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize().testTag(TAG_SURAH_PANEL_LIST),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(surahs, key = { it }) { surah ->
                val active = surah == currentSurah
                val learned = countsBySurah[surah] ?: 0
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { selectSurah(surah) }
                        .background(if (active) Border else Panel, RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 12.dp)
                        .testTag(surahPanelRowTag(surah)),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = SurahNames.label(surah),
                        color = if (active) AccentGreen else TextPrimary,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    )
                    Text(
                        text = if (learned > 0) "$learned learned" else "${QuranConstants.VERSE_COUNTS[surah - 1]} ayahs",
                        color = if (learned > 0) AccentGreen else TextMuted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

private fun matchesSurahQuery(surah: Int, query: String): Boolean {
    if (query.isBlank()) return true
    val q = query.trim()
    return surah.toString() == q || SurahNames.name(surah).contains(q, ignoreCase = true)
}
