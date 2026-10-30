package com.quran.learnedplayer.ui.player

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.core.content.FileProvider
import androidx.core.content.res.ResourcesCompat
import com.quran.learnedplayer.R
import com.quran.learnedplayer.player.PlayerSettings
import com.quran.learnedplayer.ui.video.VideoMakerViewModel
import com.quran.learnedplayer.ui.video.VideoOptions
import com.quran.learnedplayer.video.VideoAspect
import com.quran.learnedplayer.video.VideoExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * "Share → Image": the ayah playing now as a picture, drawn exactly like a frame of a shared
 * video (same background, card, text and highlighted word), in the shape the user picked. The
 * sources go in the message it's shared with, as for a video.
 */
object ShareImage {
    /** How long a shared picture is kept for the app it went to. */
    private const val KEEP_MS = 60 * 60 * 1000L

    /** The options the picture is drawn with: one ayah, looking like the player. */
    fun options(surah: Int, ayah: Int, aspect: VideoAspect): VideoOptions =
        VideoOptions(surah = surah, fromAyah = ayah, toAyah = ayah, aspect = aspect, opening = false).likeThePlayer(
            PlayerSettings.playerBackground,
            PlayerSettings.showAyahTranslation,
            PlayerSettings.showWordTranslations,
        )

    /**
     * Draws the picture into the cache's `images/` folder (shared through the FileProvider) and
     * returns it. [atMs]: how far into the ayah's recitation, for the word to light up; null
     * lights an example word, for an ayah that isn't playing. Downloads a photo it needs.
     */
    suspend fun make(context: Context, options: VideoOptions, atMs: Long?): File = withContext(Dispatchers.IO) {
        val font = ResourcesCompat.getFont(context, R.font.scheherazade_new) ?: android.graphics.Typeface.SERIF
        val bitmap = VideoExporter(context, font).still(options.toRequest(), options.aspect.width, atMs)
        val dir = File(context.cacheDir, "images").apply { mkdirs() }
        // Clear out old pictures, but not recent ones: an app shared one may not have read it yet.
        val stale = System.currentTimeMillis() - KEEP_MS
        dir.listFiles()?.filter { it.lastModified() < stale }?.forEach { it.delete() }
        val file = File(
            dir,
            String.format(Locale.ROOT, "LearnedAyahs_%03d_%03d_%s.jpg", options.surah, options.fromAyah, options.aspect.ratio.replace(':', 'x')),
        )
        // Written under another name and renamed when complete, so the picture never exists
        // half-written under the name that is shared.
        val partial = File(dir, file.name + ".part")
        try {
            partial.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        } finally {
            bitmap.recycle()
        }
        if (!partial.renameTo(file)) {
            partial.delete()
            throw java.io.IOException("Couldn't save the picture")
        }
        file
    }

    fun shareIntent(context: Context, file: File, options: VideoOptions): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, VideoMakerViewModel.caption(options))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, "Share image")
    }
}
