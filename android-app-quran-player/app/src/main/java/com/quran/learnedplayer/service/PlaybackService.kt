package com.quran.learnedplayer.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.quran.learnedplayer.MainActivity
import com.quran.learnedplayer.R
import com.quran.learnedplayer.data.AyahTrack
import com.quran.learnedplayer.data.PlaylistStore
import com.quran.learnedplayer.data.QuranConstants
import com.quran.learnedplayer.data.QuranDataRepository
import com.quran.learnedplayer.data.QueueBuilder
import com.quran.learnedplayer.data.WordAudio
import com.quran.learnedplayer.data.WordAudioDownloader
import com.quran.learnedplayer.player.PlaybackMode
import com.quran.learnedplayer.player.PlayerSettings
import com.quran.learnedplayer.player.RepeatMode
import com.quran.learnedplayer.player.playbackModeLabel
import java.io.File

@UnstableApi
class PlaybackService : MediaSessionService() {
    private var player: ExoPlayer? = null
    private var mediaSession: MediaSession? = null

    /** The active ayah queue currently loaded into the player (or navigated in word mode). */
    private var tracks: List<AyahTrack> = emptyList()
    private var mode: PlaybackMode = PlaybackMode.DEFAULT
    private var repeatMode: RepeatMode = RepeatMode.DEFAULT
    private var currentSurah: Int = 1

    /** Word clips for the current ayah when [mode] is WORD_BY_WORD and clips are on. */
    private var wordItems: List<WordAudio> = emptyList()
    private var wordAyahIndex: Int = 0

    /** Word timestamps inside the ayah MP3 when Play → Word by word uses the original reciter. */
    private var ayahSeekSegments: List<LongRange> = emptyList()
    private var ayahSeekWordIndex: Int = 0
    private var ayahSeekWordCount: Int = 0

    private fun usingWordClips(): Boolean =
        mode == PlaybackMode.WORD_BY_WORD && PlayerSettings.useWordClips

    private fun ayahSeekActive(): Boolean =
        mode == PlaybackMode.WORD_BY_WORD && !PlayerSettings.useWordClips

    private val quranData by lazy { QuranDataRepository(this) }
    private val wordDownloader by lazy { WordAudioDownloader(this) }

    /** The full learned ("master") list, used to derive surah queues. */
    private val master: List<AyahTrack>
        get() = PlaylistStore.latest?.tracks ?: emptyList()

    private enum class IntroStep { NONE, AUDHU, BISMILLAH }

    private var introStep = IntroStep.NONE
    private var introResumeIndex = 0

    private fun resolveIntroUri(filename: String, remoteUrl: String): Uri {
        val local = File(filesDir, "audio/${QuranConstants.RECITER_FOLDER}/$filename")
        return if (local.exists() && local.length() > 0L) {
            Uri.fromFile(local)
        } else {
            Uri.parse(remoteUrl)
        }
    }

    private fun audhuMediaItem(): MediaItem = introMediaItem(
        title = "A'udhu Billah",
        filename = QuranConstants.AUDHU_FILENAME,
        remoteUrl = QuranConstants.AUDHU_URL,
        mediaId = "audhubillah",
    )

    private fun bismillahMediaItem(): MediaItem = introMediaItem(
        title = "Bismillah",
        filename = QuranConstants.BISMILLAH_FILENAME,
        remoteUrl = QuranConstants.BISMILLAH_URL,
        mediaId = "bismillah",
    )

