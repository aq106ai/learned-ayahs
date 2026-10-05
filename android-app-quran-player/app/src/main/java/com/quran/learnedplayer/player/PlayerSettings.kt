package com.quran.learnedplayer.player

import android.content.Context
import android.content.SharedPreferences
import com.quran.learnedplayer.data.AppTheme
import com.quran.learnedplayer.data.LearnedAyahsStore
import com.quran.learnedplayer.data.Reciter
import com.quran.learnedplayer.data.SurahNames
import com.quran.learnedplayer.data.WordReciter
import com.quran.learnedplayer.ui.theme.applyAppTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What plays: your marked ayahs, or a full surah streamed end to end. */
enum class PlaybackMode {
    /** Play through your learned ayahs. */
    REVISE,

    /** Stream every ayah of the current surah, learned or not. */
    FULL_SURAH,

    /**
     * Play learned ayahs one word at a time. Default audio is the selected ayah reciter
     * (seek inside the ayah MP3). [PlayerSettings.useWordClips] switches to Quran.com
     * isolated word files. The reader is the word-by-word screen; Next/Previous step words.
     */
    WORD_BY_WORD,
    ;

    val label: String
        get() = when (this) {
            REVISE -> "Learned ayahs"
            FULL_SURAH -> "Full surah"
            WORD_BY_WORD -> "Word by word"
        }

    companion object {
        val DEFAULT = REVISE

        fun fromKey(key: String?): PlaybackMode = entries.firstOrNull { it.name == key } ?: DEFAULT
    }
}

/**
 * How the active queue repeats. Orthogonal to [PlaybackMode] — either mode can combine with
 * any repeat setting.
 */
enum class RepeatMode {
    /** No loop; Next/Previous move through the queue once. */
    OFF,

    /** The current surah's queue loops end to end. */
    SURAH,

    /** The current ayah repeats; [PlayerSettings.revisionDelaySeconds] pauses between repeats. */
    AYAH,
    ;

    val label: String
        get() = when (this) {
            OFF -> "Off"
            SURAH -> "Surah"
            AYAH -> "Ayah"
        }

    fun labelFor(playbackMode: PlaybackMode): String =
        if (playbackMode == PlaybackMode.WORD_BY_WORD && this == AYAH) "Word" else label

    companion object {
        val DEFAULT = OFF

        fun fromKey(key: String?): RepeatMode = entries.firstOrNull { it.name == key } ?: DEFAULT
    }
}

/**
 * How the whole-ayah reader handles an ayah too tall to fit one screen (2:282 is the extreme
 * case — the Qur'an's longest ayah).
 */
enum class AyahOverflowMode {
    /** Scroll to keep the word being recited centred as it plays. */
    AUTO_SCROLL,

    /** Shrink the ayah's font just enough that the whole thing fits without scrolling. */
    FIT_TO_SCREEN,
    ;

    val label: String
        get() = when (this) {
            AUTO_SCROLL -> "Auto scroll"
            FIT_TO_SCREEN -> "Fit to screen"
        }

    companion object {
        val DEFAULT = AUTO_SCROLL

        fun fromKey(key: String?): AyahOverflowMode =
            entries.firstOrNull { it.name == key } ?: DEFAULT
    }
}

/** The label shown wherever the active mode is surfaced (notification, mini bar, playlist headers). */
fun playbackModeLabel(mode: PlaybackMode, repeatMode: RepeatMode): String = when (mode) {
    PlaybackMode.FULL_SURAH -> when (repeatMode) {
        RepeatMode.OFF -> "Full surah"
        RepeatMode.SURAH -> "Full surah · repeat"
        RepeatMode.AYAH -> "Full surah · repeat ayah"
    }
    PlaybackMode.REVISE -> when (repeatMode) {
        RepeatMode.OFF -> "Learned ayahs"
        RepeatMode.SURAH -> "Learned ayahs · repeat surah"
        RepeatMode.AYAH -> "Learned ayahs · repeat ayah"
    }
    PlaybackMode.WORD_BY_WORD -> when (repeatMode) {
        RepeatMode.OFF -> "Word by word"
        RepeatMode.SURAH -> "Word by word"
        RepeatMode.AYAH -> "Word by word · repeat word"
    }
}

