package com.quran.learnedplayer.video

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Typeface
import com.quran.learnedplayer.data.AtomicDownload
import com.quran.learnedplayer.data.AyahMapping
import com.quran.learnedplayer.data.QuranConstants
import com.quran.learnedplayer.data.QuranDataRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.coroutines.coroutineContext

/**
 * Makes a video of a range of ayahs: downloads the chosen reciter's clips, builds the soundtrack,
 * draws every frame and encodes the MP4. Also draws the still preview the screen shows.
 *
 * Everything is done on the phone; nothing is uploaded. The work is cancellable between frames.
 */
class VideoExporter(context: Context, private val arabicTypeface: Typeface) {
    private val appContext = context.applicationContext
    private val quran = QuranDataRepository(appContext)
    private val texts = VideoTexts(appContext)
    private val photos = NaturePhotos(appContext)

    enum class Stage { DOWNLOADING, AUDIO, VIDEO }

    /** How far the export has got: [fraction] runs from 0 to 1 over the whole job. */
    data class Progress(val stage: Stage, val fraction: Float)

    /**
     * Writes the video for [request] to [output]. [sources] replaces the download with local files
     * (the tests use it); [scale] shrinks the frame for quick runs.
     */
    suspend fun export(
        request: VideoRequest,
        output: File,
        scale: Float = 1f,
        fps: Int = FPS,
        sources: ((ClipKind, Int, Int) -> File)? = null,
        onProgress: (Progress) -> Unit = {},
    ): File = withContext(Dispatchers.Default) {
        val job = coroutineContext[Job]
        val checkCancelled = { job?.ensureActive() ?: Unit }
        val plan = request.clips()
        val clips = plan.mapIndexed { index, (kind, surah, ayah) ->
            checkCancelled()
            onProgress(Progress(Stage.DOWNLOADING, 0.1f * index / plan.size))
            ClipSource(kind, surah, ayah, sources?.invoke(kind, surah, ayah) ?: download(request, kind, surah, ayah))
        }

        val scratch = File(output.parentFile, "${output.nameWithoutExtension}.aac.tmp")
        try {
            val (timeline, audio) = AudioPipeline.build(
                clips, scratch,
                onClipDone = { onProgress(Progress(Stage.AUDIO, 0.1f + 0.15f * (it + 1) / clips.size)) },
                checkCancelled = checkCancelled,
            )
            if (timeline.totalMs > VideoRequest.MAX_DURATION_MS) {
                throw IOException("That's over ${VideoRequest.MAX_DURATION_MS / 60_000} minutes of recitation. Choose fewer ayahs.")
            }
            val slots = slotsFor(request, timeline)
            downloadPhotos(slots)
            val (width, height) = VideoEncoder.supportedSize(request.aspect, scale)
            render(request, timeline, slots, audio, output, width, height, fps, checkCancelled) { fraction ->
                onProgress(Progress(Stage.VIDEO, 0.25f + 0.75f * fraction))
            }
            output
        } catch (e: Throwable) {
            output.delete()
            throw e
        } finally {
            scratch.delete()
        }
    }

    /**
     * Draws and encodes every frame. Not a suspend function on purpose: EGL binds to the thread
     * that set it up, so the whole loop has to stay on one thread.
     */
    private fun render(
        request: VideoRequest,
        timeline: VideoTimeline,
        slots: List<BackgroundSlot>,
        audio: EncodedAudio,
        output: File,
        width: Int,
        height: Int,
        fps: Int,
        checkCancelled: () -> Unit,
        onFraction: (Float) -> Unit,
    ) {
        val renderer = rendererFor(request, timeline, slots, width, height)
        val frame = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(frame)
        val encoder = VideoEncoder(width, height, fps, output, audio)
        try {
            val frames = ((timeline.totalMs * fps + 999) / 1000).toInt()
            for (i in 0 until frames) {
                checkCancelled()
                val ms = i * 1000L / fps
                renderer.draw(canvas, ms)
                encoder.encode(frame, ms * 1000)
                if (i % fps == 0) onFraction(i.toFloat() / frames)
            }
            encoder.finish()
            onFraction(1f)
        } finally {
            encoder.release()
            renderer.release()
            frame.recycle()
        }
    }

