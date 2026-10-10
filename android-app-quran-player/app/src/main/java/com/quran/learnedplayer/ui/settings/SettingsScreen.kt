package com.quran.learnedplayer.ui.settings

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
import com.quran.learnedplayer.data.DeviceRecognition
import com.quran.learnedplayer.data.Reciter
import com.quran.learnedplayer.data.WordAudioDownloader
import com.quran.learnedplayer.data.WordReciter
import com.quran.learnedplayer.player.AyahOverflowMode
import com.quran.learnedplayer.player.PlaybackMode
import com.quran.learnedplayer.player.PlayerSettings
import com.quran.learnedplayer.player.PlayerViewModel
import com.quran.learnedplayer.player.RepeatMode
import com.quran.learnedplayer.ui.theme.AccentGreen
import com.quran.learnedplayer.ui.theme.Bg
import com.quran.learnedplayer.ui.theme.Border
import com.quran.learnedplayer.ui.theme.Panel
import com.quran.learnedplayer.ui.theme.TextMuted
import com.quran.learnedplayer.ui.theme.TextPrimary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

const val TAG_SETTINGS_SCREEN = "settings_screen"
const val TAG_WORD_TRANSLATIONS_TOGGLE = "word_translations_toggle"
const val TAG_SWIPE_TO_NAVIGATE_TOGGLE = "swipe_to_navigate_toggle"
const val TAG_TAP_TO_ADVANCE_TOGGLE = "tap_to_advance_toggle"
const val TAG_DOWNLOAD_OFFLINE = "download_offline"
const val TAG_DOWNLOAD_WORD_CLIPS = "download_word_clips"
const val TAG_RECITE_BETA_TOGGLE = "recite_beta_toggle"
const val TAG_DOWNLOAD_ARABIC_PACK = "download_arabic_pack"
fun reciterTag(reciter: Reciter) = "reciter_${reciter.name}"
fun wordReciterTag(reciter: WordReciter) = "word_reciter_${reciter.name}"
const val TAG_SEEK_INSIDE_AYAH = "seek_inside_ayah_audio"
const val TAG_USE_WORD_CLIPS = "use_word_clips"
fun appThemeTag(theme: AppTheme) = "app_theme_${theme.name}"
fun ayahOverflowModeTag(mode: AyahOverflowMode) = "ayah_overflow_mode_${mode.name}"

