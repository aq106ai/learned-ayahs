package com.quran.learnedplayer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.core.content.res.ResourcesCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.quran.learnedplayer.data.Reciter
import com.quran.learnedplayer.video.BackgroundScenes
import com.quran.learnedplayer.video.VideoAspect
import com.quran.learnedplayer.video.VideoExporter
import com.quran.learnedplayer.video.VideoRequest
import com.quran.learnedplayer.video.VideoThemes
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Not a test: draws every video background, previews in each shape, and one real video, for a
 * person to look at. `store/capture-video-samples.sh` runs it and pulls the files; the
 * instrumented test runs leave it out. The video downloads its recitation, so it needs a network.
 */
@RunWith(AndroidJUnit4::class)
class VideoSamples {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val out = File(context.filesDir, "video-samples").apply { deleteRecursively(); mkdirs() }
    private val font get() = ResourcesCompat.getFont(context, R.font.scheherazade_new)!!

    @Test
    fun samples() {
        themeSheets()
        previews()
        video()
    }

    /** Every background of every theme, labelled, 30 to a sheet. */
    private fun themeSheets() {
        val cellW = 216
        val cellH = 384
        val label = 34
        val columns = 6
        val all = VideoThemes.all.flatMap { theme -> theme.variants.indices.map { theme to it } }
        all.chunked(30).forEachIndexed { sheetIndex, chunk ->
            val rows = (chunk.size + columns - 1) / columns
            val sheet = Bitmap.createBitmap(cellW * columns, (cellH + label) * rows, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(sheet)
            canvas.drawColor(Color.rgb(20, 20, 20))
            val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 20f }
            val scenes = BackgroundScenes(cellW, cellH)
            val cell = Bitmap.createBitmap(cellW, cellH, Bitmap.Config.ARGB_8888)
            chunk.forEachIndexed { i, (theme, variant) ->
                scenes.draw(Canvas(cell), theme.variants[variant], 3_000)
                val x = (i % columns) * cellW
                val y = (i / columns) * (cellH + label)
                canvas.drawBitmap(cell, x.toFloat(), y.toFloat(), null)
                canvas.drawText("${theme.key} ${variant + 1}", x + 8f, y + cellH + 25f, text)
            }
            scenes.release()
            save(sheet, "themes_${sheetIndex + 1}.png")
        }
    }

    private fun previews() {
        val exporter = VideoExporter(context, font)
        val cases = listOf(
            Triple(67, 1, "matched"), Triple(2, 255, "matched"), Triple(24, 35, "matched"),
            Triple(55, 13, "matched"), Triple(2, 282, "matched"), Triple(112, 1, "oneness"),
        )
        for ((surah, ayah, theme) in cases) for (aspect in VideoAspect.entries) {
            val request = VideoRequest(surah, ayah, ayah, Reciter.DEFAULT, aspect, true, true, false, theme.takeIf { it != "matched" })
            save(exporter.preview(request, if (aspect == VideoAspect.LANDSCAPE) 960 else 540), "preview_${surah}_${ayah}_${aspect.ratio.replace(':', 'x')}.png")
        }
        // Nature photos (copied onto the device by capture-video-samples.sh): matched, and chosen.
        val photoCases = listOf(
            Triple(67, 1, null), Triple(55, 19, null), Triple(2, 164, null), Triple(78, 6, null),
            Triple(1, 1, "photo:waterfall"), Triple(112, 1, "photo:night_sky"),
        )
        for ((surah, ayah, theme) in photoCases) for (aspect in listOf(VideoAspect.PORTRAIT, VideoAspect.LANDSCAPE)) {
            val request = VideoRequest(surah, ayah, ayah, Reciter.DEFAULT, aspect, true, true, false, theme, photos = true)
            save(exporter.preview(request, if (aspect == VideoAspect.LANDSCAPE) 960 else 540), "photo_${surah}_${ayah}_${aspect.ratio.replace(':', 'x')}.png")
        }
        // Without the word meanings and translation, for comparison.
        val plain = VideoRequest(67, 1, 1, Reciter.DEFAULT, VideoAspect.PORTRAIT, false, false, false, null)
        save(exporter.preview(plain, 540), "preview_67_1_plain.png")
    }

    private fun video() = runBlocking {
        val request = VideoRequest(67, 1, 3, Reciter.DEFAULT, VideoAspect.PORTRAIT, true, true, true, null)
        VideoExporter(context, font).export(request, File(out, "al-mulk_1-3.mp4"), scale = 0.5f)
    }

    private fun save(bitmap: Bitmap, name: String) {
        File(out, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
