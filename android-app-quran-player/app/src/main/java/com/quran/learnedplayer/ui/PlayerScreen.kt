@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.quran.learnedplayer.ui

import com.quran.learnedplayer.ui.i18n.tx
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.MenuBook
import com.quran.learnedplayer.ui.player.TafsirSheet
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.foundation.shape.CircleShape
import com.quran.learnedplayer.data.Reciter
import com.quran.learnedplayer.ui.settings.reciterTag
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Wallpaper
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
import androidx.compose.ui.graphics.vector.ImageVector
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
import androidx.compose.ui.draw.clipToBounds
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
import com.quran.learnedplayer.ui.player.BackgroundSheet
import com.quran.learnedplayer.ui.player.ShareDialog
import com.quran.learnedplayer.ui.player.TAG_AYAH_TRANSLATION_TOGGLE
import com.quran.learnedplayer.ui.theme.AccentGreen
import com.quran.learnedplayer.video.PlayerBackgrounds
import com.quran.learnedplayer.ui.theme.Bg
import com.quran.learnedplayer.ui.theme.Border
import com.quran.learnedplayer.ui.theme.OnAccentGreen
import com.quran.learnedplayer.ui.theme.Panel
import com.quran.learnedplayer.ui.theme.TextMuted
import com.quran.learnedplayer.ui.theme.TextPrimary
import com.quran.learnedplayer.ui.theme.currentPalette

