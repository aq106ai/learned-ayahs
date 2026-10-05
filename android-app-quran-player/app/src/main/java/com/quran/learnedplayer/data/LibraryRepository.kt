package com.quran.learnedplayer.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader

class LibraryRepository(context: Context) {
    private val appContext = context.applicationContext
    private val downloader = AudioDownloader(appContext)

    /**
     * Builds the master snapshot from the user's own learned selection ([LearnedAyahsStore]).
     * Reads no storage, so it cannot fail on permission; an empty selection legitimately yields
     * an empty playlist — the app no longer injects anyone else's ayahs.
     */
    suspend fun buildLocalSnapshot(learnedIds: Set<Int>): PlaylistSnapshot =
        withContext(Dispatchers.IO) {
            val tracks = learnedIds
                // Range-check explicitly: globalToSurahAyah only throws above 6236, and would
                // map 0 to a nonexistent "ayah 0" whose audio file does not exist.
                .filter { it in 1..LearnedAyahsStore.TOTAL_AYAHS }
                .sorted()
                .mapNotNull { globalId ->
                    val (surah, ayah) = runCatching { AyahMapping.globalToSurahAyah(globalId) }
                        .getOrNull() ?: return@mapNotNull null
                    AyahTrack(
                        index = 0,
                        globalId = globalId,
                        surah = surah,
                        ayah = ayah,
                        filename = AyahMapping.ayahFilename(surah, ayah),
                        remoteUrl = AyahMapping.remoteUrl(surah, ayah),
                    )
                }
                // Queues are identified by their first/last global id and must stay ordered and
                // 1-based; this used to be done by DefaultSupplement.merge.
                .mapIndexed { index, track -> track.copy(index = index + 1) }
            PlaylistSnapshot(
                sourceFileName = LOCAL_SOURCE_NAME,
                tracks = downloader.attachLocalPathsSync(tracks),
            )
        }

    /** Resolves cached local files for [tracks] (e.g. a synthesised full-surah queue), so
     *  playback prefers the cache over streaming wherever the ayah is already downloaded. */
    fun attachLocalPathsSync(tracks: List<AyahTrack>): List<AyahTrack> =
        downloader.attachLocalPathsSync(tracks)

    /** Reads the text of a user-picked document. SAF grants access, so no permission is needed. */
    private fun readDocument(uri: Uri): String =
        try {
            appContext.contentResolver.openInputStream(uri)?.use { stream ->
                BufferedReader(InputStreamReader(stream)).readText()
            } ?: error(CANNOT_READ)
        } catch (e: IOException) {
            error(CANNOT_READ)
        } catch (e: SecurityException) {
            error(CANNOT_READ)
        }

    /** Learned ayah ids from an exported [uri] in this app's own sync format. */
    suspend fun readExportedIds(uri: Uri): Result<Set<Int>> = withContext(Dispatchers.IO) {
        runCatching {
            val result = LearnedAyahsExport.parse(readDocument(uri))
            if (result.ids.isEmpty()) {
                error("No ayahs found in that file. Is it a Learned Ayahs export?")
            }
            result.ids
        }
    }

    /** Writes the export payload to a user-chosen [uri] from the SAF create-document picker. */
    suspend fun writeExport(uri: Uri, contents: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                try {
                    appContext.contentResolver.openOutputStream(uri)?.use { stream ->
                        stream.write(contents.toByteArray())
                    } ?: error("Couldn't write to the chosen file.")
                } catch (e: IOException) {
                    error("Couldn't write to the chosen file.")
                } catch (e: SecurityException) {
                    error("No permission to write to the chosen file.")
                }
            }
        }

    suspend fun cacheTrack(track: AyahTrack): AyahTrack = withContext(Dispatchers.IO) {
        if (track.localPath != null) return@withContext track
        val dest = downloader.localFileFor(track)
        if (dest.exists() && dest.length() > 0L) {
            return@withContext track.withLocalPath(dest.absolutePath)
        }
        if (downloader.downloadTo(track, dest)) {
            track.withLocalPath(dest.absolutePath)
        } else {
            track
        }
    }

    suspend fun cacheAudhuBillah(): Boolean = downloader.cacheAudhuBillah()

    suspend fun cacheIntroClips(): Pair<Boolean, Boolean> = downloader.cacheIntroClips()

    fun isAudhuCached(): Boolean = downloader.hasCachedAudhu()

    fun isBismillahCached(): Boolean = downloader.hasCachedBismillah()

    companion object {
        const val LOCAL_SOURCE_NAME = "My learned ayahs"
        private const val CANNOT_READ =
            "Couldn't read that file — it may have moved. Pick it again."
    }
}
