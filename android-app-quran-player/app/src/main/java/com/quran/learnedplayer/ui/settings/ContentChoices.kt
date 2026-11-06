package com.quran.learnedplayer.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Surface
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.layout.height
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import com.quran.learnedplayer.data.ContentCatalog
import com.quran.learnedplayer.data.ContentRepository
import com.quran.learnedplayer.data.Reciter
import com.quran.learnedplayer.data.TranslationStyle
import com.quran.learnedplayer.player.PlayerSettings
import com.quran.learnedplayer.ui.theme.AccentGreen
import com.quran.learnedplayer.ui.theme.Border
import com.quran.learnedplayer.ui.theme.Panel
import com.quran.learnedplayer.ui.theme.TextMuted
import com.quran.learnedplayer.ui.theme.TextPrimary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

const val TAG_CONTENT_LANGUAGE = "content_translation_language"
const val TAG_CONTENT_EDITION = "content_translation"
const val TAG_CONTENT_WORD_LANGUAGE = "content_word_language"
const val TAG_CONTENT_TAFSIR = "content_tafsir"
const val TAG_CONTENT_STATUS = "content_status"
const val TAG_CHOICE_SEARCH = "choice_search"
const val TAG_CHOICE_LIST = "choice_list"
fun contentStyleTag(style: TranslationStyle) = "content_style_${style.name}"
fun contentReciterTag(reciter: Reciter) = "content_reciter_${reciter.name}"
fun choiceTag(id: String) = "choice_${id.ifEmpty { "none" }}"

private enum class Picking { LANGUAGE, EDITION, WORDS, TAFSIR }

/**
 * A translation language was picked: its first edition (bundled, then recommended), and the word
 * meanings follow the language where Quran.com has them in it. The web's contentChoices.js does
 * the same.
 */
internal fun chooseTranslationLanguage(catalog: ContentCatalog, code: String) {
    val first = catalog.translationsIn(code).firstOrNull() ?: return
    if (catalog.wordMeanings.any { it.id == code }) PlayerSettings.wordMeaningLanguage = code
    PlayerSettings.translationEdition = first.id
}

/** The chosen edition's name, for labels ("Saheeh International" until the catalog is read). */
@Composable
fun rememberEditionName(): String {
    val id by PlayerSettings.translationEditionFlow.collectAsState()
    val context = LocalContext.current
    return remember(id) { ContentCatalog.get(context).translation(id).name }
}

/** The chosen translation's language, for chips like Arabic / English. */
@Composable
fun rememberTranslationLanguageName(): String {
    val id by PlayerSettings.translationEditionFlow.collectAsState()
    val context = LocalContext.current
    return remember(id) {
        val catalog = ContentCatalog.get(context)
        catalog.languageName(catalog.translation(id).language)
    }
}

/**
 * What the app shows beside the Arabic, chosen in the intro and in Settings: the translation
 * (language, then edition), optionally the style, the word-meaning language, an optional tafsir,
 * and — when [onReciter] is given — the reciter. The web app's `views/contentChoices.js` is the
 * same block. Every choice is saved as a catalog id.
 */