const val TAG_EMPTY_BROWSE = "empty_browse_surahs"
const val TAG_TOGGLE_WORD_BY_WORD = "toggle_word_by_word"
const val TAG_VIEW_FULL_AYAH = "view_ayah"
const val TAG_OPEN_ACTIONS_MENU = "open_actions_menu"
const val TAG_PLAYBACK_ERROR = "playback_error"
const val TAG_PLAY_PAUSE = "play_pause"
const val TAG_PLAYER_RECITER = "player_reciter"
const val TAG_FULLSCREEN_NEXT = "fullscreen_next"
const val TAG_FULLSCREEN_PREVIOUS = "fullscreen_previous"
const val TAG_PREVIOUS_AYAH = "previous_ayah"
const val TAG_NEXT_AYAH = "next_ayah"
const val TAG_OPEN_SURAH_PANEL = "open_surah_panel"
const val TAG_OPEN_PLAYLIST_PANEL = "open_playlist_panel"
const val TAG_SURAH_PANEL_SEARCH = "surah_panel_search"
const val TAG_SURAH_PANEL_LIST = "surah_panel_list"
const val TAG_MANAGE_AYAHS = "manage_ayahs"
const val TAG_OPEN_SETTINGS = "open_settings_from_player"
const val TAG_SHARE = "share_from_player"
const val TAG_OPEN_TAFSIR = "open_tafsir"
const val TAG_OPEN_BACKGROUND = "open_background_sheet"
const val TAG_WORD_MEANINGS_MENU = "menu_word_meanings"
const val TAG_REPEAT_WORD = "repeat_word_toggle"
const val TAG_READER_CARD = "reader_card"
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
    onMakeVideo: (surah: Int, ayah: Int) -> Unit = { _, _ -> },
) {
    val state by viewModel.uiState.collectAsState()
    val reciteBetaEnabled by PlayerSettings.reciteBetaEnabledFlow.collectAsState()
    val wordFileMode = state.wordPlayback
    val wordByWord by PlayerSettings.wordViewFlow.collectAsState()
    var panelExpanded by rememberSaveable { mutableStateOf(false) }
    var showSurahPanel by remember { mutableStateOf(false) }
    var showPlaylistPanel by remember { mutableStateOf(false) }
    var showBackgroundSheet by remember { mutableStateOf(false) }
    var showShare by remember { mutableStateOf(false) }
    var showTafsir by remember { mutableStateOf(false) }
    val background by PlayerSettings.playerBackgroundFlow.collectAsState()
    val withBackground = background != PlayerBackgrounds.PLAIN
    val showTranslation by PlayerSettings.showAyahTranslationFlow.collectAsState()
    val showWordMeanings by PlayerSettings.showWordTranslationsFlow.collectAsState()
    // How much of the surah on screen is learned: all of it makes "Revise Full Surah" the same
    // list as the learned one, so the menu leaves it out.
    val learnedIds by viewModel.learnedIds.collectAsState()
    val shownSurah = state.currentTrack?.surah
    val learnedInSurah = remember(learnedIds, shownSurah) {
        if (shownSurah == null) 0 else learnedIds.count { AyahMapping.globalToSurahAyah(it).first == shownSurah }
    }
    val surahAllLearned = shownSurah != null && learnedInSurah >= QuranConstants.VERSE_COUNTS[shownSurah - 1]

    // The scene palette is already the app's colours. The bars let the picture show through them;
    // a light palette lets less through, so dark text never lands on a dark part of the picture.
    val lightScene = withBackground && currentPalette.isLight
    val chrome = if (withBackground) Panel.copy(alpha = if (lightScene) 0.82f else 0.75f) else Border

    ImmersiveSystemBars()
    // Keep the screen on for as long as there's something to read, not only while playing —
    // this screen is now always the reader, so a paused reading session should not sleep either.
    KeepScreenOn(enabled = state.isPlaying || state.tracks.isNotEmpty())

    Box(modifier = Modifier.fillMaxSize().background(if (withBackground) Bg else Panel)) {
        if (withBackground) {
            PlayerBackground(
                choice = background,
                surah = state.currentTrack?.surah,
                ayah = state.currentTrack?.ayah,
                animating = state.isPlaying,
                modifier = Modifier.fillMaxSize(),
            )
        }
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val controlsMaxHeight = maxHeight * 0.5f
            val headerMaxHeight = maxHeight * 0.4f
            Column(modifier = Modifier.fillMaxSize()) {
                Column(Modifier.heightIn(max = headerMaxHeight).verticalScroll(rememberScrollState())) {
                    ReaderHeader(
                        state = state,
                        wordView = wordByWord,
                        showTranslation = showTranslation,
                        showWordMeanings = showWordMeanings,
                        surahAllLearned = surahAllLearned,
                        onToggleWordMeanings = { viewModel.setShowWordTranslations(!showWordMeanings) },
                        onModeChange = { viewModel.setMode(it) },
                        onWordViewChange = { viewModel.setWordView(it) },
                        onToggleTranslation = { PlayerSettings.showAyahTranslation = !showTranslation },
                        onOpenSurahPanel = { showSurahPanel = true },
                        onOpenPlaylistPanel = { showPlaylistPanel = true },
                        onOpenSettings = onOpenSettings,
                        onOpenBackground = { showBackgroundSheet = true },
                        onShare = if (state.currentTrack != null) ({ showShare = true }) else null,
                        onTafsir = if (state.currentTrack != null) ({ showTafsir = true }) else null,
                        chrome = chrome,
                    )
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clipToBounds()
                        .then(
                            // The translucent card a shared video puts behind its text.
                            if (withBackground) {
                                Modifier
                                    .padding(12.dp)
                                    .background(Panel.copy(alpha = if (lightScene) 0.5f else 0.45f), RoundedCornerShape(24.dp))
                                    .testTag(TAG_READER_CARD)
                            } else {
                                Modifier
                            },
                        ),
                ) {
                    when {
                        state.isLoading -> CenteredMessage { LoadingContent(state) }
                        // Only when there is nothing to read. An audio error keeps the ayah on
                        // screen and shows above the controls instead.
                        state.error != null && state.tracks.isEmpty() -> CenteredMessage { ErrorContent(state, onBrowseSurahs) }
                        state.tracks.isEmpty() -> CenteredMessage { EmptyContent(onBrowseSurahs) }
                        else -> AyahReaderContent(
                            state = state,
                            wordByWord = wordByWord,
                            // A swipe or tap on the whole ayah is Next/Previous: a word while the
                            // words play one at a time. The word pager's edge pages move by ayah.
                            onPrevious = {
                                if (wordFileMode && wordByWord) viewModel.skipAyah(false) else viewModel.previous()
                            },
                            onNext = {
                                if (wordFileMode && wordByWord) viewModel.skipAyah(true) else viewModel.next()
                            },
                            onPlayWord = { viewModel.playWord(it) },
                            wordFileMode = wordFileMode,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }

                Column(Modifier.heightIn(max = controlsMaxHeight).verticalScroll(rememberScrollState())) {
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
                                    tint = OnAccentGreen,
                                    modifier = Modifier.size(20.dp),
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = tx("Recite & review"),
                                    color = OnAccentGreen,
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    }

                    val playbackError = state.error?.takeIf { state.tracks.isNotEmpty() }
                    if (playbackError != null) {
                        Text(
                            text = playbackError,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(chrome)
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                                .testTag(TAG_PLAYBACK_ERROR),
                        )
                    }

                    BottomPlaybackPanel(
                        state = state,
                        viewModel = viewModel,
                        onPlayRequested = onPlayRequested,
                        expanded = panelExpanded,
                        onToggleExpanded = { panelExpanded = !panelExpanded },
                        chrome = chrome,
                    )
                }
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

    if (showBackgroundSheet) BackgroundSheet(onDismiss = { showBackgroundSheet = false })
    val track = state.currentTrack
    if (showTafsir && track != null) TafsirSheet(track.surah, track.ayah, onDismiss = { showTafsir = false })
    if (showShare && track != null) {
        ShareDialog(
            surah = track.surah,
            ayah = track.ayah,
            // The word being recited, unless an intro is playing or the ayah hasn't started.
            // With Word clips on, the player holds one word's own file, so its position is into
            // that word, not the ayah: use where the word starts in the ayah instead.
            atMs = when {
                state.introLabel != null -> null
                state.wordPlayback && PlayerSettings.useWordClips ->
                    state.wordSegments.getOrNull(state.currentWordIndex)?.let { it.first + 1 }
                else -> state.positionMs.takeIf { it > 0L }
            },
            onShareVideo = {
                showShare = false
                onMakeVideo(track.surah, track.ayah)
            },
            onDismiss = { showShare = false },
        )
    }
}

/**
 * One row: the surah list, where you are, the playlist, and three dots holding everything that is
 * not transport. Mode and view are one menu item each, named for what choosing it does.
 */
@Composable
private fun ReaderHeader(
    state: PlayerUiState,
    wordView: Boolean,
    showTranslation: Boolean,
    showWordMeanings: Boolean,
    surahAllLearned: Boolean,
    onToggleWordMeanings: () -> Unit,
    onModeChange: (PlaybackMode) -> Unit,
    onWordViewChange: (Boolean) -> Unit,
    onToggleTranslation: () -> Unit,
    onOpenSurahPanel: () -> Unit,
    onOpenPlaylistPanel: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenBackground: () -> Unit,
    onShare: (() -> Unit)?,
    onTafsir: (() -> Unit)? = null,
    chrome: Color,
) {
    // The row keeps its order in every language; the menu reads in the app's own direction.
    val direction = LocalLayoutDirection.current
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(
            modifier = Modifier.fillMaxWidth().background(chrome).padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onOpenSurahPanel, modifier = Modifier.testTag(TAG_OPEN_SURAH_PANEL)) {
                Icon(Icons.AutoMirrored.Filled.List, contentDescription = tx("Browse surahs"), tint = TextMuted)
            }
            Text(
                text = state.fullscreenMeta,
                color = TextMuted,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            IconButton(onClick = onOpenPlaylistPanel, modifier = Modifier.testTag(TAG_OPEN_PLAYLIST_PANEL)) {
                Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = tx("Playlist"), tint = TextMuted)
            }
            var menuOpen by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { menuOpen = true }, modifier = Modifier.testTag(TAG_OPEN_ACTIONS_MENU)) {
                    Icon(Icons.Filled.MoreVert, contentDescription = tx("More options"), tint = TextMuted)
                }
                CompositionLocalProvider(LocalLayoutDirection provides direction) {
                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false },
                    ) {
                        val other = if (state.mode == PlaybackMode.FULL_SURAH) PlaybackMode.REVISE else PlaybackMode.FULL_SURAH
                        // A surah learned in full is the same list either way: no "Full Surah".
                        if (other == PlaybackMode.REVISE || !surahAllLearned) MenuAction(
                            label = if (other == PlaybackMode.REVISE) tx("Revise Learned Ayahs") else tx("Revise Full Surah"),
                            icon = Icons.Filled.Repeat,
                            tag = playbackModeTag(other),
                        ) { menuOpen = false; onModeChange(other) }
                        MenuAction(
                            label = if (wordView) tx("View Full Ayah") else tx("Word by Word"),
                            icon = Icons.Filled.TextFields,
                            tag = if (wordView) TAG_VIEW_FULL_AYAH else TAG_TOGGLE_WORD_BY_WORD,
                        ) { menuOpen = false; onWordViewChange(!wordView) }
                        MenuAction(
                            label = if (showTranslation) tx("Hide Translation") else tx("Show Translation"),
                            icon = Icons.Filled.Translate,
                            tag = TAG_AYAH_TRANSLATION_TOGGLE,
                        ) { menuOpen = false; onToggleTranslation() }
                        MenuAction(
                            label = if (showWordMeanings) tx("Hide Word Meanings") else tx("Show Word Meanings"),
                            icon = Icons.Filled.Subtitles,
                            tag = TAG_WORD_MEANINGS_MENU,
                        ) { menuOpen = false; onToggleWordMeanings() }
                        androidx.compose.material3.HorizontalDivider(color = Border)
                        MenuAction(tx("Tafsir"), Icons.AutoMirrored.Filled.MenuBook, TAG_OPEN_TAFSIR, enabled = onTafsir != null) {
                            menuOpen = false; onTafsir?.invoke()
                        }
                        MenuAction(tx("Share"), Icons.Filled.Share, TAG_SHARE, enabled = onShare != null) {
                            menuOpen = false; onShare?.invoke()
                        }
                        MenuAction(tx("Theme"), Icons.Filled.Wallpaper, TAG_OPEN_BACKGROUND) {
                            menuOpen = false; onOpenBackground()
                        }
                        MenuAction(tx("Settings"), Icons.Filled.Settings, TAG_OPEN_SETTINGS) {
                            menuOpen = false; onOpenSettings()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuAction(label: String, icon: ImageVector, tag: String, enabled: Boolean = true, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label, color = if (enabled) TextPrimary else TextMuted.copy(alpha = 0.5f)) },
        leadingIcon = { Icon(icon, contentDescription = null, tint = TextMuted.copy(alpha = if (enabled) 1f else 0.5f)) },
        enabled = enabled,
        onClick = onClick,
        modifier = Modifier.testTag(tag),
    )
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
        text = tx("No ayahs marked yet."),
        color = TextPrimary,
        style = MaterialTheme.typography.titleMedium,
        textAlign = TextAlign.Center,
    )
    Text(
        text = tx("Open a surah and tap the circle beside an ayah to add it to your revision. You can also add several at once from Settings."),
        color = TextMuted,
        style = MaterialTheme.typography.bodySmall,
        textAlign = TextAlign.Center,
    )
    Button(
        onClick = onBrowseSurahs,
        modifier = Modifier.testTag(TAG_EMPTY_BROWSE),
    ) {
        Text(tx("Browse surahs"))
    }
}