object PlayerSettings {
    private const val PREFS = "player_settings"
    private const val KEY_REVISION_DELAY = "revision_delay_seconds"
    private const val KEY_SEEN_INTRO = "seen_intro"
    private const val KEY_RECITER = "reciter"
    private const val KEY_WORD_TRANSLATIONS = "show_word_translations"
    private const val KEY_APP_THEME = "app_theme"
    private const val KEY_SWIPE_TO_NAVIGATE = "swipe_to_navigate"
    private const val KEY_TAP_TO_ADVANCE = "tap_to_advance"
    private const val KEY_PLAYBACK_MODE = "playback_mode"
    private const val KEY_REPEAT_MODE = "repeat_mode"
    private const val KEY_LAST_GLOBAL_ID = "last_global_id"
    private const val KEY_AYAH_OVERFLOW_MODE = "ayah_overflow_mode"
    private const val KEY_AUTO_PLAY_MISTAKE_AUDIO = "recitation_auto_play_mistake"
    private const val KEY_AUTO_ADVANCE_SUCCESS = "recitation_auto_advance_success"
    private const val KEY_PREFER_OFFLINE_DEVICE_SPEECH = "recitation_prefer_offline_device_speech"
    private const val KEY_WORD_RECITER = "word_reciter"
    private const val KEY_RECITE_BETA_ENABLED = "recite_beta_enabled"
    private const val KEY_SEEK_INSIDE_AYAH_AUDIO = "seek_inside_ayah_audio"
    private const val KEY_USE_WORD_CLIPS = "use_word_clips"

    /** Selectable pause lengths (seconds) between repeats under [RepeatMode.AYAH]. 0 = no pause. */
    val REVISION_DELAY_CHOICES = listOf(0, 3, 5, 10)

    private var prefs: SharedPreferences? = null

