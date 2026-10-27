package com.quran.learnedplayer.ui.video

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.quran.learnedplayer.data.QuranConstants
import com.quran.learnedplayer.data.Reciter
import com.quran.learnedplayer.data.SurahNames
import com.quran.learnedplayer.ui.theme.AccentGreen
import com.quran.learnedplayer.ui.theme.Bg
import com.quran.learnedplayer.ui.theme.Border
import com.quran.learnedplayer.ui.theme.Panel
import com.quran.learnedplayer.ui.theme.TextMuted
import com.quran.learnedplayer.ui.theme.TextPrimary
import com.quran.learnedplayer.video.VideoAspect
import com.quran.learnedplayer.video.VideoExporter
import com.quran.learnedplayer.video.VideoThemes

const val TAG_VIDEO_SCREEN = "video_screen"
const val TAG_VIDEO_CREATE = "video_create"
const val TAG_VIDEO_PREVIEW = "video_preview"
const val TAG_VIDEO_SHARE = "video_share"
const val TAG_VIDEO_SAVE = "video_save"

/**
 * "Make a video": pick the ayahs, the shape, the reciter, the text and the background, see a
 * still of the first ayah, then make the video on the phone and save or share it.
 */
@Composable
fun VideoMakerScreen(
    surah: Int,
    ayah: Int,
    onBack: () -> Unit,
    viewModel: VideoMakerViewModel = viewModel(),
) {
    LaunchedEffect(surah, ayah) { viewModel.start(surah, ayah) }
    val options by viewModel.options.collectAsState()
    val preview by viewModel.preview.collectAsState()
    val export by viewModel.export.collectAsState()
    val thumbnails by viewModel.thumbnails.collectAsState()
    val matchedTheme by viewModel.matchedTheme.collectAsState()
    val context = LocalContext.current
    var pickingSurah by remember { mutableStateOf(false) }

    // Making a video takes a while; the screen must not sleep part-way.
    val view = LocalView.current
    val working = export is ExportState.Working
    DisposableEffect(working) {
        view.keepScreenOn = working
        onDispose { view.keepScreenOn = false }
    }

    val saveAs = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("video/mp4")) { uri ->
        if (uri != null) viewModel.saveTo(uri)
    }

    Column(modifier = Modifier.fillMaxSize().background(Bg).testTag(TAG_VIDEO_SCREEN)) {
        Row(modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextMuted)
            }
            Text("Make a video", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        }

        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
            // The preview, at the video's own shape.
            Box(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                val ratio = options.aspect.width.toFloat() / options.aspect.height
                Box(
                    modifier = Modifier
                        .heightIn(max = 380.dp)
                        .aspectRatio(ratio, matchHeightConstraintsFirst = ratio < 1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Panel)
                        .testTag(TAG_VIDEO_PREVIEW),
                    contentAlignment = Alignment.Center,
                ) {
                    val bitmap = preview
                    if (bitmap != null) {
                        Image(bitmap.asImageBitmap(), contentDescription = "Preview of the first ayah", contentScale = ContentScale.Fit)
                    } else {
                        Text("Preparing preview…", color = TextMuted, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            Section("Ayahs") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("${options.surah} · ${SurahNames.name(options.surah)}", style = MaterialTheme.typography.titleMedium)
                        Text("${QuranConstants.VERSE_COUNTS[options.surah - 1]} ayahs", color = TextMuted, style = MaterialTheme.typography.bodySmall)
                    }
                    OutlinedButton(onClick = { pickingSurah = true }, enabled = !working) { Text("Change") }
                }
                Spacer(Modifier.height(8.dp))
                val count = QuranConstants.VERSE_COUNTS[options.surah - 1]
                Stepper("From ayah", options.fromAyah, 1..count, enabled = !working) { v -> viewModel.update { it.copy(fromAyah = v, toAyah = maxOf(v, it.toAyah)) } }
                Stepper("To ayah", options.toAyah, options.fromAyah..count, enabled = !working) { v -> viewModel.update { it.copy(toAyah = v) } }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { viewModel.update { it.copy(fromAyah = 1, toAyah = count, opening = true) } }, enabled = !working) {
                        Text("Whole surah", color = AccentGreen)
                    }
                }
            }

            Section("Shape") {
                ChipRow {
                    VideoAspect.entries.forEach { aspect ->
                        Choice("${aspect.ratio} · ${aspect.label}", options.aspect == aspect, enabled = !working) {
                            viewModel.update { it.copy(aspect = aspect) }
                        }
                    }
                }
            }

            Section("Reciter") {
                ChipRow {
                    Reciter.entries.forEach { reciter ->
                        Choice(reciter.displayName, options.reciter == reciter, enabled = !working) {
                            viewModel.update { it.copy(reciter = reciter) }
                        }
                    }
                }
            }

            Section("Text") {
                Toggle("Word-by-word meanings", "Under each Arabic word", options.wordByWord, !working) { on ->
                    viewModel.update { it.copy(wordByWord = on) }
                }
                Toggle("Translation", "Saheeh International", options.fullTranslation, !working) { on ->
                    viewModel.update { it.copy(fullTranslation = on) }
                }
                val bismillah = options.fromAyah == 1 && options.surah !in QuranConstants.NO_BISMILLAH_INTRO_SURAHS
                Toggle(if (bismillah) "Begin with A'udhu and Bismillah" else "Begin with A'udhu", "Recited before the first ayah", options.opening, !working) { on ->
                    viewModel.update { it.copy(opening = on) }
                }
            }

            Section("Background") {
                Text(
                    "Matched picks a background for each ayah from what it is about. Or choose one for the whole video.",
                    color = TextMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ThemeTile(
                        title = "Matched",
                        subtitle = matchedTheme,
                        selected = options.themeKey == null,
                        image = null,
                        enabled = !working,
                    ) { viewModel.update { it.copy(themeKey = null) } }
                    VideoThemes.all.forEach { theme ->
                        ThemeTile(
                            title = theme.title,
                            subtitle = null,
                            selected = options.themeKey == theme.key,
                            image = thumbnails[theme.key],
                            enabled = !working,
                        ) { viewModel.update { it.copy(themeKey = theme.key) } }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Backgrounds are drawn scenes of sky, land, water, light and pattern, never people or animals. Videos carry no music, only the recitation.",
                    color = TextMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Section("Video") {
                when (val state = export) {
                    is ExportState.Idle -> {
                        Text(
                            "${options.ayahCount} ayah${if (options.ayahCount == 1) "" else "s"} · ${options.reciter.displayName}. " +
                                "It's made on this phone; keep this screen open until it's done.",
                            color = TextMuted,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.height(10.dp))
                        Button(
                            onClick = viewModel::create,
                            modifier = Modifier.fillMaxWidth().testTag(TAG_VIDEO_CREATE),
                            colors = ButtonDefaults.buttonColors(containerColor = AccentGreen, contentColor = Panel),
                        ) { Text("Create video", fontWeight = FontWeight.Bold) }
                    }
                    is ExportState.Working -> {
                        Text(stageText(state.progress.stage), style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { state.progress.fraction },
                            modifier = Modifier.fillMaxWidth(),
                            color = AccentGreen,
                            trackColor = Border,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text("${(state.progress.fraction * 100).toInt()}%", color = TextMuted, style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = viewModel::cancel) { Text("Cancel", color = TextMuted) }
                    }
                    is ExportState.Done -> {
                        val sizeMb = state.file.length() / (1024f * 1024f)
                        Text("Your video is ready (${String.format(java.util.Locale.ROOT, "%.1f", sizeMb)} MB).", style = MaterialTheme.typography.bodyMedium)
                        state.savedTo?.let { Text(it, color = AccentGreen, style = MaterialTheme.typography.bodySmall) }
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = { viewModel.shareIntent()?.let { context.startActivity(it) } },
                                modifier = Modifier.weight(1f).testTag(TAG_VIDEO_SHARE),
                                colors = ButtonDefaults.buttonColors(containerColor = AccentGreen, contentColor = Panel),
                            ) { Text("Share", fontWeight = FontWeight.Bold) }
                            OutlinedButton(
                                onClick = {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) viewModel.saveToMovies()
                                    else saveAs.launch(state.file.name)
                                },
                                modifier = Modifier.weight(1f).testTag(TAG_VIDEO_SAVE),
                            ) { Text("Save to phone") }
                        }
                    }
                    is ExportState.Failed -> {
                        Text(state.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(10.dp))
                        Button(
                            onClick = viewModel::create,
                            modifier = Modifier.fillMaxWidth().testTag(TAG_VIDEO_CREATE),
                            colors = ButtonDefaults.buttonColors(containerColor = AccentGreen, contentColor = Panel),
                        ) { Text("Try again") }
                    }
                }
            }
        }
    }

    if (pickingSurah) {
        AlertDialog(
            onDismissRequest = { pickingSurah = false },
            containerColor = Panel,
            title = { Text("Surah") },
            text = {
                LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                    items((1..114).toList()) { s ->
                        Text(
                            "$s · ${SurahNames.name(s)}",
                            color = if (s == options.surah) AccentGreen else TextPrimary,
                            modifier = Modifier.fillMaxWidth().clickable {
                                viewModel.setSurah(s)
                                pickingSurah = false
                            }.padding(vertical = 10.dp),
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { pickingSurah = false }) { Text("Close", color = TextMuted) } },
        )
    }
}

