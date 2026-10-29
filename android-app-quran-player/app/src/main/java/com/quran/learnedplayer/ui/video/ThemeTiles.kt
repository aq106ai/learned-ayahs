package com.quran.learnedplayer.ui.video

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.quran.learnedplayer.ui.theme.AccentGreen
import com.quran.learnedplayer.ui.theme.Border
import com.quran.learnedplayer.ui.theme.TextMuted
import com.quran.learnedplayer.ui.theme.TextPrimary
import com.quran.learnedplayer.video.BackgroundScenes
import com.quran.learnedplayer.video.NaturePhotos
import com.quran.learnedplayer.video.VideoThemes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The small pictures the background pickers show, made once per process and shared by the
 * video maker and the player's background sheet: each drawn theme's first scene, painted off
 * the main thread, and each photo subject's thumbnail, downloaded when first asked for.
 */
object ThemeThumbnails {
    private const val THUMB_W = 108
    private const val THUMB_H = 192

    private val _drawn = MutableStateFlow<Map<String, Bitmap>>(emptyMap())
    val drawn: StateFlow<Map<String, Bitmap>> = _drawn.asStateFlow()

    private val _photos = MutableStateFlow<Map<String, Bitmap>>(emptyMap())
    val photos: StateFlow<Map<String, Bitmap>> = _photos.asStateFlow()

    // Process-wide like the pictures themselves, so leaving a screen doesn't cancel them half-made.
    private val scope = CoroutineScope(SupervisorJob())
    private var drawnStarted = false
    private var photosStarted = false

    @Synchronized
    fun loadDrawn() {
        if (drawnStarted) return
        drawnStarted = true
        scope.launch(Dispatchers.Default) {
            val scenes = BackgroundScenes(THUMB_W, THUMB_H)
            for (theme in VideoThemes.all) {
                val bitmap = Bitmap.createBitmap(THUMB_W, THUMB_H, Bitmap.Config.ARGB_8888)
                scenes.draw(Canvas(bitmap), theme.variants.first(), 2_000)
                _drawn.update { it + (theme.key to bitmap) }
            }
            scenes.release()
        }
    }

    /** Keyed by subject key. Offline, the tiles simply stay blank; it tries again next time. */
    @Synchronized
    fun loadPhotos(context: Context) {
        if (photosStarted) return
        photosStarted = true
        val photos = NaturePhotos(context)
        scope.launch(Dispatchers.IO) {
            for (subject in photos.subjects) {
                if (subject.key in _photos.value) continue
                val file = photos.thumbnail(subject) ?: continue
                val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: continue
                _photos.update { it + (subject.key to bitmap) }
            }
        }.invokeOnCompletion { synchronized(this) { photosStarted = _photos.value.size >= photos.subjects.size } }
    }
}

/** One choice in a background picker: a small picture of it, its name, and a ring when chosen. */
@Composable
fun ThemeTile(
    title: String,
    subtitle: String?,
    selected: Boolean,
    image: Bitmap?,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier = modifier.width(76.dp).clickable(enabled = enabled, onClick = onClick),
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