@Composable
fun SettingsScreen(
    viewModel: PlayerViewModel,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val state by viewModel.uiState.collectAsState()
    val reciter by viewModel.reciter.collectAsState()
    val showTranslations by viewModel.showWordTranslations.collectAsState()
    val appTheme by PlayerSettings.appThemeFlow.collectAsState()
    val swipeToNavigate by viewModel.swipeToNavigate.collectAsState()
    val tapToAdvance by viewModel.tapToAdvance.collectAsState()
    val ayahOverflowMode by PlayerSettings.ayahOverflowModeFlow.collectAsState()
    val autoPlayMistakeAudio by PlayerSettings.autoPlayMistakeAudioFlow.collectAsState()
    val reciteBetaEnabled by PlayerSettings.reciteBetaEnabledFlow.collectAsState()
    val reciteSupported = remember { DeviceRecognition.isSupported }
    var showBetaConfirm by remember { mutableStateOf(false) }
    var arabicPackInstalled by remember { mutableStateOf(false) }
    var arabicPackStatus by remember { mutableStateOf("Checking Arabic speech support…") }
    /** The Arabic tag this phone answered for, so the download asks for one it recognises. */
    var arabicPackLanguage by remember { mutableStateOf<String?>(null) }
    var arabicPackOffered by remember { mutableStateOf(false) }
    val autoAdvanceSuccess by PlayerSettings.autoAdvanceSuccessFlow.collectAsState()
    val context = LocalContext.current
    val mainExecutor = remember(context) { ContextCompat.getMainExecutor(context) }

    // Asked once per visit to Settings, and only when it can be answered. The result decides
    // whether Recite can work without a connection, which is worth stating rather than leaving
    // the user to discover on a train.
    LaunchedEffect(reciteBetaEnabled, reciteSupported) {
        if (!reciteSupported) {
            arabicPackStatus = "Offline Arabic needs Android 13 or newer."
            return@LaunchedEffect
        }
        DeviceRecognition.queryArabicSupport(context, mainExecutor) { support ->
            arabicPackInstalled = support?.hasOfflineArabic == true
            // Remembered so the download asks for the variant this phone said it has, rather than
            // whichever tag happens to be first in the list.
            arabicPackLanguage = support?.downloadable?.firstOrNull() ?: support?.answeredFor
            arabicPackOffered = support?.canDownloadArabic == true
            arabicPackStatus = when {
                support == null ->
                    "Could not check offline Arabic on this phone — Recite will use online " +
                        "recognition."
                support.hasOfflineArabic ->
                    "Arabic is available offline (${support.installedOnDevice.joinToString()})."
                support.canDownloadArabic ->
                    "Arabic is not downloaded yet, so Recite needs a connection."
                else ->
                    "Your phone's speech service does not offer Arabic offline, so Recite needs " +
                        "a connection. That is a limit of the speech service, not of Recite."
            }
        }
    }
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
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextMuted)
            }
            Text("Settings", style = MaterialTheme.typography.titleLarge)
        }

        Section("Ayah reciter") {
            Text(
                text = "Whole-ayah recitations stream from everyayah.com. Each one ships exact " +
                    "word timings for optional highlight-while-playing.",
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
            )
            Column(modifier = Modifier.padding(top = 8.dp)) {
                Reciter.entries.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.setReciter(option) }
                            .padding(vertical = 8.dp)
                            .testTag(reciterTag(option)),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = reciter == option,
                            onClick = { viewModel.setReciter(option) },
                            colors = RadioButtonDefaults.colors(
                                selectedColor = AccentGreen,
                                unselectedColor = TextMuted,
                            ),
                        )
                        Text(
                            text = option.displayName,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                }
            }
        }

        Section("Theme") {
            Row(
                modifier = Modifier.padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AppTheme.entries.forEach { option ->
                    Choice(
                        label = option.label,
                        selected = appTheme == option,
                        tag = appThemeTag(option),
                    ) { PlayerSettings.appTheme = option }
                }
            }
        }

        Section("Reading") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { viewModel.setShowWordTranslations(!showTranslations) }
                    .padding(top = 12.dp)
                    .testTag(TAG_WORD_TRANSLATIONS_TOGGLE),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Word-by-word translation", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = "Show each word's meaning beneath it in the ayah views.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted,
                    )
                }
                Switch(
                    checked = showTranslations,
                    onCheckedChange = { viewModel.setShowWordTranslations(it) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Panel,
                        checkedTrackColor = AccentGreen,
                    ),
                )
            }
            Text(
                text = "When whole ayah is too long to fit on screen while playing:",
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
                modifier = Modifier.padding(top = 16.dp),
            )
            Row(
                modifier = Modifier.padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AyahOverflowMode.entries.forEach { option ->
                    Choice(
                        label = option.label,
                        selected = ayahOverflowMode == option,
                        tag = ayahOverflowModeTag(option),
                    ) { PlayerSettings.ayahOverflowMode = option }
                }
            }
        }

        Section("Gestures") {
            Text(
                text = "Swipe or tap on the player to move between ayahs.",
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
                    Text("Swipe to navigate", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = "Swipe left/right on the player to move between ayahs.",
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
                    Text("Tap to advance", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = "Tap the player to move to the next ayah.",
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

        Section("Recitation & AI Review") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { PlayerSettings.autoPlayMistakeAudio = !autoPlayMistakeAudio }
                    .padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Auto-play audio on mistake", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = "Play reciter audio when a mistake is detected and pause for user to re-recite.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted,
                    )
                }
                Switch(
                    checked = autoPlayMistakeAudio,
                    onCheckedChange = { PlayerSettings.autoPlayMistakeAudio = it },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Panel,
                        checkedTrackColor = AccentGreen,
                    ),
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { PlayerSettings.autoAdvanceSuccess = !autoAdvanceSuccess }
                    .padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Auto-advance on success", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = "Automatically move to next ayah when recitation is 100% correct.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted,
                    )
                }
                Switch(
                    checked = autoAdvanceSuccess,
                    onCheckedChange = { PlayerSettings.autoAdvanceSuccess = it },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Panel,
                        checkedTrackColor = AccentGreen,
                    ),
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp)
                    .background(Panel, shape = RoundedCornerShape(12.dp))
                    .padding(16.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Recite & review", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Beta",
                        style = MaterialTheme.typography.labelSmall,
                        color = AccentGreen,
                        modifier = Modifier
                            .background(AccentGreen.copy(alpha = 0.15f), shape = RoundedCornerShape(6.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
                Text(
                    text = if (!reciteSupported) {
                        "Needs Android 13 or newer. Recite listens through your phone's speech " +
                            "recognition, and the way it keeps the microphone open without " +
                            "switching it on and off is only available on newer Android versions."
                    } else {
                        "Listens while you recite a learned ayah and plays the correct word back " +
                            "when you slip. Uses your phone's Arabic speech recognition — offline " +
                            "when a voice pack is installed, otherwise online."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                        .testTag(TAG_RECITE_BETA_TOGGLE),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "Enable Recite & review",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (reciteSupported) TextPrimary else TextMuted,
                    )
                    Switch(
                        checked = reciteBetaEnabled,
                        enabled = reciteSupported,
                        // Turning it on asks first; turning it off is immediate — nobody needs a
                        // dialog to stop using something.
                        onCheckedChange = { on ->
                            if (on) showBetaConfirm = true else PlayerSettings.reciteBetaEnabled = false
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Panel,
                            checkedTrackColor = AccentGreen,
                        ),
                    )
                }

                // Offline Arabic. Only shown once Recite is on, and only when the phone can
                // actually answer the question — there is no point offering a download on a
                // version of Android with no API to request one.
                if (reciteBetaEnabled && reciteSupported) {
                    Text(
                        text = arabicPackStatus,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    // Offered only when the service said it has an Arabic model to fetch. A
                    // button that can only fail is worse than no button.
                    if (!arabicPackInstalled && arabicPackOffered) {
                        TextButton(
                            onClick = {
                                arabicPackStatus = "Asking your phone to download Arabic…"
                                DeviceRecognition.downloadArabicModel(
                                    context = context,
                                    executor = mainExecutor,
                                    preferredLanguage = arabicPackLanguage,
                                    onProgress = { percent ->
                                        arabicPackStatus = "Downloading Arabic… $percent%"
                                    },
                                    onDone = { ok ->
                                        arabicPackStatus = if (ok) {
                                            PlayerSettings.preferOfflineDeviceSpeech = true
                                            "Arabic is available offline — Recite will use it."
                                        } else {
                                            "Your phone's speech service would not download " +
                                                "Arabic${arabicPackLanguage?.let { " ($it)" }.orEmpty()}. " +
                                                "Recite will use online recognition, which needs " +
                                                "a connection but is often the more accurate of " +
                                                "the two anyway."
                                        }
                                    },
                                )
                            },
                            modifier = Modifier.testTag(TAG_DOWNLOAD_ARABIC_PACK),
                        ) {
                            Text("Download Arabic for offline use", color = AccentGreen)
                        }
                    }
                }
            }
            Text(
                text = "Word clips are played back when a wrong word is detected — they are a " +
                    "separate download from the speech engine.",
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
                Text("Save word clips for Recite & review")
            }
            Text(
                text = if (wordClipsCached > 0) {
                    "$wordClipsCached word clips saved for learned ayahs"
                } else {
                    "Not downloaded — mistake playback will stream"
                },
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        Section("Offline") {
            // Reports on whatever queue is currently active (it can be a full-surah stream
            // rather than the whole learned list), same as when this lived on the player.
            val queueIsMaster = (state.mode == PlaybackMode.REVISE || state.mode == PlaybackMode.WORD_BY_WORD) &&
                state.repeatMode != RepeatMode.SURAH
            val cached = state.tracks.count { it.localPath != null }
            val total = state.tracks.size
            val introsCached = state.audhuCached && state.bismillahCached
            val allCached = total > 0 && cached == total && (!queueIsMaster || introsCached)
            val wordMode = state.mode == PlaybackMode.WORD_BY_WORD
            val statusText = when {
                state.downloadProgress != null -> state.downloadProgress
                total == 0 -> null
                wordMode -> "Offline: word clips for your learned ayahs (stream otherwise)"
                queueIsMaster && introsCached -> "Offline: $cached/$total · A'udhu + Bismillah"
                queueIsMaster -> "Offline: $cached/$total · intro clips stream online"
                state.mode == PlaybackMode.FULL_SURAH -> "Offline: $cached/$total (your learned ayahs only)"
                else -> "Offline: $cached/$total"
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
                        wordMode -> "Save word audio for offline"
                        allCached -> "Saved offline"
                        else -> "Save learned ayahs for offline"
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

        Section("About") {
            Text(
                text = "Learned Ayahs Player v${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
            )
            Text(
                text = "Recitation: ${reciter.displayName} (everyayah.com)\n" +
                    "Arabic: Scheherazade New (SIL OFL)",
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
            )
        }
    }

    if (showBetaConfirm) {
        AlertDialog(
            onDismissRequest = { showBetaConfirm = false },
            containerColor = Panel,
            title = { Text("Turn on Recite & review?") },
            text = {
                Text(
                    text = "This is a beta feature.\n\n" +
                        "It listens through your phone's Arabic speech recognition, which is " +
                        "built for everyday speech rather than Qur'anic recitation. It will " +
                        "sometimes miss a real mistake, and it can occasionally flag a word you " +
                        "recited correctly.\n\n" +
                        "Treat it as practice help, never as a check on whether your recitation " +
                        "is correct. Your microphone is only used while you are on the Recite " +
                        "screen and have started listening.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    PlayerSettings.reciteBetaEnabled = true
                    showBetaConfirm = false
                }) {
                    Text("I understand, turn it on", color = AccentGreen)
                }
            },
            dismissButton = {
                TextButton(onClick = { showBetaConfirm = false }) {
                    Text("Cancel", color = TextMuted)
                }
            },
        )
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
