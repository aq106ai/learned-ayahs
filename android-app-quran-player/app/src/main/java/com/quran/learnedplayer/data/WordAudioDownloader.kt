package com.quran.learnedplayer.data

import android.content.Context
import com.quran.learnedplayer.player.PlayerSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Caches Quran.com (or other [WordReciter]) per-word MP3s under
 * `filesDir/audio/wbw/<folder>/`. Offline download is learned ayahs' words only.
 */
class WordAudioDownloader(context: Context) {
    private val appContext = context.applicationContext

    private fun audioRoot(reciter: WordReciter = PlayerSettings.wordReciter): File =
        File(appContext.filesDir, "audio/wbw/${reciter.folder}")

    fun localFileFor(item: WordAudio, reciter: WordReciter = PlayerSettings.wordReciter): File =
        File(audioRoot(reciter), item.filename)

    fun attachLocalPathsSync(
        items: List<WordAudio>,
        reciter: WordReciter = PlayerSettings.wordReciter,
    ): List<WordAudio> {
        val cached = audioRoot(reciter).listFiles()?.associateBy { it.name } ?: emptyMap()
        return items.map { item ->
            val local = cached[item.filename]
            if (local != null && local.length() > 0L) item.withLocalPath(local.absolutePath) else item
        }
    }

    fun cachedCount(reciter: WordReciter = PlayerSettings.wordReciter): Int =
        audioRoot(reciter).listFiles()?.count { it.length() > 0L } ?: 0

    /**
     * True when every content word of one ayah is already on disk.
     *
     * Cheap enough to call as an ayah loads: it stats [wordCount] files instead of listing the
     * whole cache directory, which holds every word of every learned ayah.
     */
    fun isAyahCached(
        surah: Int,
        ayah: Int,
        wordCount: Int,
        reciter: WordReciter = PlayerSettings.wordReciter,
    ): Boolean {
        if (wordCount <= 0) return false
        return WordAudio.itemsFor(reciter, surah, ayah, wordCount).all { item ->
            val file = localFileFor(item, reciter)
            file.exists() && file.length() > 0L
        }
    }

    /**
     * Fetches the word clips for a single ayah, nearest-first.
     *
     * The bulk fill in `RecitationViewModel.ensureRecitePack` walks the whole learned list in
     * global-id order, so the ayah actually being recited is routinely still missing when the coach
     * needs to play a correction — and a missing clip is not a quiet failure, it stalls the mic
     * (see `WordAudioPlayer.playAndAwait`). This exists so the current ayah can jump that queue.
     */
    suspend fun downloadForAyah(
        surah: Int,
        ayah: Int,
        wordCount: Int,
        reciter: WordReciter = PlayerSettings.wordReciter,
    ): Boolean = withContext(Dispatchers.IO) {
        if (wordCount <= 0) return@withContext false
        WordAudio.itemsFor(reciter, surah, ayah, wordCount)
            .all { downloadTo(it, reciter).localPath != null }
    }

    suspend fun downloadTo(
        item: WordAudio,
        reciter: WordReciter = PlayerSettings.wordReciter,
    ): WordAudio = withContext(Dispatchers.IO) {
        if (item.localPath != null) return@withContext item
        val dest = localFileFor(item, reciter)
        if (dest.exists() && dest.length() > 0L) return@withContext item.withLocalPath(dest.absolutePath)
        audioRoot(reciter).mkdirs()
        if (downloadFile(item.remoteUrl, dest)) item.withLocalPath(dest.absolutePath) else item
    }

    /**
     * Downloads every content-word clip for [tracks] (typically the learned list).
     * [wordCountFor] must return this app's content-word count for that ayah.
     */
    suspend fun downloadForTracks(
        tracks: List<AyahTrack>,
        wordCountFor: (surah: Int, ayah: Int) -> Int,
        reciter: WordReciter = PlayerSettings.wordReciter,
        onProgress: (done: Int, total: Int, failed: Int) -> Unit,
    ): Pair<Int, Int> = withContext(Dispatchers.IO) {
        val items = tracks.flatMap { track ->
            WordAudio.itemsFor(reciter, track.surah, track.ayah, wordCountFor(track.surah, track.ayah))
        }
        if (items.isEmpty()) return@withContext 0 to 0
        var done = 0
        var failed = 0
        val total = items.size
        for (item in items) {
            val cached = downloadTo(item, reciter)
            if (cached.localPath != null) done++ else failed++
            onProgress(done, total, failed)
        }
        done to failed
    }

    private fun downloadFile(url: String, dest: File): Boolean = AtomicDownload.toFile(url, dest)
}
