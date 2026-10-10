package com.quran.learnedplayer.player

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.quran.learnedplayer.data.AyahMapping
import com.quran.learnedplayer.data.AyahTrack
import com.quran.learnedplayer.data.AyahWord
import com.quran.learnedplayer.data.BookmarksStore
import com.quran.learnedplayer.data.LearnedAyahsStore
import com.quran.learnedplayer.data.LearnedAyahsExport
import com.quran.learnedplayer.data.LibraryRepository
import com.quran.learnedplayer.data.PlaylistSnapshot
import com.quran.learnedplayer.data.PlaylistStore
import com.quran.learnedplayer.data.QuranConstants
import com.quran.learnedplayer.data.QuranDataRepository
import com.quran.learnedplayer.data.QueueBuilder
import com.quran.learnedplayer.data.Reciter
import com.quran.learnedplayer.service.PlaybackService
import com.quran.learnedplayer.service.PlayerStateHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One ayah of a surah as shown in the browse/mark screen. */
data class SurahAyah(
    val ayah: Int,
    val globalId: Int,
    val words: List<AyahWord>,
)

class PlayerViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = LibraryRepository(application)
    private val quranData = QuranDataRepository(application)
    private var downloadJob: Job? = null
    /** The ayah whose words are currently in the UI state (null: none loaded). */
    private var textGlobalId: Int? = null

    /** Bumped to reload the current ayah's text and timings: a new playlist, a new reciter. */
    private val textReload = MutableStateFlow(0)

    val uiState: StateFlow<PlayerUiState> = PlayerStateHolder.uiState.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = PlayerUiState(),
    )

    /** Ayahs the user has marked learned in-app (global ids), for the browse/mark UI. */
    val learnedIds: StateFlow<Set<Int>> = LearnedAyahsStore.learnedIds

    /** Ayahs pinned for quick access from the playlist panel — independent of the learned list. */
    val bookmarkedIds: StateFlow<Set<Int>> = BookmarksStore.bookmarkedIds

    fun toggleBookmark(globalId: Int) = BookmarksStore.toggle(globalId)

    val reciter: StateFlow<Reciter> = PlayerSettings.reciterFlow

    val wordReciter: StateFlow<com.quran.learnedplayer.data.WordReciter> = PlayerSettings.wordReciterFlow

    val useWordClips: StateFlow<Boolean> = PlayerSettings.useWordClipsFlow

    val showWordTranslations: StateFlow<Boolean> = PlayerSettings.showWordTranslationsFlow

    val swipeToNavigate: StateFlow<Boolean> = PlayerSettings.swipeToNavigateFlow

    val tapToAdvance: StateFlow<Boolean> = PlayerSettings.tapToAdvanceFlow

    fun setShowWordTranslations(show: Boolean) {
        PlayerSettings.showWordTranslations = show
    }

    fun setSwipeToNavigate(enabled: Boolean) {
        PlayerSettings.swipeToNavigate = enabled
    }

    fun setTapToAdvance(enabled: Boolean) {
        PlayerSettings.tapToAdvance = enabled
    }

    /**
     * Switches reciter and rebuilds the playlist: every track's remote URL and cache path is
     * derived from the reciter, so the existing tracks would otherwise still point at the old
     * one. Playback stops rather than continuing in the previous voice mid-ayah.
     */
    fun setReciter(reciter: Reciter) {
        if (PlayerSettings.reciter == reciter) return
        if (uiState.value.isPlaying) pause()
        PlayerSettings.reciter = reciter
        textReload.value++ // word timings belong to the recitation: resolve them again
        refreshPlaylist()
    }

    fun setWordReciter(reciter: com.quran.learnedplayer.data.WordReciter) {
        if (PlayerSettings.wordReciter == reciter) return
        if (uiState.value.isPlaying && uiState.value.mode == PlaybackMode.WORD_BY_WORD) pause()
        PlayerSettings.wordReciter = reciter
        if (PlayerSettings.playbackMode == PlaybackMode.WORD_BY_WORD && PlayerSettings.useWordClips) {
            applyModeChange(PlaybackMode.WORD_BY_WORD, PlayerSettings.repeatMode)
        }
    }

    fun setUseWordClips(enabled: Boolean) {
        if (PlayerSettings.useWordClips == enabled) return
        if (uiState.value.isPlaying && uiState.value.mode == PlaybackMode.WORD_BY_WORD) pause()
        PlayerSettings.useWordClips = enabled
        if (PlayerSettings.playbackMode == PlaybackMode.WORD_BY_WORD) {
            applyModeChange(PlaybackMode.WORD_BY_WORD, PlayerSettings.repeatMode)
        }
    }

    /**
     * Every ayah of [surah] with its words, for the surah browse/mark screen. Served from the
     * bundled asset (all 6236 ayahs), so it works offline; the first call also warms the cache.
     */
    suspend fun loadSurahWords(surah: Int): List<SurahAyah> = withContext(Dispatchers.IO) {
        val count = QuranConstants.VERSE_COUNTS.getOrNull(surah - 1) ?: return@withContext emptyList()
        (1..count).map { ayah ->
            SurahAyah(
                ayah = ayah,
                globalId = AyahMapping.surahAyahToGlobal(surah, ayah),
                words = quranData.wordsFor(surah, ayah),
            )
        }
    }

    init {
        viewModelScope.launch {
            // collectLatest: a newer ayah (or a reload) cancels a load still in flight, so a
            // stale load can neither overwrite the current ayah's words nor be thrown away
            // without a replacement — which left the reader stuck on "Loading ayah text…".
            combine(
                uiState.map { it.currentTrack?.globalId }.distinctUntilChanged(),
                textReload,
            ) { globalId, _ -> globalId }
                .collectLatest {
                    val track = uiState.value.currentTrack ?: return@collectLatest
                    PlayerSettings.lastGlobalId = track.globalId
                    loadAyahText(track)
                }
        }
    }

    /** The user's own marks are the only source; nothing is read from storage. */
    fun refreshPlaylist(autoPlay: Boolean = false) {
        viewModelScope.launch {
            PlayerStateHolder.setLoading("Loading your learned ayahs…")
            onPlaylistLoaded(
                repository.buildLocalSnapshot(LearnedAyahsStore.learnedIds.value),
                autoPlay,
            )
        }
    }

    /**
     * Marks/unmarks [globalId]. Rebuilds the master silently; the on-screen queue is refreshed in
     * place only when playback isn't running through the service (the service owns its queue and
     * rebuilds on next load).
     */
    fun toggleLearned(globalId: Int) {
        LearnedAyahsStore.toggle(globalId)
        rebuildMaster()
    }

    /** Adds [ids] to the selection — used by import and add-by-description. */
    fun addLearned(ids: Collection<Int>) {
        if (ids.isEmpty()) return
        LearnedAyahsStore.addAll(ids)
        rebuildMaster()
    }

    fun removeLearned(ids: Collection<Int>) {
        if (ids.isEmpty()) return
        LearnedAyahsStore.removeAll(ids)
        rebuildMaster()
    }

    /**
     * Marks or clears every ayah of [surah] in one action.
     *
     * Whole surahs are how people actually describe what they have memorised — "I know Al-Mulk" —
     * and marking 30 ayahs one circle at a time is a chore that put people off recording an
     * accurate list at all. Partial surahs count as not-yet-complete, so the first tap completes
     * the surah rather than clearing the ayahs already marked; only a fully marked surah clears.
     */
    fun setSurahLearned(surah: Int, learned: Boolean) {
        val count = QuranConstants.VERSE_COUNTS.getOrNull(surah - 1) ?: return
        val ids = (1..count).map { AyahMapping.surahAyahToGlobal(surah, it) }
        if (learned) addLearned(ids) else removeLearned(ids)
    }

    private fun rebuildMaster() {
        viewModelScope.launch {
            PlaylistStore.latest = repository.buildLocalSnapshot(LearnedAyahsStore.learnedIds.value)
            if (!PlayerStateHolder.isServiceReady()) rebuildPreview()
        }
    }

    /** Merges a previously exported selection (this app's own sync format) into the current one. */
    fun importExportedFile(uri: Uri) {
        viewModelScope.launch {
            PlayerStateHolder.setLoading("Importing your learned ayahs…")
            repository.readExportedIds(uri)
                .onSuccess { ids ->
                    LearnedAyahsStore.addAll(ids)
                    refreshPlaylist()
                }
                .onFailure { error ->
                    PlayerStateHolder.setError(error.message ?: "Couldn't import that file")
                }
        }
    }

    /** Writes the current selection to [uri] chosen from the SAF create-document picker. */
    fun exportTo(uri: Uri) {
        viewModelScope.launch {
            val payload = LearnedAyahsExport.export(LearnedAyahsStore.learnedIds.value)
            repository.writeExport(uri, payload)
                .onSuccess {
                    PlayerStateHolder.setDownloadProgress(
                        "Exported ${LearnedAyahsStore.learnedIds.value.size} ayahs",
                    )
                }
                .onFailure { error ->
                    PlayerStateHolder.setError(error.message ?: "Couldn't export")
                }
        }
    }

    fun setMode(mode: PlaybackMode) {
        val repeat = if (mode == PlaybackMode.WORD_BY_WORD && PlayerSettings.repeatMode == RepeatMode.SURAH) {
            RepeatMode.OFF
        } else {
            PlayerSettings.repeatMode
        }
        PlayerSettings.playbackMode = mode
        PlayerSettings.repeatMode = repeat
        applyModeChange(mode, repeat)
    }

    fun setRepeatMode(repeat: RepeatMode) {
        val resolved = if (PlayerSettings.playbackMode == PlaybackMode.WORD_BY_WORD && repeat == RepeatMode.SURAH) {
            RepeatMode.OFF
        } else {
            repeat
        }
        PlayerSettings.repeatMode = resolved
        applyModeChange(PlayerSettings.playbackMode, resolved)
    }

    private fun applyModeChange(mode: PlaybackMode, repeat: RepeatMode) {
        PlayerStateHolder.updateMode(mode, repeat)

        val current = uiState.value.currentTrack
        val surah = current?.surah
            ?: QueueBuilder.learnedSurahs(PlaylistStore.latest?.tracks ?: emptyList()).firstOrNull()
            ?: 1

        if (PlayerStateHolder.isServiceReady()) {
            // The service rebuilds the queue and pushes it to the UI — updating it
            // here as well would double-swap the list.
            sendModeIntent(mode, repeat, surah, current?.globalId ?: 0)
        } else {
            rebuildPreview()
        }
    }

    fun setRevisionDelay(seconds: Int) {
        PlayerSettings.revisionDelaySeconds = seconds
    }

    private fun rebuildPreview() {
        val master = PlaylistStore.latest?.tracks ?: emptyList()
        val mode = PlayerSettings.playbackMode
        val repeat = PlayerSettings.repeatMode
        val current = uiState.value.currentTrack
        val surah = current?.surah
            ?: QueueBuilder.learnedSurahs(master).firstOrNull()
            ?: 1
        var queue = QueueBuilder.buildQueue(master, mode, repeat, surah).ifEmpty { master }
        if (mode == PlaybackMode.FULL_SURAH) queue = repository.attachLocalPathsSync(queue)
        val index = queue.indexOfFirst { it.globalId == current?.globalId }.let { exact ->
            when {
                exact >= 0 -> exact
                current != null ->
                    queue.indexOfLast { it.globalId < current.globalId }.coerceAtLeast(0)
                else -> 0
            }
        }
        PlayerStateHolder.updateQueue(queue, index)
    }

    fun downloadAllLearned() {
        val snapshot = PlaylistStore.latest ?: return
        if (downloadJob?.isActive == true) return
        downloadJob = viewModelScope.launch {
            if (PlayerSettings.playbackMode == PlaybackMode.WORD_BY_WORD) {
                downloadLearnedWordAudio()
                return@launch
            }
            val tracks = snapshot.tracks
            val total = tracks.size
            val updated = tracks.toMutableList()
            var done = 0
            var failed = 0

            PlayerStateHolder.setDownloadProgress("Saving intro clips…")
            val (audhuOk, bismillahOk) = withContext(Dispatchers.IO) { repository.cacheIntroClips() }
            PlayerStateHolder.updateIntroCache(audhuOk, bismillahOk)

            for ((i, track) in tracks.withIndex()) {
                val cached = if (track.localPath != null) {
                    track
                } else {
                    withContext(Dispatchers.IO) { repository.cacheTrack(track) }
                }
                if (cached.localPath != null) {
                    updated[i] = cached
                    done++
                } else {
                    failed++
                }
                PlayerStateHolder.setDownloadProgress(
                    "Saving offline… ${done + failed}/$total" +
                        if (failed > 0) " ($failed failed)" else "",
                )
            }
            mergeLocalPaths(updated)
            rebuildPreview()
            PlayerStateHolder.updateIntroCache(repository.isAudhuCached(), repository.isBismillahCached())
            PlayerStateHolder.setDownloadProgress(
                when {
                    failed == 0 && audhuOk && bismillahOk ->
                        "$done ayahs + intro clips saved for offline playback"
                    failed == 0 ->
                        "$done ayahs saved · intro clips ${if (audhuOk && bismillahOk) "saved" else "need connection"}"
                    audhuOk && bismillahOk ->
                        "$done saved · $failed failed · intro clips saved"
                    else ->
                        "$done saved · $failed failed · intro clips need connection"
                },
            )
        }
    }

    private suspend fun downloadLearnedWordAudio() {
        val tracks = PlaylistStore.latest?.tracks ?: return
        val downloader = com.quran.learnedplayer.data.WordAudioDownloader(getApplication())
        PlayerStateHolder.setDownloadProgress("Saving word audio…")
        val (done, failed) = downloader.downloadForTracks(
            tracks,
            wordCountFor = { surah, ayah -> quranData.contentWordCount(surah, ayah) },
            onProgress = { d, t, f ->
                PlayerStateHolder.setDownloadProgress(
                    "Saving word audio… $d/$t" + if (f > 0) " ($f failed)" else "",
                )
            },
        )
        PlayerStateHolder.setDownloadProgress(
            if (failed == 0) "$done word clips saved for offline playback"
            else "$done word clips saved · $failed failed",
        )
    }

    fun downloadRecitationWordClips() {
        if (downloadJob?.isActive == true) return
        downloadJob = viewModelScope.launch { downloadLearnedWordAudio() }
    }

    private fun onPlaylistLoaded(snapshot: PlaylistSnapshot, autoPlay: Boolean) {
        PlaylistStore.latest = snapshot
        textReload.value++
        // Best-effort cosmetic restore for the brief window before the service responds with the
        // authoritative queue (see preparePlaybackService): only exact when the last ayah is in
        // this REVISE-scoped snapshot, which is the common case.
        val restoredIndex = snapshot.tracks.indexOfFirst { it.globalId == PlayerSettings.lastGlobalId }
            .coerceAtLeast(0)
        PlayerStateHolder.updatePlaylist(snapshot, restoredIndex)
        viewModelScope.launch {
            val audhuCached = withContext(Dispatchers.IO) { repository.isAudhuCached() }
            val bismillahCached = withContext(Dispatchers.IO) { repository.isBismillahCached() }
            PlayerStateHolder.updateIntroCache(audhuCached, bismillahCached)
        }
        preparePlaybackService()
        if (autoPlay) {
            playWithService()
        }
    }

    private suspend fun loadAyahText(track: AyahTrack) {
        // A reload for the ayah already on screen keeps its words (re-blanking them would reset
        // the word reader) and only resolves the timings again.
        val haveWords = textGlobalId == track.globalId && PlayerStateHolder.uiState.value.ayahWords.isNotEmpty()
        if (!haveWords) {
            textGlobalId = null
            PlayerStateHolder.updateAyahText(emptyList(), loading = true)
            val words = withContext(Dispatchers.IO) {
                quranData.wordsFor(track.surah, track.ayah)
            }
            textGlobalId = track.globalId
            PlayerStateHolder.updateAyahText(words = words, loading = false)
            if (PlayerSettings.playbackMode == PlaybackMode.WORD_BY_WORD) {
                val count = words.count { !it.isEnd }
                val idx = PlayerStateHolder.uiState.value.currentWordIndex
                PlayerStateHolder.updateWordIndex(if (count == 0) 0 else idx.coerceIn(0, count - 1), count)
            }
        }

        val segments = withContext(Dispatchers.IO) {
            quranData.timingsFor(track.surah, track.ayah)
        }
        PlayerStateHolder.updateWordTimings(
            segments = segments.orEmpty(),
            exact = !segments.isNullOrEmpty(),
        )
    }

    /**
     * Records newly saved files on the *current* master list. Replacing it with the snapshot a long
     * download started from would quietly undo every ayah marked or unmarked while it ran. A file
     * only counts for the same recording: after a reciter change the old reciter's file is not it.
     */
    private fun mergeLocalPaths(saved: List<AyahTrack>) {
        val byId = saved.filter { it.localPath != null }.associateBy { it.globalId }
        if (byId.isEmpty()) return
        val latest = PlaylistStore.latest ?: return
        PlaylistStore.latest = latest.copy(
            tracks = latest.tracks.map { track ->
                val file = byId[track.globalId]
                if (file != null && file.remoteUrl == track.remoteUrl) {
                    track.withLocalPath(file.localPath!!)
                } else {
                    track
                }
            },
        )
    }

    private fun preparePlaybackService() {
        // A user who has marked nothing has an empty playlist. Starting the service anyway would
        // put a foreground media notification on screen for an empty queue.
        if (PlaylistStore.latest?.tracks.isNullOrEmpty()) return
        // The service derives surah + queue + index entirely from startGlobal when surah is 0
        // (loadForMode falls back to surahOfGlobal(startGlobal)), so passing the persisted last
        // position with no surah override is what actually restores it — regardless of mode, and
        // even if that ayah isn't in the learned list. Passing uiState's own (possibly wrong,
        // best-effort) surah here would override that derivation with the wrong surah.
        startPlaybackService(autoPlay = false, startGlobal = PlayerSettings.lastGlobalId, surah = 0)
    }

    private fun playWithService() {
        if (PlaylistStore.latest == null) return
        val current = uiState.value.currentTrack
        // Reach the service now, while the app is in the foreground: the downloads below can take
        // a while, the user may leave the app meanwhile, and Android 8+ refuses to start a
        // service from the background.
        if (!PlayerStateHolder.isServiceReady()) {
            startPlaybackService(autoPlay = false, startGlobal = current?.globalId ?: 0)
        }
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                repository.cacheIntroClips()
                current?.let { track ->
                    val cached = repository.cacheTrack(track)
                    if (cached.localPath != null) mergeLocalPaths(listOf(cached))
                }
            }
            PlayerStateHolder.updateIntroCache(
                repository.isAudhuCached(),
                repository.isBismillahCached(),
            )
            if (PlayerStateHolder.isServiceReady()) {
                PlayerStateHolder.play()
            } else {
                startPlaybackService(autoPlay = true, startGlobal = current?.globalId ?: 0)
            }
        }
    }

    fun playWithServiceForUi() = playWithService()

    fun playOrResume(onRequestPermission: (() -> Unit) -> Unit) {
        if (PlaylistStore.latest == null) return
        if (PlayerStateHolder.isServiceReady()) {
            PlayerStateHolder.play()
        } else {
            onRequestPermission { playWithService() }
        }
    }

    fun pause() {
        if (PlayerStateHolder.isServiceReady()) {
            PlayerStateHolder.pause()
        }
    }

    fun togglePlayPause(onPlayRequested: ((() -> Unit) -> Unit)? = null) {
        if (uiState.value.isPlaying) {
            pause()
        } else if (onPlayRequested != null) {
            onPlayRequested { playWithServiceForUi() }
        } else {
            playWithServiceForUi()
        }
    }

    fun next() {
        // The service applies this too, for external controllers; check here as well so the
        // word reader still steps words before playback has ever started.
        if (!PlayerStateHolder.isServiceReady() && PlayerStateHolder.stepWord(1)) return
        if (PlayerStateHolder.isServiceReady()) {
            PlayerStateHolder.next()
            return
        }
        val state = uiState.value
        if (state.tracks.isEmpty()) return
        // Next always steps one ayah — matching swipe/tap. FULL_SURAH is the one case a queue
        // can run out mid-traversal (repeat SURAH wraps below instead); running past the last
        // ayah crosses into the next surah rather than stalling at the end.
        if (state.mode == PlaybackMode.FULL_SURAH && state.repeatMode != RepeatMode.SURAH &&
            state.currentIndex >= state.tracks.lastIndex
        ) {
            navigateSurahPreview(forward = true)
            return
        }
        val nextIndex = if (state.repeatMode == RepeatMode.SURAH) {
            (state.currentIndex + 1) % state.tracks.size
        } else {
            (state.currentIndex + 1).coerceAtMost(state.tracks.lastIndex)
        }
        PlayerStateHolder.updateIndex(nextIndex)
    }

    fun skipAyah(forward: Boolean) {
        if (PlayerStateHolder.isServiceReady()) {
            PlayerStateHolder.skipAyah(forward)
            return
        }
        if (forward) next() else previous()
    }

    fun playWord(wordIndex: Int) {
        if (PlayerStateHolder.isServiceReady()) {
            PlayerStateHolder.seekToWord(wordIndex)
            PlayerStateHolder.play()
        } else {
            val count = uiState.value.wordCount
            val words = uiState.value.ayahWords.count { !it.isEnd }
            PlayerStateHolder.updateWordIndex(wordIndex, if (count > 0) count else words)
        }
    }

    fun previous() {
        // See next(): covers the word reader before the service exists.
        if (!PlayerStateHolder.isServiceReady() && PlayerStateHolder.stepWord(-1)) return
        if (PlayerStateHolder.isServiceReady()) {
            PlayerStateHolder.previous()
            return
        }
        val state = uiState.value
        if (state.tracks.isEmpty()) return
        if (state.mode == PlaybackMode.FULL_SURAH && state.repeatMode != RepeatMode.SURAH &&
            state.currentIndex <= 0
        ) {
            navigateSurahPreview(forward = false)
            return
        }
        val prevIndex = if (state.repeatMode == RepeatMode.SURAH) {
            (state.currentIndex - 1 + state.tracks.size) % state.tracks.size
        } else {
            (state.currentIndex - 1).coerceAtLeast(0)
        }
        PlayerStateHolder.updateIndex(prevIndex)
    }

    /** Crosses a FULL_SURAH boundary in the preview (pre-playback) path — landing on the first
     *  ayah going forward, or the last ayah going backward, same convention as the service. */
    private fun navigateSurahPreview(forward: Boolean) {
        val master = PlaylistStore.latest?.tracks ?: return
        val state = uiState.value
        val curSurah = state.currentTrack?.surah
            ?: QueueBuilder.learnedSurahs(master).firstOrNull()
            ?: return
        val newSurah = if (forward) {
            QueueBuilder.nextSurahAll(curSurah)
        } else {
            QueueBuilder.prevSurahAll(curSurah)
        }
        var queue = QueueBuilder.buildQueue(master, state.mode, state.repeatMode, newSurah).ifEmpty { master }
        queue = repository.attachLocalPathsSync(queue)
        val startIndex = if (forward) 0 else (queue.size - 1).coerceAtLeast(0)
        PlayerStateHolder.updateQueue(queue, startIndex)
    }

    fun seekTo(index: Int) {
        if (PlayerStateHolder.isServiceReady()) {
            PlayerStateHolder.seekTo(index)
            return
        }
        PlayerStateHolder.updateIndex(index)
        if (uiState.value.isPlaying) {
            playWithServiceForUi()
        }
    }

    fun seekTo(positionMs: Long) {
        if (PlayerStateHolder.isServiceReady()) {
            PlayerStateHolder.seekTo(positionMs)
            PlayerStateHolder.play()
            return
        }
        PlayerStateHolder.updateProgress(positionMs, uiState.value.durationMs)
        playWithServiceForUi()
    }

    /**
     * Moves to [surah]:[ayah] in full-surah mode (streams every ayah of the surah).
     * [startPlayback] is true for an explicit Play-from-ayah action (Surah detail);
     * playlist / bookmark / surah-picker jumps pass false so Play starts audio.
     */
    fun playSurahFrom(surah: Int, ayah: Int) = playSurahFrom(surah, ayah, persistMode = true)

    /**
     * [persistMode] is false for jumps the user didn't frame as "switch to full surah" — a
     * bookmark tap, say. The session still streams the surah (there's no other way to reach an
     * ayah outside the learned queue), but the *saved* mode is left alone, so the next thing
     * that rebuilds the queue — and the next app start — returns to the mode they actually chose.
     */
    fun playSurahFrom(
        surah: Int,
        ayah: Int,
        persistMode: Boolean,
        startPlayback: Boolean = true,
    ) {
        if (PlaylistStore.latest == null) return
        if (persistMode) PlayerSettings.playbackMode = PlaybackMode.FULL_SURAH
        PlayerStateHolder.updateMode(PlaybackMode.FULL_SURAH, PlayerSettings.repeatMode)
        playTrackIn(
            AyahTrack(
                index = ayah,
                globalId = AyahMapping.surahAyahToGlobal(surah, ayah),
                surah = surah,
                ayah = ayah,
                filename = AyahMapping.ayahFilename(surah, ayah),
                remoteUrl = AyahMapping.remoteUrl(surah, ayah),
            ),
            mode = PlaybackMode.FULL_SURAH,
            repeat = PlayerSettings.repeatMode,
            persistMode = persistMode,
            startPlayback = startPlayback,
        )
    }

    /**
     * Jumps to a bookmarked ayah without starting playback. A learned bookmark moves
     * inside the current queue; one that isn't learned borrows full-surah for the
     * session but does not persist that mode.
     */
    fun playBookmark(globalId: Int) {
        val master = PlaylistStore.latest?.tracks ?: return
        val track = master.firstOrNull { it.globalId == globalId }
        if (track != null) {
            selectTrack(track)
        } else {
            val (surah, ayah) = AyahMapping.globalToSurahAyah(globalId)
            playSurahFrom(surah, ayah, persistMode = false, startPlayback = false)
        }
    }

    /** Jump to the first learned ayah of [surah] without starting playback. */
    fun jumpToSurah(surah: Int): Boolean {
        val master = PlaylistStore.latest?.tracks ?: return false
        val track = master.firstOrNull { it.surah == surah } ?: return false
        selectTrack(track)
        return true
    }

    /** Moves to [track] without starting playback. Play starts audio. */
    fun selectTrack(track: AyahTrack) =
        playTrackIn(
            track,
            PlayerSettings.playbackMode,
            PlayerSettings.repeatMode,
            startPlayback = false,
        )

    /** Plays [track] — used when the user explicitly asked to play (Surah detail). */
    fun playTrack(track: AyahTrack) =
        playTrackIn(track, PlayerSettings.playbackMode, PlayerSettings.repeatMode)

    /** [playTrack] with the mode supplied rather than read from settings, so a one-off jump can
     *  use a queue shape the user's saved mode wouldn't build. See [playSurahFrom]. */
    private fun playTrackIn(
        track: AyahTrack,
        mode: PlaybackMode,
        repeat: RepeatMode,
        persistMode: Boolean = true,
        startPlayback: Boolean = true,
    ) {
        val master = PlaylistStore.latest?.tracks ?: return

        if (mode == PlaybackMode.FULL_SURAH || mode == PlaybackMode.WORD_BY_WORD ||
            repeat == RepeatMode.SURAH
        ) {
            var queue = QueueBuilder.buildQueue(master, mode, repeat, track.surah).ifEmpty { master }
            if (mode == PlaybackMode.FULL_SURAH) queue = repository.attachLocalPathsSync(queue)
            val qIndex = queue.indexOfFirst { it.globalId == track.globalId }.coerceAtLeast(0)
            PlayerStateHolder.updateQueue(queue, qIndex)
            if (PlayerStateHolder.isServiceReady()) {
                // The service loads the queue at this ayah itself; seeking here as well
                // would race the intent and briefly play the wrong ayah.
                sendModeIntent(
                    mode, repeat, track.surah, track.globalId,
                    autoPlay = startPlayback, persistMode = persistMode,
                )
            } else if (startPlayback) {
                playWithServiceForUi()
            }
            return
        }

        val masterIndex = master.indexOfFirst { it.globalId == track.globalId }
        if (masterIndex < 0) return
        if (PlayerStateHolder.isServiceReady()) {
            PlayerStateHolder.seekTo(masterIndex)
            if (startPlayback) PlayerStateHolder.play()
        } else {
            PlayerStateHolder.updateIndex(masterIndex)
            if (startPlayback) playWithServiceForUi()
        }
    }

    private fun startPlaybackService(
        autoPlay: Boolean,
        startGlobal: Int,
        surah: Int = uiState.value.currentTrack?.surah ?: 0,
    ) {
        if (PlaylistStore.latest == null) return
        val context = getApplication<Application>()
        val intent = Intent(context, PlaybackService::class.java).apply {
            action = PlaybackService.ACTION_LOAD_PLAYLIST
            putExtra(PlaybackService.EXTRA_AUTO_PLAY, autoPlay)
            putExtra(PlaybackService.EXTRA_MODE, PlayerSettings.playbackMode.name)
            putExtra(PlaybackService.EXTRA_REPEAT, PlayerSettings.repeatMode.name)
            putExtra(PlaybackService.EXTRA_SURAH, surah)
            putExtra(PlaybackService.EXTRA_START_GLOBAL, startGlobal)
        }
        startServiceCompat(context, intent)
    }

    private fun sendModeIntent(
        mode: PlaybackMode,
        repeat: RepeatMode,
        surah: Int,
        startGlobal: Int,
        autoPlay: Boolean = false,
        persistMode: Boolean = true,
    ) {
        val context = getApplication<Application>()
        val intent = Intent(context, PlaybackService::class.java).apply {
            action = PlaybackService.ACTION_SET_MODE
            putExtra(PlaybackService.EXTRA_MODE, mode.name)
            putExtra(PlaybackService.EXTRA_REPEAT, repeat.name)
            putExtra(PlaybackService.EXTRA_SURAH, surah)
            putExtra(PlaybackService.EXTRA_START_GLOBAL, startGlobal)
            putExtra(PlaybackService.EXTRA_AUTO_PLAY, autoPlay)
            putExtra(PlaybackService.EXTRA_PERSIST_MODE, persistMode)
        }
        startServiceCompat(context, intent)
    }

    /**
     * Sends a command to the playback service.
     *
     * A plain start, not startForegroundService: the service enters the foreground itself when
     * playback actually starts, and startForegroundService would oblige it to do so within
     * seconds even for a command that plays nothing — a crash when it doesn't. Commands come from
     * the UI, so the app is in the foreground; should one arrive after it has gone to the
     * background (where Android 8+ refuses to start services), a running service takes it
     * directly, and otherwise it is dropped rather than crashing the app.
     */
    private fun startServiceCompat(context: Application, intent: Intent) {
        try {
            context.startService(intent)
        } catch (e: IllegalStateException) {
            PlayerStateHolder.deliverCommand(intent)
        }
    }
}
