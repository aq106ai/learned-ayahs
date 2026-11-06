package com.quran.learnedplayer.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.quran.learnedplayer.data.ContentCatalog
import com.quran.learnedplayer.data.ContentRepository
import com.quran.learnedplayer.data.SurahNames
import com.quran.learnedplayer.data.TafsirText
import com.quran.learnedplayer.player.PlayerSettings
import com.quran.learnedplayer.ui.theme.AccentGreen
import com.quran.learnedplayer.ui.theme.Panel
import com.quran.learnedplayer.ui.theme.TextMuted
import com.quran.learnedplayer.ui.theme.TextPrimary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

const val TAG_TAFSIR_SHEET = "tafsir_sheet"
const val TAG_TAFSIR_LIST = "tafsir_list"
const val TAG_TAFSIR_SOURCE = "tafsir_source"
const val TAG_TAFSIR_RANGE = "tafsir_range"
const val TAG_TAFSIR_TEXT = "tafsir_text"
const val TAG_TAFSIR_NONE = "tafsir_none"
const val TAG_TAFSIR_ERROR = "tafsir_error"
const val TAG_TAFSIR_RETRY = "tafsir_retry"
const val TAG_TAFSIR_CHANGE = "tafsir_change"
fun tafsirChoiceTag(id: String) = "tafsir_choice_$id"

private sealed interface Loaded {
    data object Loading : Loaded
    data class Done(val tafsir: ContentRepository.Tafsir) : Loaded
    data class Failed(val message: String) : Loaded
}

/**
 * Tafsir on the current ayah, from the player's Tafsir button. The web app's `views/tafsir.js`
 * is the same. The chosen edition ([PlayerSettings.tafsirEdition], or picked here) is fetched once
 * per ayah and kept ([ContentRepository.ayahTafsir]); it shows with its name, author and source,
 * and the ayahs the entry covers. Commentary never appears in the reader itself.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TafsirSheet(surah: Int, ayah: Int, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val catalog = remember { ContentCatalog.get(context) }
    val tafsirId by PlayerSettings.tafsirEditionFlow.collectAsState()
    val translationId by PlayerSettings.translationEditionFlow.collectAsState()
    val edition = catalog.tafsir(tafsirId)
    var choosing by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    var loaded by remember(edition?.id, surah, ayah) { mutableStateOf<Loaded>(Loaded.Loading) }

    LaunchedEffect(edition?.id, surah, ayah, attempt) {
        if (edition == null) return@LaunchedEffect
        loaded = Loaded.Loading
        loaded = withContext(Dispatchers.IO) {
            runCatching { ContentRepository.ayahTafsir(surah, ayah, edition.id) }
                .fold({ Loaded.Done(it) }, { Loaded.Failed(it.message ?: "The download failed.") })
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Panel,
        modifier = Modifier.testTag(TAG_TAFSIR_SHEET),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).testTag(TAG_TAFSIR_LIST),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text(
                    "Tafsir · ${SurahNames.name(surah)} $surah:$ayah",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                )
            }
            if (edition == null || choosing) {
                item {
                    Text(
                        if (edition == null) {
                            "Choose a tafsir to read commentary on this ayah. It downloads from Quran.com and is kept for offline use. You can change it later in Settings."
                        } else {
                            "Choose a tafsir:"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextMuted,
                        modifier = Modifier.testTag(TAG_TAFSIR_NONE),
                    )
                }
                // Inline, not a dialog over the sheet: the reader's language first.
                items(catalog.tafsirsFor(catalog.translation(translationId).language), key = { it.id }) { t ->
                    Text(
                        "${catalog.languageName(t.language)} · ${t.label}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (t.id == edition?.id) AccentGreen else TextPrimary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                PlayerSettings.tafsirEdition = t.id
                                choosing = false
                            }
                            .padding(vertical = 8.dp)
                            .testTag(tafsirChoiceTag(t.id)),
                    )
                }
            } else {
                item {
                    Column {
                        Text(
                            "${edition.name} by ${edition.author}, from ${edition.source}.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted,
                            modifier = Modifier.testTag(TAG_TAFSIR_SOURCE),
                        )
                        TextButton(onClick = { choosing = true }, modifier = Modifier.testTag(TAG_TAFSIR_CHANGE)) {
                            Text("Change tafsir", color = AccentGreen)
                        }
                    }
                }
                when (val state = loaded) {
                    Loaded.Loading -> item { Text("Loading ${edition.name}…", color = TextMuted) }
                    is Loaded.Failed -> item {
                        Column {
                            Text(
                                "${edition.name} could not be loaded. ${state.message}",
                                color = TextPrimary,
                                modifier = Modifier.testTag(TAG_TAFSIR_ERROR),
                            )
                            TextButton(onClick = { attempt++ }, modifier = Modifier.testTag(TAG_TAFSIR_RETRY)) {
                                Text("Try again", color = AccentGreen)
                            }
                        }
                    }
                    is Loaded.Done -> {
                        val range = TafsirText.coveredRange(state.tafsir.verses)
                        if (range.isNotEmpty() && range != "$surah:$ayah") {
                            item {
                                Text(
                                    "This commentary covers $range together.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextPrimary,
                                    modifier = Modifier.testTag(TAG_TAFSIR_RANGE),
                                )
                            }
                        }
                        val rtl = catalog.isRtl(edition.language)
                        if (state.tafsir.blocks.isEmpty()) {
                            item { Text("This tafsir has no commentary on this ayah.", color = TextMuted) }
                        }
                        items(state.tafsir.blocks) { block ->
                            CompositionLocalProvider(LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                                TafsirBlock(block, Modifier.testTag(TAG_TAFSIR_TEXT))
                            }
                        }
                    }
                }
            }
            item { Text("", modifier = Modifier.padding(bottom = 24.dp)) }
        }
    }
}

@Composable
private fun TafsirBlock(block: TafsirText.Block, modifier: Modifier = Modifier) {
    val text = buildAnnotatedString {
        if (block.kind == TafsirText.Kind.ITEM) append("• ")
        block.spans.forEach { span ->
            withStyle(
                SpanStyle(
                    fontWeight = if (span.bold) FontWeight.Bold else null,
                    fontStyle = if (span.italic) FontStyle.Italic else null,
                ),
            ) { append(span.text) }
        }
    }
    val base = when (block.kind) {
        TafsirText.Kind.HEADING -> MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
        else -> MaterialTheme.typography.bodyMedium
    }
    Text(
        text,
        style = base.copy(textDirection = TextDirection.Content, lineHeight = base.fontSize * 1.6f),
        color = if (block.kind == TafsirText.Kind.QUOTE) TextMuted else TextPrimary,
        modifier = modifier.fillMaxWidth().padding(start = if (block.kind == TafsirText.Kind.QUOTE) 12.dp else 0.dp),
    )
}
