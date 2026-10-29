package com.quran.learnedplayer.ui

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntSize
import com.quran.learnedplayer.data.AyahMapping
import com.quran.learnedplayer.video.BackgroundScenes
import com.quran.learnedplayer.video.LiveBackground
import com.quran.learnedplayer.video.NaturePhotos
import com.quran.learnedplayer.video.PlayerBackgrounds
import com.quran.learnedplayer.video.SceneKey
import com.quran.learnedplayer.video.SceneSpec
import com.quran.learnedplayer.video.VideoTexts
import com.quran.learnedplayer.video.VideoThemes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

const val TAG_PLAYER_BACKGROUND = "player_background"

/** About 30 frames a second: the scenes move slowly, and twice that would only cost battery. */
private const val FRAME_MS = 33L

/** How often a paused player checks whether a held-back background is due. */
private const val IDLE_CHECK_MS = 500L

/**
 * The scene behind the player when a background is chosen ([PlayerBackgrounds]): the same
 * drawn scenes and nature photos as a shared video, following the ayah being recited by the
 * same rules ([LiveBackground]). It moves only while [animating]; paused, it holds still.
 *
 * Drawn at half resolution and scaled up: the scenes are soft gradients and shapes that lose
 * nothing by it, and each cached full-screen still would otherwise take about 10 MB.
 */
@Composable
fun PlayerBackground(
    choice: String,
    surah: Int?,
    ayah: Int?,
    animating: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val texts = remember { VideoTexts(context) }
    val photos = remember { NaturePhotos(context) }
    val live = remember { LiveBackground() }
    // The scene the current ayah asks for, with the choice it was worked out for.
    var wanted by remember { mutableStateOf<Pair<String, SceneKey>?>(null) }

    // What this ayah asks for, worked out off the main thread: the theme list is a JSON asset,
    // and a photo may have to download. Until it has, the photo isn't asked for at all, so the
    // hold doesn't keep a stand-in on screen once it arrives.
    LaunchedEffect(choice, surah, ayah) {
        if (surah == null || ayah == null) return@LaunchedEffect
        val seed = AyahMapping.surahAyahToGlobal(surah, ayah)
        val drawn = withContext(Dispatchers.Default) { texts.themeKey(surah, ayah) }
        val key = withContext(Dispatchers.Default) {
            PlayerBackgrounds.themeFor(choice, drawn) { photos.subjectFor(it)?.themeKey }
        } ?: return@LaunchedEffect
        val ready = !NaturePhotos.isPhoto(key) || withContext(Dispatchers.IO) {
            runCatching { photos.photo(key, seed)?.let(photos::ensure) }.getOrNull() != null
        }
        wanted = choice to SceneKey(if (ready) key else drawn, seed)
    }

    val wantedNow by rememberUpdatedState(wanted)
    val animatingNow by rememberUpdatedState(animating)
    // The scenes' own clock, which only runs while the recitation plays.
    var sceneMs by remember { mutableLongStateOf(0L) }
    // Bumped to redraw: read in the draw block, so only drawing is repeated, not composition.
    var frame by remember { mutableLongStateOf(0L) }

    LaunchedEffect(Unit) {
        var last = SystemClock.uptimeMillis()
        var shownChoice: String? = null
        while (true) {
            val now = SystemClock.uptimeMillis()
            wantedNow?.let { (forChoice, key) ->
                // A background the user has just picked shows at once; the ayahs' own changes wait.
                live.want(key, now, immediately = shownChoice != null && forChoice != shownChoice)
                shownChoice = forChoice
            }
            val fading = live.at(now)?.let { it.fade < 1f } == true
            if (animatingNow || fading) {
                withFrameMillis { }
                val t = SystemClock.uptimeMillis()
                if (t - last >= FRAME_MS) {
                    if (animatingNow) sceneMs += t - last
                    last = t
                    frame = t
                }
            } else {
                delay(IDLE_CHECK_MS)
                last = SystemClock.uptimeMillis()
                frame = last
            }
        }
    }

    var size by remember { mutableStateOf(IntSize.Zero) }
    val scenes = remember(size) {
        if (size.width > 1 && size.height > 1) BackgroundScenes(size.width / 2, size.height / 2) else null
    }
    DisposableEffect(scenes) { onDispose { scenes?.release() } }

    fun sceneFor(key: SceneKey): SceneSpec =
        if (NaturePhotos.isPhoto(key.themeKey)) {
            photos.photo(key.themeKey, key.variant)?.takeIf { photos.file(it).exists() }?.let(photos::scene)
                ?: VideoThemes.variant(null, key.variant)
        } else {
            VideoThemes.variant(key.themeKey, key.variant)
        }

    Canvas(modifier = modifier.onSizeChanged { size = it }.testTag(TAG_PLAYER_BACKGROUND)) {
        frame // redraw on each tick
        val drawer = scenes ?: return@Canvas
        val shown = live.at(SystemClock.uptimeMillis()) ?: return@Canvas
        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            native.save()
            native.scale(size.width.toFloat() / (size.width / 2), size.height.toFloat() / (size.height / 2))
            shown.previous?.let { drawer.draw(native, sceneFor(it), sceneMs) }
            drawer.draw(native, sceneFor(shown.current), sceneMs, (shown.fade * 255).toInt())
            native.restore()
        }
    }
}
