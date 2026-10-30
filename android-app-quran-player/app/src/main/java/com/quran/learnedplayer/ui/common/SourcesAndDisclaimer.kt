package com.quran.learnedplayer.ui.common

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

    val sources = listOf(
        Source("Qur'an text (Uthmani script) and English word-by-word meanings", "Quran.com · quran.com"),
        Source(
            "Ayah recitations by ${Reciter.entries.joinToString { it.displayName }}",
            "EveryAyah · everyayah.com",
        ),
        Source("Single-word recitations", "Quran.com · audio.qurancdn.com"),
        Source("English translation of whole ayahs (player and shared videos)", "Saheeh International · via tanzil.net"),
        Source("Nature photos for backgrounds (CC0)", "Wikimedia Commons · commons.wikimedia.org"),
        Source("Word timings used to highlight each word", "Quranic Universal Library (QUL) by Tarteel · qul.tarteel.ai"),
        Source("Arabic font Scheherazade New", "SIL International · software.sil.org"),
    )

    const val INTRO =
        "Learned Ayahs is a free tool that makes it easier to revise what you have memorised. " +
            "It does not author any of the Qur'an text, meanings or recitations it shows and plays: " +
            "it gives you access to these public online sources."

    val points = listOf(
        "If you notice a mistake in the text, a meaning or a recitation, please report it to the " +
            "source it comes from, listed above. Problems with the app itself can be reported at " +
            "github.com/aq106ai/learned-ayahs/issues.",
        "Word meanings are a study aid, not a translation of the Qur'an. Word highlighting and " +
            "Recite & review are approximate.",
        "Always confirm your memorisation and recitation with a qualified teacher.",
        "The app is provided free and \"as is\", without warranty of any kind. To the extent the law " +
            "allows, its developers are not responsible for the content of these sources or for any " +
            "loss arising from using the app.",
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
        title = { Text("Sources and disclaimer", style = MaterialTheme.typography.titleMedium, color = TextPrimary) },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                SourcesAndDisclaimerText()
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close", color = AccentGreen) }
        },
    )
}