    /** Call once from Application.onCreate to restore persisted settings. */
    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _revisionDelay.value = prefs?.getInt(KEY_REVISION_DELAY, 0) ?: 0
        _seenIntro.value = prefs?.getBoolean(KEY_SEEN_INTRO, false) ?: false
        _reciter.value = Reciter.fromKey(prefs?.getString(KEY_RECITER, null))
        _showWordTranslations.value = prefs?.getBoolean(KEY_WORD_TRANSLATIONS, true) ?: true
        _appTheme.value = AppTheme.fromKey(prefs?.getString(KEY_APP_THEME, null))
        applyAppTheme(_appTheme.value)
        _swipeToNavigate.value = prefs?.getBoolean(KEY_SWIPE_TO_NAVIGATE, true) ?: true
        _tapToAdvance.value = prefs?.getBoolean(KEY_TAP_TO_ADVANCE, true) ?: true
        _mode.value = PlaybackMode.fromKey(prefs?.getString(KEY_PLAYBACK_MODE, null))
        _repeatMode.value = RepeatMode.fromKey(prefs?.getString(KEY_REPEAT_MODE, null))
        _lastGlobalId.value = prefs?.getInt(KEY_LAST_GLOBAL_ID, 0)?.takeIf { it in 1..LearnedAyahsStore.TOTAL_AYAHS } ?: 0
        _ayahOverflowMode.value = AyahOverflowMode.fromKey(prefs?.getString(KEY_AYAH_OVERFLOW_MODE, null))
        _autoPlayMistakeAudio.value = prefs?.getBoolean(KEY_AUTO_PLAY_MISTAKE_AUDIO, true) ?: true
        _autoAdvanceSuccess.value = prefs?.getBoolean(KEY_AUTO_ADVANCE_SUCCESS, true) ?: true
        _preferOfflineDeviceSpeech.value =
            prefs?.getBoolean(KEY_PREFER_OFFLINE_DEVICE_SPEECH, false) ?: false
        _wordReciter.value = WordReciter.fromKey(prefs?.getString(KEY_WORD_RECITER, null))
        _reciteBetaEnabled.value = prefs?.getBoolean(KEY_RECITE_BETA_ENABLED, false) ?: false
        _seekInsideAyahAudio.value = prefs?.getBoolean(KEY_SEEK_INSIDE_AYAH_AUDIO, false) ?: false
        _useWordClips.value = prefs?.getBoolean(KEY_USE_WORD_CLIPS, false) ?: false
    }

    private val _mode = MutableStateFlow(PlaybackMode.DEFAULT)
    val mode: StateFlow<PlaybackMode> = _mode.asStateFlow()

    var playbackMode: PlaybackMode
        get() = _mode.value
        set(value) {
            _mode.value = value
            prefs?.edit()?.putString(KEY_PLAYBACK_MODE, value.name)?.apply()
        }

    private val _repeatMode = MutableStateFlow(RepeatMode.DEFAULT)
    val repeatModeFlow: StateFlow<RepeatMode> = _repeatMode.asStateFlow()

    var repeatMode: RepeatMode
        get() = _repeatMode.value
        set(value) {
            _repeatMode.value = value
            prefs?.edit()?.putString(KEY_REPEAT_MODE, value.name)?.apply()
        }

    private val _lastGlobalId = MutableStateFlow(0)

    /** The last ayah the user was on (0 = none yet), restored on the next cold start. */
    var lastGlobalId: Int
        get() = _lastGlobalId.value
        set(value) {
            val valid = value.takeIf { it in 1..LearnedAyahsStore.TOTAL_AYAHS } ?: 0
            _lastGlobalId.value = valid
            prefs?.edit()?.putInt(KEY_LAST_GLOBAL_ID, valid)?.commit()
        }

    private val _revisionDelay = MutableStateFlow(0)
    val revisionDelay: StateFlow<Int> = _revisionDelay.asStateFlow()

    /** Pause (seconds) before the ayah repeats under [RepeatMode.AYAH]; 0 repeats immediately. */
    var revisionDelaySeconds: Int
        get() = _revisionDelay.value
        set(value) {
            val clamped = value.coerceIn(0, 60)
            _revisionDelay.value = clamped
            prefs?.edit()?.putInt(KEY_REVISION_DELAY, clamped)?.apply()
        }

    private val _seenIntro = MutableStateFlow(false)
    val seenIntroFlow: StateFlow<Boolean> = _seenIntro.asStateFlow()

    /** Whether the first-run hadith has been shown; it appears once per install. */
    var seenIntro: Boolean
        get() = _seenIntro.value
        set(value) {
            _seenIntro.value = value
            prefs?.edit()?.putBoolean(KEY_SEEN_INTRO, value)?.apply()
        }

    private val _reciter = MutableStateFlow(Reciter.DEFAULT)
    val reciterFlow: StateFlow<Reciter> = _reciter.asStateFlow()

    /**
     * Whose recitation to stream. Read by [com.quran.learnedplayer.data.QuranConstants] to build
     * audio URLs and the cache directory, so changing it requires rebuilding the playlist —
     * go through `PlayerViewModel.setReciter` rather than setting this directly.
     */
    var reciter: Reciter
        get() = _reciter.value
        set(value) {
            _reciter.value = value
            prefs?.edit()?.putString(KEY_RECITER, value.name)?.apply()
        }

    private val _showWordTranslations = MutableStateFlow(true)
    val showWordTranslationsFlow: StateFlow<Boolean> = _showWordTranslations.asStateFlow()

    /** Show each word's English meaning beneath it in the ayah views. */
    var showWordTranslations: Boolean
        get() = _showWordTranslations.value
        set(value) {
            _showWordTranslations.value = value
            prefs?.edit()?.putBoolean(KEY_WORD_TRANSLATIONS, value)?.apply()
        }

    private val _appTheme = MutableStateFlow(AppTheme.DEFAULT)
    val appThemeFlow: StateFlow<AppTheme> = _appTheme.asStateFlow()

    /** The app's colour theme. Setting it restyles every screen immediately. */
    var appTheme: AppTheme
        get() = _appTheme.value
        set(value) {
            _appTheme.value = value
            applyAppTheme(value)
            prefs?.edit()?.putString(KEY_APP_THEME, value.name)?.apply()
        }

    private val _swipeToNavigate = MutableStateFlow(true)
    val swipeToNavigateFlow: StateFlow<Boolean> = _swipeToNavigate.asStateFlow()

    /** Swipe left/right on the player to move between ayahs (or words, in word-by-word). */
    var swipeToNavigate: Boolean
        get() = _swipeToNavigate.value
        set(value) {
            _swipeToNavigate.value = value
            prefs?.edit()?.putBoolean(KEY_SWIPE_TO_NAVIGATE, value)?.apply()
        }

    private val _tapToAdvance = MutableStateFlow(true)
    val tapToAdvanceFlow: StateFlow<Boolean> = _tapToAdvance.asStateFlow()

    /** Tap the player to move to the next ayah (or word, in word-by-word). */
    var tapToAdvance: Boolean
        get() = _tapToAdvance.value
        set(value) {
            _tapToAdvance.value = value
            prefs?.edit()?.putBoolean(KEY_TAP_TO_ADVANCE, value)?.apply()
        }

    private val _ayahOverflowMode = MutableStateFlow(AyahOverflowMode.DEFAULT)
    val ayahOverflowModeFlow: StateFlow<AyahOverflowMode> = _ayahOverflowMode.asStateFlow()

    /** How the whole-ayah reader handles an ayah taller than one screen. */
    var ayahOverflowMode: AyahOverflowMode
        get() = _ayahOverflowMode.value
        set(value) {
            _ayahOverflowMode.value = value
            prefs?.edit()?.putString(KEY_AYAH_OVERFLOW_MODE, value.name)?.apply()
        }

    private val _autoPlayMistakeAudio = MutableStateFlow(true)
    val autoPlayMistakeAudioFlow: StateFlow<Boolean> = _autoPlayMistakeAudio.asStateFlow()

    var autoPlayMistakeAudio: Boolean
        get() = _autoPlayMistakeAudio.value
        set(value) {
            _autoPlayMistakeAudio.value = value
            prefs?.edit()?.putBoolean(KEY_AUTO_PLAY_MISTAKE_AUDIO, value)?.apply()
        }

    private val _preferOfflineDeviceSpeech = MutableStateFlow(false)
    val preferOfflineDeviceSpeechFlow: StateFlow<Boolean> = _preferOfflineDeviceSpeech.asStateFlow()

    /**
     * Ask the phone speech service for its **on-device** Arabic model rather than the network one.
     *
     * Only meaningful once the user has installed an Arabic offline voice pack. Until Android 13
     * there is no API to fetch one, so Settings links out to the system screen instead. Previously
     * this was set only as a fallback after a network error; as a preference it also answers a
     * question worth knowing the answer to — whether Google's offline Arabic is as accurate as its
     * online Arabic, which decides how much of Recite can work with no connection.
     */
    var preferOfflineDeviceSpeech: Boolean
        get() = _preferOfflineDeviceSpeech.value
        set(value) {
            _preferOfflineDeviceSpeech.value = value
            prefs?.edit()?.putBoolean(KEY_PREFER_OFFLINE_DEVICE_SPEECH, value)?.apply()
        }

    private val _autoAdvanceSuccess = MutableStateFlow(true)
    val autoAdvanceSuccessFlow: StateFlow<Boolean> = _autoAdvanceSuccess.asStateFlow()

    var autoAdvanceSuccess: Boolean
        get() = _autoAdvanceSuccess.value
        set(value) {
            _autoAdvanceSuccess.value = value
            prefs?.edit()?.putBoolean(KEY_AUTO_ADVANCE_SUCCESS, value)?.apply()
        }

    private val _wordReciter = MutableStateFlow(WordReciter.DEFAULT)
    val wordReciterFlow: StateFlow<WordReciter> = _wordReciter.asStateFlow()

    /** Whose per-word MP3s to stream/cache in word-by-word mode and recite/review. */
    var wordReciter: WordReciter
        get() = _wordReciter.value
        set(value) {
            _wordReciter.value = value
            prefs?.edit()?.putString(KEY_WORD_RECITER, value.name)?.apply()
        }

    private val _reciteBetaEnabled = MutableStateFlow(false)
    val reciteBetaEnabledFlow: StateFlow<Boolean> = _reciteBetaEnabled.asStateFlow()

    /**
     * Whether the user has opted in to Recite & review.
     *
     * Off until deliberately switched on, behind a dialog saying plainly that it is a beta. The
     * feature depends on the phone's speech service and on recognising classical Quranic Arabic,
     * and neither is reliable enough to put in front of someone who has not chosen it — a coach
     * that corrects a correct recitation is worse than no coach, and worse still if it arrives
     * uninvited.
     */
    var reciteBetaEnabled: Boolean
        get() = _reciteBetaEnabled.value
        set(value) {
            _reciteBetaEnabled.value = value
            prefs?.edit()?.putBoolean(KEY_RECITE_BETA_ENABLED, value)?.apply()
        }

    private val _useWordClips = MutableStateFlow(false)
    val useWordClipsFlow: StateFlow<Boolean> = _useWordClips.asStateFlow()

    /**
     * Play → Word by word: Quran.com isolated clips when true; the ayah reciter (seek
     * to each word) when false. Recite & review always uses clips regardless of this.
     */
    var useWordClips: Boolean
        get() = _useWordClips.value
        set(value) {
            _useWordClips.value = value
            prefs?.edit()?.putBoolean(KEY_USE_WORD_CLIPS, value)?.apply()
        }

    private val _seekInsideAyahAudio = MutableStateFlow(false)
    val seekInsideAyahAudioFlow: StateFlow<Boolean> = _seekInsideAyahAudio.asStateFlow()

    /**
     * In Learned / Full surah, seek the ayah MP3 to a tapped or focused word's QUL timestamp.
     * WORD_BY_WORD mode always plays word files and ignores this.
     */
    var seekInsideAyahAudio: Boolean
        get() = _seekInsideAyahAudio.value
        set(value) {
            _seekInsideAyahAudio.value = value
            prefs?.edit()?.putBoolean(KEY_SEEK_INSIDE_AYAH_AUDIO, value)?.apply()
        }

}