@Composable
fun ContentChoices(
    includeStyle: Boolean,
    onReciter: ((Reciter) -> Unit)? = null,
) {
    val context = LocalContext.current
    val catalog = remember { ContentCatalog.get(context) }
    val editionId by PlayerSettings.translationEditionFlow.collectAsState()
    val wordsId by PlayerSettings.wordMeaningLanguageFlow.collectAsState()
    val tafsirId by PlayerSettings.tafsirEditionFlow.collectAsState()
    val showWords by PlayerSettings.showWordTranslationsFlow.collectAsState()
    val showAyah by PlayerSettings.showAyahTranslationFlow.collectAsState()
    val reciter by PlayerSettings.reciterFlow.collectAsState()
    val edition = catalog.translation(editionId)
    val style = TranslationStyle.of(showWords, showAyah)
    val words = catalog.wordMeanings(wordsId)
    val tafsir = catalog.tafsir(tafsirId)
    var picking by remember { mutableStateOf<Picking?>(null) }
    var status by remember { mutableStateOf("") }

    LaunchedEffect(edition.id) {
        if (edition.bundled) {
            status = "Included with the app — works offline."
            return@LaunchedEffect
        }
        status = "Downloading ${edition.name}…"
        val result = withContext(Dispatchers.IO) { ContentRepository.prepare(edition.id) }
        status = result.fold(
            onSuccess = { "${edition.name} is saved for offline use." },
            onFailure = { "${it.message ?: "The download failed."} Saheeh International is shown until it downloads." },
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ChoiceRow("Translation language", catalog.languageName(edition.language, native = true), TAG_CONTENT_LANGUAGE) { picking = Picking.LANGUAGE }
        ChoiceRow("Translation", edition.label, TAG_CONTENT_EDITION) { picking = Picking.EDITION }
        Text(status, style = MaterialTheme.typography.bodySmall, color = TextMuted, modifier = Modifier.testTag(TAG_CONTENT_STATUS))
        if (includeStyle) {
            Text("Show", style = MaterialTheme.typography.labelMedium, color = TextMuted)
            Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TranslationStyle.entries.forEach { option ->
                    Chip(option.label, style == option, Modifier.testTag(contentStyleTag(option))) {
                        PlayerSettings.showWordTranslations = option.showWordTranslations
                        PlayerSettings.showAyahTranslation = option.showAyahTranslation
                    }
                }
            }
        }
        if (style != TranslationStyle.AYAH) {
            ChoiceRow("Word meanings in", catalog.languageName(words.language, native = true), TAG_CONTENT_WORD_LANGUAGE) { picking = Picking.WORDS }
        }
        ChoiceRow("Tafsir (optional)", tafsir?.let { "${catalog.languageName(it.language)} · ${it.label}" } ?: "None", TAG_CONTENT_TAFSIR) { picking = Picking.TAFSIR }
        Text(
            tafsir?.let { "${it.name} by ${it.author}, from ${it.source}. Opens from the Tafsir button in the player." }
                ?: "Commentary on the current ayah, from the Tafsir button in the player.",
            style = MaterialTheme.typography.bodySmall,
            color = TextMuted,
        )
        if (onReciter != null) {
            Text("Reciter", style = MaterialTheme.typography.labelMedium, color = TextMuted)
            Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Reciter.entries.forEach { r ->
                    Chip(r.displayName, reciter == r, Modifier.testTag(contentReciterTag(r))) { onReciter(r) }
                }
            }
            Text(
                "Every reciter here has checked word timings, so each word lights up as it is recited.",
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
            )
        }
    }

    when (picking) {
        Picking.LANGUAGE -> ChoiceDialog(
            title = "Translation language",
            choices = catalog.translationLanguages().map { it to catalog.languageName(it, native = true) },
            selected = edition.language,
            onDismiss = { picking = null },
        ) { code ->
            chooseTranslationLanguage(catalog, code)
            picking = null
        }
        Picking.EDITION -> ChoiceDialog(
            title = catalog.languageName(edition.language),
            choices = catalog.translationsIn(edition.language).map { it.id to it.label + if (it.bundled) " (offline)" else "" },
            selected = edition.id,
            rtl = catalog.isRtl(edition.language),
            onDismiss = { picking = null },
        ) { id ->
            PlayerSettings.translationEdition = id
            picking = null
        }
        Picking.WORDS -> ChoiceDialog(
            title = "Word meanings in",
            choices = catalog.wordMeanings.map { it.id to catalog.languageName(it.language, native = true) },
            selected = words.id,
            onDismiss = { picking = null },
        ) { id ->
            PlayerSettings.wordMeaningLanguage = id
            picking = null
        }
        Picking.TAFSIR -> ChoiceDialog(
            title = "Tafsir",
            choices = listOf("" to "None") + catalog.tafsirsFor(edition.language).map { it.id to "${catalog.languageName(it.language)} · ${it.label}" },
            selected = tafsir?.id.orEmpty(),
            onDismiss = { picking = null },
        ) { id ->
            PlayerSettings.tafsirEdition = id
            picking = null
        }
        null -> Unit
    }
}

@Composable
private fun ChoiceRow(title: String, value: String, tag: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp)
            .testTag(tag),
    ) {
        Text(title, style = MaterialTheme.typography.labelMedium, color = TextMuted)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        modifier = modifier,
        colors = FilterChipDefaults.filterChipColors(
            containerColor = Border,
            labelColor = TextMuted,
            selectedContainerColor = AccentGreen,
            selectedLabelColor = Panel,
        ),
    )
}

/**
 * A list to pick one from, searchable when it is long (the edition and language lists are). A
 * plain [Dialog] rather than an AlertDialog: a lazy list inside AlertDialog's content slot never
 * settled (ContentChoicesTest hung on "Compose did not get idle").
 */
@Composable
private fun ChoiceDialog(
    title: String,
    choices: List<Pair<String, String>>,
    selected: String,
    rtl: Boolean = false,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val q = query.trim().lowercase()
    val shown = if (q.isEmpty()) choices else choices.filter { it.second.lowercase().contains(q) }
    Dialog(onDismissRequest = onDismiss) {
        Surface(color = Panel, shape = RoundedCornerShape(24.dp)) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = TextPrimary, modifier = Modifier.padding(bottom = 12.dp))
                if (choices.size > 8) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        placeholder = { Text("Search") },
                        modifier = Modifier.fillMaxWidth().testTag(TAG_CHOICE_SEARCH),
                    )
                }
                LazyColumn(modifier = Modifier.fillMaxWidth().height(380.dp).testTag(TAG_CHOICE_LIST)) {
                    items(shown, key = { it.first }) { (id, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(id) }
                                .padding(vertical = 10.dp)
                                .testTag(choiceTag(id)),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                label,
                                style = MaterialTheme.typography.bodyMedium.copy(textDirection = if (rtl) TextDirection.Rtl else TextDirection.Content),
                                color = TextPrimary,
                                modifier = Modifier.weight(1f),
                            )
                            if (id == selected) Icon(Icons.Default.Check, contentDescription = "Chosen", tint = AccentGreen)
                        }
                    }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("Close", color = AccentGreen) }
            }
        }
    }
}
