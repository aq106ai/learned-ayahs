package com.quran.learnedplayer.ui.video

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.core.content.res.ResourcesCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.quran.learnedplayer.R
import com.quran.learnedplayer.data.QuranConstants
import com.quran.learnedplayer.data.Reciter
import com.quran.learnedplayer.data.SurahNames
import com.quran.learnedplayer.player.PlayerSettings
import com.quran.learnedplayer.video.NaturePhotos
import com.quran.learnedplayer.video.PhotoSubject
import com.quran.learnedplayer.video.PlayerBackgrounds
import com.quran.learnedplayer.video.VideoAspect
import com.quran.learnedplayer.video.VideoExporter
import com.quran.learnedplayer.video.VideoRequest
import com.quran.learnedplayer.video.VideoTexts
import com.quran.learnedplayer.video.VideoThemes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/** What the screen shows about the video being made. */
sealed interface ExportState {
    data object Idle : ExportState
    data class Working(val progress: VideoExporter.Progress) : ExportState
    data class Done(val file: File, val savedTo: String? = null) : ExportState
    data class Failed(val message: String) : ExportState
}

data class VideoOptions(
    val surah: Int,
    val fromAyah: Int,
    val toAyah: Int,
    val reciter: Reciter = PlayerSettings.reciter,
    val aspect: VideoAspect = VideoAspect.DEFAULT,
    val wordByWord: Boolean = true,
    val fullTranslation: Boolean = true,
    val opening: Boolean = fromAyah == 1,
    /** null: matched to each ayah. */
    val themeKey: String? = null,
    /** Nature photos rather than drawn scenes (what "Matched" picks from, and the tiles shown). */
    val photos: Boolean = false,
    /** Sources and "Made with Learned Ayahs" printed on the video. */
    val credits: Boolean = false,
) {
    val ayahCount: Int get() = toAyah - fromAyah + 1

    fun toRequest() = VideoRequest(surah, fromAyah, toAyah, reciter, aspect, wordByWord, fullTranslation, opening, themeKey, photos, credits)

    /**
     * These options made to look like the player: its [background] (a plain player gets
     * matched scenes, since a video always has one), the translation if it shows it, and the
     * word meanings if it shows them.
     */
    fun likeThePlayer(background: String, translation: Boolean, wordMeanings: Boolean): VideoOptions = when (background) {
        PlayerBackgrounds.PLAIN, PlayerBackgrounds.MATCHED -> copy(themeKey = null, photos = false)
        PlayerBackgrounds.PHOTOS -> copy(themeKey = null, photos = true)
        else -> copy(themeKey = background, photos = NaturePhotos.isPhoto(background))
    }.copy(fullTranslation = translation, wordByWord = wordMeanings)
}

/**
 * The "Make a video" screen's state: the choices, a still preview that follows them, and the
 * export itself. Leaving the screen cancels an export in progress.
 */
class VideoMakerViewModel(application: Application) : AndroidViewModel(application) {
    private val context: Context get() = getApplication()
    private val exporter by lazy {
        VideoExporter(context, ResourcesCompat.getFont(context, R.font.scheherazade_new) ?: android.graphics.Typeface.SERIF)
    }
    private val texts = VideoTexts(application)
    private val naturePhotos = NaturePhotos(application)

    /** The nature photo subjects, empty if the app has none listed. */
    val photoSubjects: List<PhotoSubject> get() = naturePhotos.subjects

    val photoThumbnails: StateFlow<Map<String, Bitmap>> = ThemeThumbnails.photos

    /** Shown under the preview when it can't be drawn, such as a photo that couldn't download. */
    private val _previewError = MutableStateFlow<String?>(null)
    val previewError: StateFlow<String?> = _previewError.asStateFlow()

    /** Switches between drawn scenes and nature photos; the photos' small pictures download then. */
    fun setPhotos(on: Boolean) {
        update { it.copy(photos = on, themeKey = null) }
        if (on) ThemeThumbnails.loadPhotos(context)
    }

    private val _options = MutableStateFlow(VideoOptions(surah = 1, fromAyah = 1, toAyah = 7))
    val options: StateFlow<VideoOptions> = _options.asStateFlow()

    private val _preview = MutableStateFlow<Bitmap?>(null)
    val preview: StateFlow<Bitmap?> = _preview.asStateFlow()

    private val _export = MutableStateFlow<ExportState>(ExportState.Idle)
    val export: StateFlow<ExportState> = _export.asStateFlow()

    private var previewJob: Job? = null
    private var exportJob: Job? = null
    private var started = false

    /**
     * Sets where the screen starts: one ayah, or a whole surah (ayah 0). [fromPlayer]: the user
     * is sharing what the player shows, so the background and text start out the same.
     */
    fun start(surah: Int, ayah: Int, fromPlayer: Boolean = false) {
        if (started) return
        started = true
        val count = QuranConstants.VERSE_COUNTS[(surah - 1).coerceIn(0, 113)]
        val from = if (ayah in 1..count) ayah else 1
        val to = if (ayah in 1..count) ayah else minOf(count, DEFAULT_SURAH_AYAHS)
        val options = VideoOptions(surah = surah.coerceIn(1, 114), fromAyah = from, toAyah = to)
        _options.value = if (!fromPlayer) options else options.likeThePlayer(
            PlayerSettings.playerBackground,
            PlayerSettings.showAyahTranslation,
            PlayerSettings.showWordTranslations,
        )
        if (_options.value.photos) ThemeThumbnails.loadPhotos(context)
        refreshPreview()
    }