@Composable
private fun ErrorContent(state: PlayerUiState, onBrowseSurahs: () -> Unit) {
    Text(
        state.error ?: tx("Something went wrong"),
        color = MaterialTheme.colorScheme.error,
        textAlign = TextAlign.Center,
    )
    OutlinedButton(onClick = onBrowseSurahs) {
        Text(tx("Browse surahs"))
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
    chrome: Color,
) {
    Column(modifier = Modifier.fillMaxWidth().background(chrome)) {
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
                        ?: tx("Ready — press Play"),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = buildString {
                        if (state.tracks.isNotEmpty()) {
                            append(tx("{0} of {1}", state.currentIndex + 1, state.tracks.size))
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
                contentDescription = if (expanded) tx("Collapse controls") else tx("Expand controls"),
                tint = TextMuted,
            )
        }

        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            // While the words play one at a time, Previous and Next step words and the outer pair
            // move by ayah.
            val words = state.wordPlayback && state.currentTrack != null
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(if (words) 12.dp else 20.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (words) {
                    TransportButton(Icons.Filled.SkipPrevious, tx("Previous ayah"), TAG_PREVIOUS_AYAH, size = 42) { viewModel.skipAyah(false) }
                }
                if (words) {
                    TransportButton(Icons.AutoMirrored.Filled.KeyboardArrowLeft, tx("Previous word"), TAG_FULLSCREEN_PREVIOUS) { viewModel.previous() }
                } else {
                    TransportButton(Icons.Filled.SkipPrevious, tx("Previous"), TAG_FULLSCREEN_PREVIOUS) { viewModel.previous() }
                }
                Surface(
                    onClick = {
                        if (state.isPlaying) viewModel.pause() else viewModel.playOrResume(onPlayRequested)
                    },
                    shape = CircleShape,
                    color = AccentGreen,
                    modifier = Modifier.size(60.dp).testTag(TAG_PLAY_PAUSE),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (state.isPlaying) tx("Pause") else tx("Play"),
                            tint = OnAccentGreen,
                            modifier = Modifier.size(32.dp),
                        )
                    }
                }
                if (words) {
                    TransportButton(Icons.AutoMirrored.Filled.KeyboardArrowRight, tx("Next word"), TAG_FULLSCREEN_NEXT) { viewModel.next() }
                    TransportButton(Icons.Filled.SkipNext, tx("Next ayah"), TAG_NEXT_AYAH, size = 42) { viewModel.skipAyah(true) }
                } else {
                    TransportButton(Icons.Filled.SkipNext, tx("Next"), TAG_FULLSCREEN_NEXT) { viewModel.next() }
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
                val repeatWord by PlayerSettings.repeatWordFlow.collectAsState()
                val useWordClips by viewModel.useWordClips.collectAsState()
                val learnedIds by viewModel.learnedIds.collectAsState()
                val surah = state.currentTrack?.surah
                // "Surah" repeats this surah's learned ayahs in the learned list; say so when
                // there is more than one of them to repeat.
                val severalLearnedHere = state.mode == PlaybackMode.REVISE && surah != null &&
                    remember(learnedIds, surah) { learnedIds.count { AyahMapping.globalToSurahAyah(it).first == surah } } > 1
                RepeatSelector(
                    active = state.repeatMode,
                    repeatWord = repeatWord,
                    surahLearnedAyahs = severalLearnedHere,
                    onSelect = { viewModel.setRepeatMode(it) },
                    onWord = { viewModel.setRepeatWord(!repeatWord) },
                )
                // Word clips repeat without a pause: each is a file of its own, looped by the player.
                if (state.repeatMode == RepeatMode.AYAH || (repeatWord && !useWordClips)) {
                    val revisionDelay by PlayerSettings.revisionDelay.collectAsState()
                    RevisionDelaySelector(revisionDelay) { viewModel.setRevisionDelay(it) }
                }
                WordClipsToggle(useWordClips) { viewModel.setUseWordClips(it) }
                // Word clips are Quran.com's own recording, so there is no reciter to choose.
                if (!useWordClips) {
                    val reciter by PlayerSettings.reciterFlow.collectAsState()
                    ReciterSelector(reciter) { viewModel.setReciter(it) }
                }
            }
        }
    }
}

