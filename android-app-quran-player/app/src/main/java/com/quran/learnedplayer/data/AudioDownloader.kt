package com.quran.learnedplayer.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class AudioDownloader(private val context: Context) {
    private val audioRoot: File
        get() = File(context.filesDir, "audio/${QuranConstants.RECITER_FOLDER}")

    fun localFileFor(track: AyahTrack): File = File(audioRoot, track.filename)

    fun localAudhuFile(): File = File(audioRoot, QuranConstants.AUDHU_FILENAME)

    fun localBismillahFile(): File = File(audioRoot, QuranConstants.BISMILLAH_FILENAME)

    fun hasCachedAudhu(): Boolean {
        val file = localAudhuFile()
        return file.exists() && file.length() > 0L
    }

    fun hasCachedBismillah(): Boolean {
        val file = localBismillahFile()
        return file.exists() && file.length() > 0L
    }

    suspend fun cacheAudhuBillah(): Boolean = withContext(Dispatchers.IO) {
        cacheIntroFile(QuranConstants.AUDHU_URL, localAudhuFile())
    }

    suspend fun cacheBismillah(): Boolean = withContext(Dispatchers.IO) {
        cacheIntroFile(QuranConstants.BISMILLAH_URL, localBismillahFile())
    }

    suspend fun cacheIntroClips(): Pair<Boolean, Boolean> = withContext(Dispatchers.IO) {
        cacheAudhuBillah() to cacheBismillah()
    }

    private fun cacheIntroFile(url: String, dest: File): Boolean {
        if (dest.exists() && dest.length() > 0L) return true
        audioRoot.mkdirs()
        return downloadFile(url, dest)
    }

    suspend fun attachLocalPaths(tracks: List<AyahTrack>): List<AyahTrack> =
        withContext(Dispatchers.IO) {
            attachLocalPathsSync(tracks)
        }

    /**
     * Maps tracks to their cached files if present. Uses a single directory listing rather than
     * a per-track exists()/length() stat pair — for a full surah (up to 286 tracks) that's up to
     * 572 blocking syscalls, which stalled playback when this ran on the service's main thread.
     */
    fun attachLocalPathsSync(tracks: List<AyahTrack>): List<AyahTrack> {
        val cached = audioRoot.listFiles()?.associateBy { it.name } ?: emptyMap()
        return tracks.map { track ->
            val local = cached[track.filename]
            if (local != null && local.length() > 0L) {
                track.withLocalPath(local.absolutePath)
            } else {
                track
            }
        }
    }

    suspend fun downloadTo(track: AyahTrack, dest: File): Boolean = withContext(Dispatchers.IO) {
        audioRoot.mkdirs()
        downloadFile(track.remoteUrl, dest)
    }

    private fun downloadFile(url: String, dest: File): Boolean = AtomicDownload.toFile(url, dest)
}
