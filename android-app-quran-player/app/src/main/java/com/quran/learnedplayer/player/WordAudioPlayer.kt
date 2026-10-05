package com.quran.learnedplayer.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.quran.learnedplayer.data.WordAudio
import com.quran.learnedplayer.data.WordAudioDownloader
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * One-shot player for a single word MP3. Recite/review and "tap this word" use this so they
 * never mutate the main ayah queue, mode, or last position.
 */
@UnstableApi
class WordAudioPlayer(context: Context) {
    private val appContext = context.applicationContext
    private val downloader = WordAudioDownloader(appContext)
    private var player: ExoPlayer? = null
    private var listener: Player.Listener? = null

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    /**
     * Bumped whenever a clip finishes, errors, or is replaced.
     *
     * [playAndAwait] cannot simply wait for [isPlaying] to go true and then false: when the clip is
     * missing or the network is down, playback never starts, so that wait sat out its whole 8s
     * timeout — with the microphone paused for a correction the entire time. To the student that is
     * indistinguishable from the coach ignoring them. A counter gives the wait something that ticks
     * on failure as well as on success.
     */
    private val completions = MutableStateFlow(0)

    fun play(surah: Int, ayah: Int, wordIndex: Int) {
        stop()
        val reciter = PlayerSettings.wordReciter
        // Resolve straight to the expected file rather than listing the whole word cache: this runs
        // on the main thread for every correction, and that directory holds every word of every
        // learned ayah.
        val base = WordAudio.item(reciter, surah, ayah, wordIndex)
        val localFile = downloader.localFileFor(base, reciter)
        val item = if (localFile.exists() && localFile.length() > 0L) {
            base.withLocalPath(localFile.absolutePath)
        } else {
            base
        }
        val exo = player ?: ExoPlayer.Builder(appContext).build().also { player = it }
        val playListener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    _isPlaying.value = false
                    exo.pause()
                    completions.value++
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _isPlaying.value = isPlaying
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                // A clip that cannot be fetched or decoded must end the wait immediately, not
                // leave the caller holding the microphone until its timeout expires.
                _isPlaying.value = false
                completions.value++
            }
        }
        listener = playListener
        exo.addListener(playListener)
        exo.setMediaItem(item.toMediaItem())
        exo.prepare()
        exo.play()
    }

    /**
     * Plays [wordIndex] and suspends until it finishes, errors, or [timeoutMs] elapses.
     *
     * Waits on [completions] rather than on [isPlaying] going true then false, so a clip that never
     * starts — missing file, dead network — returns as soon as the player gives up instead of
     * holding the caller (and the paused microphone) for the full timeout.
     */
    suspend fun playAndAwait(surah: Int, ayah: Int, wordIndex: Int, timeoutMs: Long = 8_000L) {
        val before = completions.value
        play(surah, ayah, wordIndex)
        withTimeoutOrNull(timeoutMs) {
            completions.first { it != before }
        }
    }

    fun stop() {
        val exo = player ?: return
        listener?.let { exo.removeListener(it) }
        listener = null
        runCatching { exo.pause() }
        _isPlaying.value = false
    }

    fun release() {
        stop()
        player?.release()
        player = null
    }

    private fun WordAudio.toMediaItem(): MediaItem {
        val uri = if (localPath != null) Uri.fromFile(File(localPath)) else Uri.parse(remoteUrl)
        return MediaItem.Builder()
            .setUri(uri)
            .setMediaId(mediaId)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle("$surah:$ayah word ${wordIndex + 1}")
                    .setArtist(PlayerSettings.wordReciter.displayName)
                    .build(),
            )
            .build()
    }
}