/** Previous / Next: a quiet round button either side of Play, as on the web. */
@Composable
private fun TransportButton(icon: ImageVector, label: String, tag: String, size: Int = 48, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = Panel,
        border = androidx.compose.foundation.BorderStroke(1.dp, Border),
        modifier = Modifier.size(size.dp).testTag(tag),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = label, tint = TextPrimary, modifier = Modifier.size((size * 0.54f).dp))
        }
    }
}

/** The ayah reciter, picked where it is heard. Changing it rebuilds the queue (setReciter). */
@Composable
private fun ReciterSelector(active: Reciter, onSelect: (Reciter) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(tx("Reciter"), color = TextMuted, style = MaterialTheme.typography.bodySmall)
        Box {
            Surface(
                onClick = { open = true },
                shape = RoundedCornerShape(12.dp),
                color = Panel,
                border = androidx.compose.foundation.BorderStroke(1.dp, Border),
                modifier = Modifier.fillMaxWidth().testTag(TAG_PLAYER_RECITER),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(active.displayName, color = TextPrimary, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = TextMuted)
                }
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                Reciter.entries.forEach { option ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                option.displayName,
                                color = if (option == active) AccentGreen else TextPrimary,
                                fontWeight = if (option == active) FontWeight.SemiBold else FontWeight.Normal,
                            )
                        },
                        onClick = { open = false; onSelect(option) },
                        modifier = Modifier.testTag(reciterTag(option)),
                    )
                }
            }
        }
    }
}

