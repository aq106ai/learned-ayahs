package com.quran.learnedplayer.ui.player

import android.widget.Toast
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.quran.learnedplayer.ui.AyahShareText
import com.quran.learnedplayer.ui.theme.AccentGreen
import com.quran.learnedplayer.ui.theme.Border
import com.quran.learnedplayer.ui.theme.Panel
import com.quran.learnedplayer.ui.theme.TextMuted
import com.quran.learnedplayer.video.VideoAspect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

const val TAG_SHARE_DIALOG = "share_dialog"
const val TAG_SHARE_VIDEO = "share_video"
const val TAG_SHARE_IMAGE = "share_image"
const val TAG_SHARE_TEXT = "share_text"
const val TAG_SHARE_TEXT_ARABIC = "share_text_arabic"
const val TAG_SHARE_TEXT_ENGLISH = "share_text_english"

/**
 * The player's Share button: a video (the video maker, set up like the player), an image of
 * the ayah as it looks now, or the ayah as text. The picture and the video go out on their own.
 *
 * [atMs]: how far into the ayah's recitation the player is, for the highlighted word; null when
 * it isn't playing the ayah itself.
 */
@Composable
fun ShareDialog(
    surah: Int,
    ayah: Int,
    atMs: Long?,
    onShareVideo: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var aspect by remember { mutableStateOf(VideoAspect.PORTRAIT) }
    var arabic by remember { mutableStateOf(true) }
    var english by remember { mutableStateOf(true) }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun shareImage() {
        working = true
        error = null
        scope.launch {
            val options = ShareImage.options(surah, ayah, aspect)
            try {
                val file = ShareImage.make(context, options, atMs)
                context.startActivity(ShareImage.shareIntent(context, file))
                onDismiss()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                error = e.message?.takeIf { it.isNotBlank() } ?: "The image couldn't be made."
            } finally {
                working = false
            }
        }
    }

    fun shareText(copy: Boolean) {
        working = true
        error = null
        val includeArabic = arabic
        val includeEnglish = english
        scope.launch {
            try {
                if (copy) {
                    val text = withContext(Dispatchers.IO) {
                        AyahShareText.of(context, surah, ayah, ayah, includeArabic, includeEnglish)
                    }
                    AyahShareText.copy(context, text)
                    Toast.makeText(context, "Text copied", Toast.LENGTH_SHORT).show()
                } else {
                    val send = withContext(Dispatchers.IO) {
                        AyahShareText.intent(context, surah, ayah, ayah, includeArabic, includeEnglish)
                    }
                    context.startActivity(send)
                }
                onDismiss()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "The text couldn't be shared."
            } finally {
                working = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!working) onDismiss() },
        containerColor = Panel,
        modifier = Modifier.testTag(TAG_SHARE_DIALOG),
        title = { Text("Share this ayah") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "A video of it recited, a picture of it as it looks now, or the words themselves.",
                    color = TextMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(
                    onClick = onShareVideo,
                    enabled = !working,
                    modifier = Modifier.fillMaxWidth().testTag(TAG_SHARE_VIDEO),
                ) { Text("Video…") }
                Text("Image shape", color = TextMuted, style = MaterialTheme.typography.bodySmall)
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    VideoAspect.entries.forEach { shape ->
                        FilterChip(
                            selected = aspect == shape,
                            onClick = { aspect = shape },
                            enabled = !working,
                            label = { Text(shape.ratio) },
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = Border,
                                labelColor = TextMuted,
                                selectedContainerColor = AccentGreen,
                                selectedLabelColor = Panel,
                            ),
                        )
                    }
                }
                Button(
                    onClick = ::shareImage,
                    enabled = !working,
                    modifier = Modifier.fillMaxWidth().testTag(TAG_SHARE_IMAGE),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentGreen, contentColor = Panel),
                ) { Text("Share image", fontWeight = FontWeight.Bold) }
                Text("Text", color = TextMuted, style = MaterialTheme.typography.bodySmall)
                TextChoices(arabic, english, enabled = !working, onArabic = { arabic = it }, onEnglish = { english = it })
                OutlinedButton(
                    onClick = { shareText(false) },
                    enabled = !working && (arabic || english),
                    modifier = Modifier.fillMaxWidth().testTag(TAG_SHARE_TEXT),
                ) { Text("Share text") }
                OutlinedButton(
                    onClick = { shareText(true) },
                    enabled = !working,
                    modifier = Modifier.fillMaxWidth().testTag("copy_text"),
                ) { Text("Copy text") }
                if (working) LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(3.dp), color = AccentGreen, trackColor = Border)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss, enabled = !working) { Text("Close", color = TextMuted) } },
    )
}

/** Arabic and English. Turning the last one off is ignored, so a share always has words. */
@Composable
fun TextChoices(
    arabic: Boolean,
    english: Boolean,
    enabled: Boolean,
    onArabic: (Boolean) -> Unit,
    onEnglish: (Boolean) -> Unit,
) {
    Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = arabic,
            onClick = { if (!arabic || english) onArabic(!arabic) },
            enabled = enabled,
            label = { Text("Arabic") },
            modifier = Modifier.testTag(TAG_SHARE_TEXT_ARABIC),
            colors = FilterChipDefaults.filterChipColors(
                containerColor = Border,
                labelColor = TextMuted,
                selectedContainerColor = AccentGreen,
                selectedLabelColor = Panel,
            ),
        )
        FilterChip(
            selected = english,
            onClick = { if (!english || arabic) onEnglish(!english) },
            enabled = enabled,
            label = { Text("English") },
            modifier = Modifier.testTag(TAG_SHARE_TEXT_ENGLISH),
            colors = FilterChipDefaults.filterChipColors(
                containerColor = Border,
                labelColor = TextMuted,
                selectedContainerColor = AccentGreen,
                selectedLabelColor = Panel,
            ),
        )
    }
}
