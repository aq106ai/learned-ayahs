package com.quran.learnedplayer.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.quran.learnedplayer.data.AyahMapping
import com.quran.learnedplayer.data.AyahRef
import com.quran.learnedplayer.data.LearnedAyahsExport
import com.quran.learnedplayer.player.PlayerViewModel
import com.quran.learnedplayer.ui.theme.AccentGreen
import com.quran.learnedplayer.ui.theme.Bg
import com.quran.learnedplayer.ui.theme.Panel
import com.quran.learnedplayer.ui.theme.TextMuted

const val TAG_DESCRIPTION_FIELD = "description_field"
const val TAG_DESCRIPTION_PREVIEW = "description_preview"
const val TAG_CURRENT_SELECTION = "description_current_selection"
const val TAG_DESCRIPTION_APPLY = "description_apply"
const val TAG_COPY_LLM_PROMPT = "copy_llm_prompt"

/**
 * Bulk-add ayahs by typing references, or by pasting what an LLM wrote.
 *
 * The same [AyahRef] grammar backs the box and the export format, so an LLM reply can be pasted
 * in whole — the parser ignores the surrounding JSON punctuation.
 */
@Composable
fun AddByDescriptionScreen(
    viewModel: PlayerViewModel,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val learnedIds by viewModel.learnedIds.collectAsState()
    var input by rememberSaveable { mutableStateOf("") }
    var applied by remember { mutableStateOf<Int?>(null) }

    val parsed = remember(input) { AyahRef.parse(input) }
    // Compressed back into refs (2:255, 36:1-83, 112) — the same grammar the box accepts, so the
    // list reads as something you could have typed.
    val currentRefs = remember(learnedIds) { AyahRef.format(learnedIds) }
    val currentSurahCount = remember(learnedIds) {
        learnedIds.map { AyahMapping.globalToSurahAyah(it).first }.distinct().size
    }
    val newCount = remember(parsed, learnedIds) { (parsed.ids - learnedIds).size }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg)
            .verticalScroll(rememberScrollState()),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = TextMuted)
            }
            Text("Add by description", style = MaterialTheme.typography.titleLarge)
        }

        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            // What is already marked, in the same grammar the box accepts. Without this the screen
            // asks you to describe your selection while hiding the selection — you cannot tell
            // whether something is already there, and the "new" count below has nothing to be read
            // against. It doubles as a worked example of the syntax, using your own data.
            if (currentRefs.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Panel)
                        .padding(12.dp)
                        .semantics(mergeDescendants = true) {}
                        .testTag(TAG_CURRENT_SELECTION),
                ) {
                    Text(
                        text = "Already marked · ${learnedIds.size} ayahs " +
                            "in ${currentSurahCount} surah${if (currentSurahCount == 1) "" else "s"}",
                        style = MaterialTheme.typography.labelLarge,
                        color = AccentGreen,
                    )
                    Text(
                        text = currentRefs.joinToString(", "),
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }

            Text(
                text = "Type what you've learned — for example:\n" +
                    "36:1-83,  2:255,  Al-Baqarah 1-5,  112,  78-114",
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
                modifier = Modifier.padding(top = 8.dp),
            )

            OutlinedTextField(
                value = input,
                onValueChange = { input = it; applied = null },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 140.dp)
                    .padding(top = 12.dp)
                    .testTag(TAG_DESCRIPTION_FIELD),
                placeholder = { Text("36:1-83, Al-Mulk, 78-114") },
                label = { Text("Ayah references, or JSON from an LLM") },
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Panel)
                    .padding(12.dp)
                    // Merge so the summary and the "Ignored" line read as one node.
                    .semantics(mergeDescendants = true) {}
                    .testTag(TAG_DESCRIPTION_PREVIEW),
            ) {
                Text(
                    text = applied?.let { "Added $it ayahs." }
                        ?: when {
                            input.isBlank() -> "Nothing entered yet."
                            parsed.ids.isEmpty() -> "Couldn't read any ayahs from that."
                            else -> "Will mark ${parsed.ids.size} ayahs · $newCount new."
                        },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (applied != null || parsed.ids.isNotEmpty()) AccentGreen else TextMuted,
                )
                if (parsed.unparsed.isNotEmpty()) {
                    Text(
                        text = "Ignored: ${parsed.unparsed.joinToString(", ")}",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = {
                        viewModel.addLearned(parsed.ids)
                        applied = parsed.ids.size
                        input = ""
                    },
                    enabled = parsed.ids.isNotEmpty(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AccentGreen,
                        contentColor = Panel,
                    ),
                    modifier = Modifier.testTag(TAG_DESCRIPTION_APPLY),
                ) {
                    Text("Add")
                }
                OutlinedButton(
                    onClick = { copyToClipboard(context, llmPrompt()) },
                    modifier = Modifier.testTag(TAG_COPY_LLM_PROMPT),
                ) {
                    Text("Copy LLM prompt")
                }
            }

            Text(
                text = "Copy the prompt into any AI assistant, describe your memorisation in " +
                    "your own words, then paste its reply back into the box above. Nothing is " +
                    "sent anywhere by this app.",
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
                modifier = Modifier.padding(top = 12.dp, bottom = 24.dp),
            )
        }
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Learned Ayahs prompt", text))
}

/** The schema mirrors [LearnedAyahsExport], so a reply can be pasted in or saved and imported. */
private fun llmPrompt(): String = """
    I am tracking which ayahs of the Qur'an I have memorised.

    Convert my description below into JSON of exactly this shape, and reply with the JSON only:

    {
      "format": "${LearnedAyahsExport.FORMAT}",
      "version": ${LearnedAyahsExport.VERSION},
      "ayahs": ["2:255", "36:1-83", "112", "78-114"]
    }

    Reference rules:
    - "2:255"    a single ayah (surah:ayah)
    - "36:1-83"  a range of ayahs within a surah
    - "112"      an entire surah
    - "78-114"   a range of entire surahs
    Use surah numbers (1-114), not names. Do not include ayahs I did not mention.

    My description:
""".trimIndent() + "\n"