@Composable
private fun SeekSlider(state: PlayerUiState, viewModel: PlayerViewModel) {
    // Seek once on release — seeking on every drag pixel makes playback stutter.
    var dragPositionMs by remember(state.currentTrack?.globalId, state.durationMs) { mutableStateOf<Float?>(null) }
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
            text = tx("Pause before repeat — time to revise or press Next"),
            color = TextMuted,
            style = MaterialTheme.typography.bodySmall,
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PlayerSettings.REVISION_DELAY_CHOICES.forEach { seconds ->
                ModeChip(
                    label = if (seconds == 0) tx("Off") else "${seconds}s",
                    selected = seconds == activeSeconds,
                    modifier = Modifier.testTag(revisionDelayTag(seconds)),
                    onClick = { onSelect(seconds) },
                )
            }
        }
    }
}

@Composable
private fun RepeatSelector(
    active: RepeatMode,
    repeatWord: Boolean,
    surahLearnedAyahs: Boolean,
    onSelect: (RepeatMode) -> Unit,
    onWord: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = tx("Repeat"),
            color = TextMuted,
            style = MaterialTheme.typography.bodySmall,
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // One choice of four. Word repeats the word being recited, in either view.
            ModeChip(
                label = tx("Ayah"),
                selected = !repeatWord && active == RepeatMode.AYAH,
                modifier = Modifier.testTag(repeatModeTag(RepeatMode.AYAH)),
                onClick = { onSelect(RepeatMode.AYAH) },
            )
            ModeChip(
                label = if (surahLearnedAyahs) tx("Surah learned ayahs") else tx("Surah"),
                selected = !repeatWord && active == RepeatMode.SURAH,
                modifier = Modifier.testTag(repeatModeTag(RepeatMode.SURAH)),
                onClick = { onSelect(RepeatMode.SURAH) },
            )
            ModeChip(
                label = tx("Word"),
                selected = repeatWord,
                modifier = Modifier.testTag(TAG_REPEAT_WORD),
                onClick = onWord,
            )
            ModeChip(
                label = tx("Off"),
                selected = !repeatWord && active == RepeatMode.OFF,
                modifier = Modifier.testTag(repeatModeTag(RepeatMode.OFF)),
                onClick = { onSelect(RepeatMode.OFF) },
            )
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
            Text(tx("Word clips (Quran.com)"), color = TextPrimary, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = if (enabled) {
                    tx("Each word from Quran.com, one at a time, in place of your reciter.")
                } else {
                    tx("Off: your ayah reciter. On: each word from Quran.com, one at a time.")
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
                text = tx("Surahs"),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onDismiss) {
                Icon(Icons.Filled.Close, contentDescription = tx("Close"), tint = TextMuted)
            }
        }
        OutlinedButton(
            onClick = onBrowseSurahs,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp).testTag(TAG_MANAGE_AYAHS),
        ) {
            Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.padding(end = 8.dp).size(18.dp))
            Text(tx("Manage learned ayahs"))
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
                .testTag(TAG_SURAH_PANEL_SEARCH),
            singleLine = true,
            placeholder = { Text(tx("Search surah")) },
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
                        text = if (learned > 0) tx("{0} learned", learned) else tx("{0} ayahs", QuranConstants.VERSE_COUNTS[surah - 1]),
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
    return surah.toString() == q || SurahNames.name(surah).contains(q, ignoreCase = true) || SurahNames.latin(surah).contains(q, ignoreCase = true)
}
