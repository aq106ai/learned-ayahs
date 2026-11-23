package com.quran.learnedplayer.ui.settings

import com.quran.learnedplayer.ui.i18n.tx
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.quran.learnedplayer.BuildConfig
import com.quran.learnedplayer.data.AppTheme
import com.quran.learnedplayer.data.Reciter
import com.quran.learnedplayer.data.WordAudioDownloader
import com.quran.learnedplayer.data.WordReciter
import com.quran.learnedplayer.player.PlaybackMode
import com.quran.learnedplayer.player.PlayerSettings
import com.quran.learnedplayer.player.PlayerViewModel
import com.quran.learnedplayer.player.RepeatMode
import com.quran.learnedplayer.ui.common.SourcesAndDisclaimerText
import com.quran.learnedplayer.ui.theme.AccentGreen
import com.quran.learnedplayer.ui.theme.Bg
import com.quran.learnedplayer.ui.theme.Border
import com.quran.learnedplayer.ui.theme.Panel
import com.quran.learnedplayer.ui.theme.TextMuted
import com.quran.learnedplayer.ui.theme.TextPrimary
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.quran.learnedplayer.data.ContentRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

const val TAG_SETTINGS_SCREEN = "settings_screen"
const val TAG_WORD_TRANSLATIONS_TOGGLE = "word_translations_toggle"
const val TAG_SETTINGS_AYAH_TRANSLATION = "settings_ayah_translation_toggle"
const val TAG_SETTINGS_CLEAR_CONTENT = "settings_clear_content"
const val TAG_SWIPE_TO_NAVIGATE_TOGGLE = "swipe_to_navigate_toggle"
const val TAG_TAP_TO_ADVANCE_TOGGLE = "tap_to_advance_toggle"
const val TAG_DOWNLOAD_OFFLINE = "download_offline"
const val TAG_DOWNLOAD_WORD_CLIPS = "download_word_clips"
fun reciterTag(reciter: Reciter) = "reciter_${reciter.name}"
fun wordReciterTag(reciter: WordReciter) = "word_reciter_${reciter.name}"
const val TAG_SEEK_INSIDE_AYAH = "seek_inside_ayah_audio"
const val TAG_USE_WORD_CLIPS = "use_word_clips"
/** The Dark and Light chips, which live in the player's Background and theme sheet. */
fun appThemeTag(theme: AppTheme) = "app_theme_${theme.name}"

