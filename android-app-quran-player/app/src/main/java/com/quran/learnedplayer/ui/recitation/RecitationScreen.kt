package com.quran.learnedplayer.ui.recitation

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.quran.learnedplayer.data.SurahNames
import com.quran.learnedplayer.data.WordEvaluationStatus
import com.quran.learnedplayer.player.PlayerViewModel
import com.quran.learnedplayer.player.RecitationViewModel
import com.quran.learnedplayer.ui.theme.Accent
import com.quran.learnedplayer.ui.theme.AccentGreen
import com.quran.learnedplayer.ui.theme.Bg
import com.quran.learnedplayer.ui.theme.Border
import com.quran.learnedplayer.ui.theme.Panel
import com.quran.learnedplayer.ui.theme.TextMuted
import com.quran.learnedplayer.ui.theme.TextPrimary

const val TAG_RECITATION_SCREEN = "recitation_screen"
const val TAG_PREV_AYAH = "recitation_prev_ayah"
const val TAG_NEXT_AYAH = "recitation_next_ayah"
const val TAG_RECITATION_RETRY = "recitation_retry"
const val TAG_RECITATION_REPORT = "recitation_report"

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RecitationScreen(
    initialGlobalId: Int = 1,
    playerViewModel: PlayerViewModel,
    onBack: () -> Unit,
    recitationViewModel: RecitationViewModel = viewModel(),
) {
    val context = LocalContext.current

    val (surah, ayah) = recitationViewModel.surahAndAyah.collectAsState().value
    val currentGlobalId by recitationViewModel.currentGlobalId.collectAsState()
    val words by recitationViewModel.currentWords.collectAsState()
    val evalResult by recitationViewModel.evaluationResult.collectAsState()
    val isListening by recitationViewModel.isListening.collectAsState()
    val recognizedText by recitationViewModel.recognizedText.collectAsState()
    val rmsDb by recitationViewModel.rmsDb.collectAsState()
    val speechError by recitationViewModel.speechError.collectAsState()
    val isAutoPlayingMistake by recitationViewModel.isAutoPlayingMistake.collectAsState()
    // The coach's decision, not the raw evaluation. See RecitationViewModel.confirmedMistake
    // for why the screen must not call a word wrong on its own.
    val confirmedMistake by recitationViewModel.confirmedMistake.collectAsState()
    val believedMistakes by recitationViewModel.believedMistakeIndices.collectAsState()
    val wrongAyahFeedback by recitationViewModel.wrongAyahFeedback.collectAsState()
    val detectedWrongAyahId by recitationViewModel.detectedWrongAyahId.collectAsState()
    val packProgress by recitationViewModel.packProgress.collectAsState()
    val sessionReport by recitationViewModel.sessionReport.collectAsState()

    var hasMicPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasMicPermission = granted
        if (granted) {
            recitationViewModel.startRecording()
        }
    }

    LaunchedEffect(initialGlobalId, hasMicPermission) {
        playerViewModel.pause()
        recitationViewModel.selectAyah(
            initialGlobalId,
            startListening = hasMicPermission,
        )
    }

    val surahName = SurahNames.name(surah)
    val micScale by animateFloatAsState(
        targetValue = if (isListening) 1f + (rmsDb / 15f).coerceIn(0f, 0.3f) else 1f,
        label = "micScale"
    )

    Surface(
        modifier = Modifier.fillMaxSize().testTag(TAG_RECITATION_SCREEN),
        color = Bg,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
        ) {
            // Top Navigation & Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = TextPrimary,
                    )
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "Voice Recitation Practice",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary,
                    )
                    Text(
                        text = "$surahName ($surah:$ayah) • Global Ayah #$currentGlobalId",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted,
                    )
                }

                // Moving ayah must not end the session. Nothing auto-advances any more, so these
                // buttons are the deliberate way past an ayah the coach cannot complete — and
                // dropping the microphone there would make the student re-arm it every time.
                Row {
                    IconButton(
                        onClick = { recitationViewModel.previousAyah(startListening = isListening) },
                        modifier = Modifier.testTag(TAG_PREV_AYAH),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Previous Ayah",
                            tint = AccentGreen,
                        )
                    }
                    IconButton(
                        onClick = { recitationViewModel.nextAyah(startListening = isListening) },
                        modifier = Modifier.testTag(TAG_NEXT_AYAH),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = "Next Ayah",
                            tint = AccentGreen,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (packProgress != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Panel),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            text = "Saving word clips",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary,
                        )
                        Text(
                            text = packProgress?.message ?: "Downloading reciter clips for learned ayahs…",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted,
                        )
                        LinearProgressIndicator(
                            progress = { packProgress?.fraction?.coerceIn(0f, 1f) ?: 0f },
                            modifier = Modifier.fillMaxWidth(),
                            color = AccentGreen,
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            // Score & Accuracy Bar
            evalResult?.let { result ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Panel),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column {
                            Text(
                                text = "Recitation Accuracy",
                                style = MaterialTheme.typography.labelMedium,
                                color = TextMuted,
                            )
                            Row(verticalAlignment = Alignment.Bottom) {
                                Text(
                                    text = "${result.accuracyPercentage.toInt()}%",
                                    style = MaterialTheme.typography.headlineMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = when {
                                        result.accuracyPercentage >= 90f -> AccentGreen
                                        result.accuracyPercentage >= 70f -> Color(0xFFFFA726)
                                        else -> Color(0xFFEF5350)
                                    },
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "${result.correctCount}/${result.totalContentWords} words correct",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextMuted,
                                    modifier = Modifier.padding(bottom = 4.dp),
                                )
                            }
                        }

                        val showLiveMistakeChip = confirmedMistake?.spokenWord != null
                        if (showLiveMistakeChip) {
                            Surface(
                                color = Color(0x22EF5350),
                                shape = RoundedCornerShape(16.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFEF5350)),
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Error,
                                        contentDescription = null,
                                        tint = Color(0xFFEF5350),
                                        modifier = Modifier.size(16.dp),
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "Mistake",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color(0xFFEF5350),
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                            }
                        } else if (result.isComplete) {
                            Surface(
                                color = Color(0x2245D483),
                                shape = RoundedCornerShape(16.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, AccentGreen),
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = null,
                                        tint = AccentGreen,
                                        modifier = Modifier.size(16.dp),
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "Perfect Recitation!",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = AccentGreen,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            sessionReport?.let { report ->
                Spacer(modifier = Modifier.height(12.dp))
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(TAG_RECITATION_REPORT),
                    colors = CardDefaults.cardColors(containerColor = Panel),
                    shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Border),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        val reportSurahName = report.surah?.let { SurahNames.name(it) }
                        Text(
                            text = when {
                                report.reachedEndOfSurah && reportSurahName != null -> "$reportSurahName complete"
                                reportSurahName != null -> "$reportSurahName so far"
                                else -> "Recitation report"
                            },
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary,
                        )
                        Text(
                            text = "${report.correctCount}/${report.totalContentWords} words correct " +
                                "· ${report.accuracyPercent}% · ${report.perfectAyahs}/${report.ayahs.size} ayahs perfect",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextMuted,
                        )

                        // One row per ayah, so a long run stays readable and every mistaken word
                        // is still one tap from being heard.
                        report.ayahs.forEach { outcome ->
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = "${outcome.surah}:${outcome.ayah} — " +
                                        "${outcome.correctCount}/${outcome.totalContentWords}" +
                                        if (outcome.isPerfect) " ✓" else "",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (outcome.isPerfect) AccentGreen else TextPrimary,
                                )
                                if (outcome.mistakenWordIndices.isNotEmpty()) {
                                    FlowRow(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        outcome.mistakenWordIndices.forEach { idx ->
                                            OutlinedButton(
                                                onClick = {
                                                    recitationViewModel.playWordOf(
                                                        outcome.surah, outcome.ayah, idx,
                                                    )
                                                },
                                                colors = ButtonDefaults.outlinedButtonColors(
                                                    contentColor = AccentGreen,
                                                ),
                                            ) {
                                                Icon(
                                                    imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(14.dp),
                                                )
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text("Word ${idx + 1}", maxLines = 1)
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextButton(onClick = { recitationViewModel.dismissSessionReport() }) {
                                Text("Done")
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(
                                onClick = { recitationViewModel.retryCurrentAyah() },
                                colors = ButtonDefaults.buttonColors(containerColor = Accent),
                            ) {
                                Text("Recite again")
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Main Quranic Word Display (RTL flow)
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                colors = CardDefaults.cardColors(containerColor = Panel),
                shape = RoundedCornerShape(16.dp),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                        .verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    if (words.isEmpty()) {
                        CircularProgressIndicator(color = AccentGreen)
                    } else {
                        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center,
                                verticalArrangement = Arrangement.Center,
                            ) {
                                val evaluations = evalResult?.evaluations ?: emptyList()
                                var contentWordIndex = 0

                                for (word in words) {
                                    if (word.isEnd) {
                                        // End of Ayah glyph
                                        Text(
                                            text = word.text,
                                            style = MaterialTheme.typography.headlineSmall,
                                            color = AccentGreen,
                                            modifier = Modifier.padding(6.dp),
                                        )
                                    } else {
                                        val eval = evaluations.getOrNull(contentWordIndex)
                                        contentWordIndex++
                                        // A word turns red only once the coach believes it was
                                        // wrong. An unmatched word is otherwise just unsettled —
                                        // the recogniser is often the one at fault — and painting
                                        // it red accuses the student of a mistake the coach has
                                        // explicitly declined to report.
                                        val status = when {
                                            eval?.status == WordEvaluationStatus.CORRECT ->
                                                WordEvaluationStatus.CORRECT
                                            (contentWordIndex - 1) in believedMistakes ->
                                                WordEvaluationStatus.MISTAKE
                                            else -> WordEvaluationStatus.PENDING
                                        }

                                        val (bgColor, borderColor, textColor) = when (status) {
                                            WordEvaluationStatus.CORRECT -> Triple(
                                                Color(0x3345D483),
                                                AccentGreen,
                                                TextPrimary
                                            )
                                            WordEvaluationStatus.MISTAKE -> Triple(
                                                Color(0x33EF5350),
                                                Color(0xFFEF5350),
                                                Color(0xFFFF8A80)
                                            )
                                            WordEvaluationStatus.PENDING -> Triple(
                                                Bg,
                                                Border,
                                                TextMuted
                                            )
                                        }

                                        val wordIdx = contentWordIndex - 1
                                        Box(
                                            modifier = Modifier
                                                .padding(4.dp)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(bgColor)
                                                .border(1.dp, borderColor, RoundedCornerShape(8.dp))
                                                .clickable {
                                                    recitationViewModel.playWord(wordIdx)
                                                }
                                                .padding(horizontal = 10.dp, vertical = 8.dp),
                                        ) {
                                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                Text(
                                                    text = word.text,
                                                    style = MaterialTheme.typography.titleLarge,
                                                    fontWeight = FontWeight.Medium,
                                                    color = textColor,
                                                )
                                                if (word.translation != null) {
                                                    Text(
                                                        text = word.translation,
                                                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                                                        color = TextMuted,
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            wrongAyahFeedback?.let { message ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0x1DEF5350)),
                    shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFEF5350)),
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = "Wrong ayah",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFEF5350),
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextPrimary,
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            detectedWrongAyahId?.let { targetId ->
                                val (wSurah, wAyah) = com.quran.learnedplayer.data.AyahMapping.globalToSurahAyah(targetId)
                                OutlinedButton(
                                    onClick = { recitationViewModel.selectAyah(targetId, startListening = true) },
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentGreen),
                                ) {
                                    Text("Switch to $wSurah:$wAyah")
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            Button(
                                onClick = { recitationViewModel.retryCurrentAyah() },
                                colors = ButtonDefaults.buttonColors(containerColor = Accent),
                            ) {
                                Text("Retry")
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            // Only a mistake the coach believes. `evalResult.firstMistake` is a single decode's
            // opinion and flaps between passes; showing it here bypassed corroboration and the
            // near-miss band entirely, which is how a correct recitation of 102:1 got a red card.
            val liveMistake = confirmedMistake?.takeIf { it.spokenWord != null }
            if (wrongAyahFeedback == null) liveMistake?.let { mistake ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0x1DEF5350)),
                    shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFEF5350)),
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Error,
                                contentDescription = null,
                                tint = Color(0xFFEF5350),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Mistake Detected",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFEF5350),
                            )
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        Text(
                            text = mistake.feedback ?: "Expected '${mistake.originalWord.text}'",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextPrimary,
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            OutlinedButton(
                                onClick = { recitationViewModel.playWord(mistake.wordIndex) },
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentGreen),
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Listen Reciter")
                            }

                            Spacer(modifier = Modifier.width(8.dp))

                            Button(
                                onClick = { recitationViewModel.retryCurrentAyah() },
                                modifier = Modifier.testTag(TAG_RECITATION_RETRY),
                                colors = ButtonDefaults.buttonColors(containerColor = Accent),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Retry")
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            // Speech Error Message
            speechError?.let { err ->
                Text(
                    text = err,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFEF5350),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                )
            }

            // Live recognized transcript snippet
            if (recognizedText.isNotBlank()) {
                Text(
                    text = "Recognized: \"$recognizedText\"",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                )
            }

            // Recording Controls Footer
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                IconButton(
                    onClick = {
                        if (hasMicPermission) {
                            recitationViewModel.toggleRecording()
                        } else {
                            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                    modifier = Modifier
                        .scale(micScale)
                        .size(68.dp)
                        .clip(CircleShape)
                        .background(if (isListening) Color(0xFFEF5350) else AccentGreen),
                ) {
                    Icon(
                        imageVector = if (isListening) Icons.Default.Mic else Icons.Default.MicOff,
                        contentDescription = if (isListening) "Stop Recording" else "Start Reciting",
                        tint = Color.White,
                        modifier = Modifier.size(32.dp),
                    )
                }
            }

            Text(
                text = when {
                    isAutoPlayingMistake -> "Playing correct word… keep going after"
                    isListening -> "Listening to your recitation..."
                    else -> "Tap microphone & recite the Ayah"
                },
                style = MaterialTheme.typography.labelMedium,
                color = when {
                    isAutoPlayingMistake -> Color(0xFFEF5350)
                    isListening -> AccentGreen
                    else -> TextMuted
                },
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
        }
    }
}