private fun stageText(stage: VideoExporter.Stage) = when (stage) {
    VideoExporter.Stage.DOWNLOADING -> "Downloading the recitation…"
    VideoExporter.Stage.AUDIO -> "Preparing the audio…"
    VideoExporter.Stage.VIDEO -> "Drawing the video…"
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Panel)
            .padding(12.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Column(modifier = Modifier.padding(top = 6.dp)) { content() }
    }
}

@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}

@Composable
private fun Choice(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        enabled = enabled,
        label = { Text(label) },
        colors = FilterChipDefaults.filterChipColors(
            containerColor = Border,
            labelColor = TextMuted,
            selectedContainerColor = AccentGreen,
            selectedLabelColor = Panel,
        ),
    )
}

@Composable
private fun Toggle(title: String, subtitle: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(subtitle, color = TextMuted, style = MaterialTheme.typography.bodySmall)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(checkedThumbColor = Panel, checkedTrackColor = AccentGreen),
        )
    }
}

@Composable
private fun Stepper(label: String, value: Int, range: IntRange, enabled: Boolean, onChange: (Int) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        IconButton(onClick = { onChange((value - 1).coerceIn(range)) }, enabled = enabled && value > range.first) {
            Icon(Icons.Filled.Remove, contentDescription = "$label down", tint = TextMuted)
        }
        Text("$value", modifier = Modifier.width(44.dp), style = MaterialTheme.typography.titleMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        IconButton(onClick = { onChange((value + 1).coerceIn(range)) }, enabled = enabled && value < range.last) {
            Icon(Icons.Filled.Add, contentDescription = "$label up", tint = TextMuted)
        }
    }
}

@Composable
private fun ThemeTile(
    title: String,
    subtitle: String?,
    selected: Boolean,
    image: android.graphics.Bitmap?,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier.width(76.dp).clickable(enabled = enabled, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(width = 68.dp, height = 104.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Border)
                .border(if (selected) 3.dp else 0.dp, if (selected) AccentGreen else Border, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (image != null) {
                Image(image.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else if (subtitle != null) {
                Text("Auto", color = if (selected) AccentGreen else TextMuted, style = MaterialTheme.typography.labelLarge)
            }
        }
        Text(title, style = MaterialTheme.typography.labelSmall, color = if (selected) AccentGreen else TextPrimary, maxLines = 2)
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.labelSmall, color = TextMuted, maxLines = 1)
    }
}