@Composable
fun SettingsScreen(
    viewModel: PlayerViewModel,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val state by viewModel.uiState.collectAsState()
    val reciter by viewModel.reciter.collectAsState()
    val swipeToNavigate by viewModel.swipeToNavigate.collectAsState()
    val tapToAdvance by viewModel.tapToAdvance.collectAsState()
    val context = LocalContext.current
    val mainExecutor = remember(context) { ContextCompat.getMainExecutor(context) }

    // Counting the cache lists the directory and stats every clip, so it runs off the main thread,
    // and only once the download progress has settled for a moment (it ticks once per file).
    var wordClipsCached by remember { mutableStateOf(0) }
    LaunchedEffect(state.downloadProgress, state.tracks) {
        delay(300)
        wordClipsCached = withContext(Dispatchers.IO) { WordAudioDownloader(context).cachedCount() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg)
            .verticalScroll(rememberScrollState())
            .testTag(TAG_SETTINGS_SCREEN),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = tx("Back"), tint = TextMuted)
            }
            Text(tx("Settings"), style = MaterialTheme.typography.titleLarge)
        }

        Section(tx("App language")) {
            Text(
                text = tx("The language of the app's menus and messages. The Qur'an, translations and tafsir have their own choices below."),
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            UiLanguagePicker()
        }

        Section(tx("Translation and tafsir")) {
            Text(
                text = tx("Shown beside the Arabic in the player, the surah pages, shared text and videos. Saheeh International and the English word meanings come with the app; other editions download from Quran.com when chosen and are kept for offline use."),
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            ContentChoices(includeStyle = false)
            var cleared by remember { mutableStateOf<String?>(null) }
            val scope = rememberCoroutineScope()
            TextButton(
                onClick = {
                    scope.launch {
                        val bytes = withContext(Dispatchers.IO) { ContentRepository.downloadedBytes() }
                        if (bytes == 0L) {
                            cleared = tx("Nothing is downloaded. The bundled translation and meanings always stay.")
                        } else {
                            withContext(Dispatchers.IO) { ContentRepository.clearDownloaded() }
                            cleared = tx("Downloaded translations removed ({0} KB). They download again when shown.", bytes / 1024)
                        }
                    }
                },
                modifier = Modifier.testTag(TAG_SETTINGS_CLEAR_CONTENT),
            ) { Text(tx("Remove downloaded translations"), color = AccentGreen) }
            cleared?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = TextMuted) }
        }

        Section(tx("Gestures")) {
            Text(
                text = tx("Swipe or tap on the player to move between ayahs."),
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { viewModel.setSwipeToNavigate(!swipeToNavigate) }
                    .padding(top = 12.dp)
                    .testTag(TAG_SWIPE_TO_NAVIGATE_TOGGLE),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(tx("Swipe to navigate"), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = tx("Swipe left/right on the player to move between ayahs."),
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted,
                    )
                }
                Switch(
                    checked = swipeToNavigate,
                    onCheckedChange = { viewModel.setSwipeToNavigate(it) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Panel,
                        checkedTrackColor = AccentGreen,
                    ),
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { viewModel.setTapToAdvance(!tapToAdvance) }
                    .padding(top = 12.dp)
                    .testTag(TAG_TAP_TO_ADVANCE_TOGGLE),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(tx("Tap to advance"), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = tx("Tap the player to move to the next ayah."),
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted,
                    )
                }
                Switch(
                    checked = tapToAdvance,
                    onCheckedChange = { viewModel.setTapToAdvance(it) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Panel,
                        checkedTrackColor = AccentGreen,
                    ),
                )
            }
        }

        Section(tx("Word audio")) {
            Text(
                text = tx("Word-by-word playback uses one clip per word. Saving them lets it run without a connection."),
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
                modifier = Modifier.padding(top = 16.dp),
            )
            Button(
                onClick = viewModel::downloadRecitationWordClips,
                enabled = state.tracks.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AccentGreen,
                    contentColor = Panel,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
                    .testTag(TAG_DOWNLOAD_WORD_CLIPS),
            ) {
                Text(tx("Save word clips for offline playback"))
            }
            Text(
                text = if (wordClipsCached > 0) {
                    tx("{0} word clips saved for learned ayahs", wordClipsCached)
                } else {
                    tx("Not downloaded — word-by-word playback will stream")
                },
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        Section(tx("Offline")) {
            // Reports on whatever queue is currently active (it can be a full-surah stream
            // rather than the whole learned list), same as when this lived on the player.
            val queueIsMaster = (state.mode == PlaybackMode.REVISE || state.mode == PlaybackMode.WORD_BY_WORD) &&
                state.repeatMode != RepeatMode.SURAH
            val cached = state.tracks.count { it.localPath != null }
            val total = state.tracks.size
            val introsCached = state.audhuCached && state.bismillahCached
            val allCached = total > 0 && cached == total && (!queueIsMaster || introsCached)
            val wordMode = state.wordPlayback && PlayerSettings.useWordClips
            val statusText = when {
                state.downloadProgress != null -> state.downloadProgress
                total == 0 -> null
                wordMode -> tx("Offline: word clips for your learned ayahs (stream otherwise)")
                queueIsMaster && introsCached -> tx("Offline: {0}/{1} · A'udhu + Bismillah", cached, total)
                queueIsMaster -> tx("Offline: {0}/{1} · intro clips stream online", cached, total)
                state.mode == PlaybackMode.FULL_SURAH -> tx("Offline: {0}/{1} (your learned ayahs only)", cached, total)
                else -> tx("Offline: {0}/{1}", cached, total)
            }

            Button(
                onClick = viewModel::downloadAllLearned,
                enabled = state.tracks.isNotEmpty() && (wordMode || !allCached),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AccentGreen,
                    contentColor = Panel,
                ),
                modifier = Modifier.testTag(TAG_DOWNLOAD_OFFLINE),
            ) {
                Text(
                    when {
                        wordMode -> tx("Save word audio for offline")
                        allCached -> tx("Saved offline")
                        else -> tx("Save learned ayahs for offline")
                    },
                )
            }
            statusText?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (allCached) AccentGreen else TextMuted,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }

        Section(tx("About")) {
            Text(
                text = tx("Learned Ayahs Player v{0}", BuildConfig.VERSION_NAME),
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
            )
            Text(
                text = tx("Recitation: {0} (everyayah.com)\nArabic: Scheherazade New (SIL OFL)", reciter.displayName),
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
            )
        }

        Section(tx("Sources and disclaimer")) {
            SourcesAndDisclaimerText()
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Panel)
            .padding(12.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Column(modifier = Modifier.padding(top = 6.dp)) { content() }
    }
}

@Composable
private fun Choice(
    label: String,
    selected: Boolean,
    tag: String,
    onClick: () -> Unit,
) {
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