    fun update(change: (VideoOptions) -> VideoOptions) {
        _options.update { old ->
            val new = change(old)
            val count = QuranConstants.VERSE_COUNTS[new.surah - 1]
            val from = new.fromAyah.coerceIn(1, count)
            new.copy(fromAyah = from, toAyah = new.toAyah.coerceIn(from, count))
        }
        if (_export.value !is ExportState.Working) _export.value = ExportState.Idle
        refreshPreview()
    }

    fun setSurah(surah: Int) = update {
        val count = QuranConstants.VERSE_COUNTS[surah - 1]
        it.copy(surah = surah, fromAyah = 1, toAyah = minOf(count, DEFAULT_SURAH_AYAHS), opening = true)
    }

    /** The theme the first ayah is matched to, shown under the "Matched" choice. */
    private val _matchedTheme = MutableStateFlow("")
    val matchedTheme: StateFlow<String> = _matchedTheme.asStateFlow()

    /** A small picture of each theme's first background, for the theme picker. */
    val thumbnails: StateFlow<Map<String, Bitmap>> = ThemeThumbnails.drawn

    init {
        ThemeThumbnails.loadDrawn()
    }

    private fun refreshPreview() {
        previewJob?.cancel()
        previewJob = viewModelScope.launch {
            delay(150) // let quick changes settle
            val request = _options.value.toRequest()
            _matchedTheme.value = withContext(Dispatchers.Default) {
                val drawn = texts.themeKey(request.surah, request.fromAyah)
                if (request.photos) naturePhotos.subjectFor(drawn)?.title ?: "" else VideoThemes.theme(drawn).title
            }
            // A photo may have to download first, so this runs on the IO pool.
            val result = withContext(Dispatchers.IO) { runCatching { exporter.preview(request, PREVIEW_W) } }
            _preview.value = result.getOrNull()
            _previewError.value = result.exceptionOrNull()?.let { it.message ?: "The preview couldn't be drawn." }
        }
    }

    fun create() {
        if (_export.value is ExportState.Working) return
        val options = _options.value
        exportJob = viewModelScope.launch {
            _export.value = ExportState.Working(VideoExporter.Progress(VideoExporter.Stage.DOWNLOADING, 0f))
            val dir = File(context.cacheDir, "videos").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() } // one video at a time; the last is shared or saved by now
            val output = File(dir, fileName(options))
            _export.value = try {
                exporter.export(options.toRequest(), output) { progress -> _export.value = ExportState.Working(progress) }
                ExportState.Done(output)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                ExportState.Failed(e.message?.takeIf { it.isNotBlank() } ?: "The video couldn't be made on this phone.")
            }
        }
    }

    fun cancel() {
        exportJob?.cancel()
        _export.value = ExportState.Idle
    }

    /** Saves the finished video to Movies/Learned Ayahs (Android 10+). */
    fun saveToMovies() {
        val done = _export.value as? ExportState.Done ?: return
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) throw IllegalStateException("Needs Android 10")
                    insertIntoMovies(done.file)
                }
            }
            _export.value = if (result.isSuccess) done.copy(savedTo = "Saved to Movies › Learned Ayahs") else done.copy(savedTo = "Couldn't save the video.")
        }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private fun insertIntoMovies(file: File) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Learned Ayahs")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: error("No space for the video")
        resolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
        values.clear()
        values.put(MediaStore.Video.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
    }

    /** Writes the finished video to a file the user picked (Android 8 and 9). */
    fun saveTo(uri: Uri) {
        val done = _export.value as? ExportState.Done ?: return
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)!!.use { out -> done.file.inputStream().use { it.copyTo(out) } }
                }.isSuccess
            }
            _export.value = done.copy(savedTo = if (ok) "Saved." else "Couldn't save the video.")
        }
    }

    fun shareIntent(): Intent? {
        val done = _export.value as? ExportState.Done ?: return null
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", done.file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, caption(_options.value))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, "Share video")
    }

    override fun onCleared() {
        exportJob?.cancel()
    }

    companion object {
        /** A whole short surah, or the start of a long one; the user can widen it. */
        const val DEFAULT_SURAH_AYAHS = 10
        const val PREVIEW_W = 540

        fun fileName(options: VideoOptions): String = String.format(
            Locale.ROOT, "LearnedAyahs_%03d_%03d-%03d_%s.mp4",
            options.surah, options.fromAyah, options.toAyah, options.aspect.ratio.replace(':', 'x'),
        )

        fun reference(options: VideoOptions): String {
            val range = if (options.fromAyah == options.toAyah) "${options.fromAyah}" else "${options.fromAyah}-${options.toAyah}"
            return "${SurahNames.name(options.surah)} ${options.surah}:$range · ${options.reciter.displayName}"
        }

        /**
         * The text shared with the video: what it is, then its sources. The video itself carries
         * no credits unless asked for, so this is where they travel with it.
         */
        fun caption(options: VideoOptions): String = buildList {
            add(reference(options))
            add("")
            add("Recitation: ${options.reciter.displayName} (everyayah.com)")
            if (options.fullTranslation) add("Translation: Saheeh International (tanzil.net)")
            if (options.wordByWord) add("Word meanings: Quran.com")
            if (options.photos || NaturePhotos.isPhoto(options.themeKey)) add("Background photos: Wikimedia Commons (CC0)")
            add("Made with Learned Ayahs")
        }.joinToString("\n")
    }
}
