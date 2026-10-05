package com.quran.learnedplayer.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.quran.learnedplayer.data.AyahTrack
import com.quran.learnedplayer.data.SurahNames
import com.quran.learnedplayer.ui.theme.AccentGreen
import com.quran.learnedplayer.ui.theme.TextMuted
import com.quran.learnedplayer.ui.theme.TextPrimary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SurahJumpSheet(
    availableSurahs: List<Int>,
    tracks: List<AyahTrack>,
    currentSurah: Int?,
    onDismiss: () -> Unit,
    onSurahSelected: (Int) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var numberText by remember(currentSurah) { mutableStateOf(currentSurah?.toString() ?: "") }
    var errorText by remember { mutableStateOf<String?>(null) }

    fun tryGoToNumber() {
        val n = numberText.trim().toIntOrNull()
        when {
            n == null -> errorText = "Enter a surah number"
            n !in 1..114 -> errorText = "Use a number from 1 to 114"
            n !in availableSurahs -> errorText = "Surah $n is not in your learned playlist"
            else -> {
                onSurahSelected(n)
                onDismiss()
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Go to surah",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
            )
            Text(
                text = "Type a surah number or pick from your learned surahs below.",
                color = TextMuted,
                style = MaterialTheme.typography.bodySmall,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = numberText,
                    onValueChange = {
                        numberText = it.filter { ch -> ch.isDigit() }.take(3)
                        errorText = null
                    },
                    label = { Text("Surah #") },
                    placeholder = { Text("e.g. 114") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Button(onClick = { tryGoToNumber() }) {
                    Text("Go")
                }
            }
            errorText?.let {
                Text(text = it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Text(
                text = "Learned surahs (${availableSurahs.size})",
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary,
            )
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(availableSurahs, key = { it }) { surah ->
                    val active = surah == currentSurah
                    val ayahCount = tracks.count { it.surah == surah }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onSurahSelected(surah)
                                onDismiss()
                            }
                            .padding(horizontal = 8.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = SurahNames.label(surah),
                            color = if (active) AccentGreen else TextPrimary,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                        )
                        Text(
                            text = "$ayahCount ayahs",
                            color = TextMuted,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SurahJumpButton(
    currentSurah: Int?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
    ) {
        Text(
            text = currentSurah?.let { SurahNames.title(it) } ?: "Choose surah…",
            maxLines = 1,
        )
    }
}
