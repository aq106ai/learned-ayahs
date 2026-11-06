package com.quran.learnedplayer.ui.common

import com.quran.learnedplayer.ui.i18n.tx
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.quran.learnedplayer.data.Reciter
import com.quran.learnedplayer.ui.theme.AccentGreen
import com.quran.learnedplayer.ui.theme.Panel
import com.quran.learnedplayer.ui.theme.TextMuted
import com.quran.learnedplayer.ui.theme.TextPrimary

const val TAG_SOURCES_DIALOG = "sources_dialog"

/**
 * Where every piece of Qur'an content in the app comes from, and what the app does and does not
 * answer for. The web app shows the same words (`web-app/static/js/views/terms.js`); keep the two
 * in step. Always in Settings, and a tap away from the welcome's last page. Until 1.12.0 everyone
 * had to tap "I agree" before using the app; the licences ask for the sources to be credited,
 * not accepted, and a gate in front of a free Qur'an app only got in the way.
 */
object SourcesAndDisclaimer {
    data class Source(val what: String, val from: String)

    // Getters, not values: an object's vals are computed once, and the interface language can change.
    val sources get() = listOf(
        Source(tx("Qur'an text (Uthmani script) and English word-by-word meanings"), tx("Quran.com · quran.com")),
        Source(
            tx("Ayah recitations by {0}", Reciter.entries.joinToString { it.displayName }),
            tx("EveryAyah · everyayah.com"),
        ),
        Source(tx("Single-word recitations"), tx("Quran.com · audio.qurancdn.com")),
        Source(tx("English translation of whole ayahs (player and shared videos)"), tx("Saheeh International · via tanzil.net")),
        Source(
            tx("Other translations, word meanings in other languages, and tafsir (downloaded when you choose them, each named where it is shown)"),
            tx("Quran.com · quran.com"),
        ),
        Source(tx("Nature photos for backgrounds (CC0)"), tx("Wikimedia Commons · commons.wikimedia.org")),
        Source(tx("Word timings used to highlight each word"), tx("Quranic Universal Library (QUL) by Tarteel · qul.tarteel.ai")),
        Source(tx("Arabic font Scheherazade New"), tx("SIL International · software.sil.org")),
        Source(tx("Arabic font Amiri Quran (the \"Amiri Quran\" mushaf style)"), tx("The Amiri Project, Khaled Hosny · github.com/aliftype/amiri")),
    )

    val INTRO get() =
        tx("Learned Ayahs is a free tool that makes it easier to revise what you have memorised. It does not author any of the Qur'an text, meanings or recitations it shows and plays: it gives you access to these public online sources.")

    val points get() = listOf(
        tx("If you notice a mistake in the text, a meaning or a recitation, please report it to the source it comes from, listed above. Problems with the app itself can be reported at github.com/aq106ai/learned-ayahs/issues."),
        tx("Word meanings are a study aid, not a translation of the Qur'an. Word highlighting and Recite & review are approximate."),
        tx("Always confirm your memorisation and recitation with a qualified teacher."),
        tx("The app is provided free and \"as is\", without warranty of any kind. To the extent the law allows, its developers are not responsible for the content of these sources or for any loss arising from using the app."),
    )
}

/** The sources and the disclaimer, as body text for the dialog and for Settings → About. */
@Composable
fun SourcesAndDisclaimerText() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(SourcesAndDisclaimer.INTRO, style = MaterialTheme.typography.bodySmall, color = TextPrimary)
        SourcesAndDisclaimer.sources.forEach { source ->
            Column {
                Text(source.what, style = MaterialTheme.typography.bodySmall, color = TextPrimary, fontWeight = FontWeight.SemiBold)
                Text(source.from, style = MaterialTheme.typography.bodySmall, color = AccentGreen)
            }
        }
        SourcesAndDisclaimer.points.forEach { point ->
            Text("• $point", style = MaterialTheme.typography.bodySmall, color = TextMuted)
        }
    }
}

/** The sources and the disclaimer on their own, from the welcome's last page. */
@Composable
fun SourcesDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Panel,
        modifier = Modifier.testTag(TAG_SOURCES_DIALOG),
        title = { Text(tx("Sources and disclaimer"), style = MaterialTheme.typography.titleMedium, color = TextPrimary) },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                SourcesAndDisclaimerText()
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(tx("Close"), color = AccentGreen) }
        },
    )
}