data class PlayerUiState(
    val isLoading: Boolean = false,
    val loadingMessage: String = "Scanning Downloads…",
    val downloadProgress: String? = null,
    val error: String? = null,
    val sourceFileName: String? = null,
    val tracks: List<com.quran.learnedplayer.data.AyahTrack> = emptyList(),
    val currentIndex: Int = 0,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val mode: PlaybackMode = PlaybackMode.DEFAULT,
    val repeatMode: RepeatMode = RepeatMode.DEFAULT,
    val ayahWords: List<com.quran.learnedplayer.data.AyahWord> = emptyList(),
    val ayahTextLoading: Boolean = false,
    /** Exact per-word [startMs, endMs) recitation segments for the current ayah. */
    val wordSegments: List<LongRange> = emptyList(),
    val wordSyncExact: Boolean = false,
    /** 0-based content-word index while [mode] is WORD_BY_WORD. */
    val currentWordIndex: Int = 0,
    val wordCount: Int = 0,
    val introLabel: String? = null,
    val audhuCached: Boolean = false,
    val bismillahCached: Boolean = false,
) {
    val currentTrack: com.quran.learnedplayer.data.AyahTrack?
        get() = tracks.getOrNull(currentIndex)

    val fullscreenMeta: String
        get() {
            introLabel?.let { return it }
            val track = currentTrack ?: return ""
            return SurahNames.trackTitle(track.surah, track.ayah)
        }

    /**
     * True when the queue is scoped to a single surah — either streaming it whole, or
     * looping just its learned ayahs — so the UI should show the surah name rather than a
     * position within the whole learned list.
     */
    val isSingleSurahQueue: Boolean
        get() = mode == PlaybackMode.FULL_SURAH || repeatMode == RepeatMode.SURAH

    val modeLabel: String
        get() = playbackModeLabel(mode, repeatMode)
}