    /** A still of the first ayah as it will look, with a word lit up, [width] pixels wide. */
    fun preview(request: VideoRequest, width: Int): Bitmap {
        val (fullW, fullH) = request.aspect.size()
        val height = width * fullH / fullW
        val first = TimelineClip(ClipKind.AYAH, request.surah, request.fromAyah, 0, 0)
        val text = textFor(request, first)
        val durationMs = text.segments?.lastOrNull()?.last?.plus(300) ?: 5_000L
        val timeline = VideoTimeline.Builder().apply { add(ClipKind.AYAH, request.surah, request.fromAyah, durationMs) }.build()
        val clip = timeline.clips.first()
        val slots = slotsFor(request, timeline)
        downloadPhotos(slots)
        val renderer = rendererFor(request, timeline, slots, width, height)
        val at = text.segments?.let { it[it.size / 3].first } ?: 0L
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
            renderer.draw(Canvas(it), clip.startMs + at)
            renderer.release()
        }
    }

    /** Which background shows when: drawn themes, or nature photos matched to the same themes. */
    private fun slotsFor(request: VideoRequest, timeline: VideoTimeline): List<BackgroundSlot> =
        BackgroundPlan.plan(
            timeline.clips,
            request.themeKey,
            themeOf = { surah, ayah ->
                val drawn = texts.themeKey(surah, ayah)
                if (request.photos) photos.subjectFor(drawn)?.themeKey ?: drawn else drawn
            },
            variantSeed = { surah, ayah -> AyahMapping.surahAyahToGlobal(surah, ayah) },
        )

    /** Fetches every photo the video shows before the first frame is drawn. */
    private fun downloadPhotos(slots: List<BackgroundSlot>) {
        for (slot in slots) photos.photo(slot.themeKey, slot.variant)?.let(photos::ensure)
    }

    private fun rendererFor(
        request: VideoRequest,
        timeline: VideoTimeline,
        slots: List<BackgroundSlot>,
        width: Int,
        height: Int,
    ): FrameRenderer = FrameRenderer(
        width, height, request, timeline, slots, { textFor(request, it) }, arabicTypeface,
        sceneFor = { key, variant ->
            if (NaturePhotos.isPhoto(key)) {
                photos.photo(key, variant)?.let(photos::scene) ?: VideoThemes.variant(null, variant)
            } else {
                VideoThemes.variant(key, variant)
            }
        },
        creditFor = { key, variant ->
            photos.photo(key, variant)?.takeIf { NaturePhotos.isPhoto(key) }?.let { "Photo: ${it.author} · Wikimedia Commons (CC0)" }
        },
    )

    private fun textFor(request: VideoRequest, clip: TimelineClip): ClipText = when (clip.kind) {
        ClipKind.AUDHU -> ClipText(
            AUDHU_WORDS.mapIndexed { i, (arabic, gloss) -> LayoutWord(arabic, gloss, i) },
            segments = null,
            translation = AUDHU_TRANSLATION,
            reference = FrameRenderer.reference(clip),
        )
        ClipKind.BISMILLAH -> {
            // The same words as Al-Fatihah 1:1, without its ayah number.
            val words = layoutWords(1, 1).filter { it.contentIndex >= 0 }
            ClipText(words, segments = null, translation = texts.translation(1, 1), reference = FrameRenderer.reference(clip))
        }
        ClipKind.AYAH -> ClipText(
            layoutWords(clip.surah, clip.ayah),
            segments = quran.timingsFor(request.reciter, clip.surah, clip.ayah),
            translation = texts.translation(clip.surah, clip.ayah),
            reference = FrameRenderer.reference(clip),
        )
    }

    private fun layoutWords(surah: Int, ayah: Int): List<LayoutWord> {
        var index = 0
        return quran.wordsForSync(surah, ayah).map { word ->
            if (word.isEnd) LayoutWord(word.text, null, -1) else LayoutWord(word.text, word.translation, index++)
        }
    }

    private fun download(request: VideoRequest, kind: ClipKind, surah: Int, ayah: Int): File {
        val folder = request.reciter.folder
        val (name, url) = when (kind) {
            ClipKind.AUDHU -> QuranConstants.AUDHU_FILENAME to QuranConstants.AUDHU_URL
            ClipKind.BISMILLAH -> QuranConstants.BISMILLAH_FILENAME to QuranConstants.BISMILLAH_URL
            ClipKind.AYAH -> AyahMapping.ayahFilename(surah, ayah).let { it to "${QuranConstants.AUDIO_HOST}/$folder/$it" }
        }
        // The same cache the player uses, so clips already played or saved offline are reused.
        val file = File(appContext.filesDir, "audio/$folder/$name")
        if (file.exists() && file.length() > 0) return file
        if (!AtomicDownload.toFile(url, file)) {
            throw IOException("Couldn't download the recitation. Check your connection and try again.")
        }
        return file
    }

    companion object {
        const val FPS = 30

        private val AUDHU_WORDS = listOf(
            "أَعُوذُ" to "I seek refuge",
            "بِٱللَّهِ" to "in Allah",
            "مِنَ" to "from",
            "ٱلشَّيْطَٰنِ" to "Satan",
            "ٱلرَّجِيمِ" to "the accursed",
        )
        private const val AUDHU_TRANSLATION = "I seek refuge in Allah from Satan, the accursed."
    }
}