    private fun introMediaItem(
        title: String,
        filename: String,
        remoteUrl: String,
        mediaId: String,
    ): MediaItem =
        MediaItem.Builder()
            .setUri(resolveIntroUri(filename, remoteUrl))
            .setMediaId(mediaId)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist("Maher Al Muaiqly")
                    .build(),
            )
            .build()

    private fun needsSurahIntro(track: AyahTrack?): Boolean =
        mode != PlaybackMode.WORD_BY_WORD && track?.ayah == 1

    private fun currentTrack(): AyahTrack? =
        if (mode == PlaybackMode.WORD_BY_WORD) {
            tracks.getOrNull(wordAyahIndex)
        } else {
            tracks.getOrNull(player?.currentMediaItemIndex ?: 0)
        }

    private val handler = Handler(Looper.getMainLooper())

    /** True while playback is paused in the revision-mode gap between repeats. */
    private var revisionGapPending = false

    /**
     * True from the moment [parkForRevisionGap] seeks to 0 until playback actually
     * resumes. [play] must consult this — position 0 here means "just finished a
     * repeat, waiting," not "ayah 1 starting fresh," so it must not replay the
     * A'udhu/Bismillah intro the way a genuine fresh start would.
     */
    private var revisionGapParked = false

    private val revisionGapResume = Runnable {
        revisionGapPending = false
        revisionGapParked = false
        player?.play()
    }

    private fun cancelRevisionGap() {
        revisionGapParked = false
        if (!revisionGapPending) return
        revisionGapPending = false
        handler.removeCallbacks(revisionGapResume)
    }

    private val progressTicker = object : Runnable {
        override fun run() {
            val current = player ?: return
            pushProgress(current)
            maybeParkBeforeRevisionRepeat(current)
            maybeParkAtWordEnd(current)
            if (current.isPlaying) {
                handler.postDelayed(this, PROGRESS_INTERVAL_MS)
            }
        }
    }

    /**
     * Pauses just *before* REPEAT_MODE_ONE would naturally loop the ayah, instead of
     * reacting to the loop after the fact: by the time `onMediaItemTransition(REASON_REPEAT)`
     * fires, ExoPlayer has already started rendering audio from position 0 of the new
     * loop, so `pause()` there can't un-play the sliver of audio already sent to the
     * speaker — audible as the first word restarting before the gap silence kicks in.
     * Checking a few ticks ahead avoids that entirely; `onMediaItemTransition`'s
     * REASON_REPEAT handling stays as a fallback for the rare case duration isn't
     * known yet (e.g. very early in playback).
     */
    private fun maybeParkBeforeRevisionRepeat(p: Player) {
        if (ayahSeekActive()) return
        if (repeatMode != RepeatMode.AYAH || introStep != IntroStep.NONE) return
        if (PlayerSettings.revisionDelaySeconds <= 0) return
        if (!p.isPlaying) return
        val duration = p.duration
        if (duration <= REVISION_GAP_LEAD_MS * 2 || duration == C.TIME_UNSET) return
        if (duration - p.currentPosition <= REVISION_GAP_LEAD_MS) {
            parkForRevisionGap()
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            PlayerStateHolder.updatePlaying(isPlaying)
            updateNotification()
            if (isPlaying) {
                startProgressTicker()
            } else {
                stopProgressTicker()
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (introStep != IntroStep.NONE) {
                // The player is holding an intro clip, not a queue item — keep the UI
                // pointing at the ayah the intro belongs to.
                PlayerStateHolder.updateIndex(introResumeIndex)
                updateNotification()
                return
            }
            if (mode == PlaybackMode.WORD_BY_WORD) {
                if (usingWordClips()) {
                    val wordIndex = player?.currentMediaItemIndex ?: 0
                    PlayerStateHolder.updateWordIndex(wordIndex, wordItems.size)
                    if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT &&
                        repeatMode == RepeatMode.AYAH &&
                        PlayerSettings.revisionDelaySeconds > 0
                    ) {
                        parkForRevisionGap()
                    }
                }
                updateNotification()
                return
            }
            val index = player?.currentMediaItemIndex ?: 0
            PlayerStateHolder.updateIndex(index)

            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT && repeatMode == RepeatMode.AYAH) {
                // REPEAT_MODE_ONE just looped the same ayah — optionally hold here so
                // the user has think-time (or can press Next) before it plays again.
                if (PlayerSettings.revisionDelaySeconds > 0) {
                    parkForRevisionGap()
                }
                updateNotification()
                return
            }

            val track = tracks.getOrNull(index)
            if (
                needsSurahIntro(track) &&
                player?.playWhenReady == true &&
                (
                    reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO ||
                        reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK
                    )
            ) {
                player?.pause()
                beginSurahIntro(index)
                updateNotification()
                return
            }
            updateNotification()
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            if (introStep != IntroStep.NONE) {
                // Intro clip failed to load (e.g. offline and not cached) — skip it
                // and play the ayah itself instead of freezing playback.
                finishIntroAndResume()
                return
            }
            if (mode == PlaybackMode.WORD_BY_WORD) {
                skipWord(forward = true)
            }
        }

        override fun onEvents(player: Player, events: Player.Events) {
            if (events.containsAny(
                    Player.EVENT_TIMELINE_CHANGED,
                    Player.EVENT_MEDIA_ITEM_TRANSITION,
                    Player.EVENT_PLAYBACK_STATE_CHANGED,
                    Player.EVENT_IS_PLAYING_CHANGED,
                )
            ) {
                pushProgress(player)
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState != Player.STATE_ENDED) return
            if (introStep != IntroStep.NONE) {
                when (introStep) {
                    IntroStep.AUDHU -> {
                        val track = tracks.getOrNull(introResumeIndex)
                        if (track != null && track.surah !in QuranConstants.NO_BISMILLAH_INTRO_SURAHS) {
                            startIntroBismillah()
                        } else {
                            finishIntroAndResume()
                        }
                    }
                    IntroStep.BISMILLAH -> finishIntroAndResume()
                    IntroStep.NONE -> Unit
                }
                return
            }
            if (mode == PlaybackMode.WORD_BY_WORD) {
                if (usingWordClips()) onWordPlaylistEnded() else onAyahSeekWordEnded()
            }
        }
    }

    /**
     * After REPEAT_MODE_ONE loops an ayah in revision mode: stay at position 0, silent
     * for [PlayerSettings.revisionDelaySeconds], then play again.
     */
    private fun parkForRevisionGap() {
        val p = player ?: return
        handler.removeCallbacks(revisionGapResume)
        p.pause()
        if (ayahSeekActive()) {
            val start = ayahSeekSegments.getOrNull(ayahSeekWordIndex)?.first ?: 0L
            p.seekTo(start)
            PlayerStateHolder.updateWordIndex(ayahSeekWordIndex, ayahSeekWordCount)
        } else if (mode == PlaybackMode.WORD_BY_WORD && wordItems.isNotEmpty()) {
            val idx = p.currentMediaItemIndex.coerceIn(0, wordItems.lastIndex)
            p.seekTo(idx, 0L)
            PlayerStateHolder.updateWordIndex(idx, wordItems.size)
        } else {
            p.seekTo(0)
        }
        val duration = p.duration
        PlayerStateHolder.updateProgress(
            0L,
            if (duration > 0 && duration != C.TIME_UNSET) duration else 0L,
        )
        val gapMs = PlayerSettings.revisionDelaySeconds * 1000L
        if (gapMs > 0) {
            revisionGapPending = true
            revisionGapParked = true
            handler.postDelayed(revisionGapResume, gapMs)
        } else {
            revisionGapPending = false
            p.play()
        }
        updateNotification()
    }

    private fun pushProgress(player: Player) {
        if (introStep != IntroStep.NONE) {
            PlayerStateHolder.updateProgress(0L, 0L)
            return
        }
        val duration = player.duration
        PlayerStateHolder.updateProgress(
            positionMs = player.currentPosition.coerceAtLeast(0L),
            durationMs = if (duration > 0 && duration != C.TIME_UNSET) duration else 0L,
        )
    }

    private fun startProgressTicker() {
        handler.removeCallbacks(progressTicker)
        handler.post(progressTicker)
    }

    private fun stopProgressTicker() {
        handler.removeCallbacks(progressTicker)
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val exoPlayer = ExoPlayer.Builder(this).build().apply {
            addListener(playerListener)
        }
        player = exoPlayer
        mediaSession = MediaSession.Builder(this, SessionPlayer(exoPlayer)).build()
        PlayerStateHolder.attachService(this)
    }

    /**
     * Player exposed to the MediaSession, so transport commands from external
     * controllers — Bluetooth (AVRCP), lockscreen / system media controls,
     * Android Auto, watches — go through the service's mode-aware methods, the
     * same path as the on-screen and notification buttons. Raw ExoPlayer calls
     * would bypass the revision gap and surah-intro handling, and ExoPlayer's
     * seekToPrevious() merely restarts the current item once it is >3s in —
     * which in looping revision mode makes the previous button feel dead.
     */
    private inner class SessionPlayer(player: Player) : ForwardingPlayer(player) {
        override fun play() = this@PlaybackService.play()

        override fun pause() = this@PlaybackService.pause()

        override fun setPlayWhenReady(playWhenReady: Boolean) {
            if (playWhenReady) this@PlaybackService.play() else this@PlaybackService.pause()
        }

        override fun seekToNext() = skipNext()

        override fun seekToNextMediaItem() = skipNext()

        override fun seekToPrevious() = skipPrevious()

        override fun seekToPreviousMediaItem() = skipPrevious()

        override fun seekTo(positionMs: Long) = this@PlaybackService.seekTo(positionMs)

        override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
            // A scrub within the current item is a position seek; anything else
            // is a queue jump and must go through the service (which reloads the
            // queue if an intro clip is currently occupying the player).
            if (introStep == IntroStep.NONE && mediaItemIndex == currentMediaItemIndex) {
                this@PlaybackService.seekTo(positionMs)
            } else {
                this@PlaybackService.seekTo(mediaItemIndex)
            }
        }

        override fun seekToDefaultPosition(mediaItemIndex: Int) =
            this@PlaybackService.seekTo(mediaItemIndex)

        override fun stop() {
            // Stop must stick: never let a pending revision-gap resume restart us.
            cancelRevisionGap()
            super.stop()
        }

        // While a surah intro plays, the underlying player holds a single-item
        // playlist, which would strip next/previous from the session and make
        // Bluetooth buttons dead during the intro. Whenever a queue is loaded,
        // keep them advertised — skipNext/skipPrevious handle every state.
        override fun getAvailableCommands(): Player.Commands {
            val commands = super.getAvailableCommands()
            if (tracks.isEmpty()) return commands
            return commands.buildUpon()
                .addAll(
                    Player.COMMAND_SEEK_TO_NEXT,
                    Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                    Player.COMMAND_SEEK_TO_PREVIOUS,
                    Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                )
                .build()
        }

        override fun isCommandAvailable(command: Int): Boolean =
            getAvailableCommands().contains(command)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onDestroy() {
        cancelRevisionGap()
        stopProgressTicker()
        PlayerStateHolder.detachService()
        mediaSession?.release()
        mediaSession = null
        player?.release()
        player = null
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        promoteToForeground()

        when (intent?.action) {
            ACTION_LOAD_PLAYLIST -> {
                val autoPlay = intent.getBooleanExtra(EXTRA_AUTO_PLAY, false)
                val requestedMode = readMode(intent)
                val requestedRepeat = readRepeat(intent)
                val requestedSurah = intent.getIntExtra(EXTRA_SURAH, 0)
                val startGlobal = intent.getIntExtra(EXTRA_START_GLOBAL, 0)
                if (master.isNotEmpty()) {
                    loadForMode(requestedMode, requestedRepeat, requestedSurah, startGlobal)
                    if (autoPlay) play()
                }
            }
            ACTION_SET_MODE -> {
                val requestedMode = readMode(intent)
                val requestedRepeat = readRepeat(intent)
                val requestedSurah = intent.getIntExtra(EXTRA_SURAH, 0)
                val startGlobal = intent.getIntExtra(EXTRA_START_GLOBAL, currentGlobalId())
                val autoPlay = intent.getBooleanExtra(EXTRA_AUTO_PLAY, false)
                val persistMode = intent.getBooleanExtra(EXTRA_PERSIST_MODE, true)
                val wasPlaying = player?.playWhenReady == true
                loadForMode(
                    requestedMode, requestedRepeat, requestedSurah, startGlobal,
                    persistMode = persistMode,
                )
                if (wasPlaying || autoPlay) play()
            }
            ACTION_NOTIF_PREV -> skipPrevious()
            ACTION_NOTIF_NEXT -> skipNext()
            ACTION_NOTIF_PLAY_PAUSE -> {
                if (player?.isPlaying == true) pause() else play()
            }
        }
        updateNotification()
        return super.onStartCommand(intent, flags, startId)
    }

    /**
     * Builds and loads the queue for [requestedMode]/[requestedRepeat]. [requestedSurah] selects
     * the surah for surah-scoped queues (0 = derive from [startGlobal] / current ayah). Playback
     * starts at [startGlobal] if that ayah exists in the queue, otherwise at the first item.
     */
    private fun loadForMode(
        requestedMode: PlaybackMode,
        requestedRepeat: RepeatMode,
        requestedSurah: Int,
        startGlobal: Int,
        persistMode: Boolean = true,
    ) {
        cancelRevisionGap()
        clearIntroState()
        mode = requestedMode
        repeatMode = requestedRepeat
        // The session always moves to the requested mode; only the *saved* choice is conditional,
        // so a one-off jump into a surah can't quietly redefine what the user picked.
        if (persistMode) PlayerSettings.playbackMode = requestedMode
        PlayerSettings.repeatMode = requestedRepeat

        currentSurah = when {
            requestedSurah > 0 -> requestedSurah
            startGlobal > 0 -> surahOfGlobal(startGlobal)
            else -> QueueBuilder.learnedSurahs(master).firstOrNull() ?: 1
        }

        var queue = QueueBuilder.buildQueue(master, mode, repeatMode, currentSurah)
        if (queue.isEmpty()) {
            // Fallback: if a surah has no learned ayahs, fall back to the full learned list.
            queue = master
        } else if (mode == PlaybackMode.FULL_SURAH) {
            // Prefer already-downloaded files over streaming, same as any other queue.
            queue = attachLocalPaths(queue)
        }
        tracks = queue

        val startIndex = startIndexFor(queue, startGlobal)

        if (mode == PlaybackMode.WORD_BY_WORD) {
            applyLoopConfig()
            PlayerStateHolder.updateMode(mode, repeatMode)
            PlayerStateHolder.updateQueue(queue, startIndex)
            loadCurrentWordAyah(startIndex, startWord = 0, autoPlay = false)
            return
        }

        val current = player

        // Repeat OFF <-> AYAH share the same queue: keep the playback position and
        // just flip loop config instead of reloading (which restarts the ayah).
        if (current != null && queueMatchesPlayer(queue, current)) {
            applyLoopConfig()
            val playerIndex = current.currentMediaItemIndex
            val resolvedIndex =
                if (startGlobal > 0 && queue.getOrNull(playerIndex)?.globalId != startGlobal && startIndex != playerIndex) {
                    current.seekTo(startIndex, 0L)
                    startIndex
                } else {
                    playerIndex
                }
            PlayerStateHolder.updateMode(mode, repeatMode)
            PlayerStateHolder.updateQueue(queue, resolvedIndex)
            return
        }

        current?.apply {
            setMediaItems(queue.map { it.toMediaItem() }, startIndex, 0L)
            applyLoopConfig()
            prepare()
        }

        PlayerStateHolder.updateMode(mode, repeatMode)
        PlayerStateHolder.updateQueue(queue, startIndex)
    }

    /** Prefers cached local files over streaming for a synthesised (non-learned-list) queue. */
    private fun attachLocalPaths(queue: List<AyahTrack>): List<AyahTrack> =
        com.quran.learnedplayer.data.AudioDownloader(this).attachLocalPathsSync(queue)

    /** Exact match on [startGlobal], else the nearest earlier ayah (keeps position when the
     *  current ayah doesn't exist in the new queue, e.g. leaving a full-surah loop). */
    private fun startIndexFor(queue: List<AyahTrack>, startGlobal: Int): Int {
        if (startGlobal <= 0) return 0
        val exact = queue.indexOfFirst { it.globalId == startGlobal }
        if (exact >= 0) return exact
        return queue.indexOfLast { it.globalId < startGlobal }.coerceAtLeast(0)
    }

    private fun queueMatchesPlayer(queue: List<AyahTrack>, player: ExoPlayer): Boolean {
        if (queue.isEmpty() || player.mediaItemCount != queue.size) return false
        // Queues are ordered and duplicate-free, so first + last id identify them.
        return player.getMediaItemAt(0).mediaId == queue.first().globalId.toString() &&
            player.getMediaItemAt(queue.size - 1).mediaId == queue.last().globalId.toString()
    }

    private fun clearIntroState() {
        if (introStep == IntroStep.NONE) return
        introStep = IntroStep.NONE
        PlayerStateHolder.updateIntroLabel(null)
    }

    /**
     * Rebuilds the current surah queue (after a surah change crossing a FULL_SURAH boundary) and
     * starts from the first ayah, or the last when [startAtEnd] — landing a backward crossing on
     * the previous surah's final ayah, the way the word-by-word reader lands on edge crossings.
     */
    private fun reloadSurah(startAtEnd: Boolean = false) {
        var queue = QueueBuilder.buildQueue(master, mode, repeatMode, currentSurah)
        if (queue.isEmpty()) {
            queue = master
        } else if (mode == PlaybackMode.FULL_SURAH) {
            queue = attachLocalPaths(queue)
        }
        tracks = queue
        val startIndex = if (startAtEnd) (queue.size - 1).coerceAtLeast(0) else 0
        if (mode == PlaybackMode.WORD_BY_WORD) {
            PlayerStateHolder.updateQueue(queue, startIndex)
            loadCurrentWordAyah(startIndex, startWord = if (startAtEnd) Int.MAX_VALUE else 0, autoPlay = true)
            return
        }
        player?.apply {
            setMediaItems(queue.map { it.toMediaItem() }, startIndex, 0L)
            applyLoopConfig()
            prepare()
            play()
        }
        PlayerStateHolder.updateQueue(queue, startIndex)
        updateNotification()
    }

    /** Repeat mode for the active queue, independent of [PlaybackMode]. */
    private fun applyLoopConfig() {
        val p = player ?: return
        // Word files are a per-ayah playlist; looping/wrapping is handled in skip / STATE_ENDED.
        p.repeatMode = when {
            usingWordClips() && repeatMode == RepeatMode.AYAH -> Player.REPEAT_MODE_ONE
            mode == PlaybackMode.WORD_BY_WORD -> Player.REPEAT_MODE_OFF
            repeatMode == RepeatMode.AYAH -> Player.REPEAT_MODE_ONE
            repeatMode == RepeatMode.SURAH -> Player.REPEAT_MODE_ALL
            else -> Player.REPEAT_MODE_OFF
        }
    }

    private fun surahOfGlobal(globalId: Int): Int =
        master.firstOrNull { it.globalId == globalId }?.surah
            ?: com.quran.learnedplayer.data.AyahMapping.globalToSurahAyah(globalId).first

    private fun currentGlobalId(): Int {
        if (mode == PlaybackMode.WORD_BY_WORD) {
            return tracks.getOrNull(wordAyahIndex)?.globalId ?: 0
        }
        val index = if (introStep != IntroStep.NONE) {
            introResumeIndex
        } else {
            player?.currentMediaItemIndex ?: 0
        }
        return tracks.getOrNull(index)?.globalId ?: 0
    }

    private fun readMode(intent: Intent): PlaybackMode {
        val name = intent.getStringExtra(EXTRA_MODE)
        return PlaybackMode.entries.firstOrNull { it.name == name } ?: PlayerSettings.playbackMode
    }

    private fun readRepeat(intent: Intent): RepeatMode {
        val name = intent.getStringExtra(EXTRA_REPEAT)
        return RepeatMode.entries.firstOrNull { it.name == name } ?: PlayerSettings.repeatMode
    }

    fun play() {
        // Capture before cancelRevisionGap() clears it — a manual Play tap while
        // parked in the revision gap must resume the ayah directly, not replay
        // its intro (position is 0 here because we just finished a repeat, not
        // because the ayah is genuinely starting fresh).
        val resumingFromGapPark = revisionGapParked
        cancelRevisionGap()
        if (introStep != IntroStep.NONE) {
            player?.play()
            promoteToForeground()
            return
        }
        val p = player ?: return
        // Play must actually start: ExoPlayer ignores play() when IDLE / empty /
        // STATE_ENDED (ayah finished). Playlist taps used to hide this by seeking first.
        if (p.mediaItemCount == 0 || p.playbackState == Player.STATE_IDLE) {
            val startGlobal = currentGlobalId().takeIf { it > 0 } ?: PlayerSettings.lastGlobalId
            loadForMode(mode, repeatMode, currentSurah, startGlobal)
        }
        if (mode == PlaybackMode.WORD_BY_WORD && p.playbackState == Player.STATE_ENDED) {
            loadCurrentWordAyah(wordAyahIndex, startWord = 0, autoPlay = true)
            promoteToForeground()
            return
        }
        if (p.playbackState == Player.STATE_ENDED) {
            p.seekTo(p.currentMediaItemIndex.coerceAtLeast(0), 0L)
        }
        val track = currentTrack()
        // Only intro when the ayah is starting fresh — resuming a paused ayah 1
        // mid-way must not replay A'udhu + Bismillah.
        val atAyahStart = p.currentPosition < INTRO_REPLAY_THRESHOLD_MS
        if (needsSurahIntro(track) && atAyahStart && !resumingFromGapPark) {
            beginSurahIntro(p.currentMediaItemIndex)
            return
        }
        p.play()
        promoteToForeground()
    }

    private fun beginSurahIntro(index: Int) {
        cancelRevisionGap()
        introResumeIndex = index.coerceIn(0, (tracks.size - 1).coerceAtLeast(0))
        introStep = IntroStep.AUDHU
        PlayerStateHolder.updateIndex(introResumeIndex)
        PlayerStateHolder.updateIntroLabel("A'udhu Billah")
        player?.apply {
            // The queue's repeat mode (REPEAT_MODE_ONE in revision, ALL in surah modes)
            // would loop this single intro clip forever and STATE_ENDED would never
            // fire — so Bismillah and the ayah would never play. Intros never repeat.
            repeatMode = Player.REPEAT_MODE_OFF
            setMediaItem(audhuMediaItem())
            prepare()
            play()
        }
        promoteToForeground()
        updateNotification()
    }

    private fun startIntroBismillah() {
        introStep = IntroStep.BISMILLAH
        PlayerStateHolder.updateIntroLabel("Bismillah")
        player?.apply {
            setMediaItem(bismillahMediaItem())
            prepare()
            play()
        }
        updateNotification()
    }

    private fun finishIntroAndResume() {
        introStep = IntroStep.NONE
        PlayerStateHolder.updateIntroLabel(null)
        PlayerStateHolder.updateProgress(0L, 0L)
        if (master.isEmpty()) return
        val resumeGlobal = tracks.getOrNull(introResumeIndex)?.globalId ?: currentGlobalId()
        loadForMode(mode, repeatMode, currentSurah, resumeGlobal)
        player?.play()
        PlayerStateHolder.updateIndex(introResumeIndex)
        promoteToForeground()
        updateNotification()
    }

    fun pause() {
        // A user pause during the revision gap must stick — drop the queued resume.
        cancelRevisionGap()
        player?.pause()
    }

    fun seekTo(index: Int) {
        cancelRevisionGap()
        if (introStep != IntroStep.NONE) {
            // The player is holding an intro clip, not the queue — reload the queue
            // at the requested ayah instead of seeking into a 1-item playlist.
            clearIntroState()
            val target = tracks.getOrNull(index)?.globalId ?: 0
            loadForMode(mode, repeatMode, currentSurah, target)
            player?.play()
            return
        }
        if (mode == PlaybackMode.WORD_BY_WORD) {
            val ayahIndex = index.coerceIn(0, (tracks.size - 1).coerceAtLeast(0))
            loadCurrentWordAyah(ayahIndex, startWord = 0, autoPlay = player?.playWhenReady == true)
            return
        }
        player?.seekTo(index, 0L)
        PlayerStateHolder.updateIndex(index)
        // Intro (if the target is ayah 1 and we're playing) is triggered by
        // onMediaItemTransition, so no duplicate handling here.
    }

    fun seekTo(positionMs: Long) {
        cancelRevisionGap()
        if (introStep != IntroStep.NONE) {
            val resumeGlobal = tracks.getOrNull(introResumeIndex)?.globalId ?: currentGlobalId()
            clearIntroState()
            loadForMode(mode, repeatMode, currentSurah, resumeGlobal)
        }
        if (ayahSeekActive() && ayahSeekSegments.isNotEmpty()) {
            val idx = ayahSeekSegments.indexOfLast { positionMs >= it.first }.coerceAtLeast(0)
            seekToWord(idx)
            return
        }
        player?.seekTo(positionMs)
        player?.play()
    }

    fun skipNext() {
        if (introStep != IntroStep.NONE) {
            // Skip the intro and go straight to the ayah it was introducing.
            finishIntroAndResume()
            return
        }
        if (mode == PlaybackMode.WORD_BY_WORD) {
            skipWord(forward = true)
            return
        }
        // While the word-by-word reader is open, Next means "next word" for every controller:
        // the notification, lock screen, Bluetooth and Auto all reach the transport through
        // here. The reader only seeks within the current ayah, so nothing below is bypassed.
        if (PlayerStateHolder.stepWord(1)) return
        // Advancing during the revision gap means "move on now" — the gap's pause
        // was ours, so restart playback on the new ayah.
        val resumeAfterGap = revisionGapPending
        cancelRevisionGap()
        // Next always steps one ayah, in every mode — matching swipe/tap. FULL_SURAH is the one
        // case a queue can run out mid-traversal (REPEAT_MODE_ALL under repeat SURAH already
        // wraps within ExoPlayer itself, so this only fires when repeat is OFF/AYAH): running
        // past the last ayah advances into the next surah instead of stalling at the end.
        val atEnd = (player?.currentMediaItemIndex ?: 0) >= tracks.lastIndex
        if (mode == PlaybackMode.FULL_SURAH && repeatMode != RepeatMode.SURAH && atEnd) {
            currentSurah = QueueBuilder.nextSurahAll(currentSurah)
            reloadSurah()
        } else {
            stepMediaItem(forward = true)
            if (resumeAfterGap) play()
            updateNotification()
        }
    }

    fun skipPrevious() {
        if (introStep != IntroStep.NONE) {
            finishIntroAndResume()
            return
        }
        if (mode == PlaybackMode.WORD_BY_WORD) {
            skipWord(forward = false)
            return
        }
        // See skipNext: the word reader consumes this for every controller when it is open.
        if (PlayerStateHolder.stepWord(-1)) return
        val resumeAfterGap = revisionGapPending
        cancelRevisionGap()
        val atStart = (player?.currentMediaItemIndex ?: 0) <= 0
        if (mode == PlaybackMode.FULL_SURAH && repeatMode != RepeatMode.SURAH && atStart) {
            // Land on the previous surah's last ayah, so paging back and forth across the
            // boundary reads continuously — the same convention the word reader uses.
            currentSurah = QueueBuilder.prevSurahAll(currentSurah)
            reloadSurah(startAtEnd = true)
        } else {
            stepMediaItem(forward = false)
            if (resumeAfterGap) play()
            updateNotification()
        }
    }

    /**
     * Moves one item through the queue, wrapping only under repeat SURAH.
     *
     * Deliberately *not* `seekToNextMediaItem`/`seekToPreviousMediaItem`: those resolve their
     * target through `Timeline.getNextWindowIndex(..., repeatMode, ...)`, which under
     * `REPEAT_MODE_ONE` (our repeat-AYAH) answers "the current item" — so they silently re-seek
     * to the same ayah and Next/Previous, swipe and tap all appear dead. Computing the index
     * here keeps a step meaning one ayah in every repeat setting, as the gestures promise.
     */
    private fun stepMediaItem(forward: Boolean) {
        val p = player ?: return
        if (tracks.isEmpty()) return
        val current = p.currentMediaItemIndex
        val target = if (forward) current + 1 else current - 1
        val resolved = when {
            target in tracks.indices -> target
            // Only a looping queue wraps; otherwise stay put at the edge.
            repeatMode == RepeatMode.SURAH -> (target + tracks.size) % tracks.size
            else -> return
        }
        p.seekTo(resolved, 0L)
    }

    fun seekToWord(wordIndex: Int) {
        if (mode != PlaybackMode.WORD_BY_WORD) return
        cancelRevisionGap()
        if (ayahSeekActive()) {
            playAyahSeekWord(wordIndex, play = true)
            return
        }
        val p = player ?: return
        if (wordItems.isEmpty()) return
        val target = wordIndex.coerceIn(0, wordItems.lastIndex)
        p.seekTo(target, 0L)
        PlayerStateHolder.updateWordIndex(target, wordItems.size)
        if (p.playWhenReady) p.play()
        updateNotification()
    }

    fun skipAyah(forward: Boolean) {
        if (mode != PlaybackMode.WORD_BY_WORD) {
            if (forward) skipNext() else skipPrevious()
            return
        }
        cancelRevisionGap()
        advanceWordAyah(forward)
    }

    private fun skipWord(forward: Boolean) {
        if (ayahSeekActive()) {
            skipAyahSeekWord(forward)
            return
        }
        val resumeAfterGap = revisionGapPending
        cancelRevisionGap()
        val current = player?.currentMediaItemIndex ?: 0
        val atEnd = wordItems.isEmpty() || current >= wordItems.lastIndex
        val atStart = wordItems.isEmpty() || current <= 0
        when {
            forward && atEnd -> {
                if (!advanceWordAyah(forward = true)) player?.pause()
            }
            !forward && atStart -> {
                if (!advanceWordAyah(forward = false)) player?.pause()
            }
            else -> {
                val target = if (forward) current + 1 else current - 1
                player?.seekTo(target, 0L)
                PlayerStateHolder.updateWordIndex(target, wordItems.size)
                if (resumeAfterGap) play()
                updateNotification()
            }
        }
    }

    private fun skipAyahSeekWord(forward: Boolean) {
        val resumeAfterGap = revisionGapPending
        cancelRevisionGap()
        val last = (ayahSeekWordCount - 1).coerceAtLeast(0)
        val atEnd = ayahSeekWordCount == 0 || ayahSeekWordIndex >= last
        val atStart = ayahSeekWordIndex <= 0
        when {
            forward && atEnd -> {
                if (!advanceWordAyah(forward = true)) player?.pause()
            }
            !forward && atStart -> {
                if (!advanceWordAyah(forward = false)) player?.pause()
            }
            else -> {
                playAyahSeekWord(
                    if (forward) ayahSeekWordIndex + 1 else ayahSeekWordIndex - 1,
                    play = true,
                )
                if (resumeAfterGap) play()
            }
        }
    }

    private fun maybeParkAtWordEnd(p: Player) {
        if (!ayahSeekActive() || !p.isPlaying) return
        val seg = ayahSeekSegments.getOrNull(ayahSeekWordIndex) ?: return
        val endMs = seg.last + 1
        if (endMs <= 0L) return
        if (p.currentPosition >= (endMs - WORD_END_LEAD_MS).coerceAtLeast(seg.first)) {
            p.pause()
            onAyahSeekWordEnded()
        }
    }

    private fun onAyahSeekWordEnded() {
        when (repeatMode) {
            RepeatMode.AYAH -> parkForRevisionGap()
            RepeatMode.SURAH, RepeatMode.OFF -> skipAyahSeekWord(forward = true)
        }
    }

    private fun onWordPlaylistEnded() {
        when (repeatMode) {
            RepeatMode.AYAH -> {
                val idx = (player?.currentMediaItemIndex ?: wordItems.lastIndex).coerceAtLeast(0)
                if (PlayerSettings.revisionDelaySeconds > 0) {
                    parkForRevisionGap()
                } else {
                    player?.seekTo(idx, 0L)
                    player?.play()
                    PlayerStateHolder.updateWordIndex(idx, wordItems.size)
                }
            }
            RepeatMode.SURAH, RepeatMode.OFF -> advanceWordAyah(forward = true)
        }
    }

    private fun advanceWordAyah(forward: Boolean): Boolean {
        if (tracks.isEmpty()) return false
        val nextIndex = if (forward) wordAyahIndex + 1 else wordAyahIndex - 1
        val resolved = when {
            nextIndex in tracks.indices -> nextIndex
            repeatMode == RepeatMode.SURAH -> (nextIndex + tracks.size) % tracks.size
            else -> return false
        }
        val startWord = if (forward) 0 else Int.MAX_VALUE
        val wasPlaying = player?.playWhenReady == true
        loadCurrentWordAyah(resolved, startWord = startWord, autoPlay = wasPlaying)
        return true
    }

    private fun loadCurrentWordAyah(ayahIndex: Int, startWord: Int, autoPlay: Boolean) {
        if (PlayerSettings.useWordClips) {
            loadWordPlaylist(ayahIndex, startWord, autoPlay)
        } else {
            loadAyahWordSeek(ayahIndex, startWord, autoPlay)
        }
    }

    private fun playAyahSeekWord(wordIndex: Int, play: Boolean) {
        val last = (ayahSeekWordCount - 1).coerceAtLeast(0)
        ayahSeekWordIndex = wordIndex.coerceIn(0, last)
        val start = ayahSeekSegments.getOrNull(ayahSeekWordIndex)?.first ?: 0L
        player?.seekTo(start)
        PlayerStateHolder.updateWordIndex(ayahSeekWordIndex, ayahSeekWordCount)
        if (play) player?.play()
        updateNotification()
    }

    private fun loadAyahWordSeek(ayahIndex: Int, startWord: Int, autoPlay: Boolean) {
        wordItems = emptyList()
        if (tracks.isEmpty()) {
            ayahSeekSegments = emptyList()
            ayahSeekWordCount = 0
            ayahSeekWordIndex = 0
            PlayerStateHolder.updateWordIndex(0, 0)
            return
        }
        wordAyahIndex = ayahIndex.coerceIn(0, tracks.lastIndex)
        val raw = tracks[wordAyahIndex]
        val track = attachLocalPaths(listOf(raw)).first()
        currentSurah = track.surah
        ayahSeekSegments = quranData.timingsFor(PlayerSettings.reciter, track.surah, track.ayah).orEmpty()
        val textCount = quranData.contentWordCount(track.surah, track.ayah)
        ayahSeekWordCount = when {
            ayahSeekSegments.isNotEmpty() -> ayahSeekSegments.size
            else -> textCount
        }
        ayahSeekWordIndex = when {
            ayahSeekWordCount <= 0 -> 0
            startWord == Int.MAX_VALUE -> ayahSeekWordCount - 1
            else -> startWord.coerceIn(0, ayahSeekWordCount - 1)
        }
        val startMs = ayahSeekSegments.getOrNull(ayahSeekWordIndex)?.first ?: 0L
        player?.apply {
            setMediaItems(listOf(track.toMediaItem()), 0, startMs)
            applyLoopConfig()
            prepare()
            if (autoPlay) play()
        }
        PlayerStateHolder.updateQueue(tracks, wordAyahIndex)
        PlayerStateHolder.updateWordIndex(ayahSeekWordIndex, ayahSeekWordCount)
        PlayerStateHolder.updateWordTimings(ayahSeekSegments, exact = ayahSeekSegments.isNotEmpty())
        updateNotification()
    }

    private fun loadWordPlaylist(ayahIndex: Int, startWord: Int, autoPlay: Boolean) {
        if (tracks.isEmpty()) {
            wordItems = emptyList()
            PlayerStateHolder.updateWordIndex(0, 0)
            return
        }
        wordAyahIndex = ayahIndex.coerceIn(0, tracks.lastIndex)
        val track = tracks[wordAyahIndex]
        currentSurah = track.surah
        val reciter = PlayerSettings.wordReciter
        val wordCount = quranData.contentWordCount(track.surah, track.ayah)
        wordItems = wordDownloader.attachLocalPathsSync(
            WordAudio.itemsFor(reciter, track.surah, track.ayah, wordCount),
            reciter,
        )
        val wordStart = when {
            wordItems.isEmpty() -> 0
            startWord == Int.MAX_VALUE -> wordItems.lastIndex
            else -> startWord.coerceIn(0, wordItems.lastIndex)
        }
        player?.apply {
            if (wordItems.isEmpty()) {
                stop()
            } else {
                setMediaItems(wordItems.map { it.toMediaItem() }, wordStart, 0L)
                applyLoopConfig()
                prepare()
                if (autoPlay) play()
            }
        }
        PlayerStateHolder.updateQueue(tracks, wordAyahIndex)
        PlayerStateHolder.updateWordIndex(wordStart, wordItems.size)
        updateNotification()
    }

    private fun WordAudio.toMediaItem(): MediaItem {
        val uri = if (localPath != null) Uri.fromFile(File(localPath)) else Uri.parse(remoteUrl)
        return MediaItem.Builder()
            .setUri(uri)
            .setMediaId(mediaId)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle("${surah}:${ayah} · word ${wordIndex + 1}")
                    .setArtist(PlayerSettings.wordReciter.displayName)
                    .setAlbumTitle("Word by word")
                    .build(),
            )
            .build()
    }

    private fun AyahTrack.toMediaItem(): MediaItem {
        val uri = when {
            localPath != null -> Uri.fromFile(File(localPath))
            else -> Uri.parse(remoteUrl)
        }
        return MediaItem.Builder()
            .setUri(uri)
            .setMediaId(globalId.toString())
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(label)
                    .setArtist("Maher Al Muaiqly")
                    .setAlbumTitle("Learned Ayahs")
                    .build(),
            )
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.playback_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.playback_channel_desc)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun servicePendingIntent(action: String): PendingIntent {
        val intent = Intent(this, PlaybackService::class.java).setAction(action)
        return PendingIntent.getService(
            this,
            action.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun buildNotification(): android.app.Notification {
        val isPlaying = player?.isPlaying == true
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(
                PlayerStateHolder.uiState.value.introLabel
                    ?: currentTrack()?.label
                    ?: getString(R.string.app_name),
            )
            .setContentText(
                buildString {
                    append(playbackModeLabel(mode, repeatMode))
                    append(" · ")
                    if (mode == PlaybackMode.WORD_BY_WORD) {
                        if (usingWordClips()) {
                            append(PlayerSettings.wordReciter.displayName)
                            if (wordItems.isNotEmpty()) {
                                append(" · word ${(player?.currentMediaItemIndex ?: 0) + 1}/${wordItems.size}")
                            }
                        } else {
                            append(PlayerSettings.reciter.displayName)
                            if (ayahSeekWordCount > 0) {
                                append(" · word ${ayahSeekWordIndex + 1}/$ayahSeekWordCount")
                            }
                        }
                    } else {
                        append(PlayerSettings.reciter.displayName)
                    }
                },
            )
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .setOngoing(isPlaying)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(
                android.R.drawable.ic_media_previous,
                "Previous",
                servicePendingIntent(ACTION_NOTIF_PREV),
            )
            .addAction(
                if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (isPlaying) "Pause" else "Play",
                servicePendingIntent(ACTION_NOTIF_PLAY_PAUSE),
            )
            .addAction(
                android.R.drawable.ic_media_next,
                "Next",
                servicePendingIntent(ACTION_NOTIF_NEXT),
            )

        mediaSession?.let { session ->
            builder.setStyle(
                androidx.media3.session.MediaStyleNotificationHelper.MediaStyle(session)
                    .setShowActionsInCompactView(0, 1, 2),
            )
        }
        return builder.build()
    }

    private fun updateNotification() {
        if (mediaSession == null) return
        runCatching {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, buildNotification())
        }
    }

    private fun promoteToForeground() {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            } else {
                0
            },
        )
    }

    companion object {
        private const val CHANNEL_ID = "playback"
        private const val NOTIFICATION_ID = 1001
        private const val PROGRESS_INTERVAL_MS = 250L

        /** Below this position an ayah counts as "not started" for intro replay purposes. */
        private const val INTRO_REPLAY_THRESHOLD_MS = 500L

        /**
         * How far before an ayah's natural end to preemptively pause for the revision
         * gap. Must exceed [PROGRESS_INTERVAL_MS] so at least one tick always lands
         * inside this window before the loop would otherwise fire.
         */
        private const val REVISION_GAP_LEAD_MS = 350L
        private const val WORD_END_LEAD_MS = 60L

        const val ACTION_LOAD_PLAYLIST = "com.quran.learnedplayer.LOAD_PLAYLIST"
        const val ACTION_SET_MODE = "com.quran.learnedplayer.SET_MODE"
        const val ACTION_NOTIF_PREV = "com.quran.learnedplayer.NOTIF_PREV"
        const val ACTION_NOTIF_NEXT = "com.quran.learnedplayer.NOTIF_NEXT"
        const val ACTION_NOTIF_PLAY_PAUSE = "com.quran.learnedplayer.NOTIF_PLAY_PAUSE"
        const val EXTRA_MODE = "extra_mode"
        const val EXTRA_REPEAT = "extra_repeat"

        /** False for a queue load that borrows a mode for one jump (see
         *  `PlayerViewModel.playBookmark`) — the session switches, the saved choice doesn't. */
        const val EXTRA_PERSIST_MODE = "extra_persist_mode"
        const val EXTRA_SURAH = "extra_surah"
        const val EXTRA_START_GLOBAL = "extra_start_global"
        const val EXTRA_AUTO_PLAY = "extra_auto_play"
    }
}
