package com.quran.learnedplayer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.quran.learnedplayer.data.AyahSection
import com.quran.learnedplayer.data.AyahSections
import com.quran.learnedplayer.data.SurahNames
import com.quran.learnedplayer.ui.common.SidePanel
import com.quran.learnedplayer.ui.common.SidePanelEdge
import com.quran.learnedplayer.ui.i18n.tx
import com.quran.learnedplayer.ui.theme.AccentGreen
import com.quran.learnedplayer.ui.theme.Border
import com.quran.learnedplayer.ui.theme.Panel
import com.quran.learnedplayer.ui.theme.TextMuted
import com.quran.learnedplayer.ui.theme.TextPrimary
import com.quran.learnedplayer.ui.theme.quranArabicStyle

const val TAG_SECTIONS_SCREEN = "sections_screen"

fun sectionRowTag(key: String) = "section_row_$key"
fun sectionPeekTag(key: String) = "section_peek_$key"
fun sectionGoToTag(key: String) = "section_goto_$key"

/** The peeked word's size (sp) — a prompt, not a reading line. */
private const val PEEK_WORD_SIZE = 22f

/**
 * Revise by section: a full screen of every unbroken run of ayahs the user has marked, surah by
 * surah and numbered 1, 2, 3… within each, named by its ayah numbers alone so it can be recited
 * from memory. A row peeks at its first word when the numbers are not prompt enough, and Go to
 * sends the player to the section's first ayah.
 *
 * It changes nothing about playback or the reader behind it — the queue stays per ayah. The
 * player only offers it in [com.quran.learnedplayer.player.PlaybackMode.REVISE], the mode whose
 * queue these sections describe.
 *
 * Web port: the sections screen in `web-app/static/js/views/player.js`.
 */
@Composable
fun SectionsScreen(
    visible: Boolean,
    learnedIds: Set<Int>,
    currentGlobalId: Int?,
    firstWordOf: suspend (surah: Int, ayah: Int) -> String?,
    onGoTo: (globalId: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val bySurah = remember(learnedIds) { AyahSections.bySurah(learnedIds) }
    val total = remember(bySurah) { bySurah.values.sumOf { it.size } }
    val currentSurah = remember(currentGlobalId, learnedIds) {
        currentGlobalId?.let { AyahSections.at(learnedIds, it)?.surah }
    }
    // A long list stays manageable: only the surah being revised starts open.
    var expanded by remember(currentSurah) {
        mutableStateOf(setOfNotNull(currentSurah ?: bySurah.keys.firstOrNull()))
    }
    // Open on the section being revised rather than at the top: with a few hundred ayahs marked
    // the surah in hand can be a long way down. Keyed on opening only, so expanding another
    // surah afterwards doesn't yank the list back.
    val listState = rememberLazyListState()
    LaunchedEffect(visible, currentSurah) {
        if (!visible || currentSurah == null) return@LaunchedEffect
        var index = 0
        for ((surah, sections) in bySurah) {
            if (surah == currentSurah) break
            index += 1 + if (surah in expanded) sections.size else 0
        }
        listState.scrollToItem(index)
    }

    // Full width, so it is a screen rather than a panel at one edge.
    SidePanel(visible = visible, edge = SidePanelEdge.END, onDismiss = onDismiss, widthFraction = 1f) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp).testTag(TAG_SECTIONS_SCREEN)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = tx("Sections"),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary,
                    )
                    if (total > 0) {
                        Text(
                            text = tx("{0} surahs · {1} sections", bySurah.size, total),
                            color = TextMuted,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = tx("Close"), tint = TextMuted)
                }
            }
            if (total == 0) {
                Text(
                    text = tx("No ayahs marked yet."),
                    color = TextMuted,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
                return@SidePanel
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(top = 8.dp),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                bySurah.forEach { (surah, sections) ->
                    item(key = "h-$surah") {
                        SurahSectionsHeader(
                            surah = surah,
                            count = sections.size,
                            open = surah in expanded,
                            active = surah == currentSurah,
                            onClick = {
                                expanded = if (surah in expanded) expanded - surah else expanded + surah
                            },
                        )
                    }
                    if (surah !in expanded) return@forEach
                    items(sections.size, key = { "s-$surah-${sections[it].from}" }) { i ->
                        val section = sections[i]
                        SectionRow(
                            section = section,
                            active = currentGlobalId != null &&
                                currentGlobalId in section.firstGlobalId..section.lastGlobalId,
                            firstWordOf = firstWordOf,
                            onGoTo = { onGoTo(section.firstGlobalId) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SurahSectionsHeader(
    surah: Int,
    count: Int,
    open: Boolean,
    active: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .background(if (active) Border else Panel)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (open) "▾" else "▸",
                color = if (active) AccentGreen else TextMuted,
                modifier = Modifier.padding(end = 8.dp),
            )
            Text(
                text = SurahNames.title(surah),
                color = if (active) AccentGreen else TextPrimary,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Text(sectionCountLabel(count), color = TextMuted, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun SectionRow(
    section: AyahSection,
    active: Boolean,
    firstWordOf: suspend (surah: Int, ayah: Int) -> String?,
    onGoTo: () -> Unit,
) {
    val key = AyahSections.key(section).orEmpty()
    var shown by remember(key) { mutableStateOf(false) }
    var word by remember(key) { mutableStateOf<String?>(null) }
    // The word is read only once it is asked for, and only once.
    LaunchedEffect(key, shown) { if (shown && word == null) word = firstWordOf(section.surah, section.from) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)
            .testTag(sectionRowTag(key)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Its place in the surah: section 1, 2, 3…
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(if (active) AccentGreen else Border),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = section.index.toString(),
                color = if (active) Panel else TextMuted,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Column(modifier = Modifier.weight(1f).padding(start = 10.dp)) {
            Text(
                // A section of one ayah is named as that ayah, the rule AyahRef's refs follow.
                text = if (section.from == section.to) tx("Ayah {0}", section.from)
                else tx("Ayahs {0}–{1}", section.from, section.to),
                color = if (active) AccentGreen else TextPrimary,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodyMedium,
            )
            if (section.count > 1) {
                Text(
                    text = tx("{0} ayahs", section.count),
                    color = TextMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (shown) {
                Text(
                    text = word.orEmpty(),
                    color = AccentGreen,
                    style = quranArabicStyle(PEEK_WORD_SIZE),
                )
            }
        }
        IconButton(
            onClick = { shown = !shown },
            modifier = Modifier.size(40.dp).testTag(sectionPeekTag(key)),
        ) {
            Icon(
                imageVector = Icons.Filled.TextFields,
                contentDescription = if (shown) tx("Hide the first word") else tx("Show the first word"),
                tint = if (shown) AccentGreen else TextMuted,
            )
        }
        TextButton(onClick = onGoTo, modifier = Modifier.testTag(sectionGoToTag(key))) {
            Text(tx("Go to"), color = AccentGreen)
        }
    }
}
