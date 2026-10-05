package com.quran.learnedplayer.service

import com.quran.learnedplayer.data.PlaylistSnapshot
import com.quran.learnedplayer.player.PlayerSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object PlayerStateHolder {
    private val _uiState = MutableStateFlow(com.quran.learnedplayer.player.PlayerUiState())
    val uiState: StateFlow<com.quran.learnedplayer.player.PlayerUiState> = _uiState.asStateFlow()

    private var service: PlaybackService? = null

    fun attachService(playbackService: PlaybackService) {
        service = playbackService
    }

    fun detachService() {
        service = null
    }

    fun isServiceReady(): Boolean = service != null

    /**
     * Set by the word-by-word reader while it is on screen. When present, Next/Previous move one
     * word instead of one ayah — for *every* controller, since the notification, lock screen,
     * Bluetooth and Android Auto all funnel through the service's skip methods.
     */
    private var wordStepHandler: ((Int) -> Boolean)? = null
    private var bypassWordStep = false

    fun setWordStepHandler(handler: ((Int) -> Boolean)?) {
        wordStepHandler = handler
    }

    /** Returns true when a word reader consumed the step, so the caller must not skip the ayah. */
    fun stepWord(delta: Int): Boolean =
        if (bypassWordStep) false else wordStepHandler?.invoke(delta) ?: false

    /**
     * Runs [block] with the word-step hook disabled. The reader needs this when it walks off the
     * end of an ayah and asks for a real ayah change: without it, its own hook would swallow the
     * request and step a word instead, and the reader could never cross a boundary.
     */
    fun skippingWordStep(block: () -> Unit) {
        bypassWordStep = true
        try {
            block()
        } finally {
            bypassWordStep = false
        }
    }

    /**
     * Restores the initial UI state. Exists for the instrumented tests: this holder is a
     * process-wide singleton, so without it one test's error/queue leaks into the next.
     */
    @androidx.annotation.VisibleForTesting
    fun resetState() {
        wordStepHandler = null
        bypassWordStep = false
        _uiState.value = com.quran.learnedplayer.player.PlayerUiState()
    }

    fun updatePlaylist(snapshot: PlaylistSnapshot, index: Int) {
        val safeIndex = index.coerceIn(0, (snapshot.tracks.size - 1).coerceAtLeast(0))
        _uiState.value = _uiState.value.copy(
            isLoading = false,
            error = null,
            sourceFileName = snapshot.sourceFileName,
            tracks = snapshot.tracks,
            currentIndex = safeIndex,
            downloadProgress = null,
            loadingMessage = "Ready",
        )
        snapshot.tracks.getOrNull(safeIndex)?.let { PlayerSettings.lastGlobalId = it.globalId }
    }

    fun updateIndex(index: Int) {
        val tracks = _uiState.value.tracks
        val safeIndex = index.coerceIn(0, (tracks.size - 1).coerceAtLeast(0))
        _uiState.value = _uiState.value.copy(currentIndex = safeIndex)
        // Persist here, not only in the ViewModel collector: Next/Previous from the UI or
        // lock-screen happen on this path, and SharedPreferences.apply() from a collector can
        // lose the write if the process is killed (or a test re-reads prefs) immediately after.
        tracks.getOrNull(safeIndex)?.let { PlayerSettings.lastGlobalId = it.globalId }
    }

    fun updateWordIndex(index: Int, wordCount: Int) {
        val safe = if (wordCount <= 0) 0 else index.coerceIn(0, wordCount - 1)
        _uiState.value = _uiState.value.copy(currentWordIndex = safe, wordCount = wordCount)
    }

    fun updatePlaying(isPlaying: Boolean) {
        _uiState.value = _uiState.value.copy(isPlaying = isPlaying)
    }

    fun updateProgress(positionMs: Long, durationMs: Long) {
        _uiState.value = _uiState.value.copy(positionMs = positionMs, durationMs = durationMs)
    }

    fun updateMode(
        mode: com.quran.learnedplayer.player.PlaybackMode,
        repeatMode: com.quran.learnedplayer.player.RepeatMode,
    ) {
        _uiState.value = _uiState.value.copy(mode = mode, repeatMode = repeatMode)
    }

    /** Replaces the active queue (e.g. when switching to/within a surah mode) keeping the source. */
    fun updateQueue(tracks: List<com.quran.learnedplayer.data.AyahTrack>, index: Int) {
        val safeIndex = index.coerceIn(0, (tracks.size - 1).coerceAtLeast(0))
        _uiState.value = _uiState.value.copy(
            isLoading = false,
            error = null,
            tracks = tracks,
            currentIndex = safeIndex,
        )
        tracks.getOrNull(safeIndex)?.let { PlayerSettings.lastGlobalId = it.globalId }
    }

    fun setLoading(message: String) {
        _uiState.value = _uiState.value.copy(
            isLoading = true,
            loadingMessage = message,
            error = null,
        )
    }

    fun setDownloadProgress(text: String) {
        _uiState.value = _uiState.value.copy(downloadProgress = text)
    }

    fun setError(message: String) {
        _uiState.value = _uiState.value.copy(
            isLoading = false,
            error = message,
            downloadProgress = null,
        )
    }

    fun updateAyahText(
        words: List<com.quran.learnedplayer.data.AyahWord>,
        loading: Boolean,
    ) {
        _uiState.value = _uiState.value.copy(
            ayahWords = words,
            ayahTextLoading = loading,
            // Segments belong to the previous ayah — clear until the new ones load.
            wordSegments = emptyList(),
            wordSyncExact = false,
        )
    }

    fun updateWordTimings(segments: List<LongRange>, exact: Boolean) {
        _uiState.value = _uiState.value.copy(
            wordSegments = segments,
            wordSyncExact = exact,
        )
    }

    fun updateIntroLabel(label: String?) {
        _uiState.value = _uiState.value.copy(
            introLabel = label,
            positionMs = if (label != null) 0L else _uiState.value.positionMs,
            durationMs = if (label != null) 0L else _uiState.value.durationMs,
        )
    }

    fun updateIntroCache(audhuCached: Boolean, bismillahCached: Boolean) {
        _uiState.value = _uiState.value.copy(
            audhuCached = audhuCached,
            bismillahCached = bismillahCached,
        )
    }

    fun play() = service?.play()
    fun pause() = service?.pause()
    fun next() = service?.skipNext()
    fun previous() = service?.skipPrevious()
    fun seekTo(index: Int) = service?.seekTo(index)
    fun seekTo(positionMs: Long) = service?.seekTo(positionMs)
    fun seekToWord(wordIndex: Int) = service?.seekToWord(wordIndex)
    fun skipAyah(forward: Boolean) = service?.skipAyah(forward)
}
