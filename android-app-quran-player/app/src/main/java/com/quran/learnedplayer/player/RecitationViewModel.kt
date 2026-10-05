package com.quran.learnedplayer.player

import android.app.Application
import android.os.SystemClock
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.quran.learnedplayer.data.ArabicTextNormalizer
import com.quran.learnedplayer.data.ArabicWordAligner
import com.quran.learnedplayer.data.AyahMapping
import com.quran.learnedplayer.data.AyahTrack
import com.quran.learnedplayer.data.AyahWord
import com.quran.learnedplayer.data.LearnedAyahsStore
import com.quran.learnedplayer.data.PlaylistStore
import com.quran.learnedplayer.data.QuranDataRepository
import com.quran.learnedplayer.data.RecitationTuning
import com.quran.learnedplayer.data.RecitationEvaluator
import com.quran.learnedplayer.data.RecitationIntroFilter
import com.quran.learnedplayer.data.RecitationResult
import com.quran.learnedplayer.data.WordAudioDownloader
import com.quran.learnedplayer.data.WordEvaluation
import com.quran.learnedplayer.data.WordEvaluationStatus
import com.quran.learnedplayer.data.WrongAyahDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One ayah's outcome inside a run. Kept so the recap can point at the exact words missed. */
data class RecitationAyahOutcome(
    val globalId: Int,
    val surah: Int,
    val ayah: Int,
    val correctCount: Int,
    val totalContentWords: Int,
    val mistakenWordIndices: List<Int>,
) {
    val isPerfect: Boolean get() = mistakenWordIndices.isEmpty() && correctCount == totalContentWords
}

/**
 * The recap shown once a run ends, not after every ayah.
 *
 * Stopping to acknowledge a card between ayahs breaks the rhythm of revision — the point is to
 * recite a surah the way you would to a teacher and be told afterwards how it went.
 */
data class RecitationSessionReport(
    val ayahs: List<RecitationAyahOutcome>,
    val reachedEndOfSurah: Boolean,
) {
    val totalContentWords: Int get() = ayahs.sumOf { it.totalContentWords }
    val correctCount: Int get() = ayahs.sumOf { it.correctCount }
    val mistakeCount: Int get() = ayahs.sumOf { it.mistakenWordIndices.size }
    val perfectAyahs: Int get() = ayahs.count { it.isPerfect }
    val surah: Int? get() = ayahs.firstOrNull()?.surah?.takeIf { s -> ayahs.all { it.surah == s } }
    val accuracyPercent: Int
        get() = if (totalContentWords > 0) correctCount * 100 / totalContentWords else 0
}

data class RecitePackProgress(
    val message: String,
    val fraction: Float = 0f,
)

class RecitationViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = QuranDataRepository(application)
    private val wordAudioDownloader = WordAudioDownloader(application)
    private val wordPlayer = WordAudioPlayer(application)

    /** The one recogniser. See [RecitationSpeechManager] for why there is no longer a choice. */
    private val speech = RecitationSpeechManager(application)

    private val _learnedAyahs = MutableStateFlow<List<Int>>(emptyList())
    val learnedAyahs: StateFlow<List<Int>> = _learnedAyahs.asStateFlow()

    private val _currentGlobalId = MutableStateFlow(1)
    val currentGlobalId: StateFlow<Int> = _currentGlobalId.asStateFlow()

    private val _surahAndAyah = MutableStateFlow(Pair(1, 1))
    val surahAndAyah: StateFlow<Pair<Int, Int>> = _surahAndAyah.asStateFlow()

    private val _currentWords = MutableStateFlow<List<AyahWord>>(emptyList())
    val currentWords: StateFlow<List<AyahWord>> = _currentWords.asStateFlow()

    private val _evaluationResult = MutableStateFlow<RecitationResult?>(null)
    val evaluationResult: StateFlow<RecitationResult?> = _evaluationResult.asStateFlow()

    private val _wrongAyahFeedback = MutableStateFlow<String?>(null)
    val wrongAyahFeedback: StateFlow<String?> = _wrongAyahFeedback.asStateFlow()

    private val _detectedWrongAyahId = MutableStateFlow<Int?>(null)
    val detectedWrongAyahId: StateFlow<Int?> = _detectedWrongAyahId.asStateFlow()

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _recognizedText = MutableStateFlow("")
    val recognizedText: StateFlow<String> = _recognizedText.asStateFlow()

    private val _rmsDb = MutableStateFlow(0f)
    val rmsDb: StateFlow<Float> = _rmsDb.asStateFlow()

    private val _speechError = MutableStateFlow<String?>(null)
    val speechError: StateFlow<String?> = _speechError.asStateFlow()

    private val _isAutoPlayingMistake = MutableStateFlow(false)
    val isAutoPlayingMistake: StateFlow<Boolean> = _isAutoPlayingMistake.asStateFlow()

    /**
     * The mistake the coach has actually decided to believe, or null.
     *
     * **This, not `evaluationResult.firstMistake`, is what the screen may call a mistake.** Every
     * gate in [applyEvaluation] — corroboration across passes, [isConfidentMistake]'s near-miss
     * band — decides whether a word is really wrong, and all of it used to govern only the
     * correction clip and the recap. The UI read the raw evaluation instead, so a single transient
     * decode popped a red "Mistake Detected" card for a word the coach itself had dismissed: on
     * At-Takathur 102:1 the recognizer offered `الحاكم` for أَلْهَىٰكُمُ, the coach correctly stayed
     * silent, and the student was still told they were wrong.
     */
    private val _confirmedMistake = MutableStateFlow<WordEvaluation?>(null)
    val confirmedMistake: StateFlow<WordEvaluation?> = _confirmedMistake.asStateFlow()

    /** Every word index the coach believes was wrong this ayah — what may be painted red. */
    private val _believedMistakeIndices = MutableStateFlow<Set<Int>>(emptySet())
    val believedMistakeIndices: StateFlow<Set<Int>> = _believedMistakeIndices.asStateFlow()

    private val _packReady = MutableStateFlow(true)
    val packReady: StateFlow<Boolean> = _packReady.asStateFlow()

    private val _packProgress = MutableStateFlow<RecitePackProgress?>(null)
    val packProgress: StateFlow<RecitePackProgress?> = _packProgress.asStateFlow()

    private val _sessionReport = MutableStateFlow<RecitationSessionReport?>(null)
    val sessionReport: StateFlow<RecitationSessionReport?> = _sessionReport.asStateFlow()

    /** Outcomes for the ayahs recited so far in this run, oldest first. */
    private val runOutcomes = linkedMapOf<Int, RecitationAyahOutcome>()

    private var lastHandledMistakeWordIndex: Int = -1
    private var isAdvancingAyah: Boolean = false
    private var startListeningAfterLoad: Boolean = false
    private var lastPartialLastToken: String? = null
    private var lastPartialRepeat: Int = 0
    private var lockedCorrectCount: Int = 0
    private var mistakeCooldownUntilElapsedMs: Long = 0L

    /**
     * The wrong word currently being corroborated, as `wordIndex:spokenWord`, and how many
     * consecutive evaluations have named it. See [RecitationTuning.MISTAKE_CONFIRMATIONS].
     */
    private var pendingMistakeKey: String? = null
    private var pendingMistakeRepeats: Int = 0

    /**
     * Which words of this ayah have been recited correctly **at least once**, cumulatively.
     *
     * The ayah is finished when every entry is true — not when a single evaluation happens to come
     * back all-correct. Those are very different things. `RecitationResult.isComplete` describes one
     * decode, and after a correction clip `trimToLockedPrefix` rewrites the transcript to the locked
     * prefix, so a forward re-alignment could satisfy it in one step and skip the student straight to
     * the next ayah — which is exactly the "it jumps as soon as I make a mistake" complaint. Tracking
     * it per word means a word the student got wrong must actually be said correctly before the ayah
     * can complete, however many attempts that takes.
     */
    private var wordsEverCorrect = BooleanArray(0)

    /** Correction clips already played per word index, this ayah. See [MAX_CORRECTIONS_PER_WORD]. */
    private val correctionsPerWord = mutableMapOf<Int, Int>()

    /** When speech was last heard, and whether any was heard at all for this ayah. */
    @Volatile private var lastSpeechAtElapsedMs: Long = 0L
    @Volatile private var spokeThisAyah: Boolean = false
    private var learnedOpenings: List<WrongAyahDetector.Opening> = emptyList()
    private val sessionMistakeIndices = linkedSetOf<Int>()
    private val sessionSpokenTokens = mutableListOf<String>()
    private var packPrepareStarted = false

    /** False when this phone is too old for the streamed-audio recogniser Recite is built on. */
    val isRecitationSupported: Boolean get() = speech.isAvailable

    init {
        val initialLearned = LearnedAyahsStore.learnedIds.value.toList().sorted()
        _learnedAyahs.value = initialLearned
        selectAyah(initialLearned.firstOrNull() ?: 1)

        viewModelScope.launch {
            LearnedAyahsStore.learnedIds.collect { learnedSet ->
                val learnedIds = learnedSet.toList().sorted()
                _learnedAyahs.value = learnedIds
                learnedOpenings = withContext(Dispatchers.IO) {
                    learnedIds.map { id ->
                        val (surah, ayah) = AyahMapping.globalToSurahAyah(id)
                        val tokens = repository.wordsFor(surah, ayah)
                            .filter { !it.isEnd }
                            .take(3)
                            .flatMap { ArabicTextNormalizer.tokenize(it.text) }
                        WrongAyahDetector.Opening(id, tokens)
                    }
                }
                val words = _currentWords.value
                val id = _currentGlobalId.value
                if (words.isNotEmpty()) {
                    val contentWords = words.filter { !it.isEnd }
                    val uthmani = contentWords.joinToString(" ") { it.text }
                    val expected = contentWords.flatMap { ArabicTextNormalizer.tokenize(it.text) } +
                        learnedOpenings.filter { it.globalId != id }.flatMap { it.tokens }
                    speech.setExpectedText(uthmani, expected)
                }
            }
        }
        relaySpeech()
        ensureRecitePack()
    }

    private fun relaySpeech() {
        viewModelScope.launch {
            speech.recognizedText.collect { text ->
                _recognizedText.value = text
                onTranscript(text, committed = false)
            }
        }
        viewModelScope.launch {
            speech.committedText.collect { text ->
                // Read at the moment the transcript is handled, so the readings always belong to
                // the utterance being scored. Partials carry no alternatives.
                onTranscript(text, committed = true, alternatives = speech.committedAlternatives)
            }
        }
        viewModelScope.launch { speech.isListening.collect { _isListening.value = it } }
        viewModelScope.launch { speech.rmsDb.collect { _rmsDb.value = it } }
        viewModelScope.launch { speech.errorState.collect { _speechError.value = it } }
    }

    fun ensureRecitePack() {
        if (packPrepareStarted && _packReady.value) return
        packPrepareStarted = true
        viewModelScope.launch {
            // Recite must listen immediately — word clips are for the correction playback,
            // not for recognition, so they can keep downloading while the student recites.
            _packReady.value = true
            if (startListeningAfterLoad && _currentWords.value.isNotEmpty()) {
                startListeningAfterLoad = false
                startRecording()
            }
            val tracks = learnedTracks()
            if (tracks.isNotEmpty()) {
                _packProgress.value = RecitePackProgress("Saving word clips for review…", 0.05f)
                withContext(Dispatchers.IO) {
                    wordAudioDownloader.downloadForTracks(
                        tracks = tracks,
                        wordCountFor = { s, a -> repository.contentWordCount(s, a) },
                        onProgress = { done, total, _ ->
                            val frac = if (total > 0) done.toFloat() / total else 1f
                            _packProgress.value = RecitePackProgress(
                                "Saving word clips… $done/$total",
                                frac,
                            )
                        },
                    )
                }
            }
            _packProgress.value = null
        }
    }

    fun retryPackDownload() {
        packPrepareStarted = false
        _packReady.value = false
        ensureRecitePack()
    }

    private fun learnedTracks(): List<AyahTrack> {
        val fromPlaylist = PlaylistStore.latest?.tracks
        if (!fromPlaylist.isNullOrEmpty()) return fromPlaylist
        return _learnedAyahs.value.mapIndexed { index, globalId ->
            val (surah, ayah) = AyahMapping.globalToSurahAyah(globalId)
            AyahTrack(
                index = index,
                globalId = globalId,
                surah = surah,
                ayah = ayah,
                filename = AyahMapping.ayahFilename(surah, ayah),
                remoteUrl = AyahMapping.remoteUrl(surah, ayah),
            )
        }
    }

    fun loadLearnedAyahs() {
        val learnedIds = LearnedAyahsStore.learnedIds.value.toList().sorted()
        _learnedAyahs.value = learnedIds

        val initialId = learnedIds.firstOrNull() ?: 1
        selectAyah(initialId)

        viewModelScope.launch {
            learnedOpenings = withContext(Dispatchers.IO) {
                learnedIds.map { id ->
                    val (surah, ayah) = AyahMapping.globalToSurahAyah(id)
                    val tokens = repository.wordsFor(surah, ayah)
                        .filter { !it.isEnd }
                        .take(3)
                        .flatMap { ArabicTextNormalizer.tokenize(it.text) }
                    WrongAyahDetector.Opening(id, tokens)
                }
            }
        }
    }

    fun selectAyah(globalId: Int, startListening: Boolean = false) {
        val id = globalId.coerceIn(1, 6236)
        if (_currentWords.value.isNotEmpty()) {
            recordCurrentAyahOutcome()
        }
        _currentGlobalId.value = id
        val (surah, ayah) = AyahMapping.globalToSurahAyah(id)
        _surahAndAyah.value = Pair(surah, ayah)

        resetAyahSessionState()
        startListeningAfterLoad = startListening
        // Moving to the next ayah *inside a run* must not close the microphone — the whole design
        // rests on one AudioRecord spanning the recitation. Clear the transcript and carry on
        // listening; only a deliberate stop closes the mic.
        if (!startListening) speech.stopListening()
        speech.updateRecognizedText("")
        _recognizedText.value = ""
        wordPlayer.stop()
        _currentWords.value = emptyList()

        viewModelScope.launch {
            val words = withContext(Dispatchers.IO) { repository.wordsFor(surah, ayah) }
            if (_currentGlobalId.value != id) return@launch
            _currentWords.value = words
            _evaluationResult.value = RecitationEvaluator.evaluate(words, "", committed = true)
            val contentWords = words.filter { !it.isEnd }
            // Uthmani text biases Whisper's decoder toward this exact ayah; the token list
            // additionally carries other learned ayahs' openings so a wrong-ayah start is
            // still recognisable rather than being forced onto this ayah's words.
            val uthmani = contentWords.joinToString(" ") { it.text }
            val expected = contentWords.flatMap { ArabicTextNormalizer.tokenize(it.text) } +
                learnedOpenings.filter { it.globalId != id }.flatMap { it.tokens }
            speech.setExpectedText(uthmani, expected)
            if (startListeningAfterLoad) {
                startListeningAfterLoad = false
                startRecording()
            }
            prefetchWordClips(id)
        }
    }

    /**
     * Warms the correction clips for this ayah and the next one, in the background.
     *
     * Started after listening begins, never before it: the clips are for *playing a correction*,
     * not for recognising speech, so making the student wait on a download before they may recite
     * would be the wrong trade. But they do have to be there by the time a mistake lands — a clip
     * that is still missing stalls the microphone while the player waits on audio that never
     * arrives, which reads as the coach silently ignoring the mistake.
     *
     * The bulk fill in [ensureRecitePack] covers the whole learned list eventually; this is what
     * makes the ayah in front of the student jump that queue.
     */
    private fun prefetchWordClips(globalId: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            val ids = buildList {
                add(globalId)
                val list = _learnedAyahs.value
                val index = list.indexOf(globalId)
                if (index in 0 until list.lastIndex) add(list[index + 1])
            }
            for (id in ids) {
                val (surah, ayah) = AyahMapping.globalToSurahAyah(id)
                val wordCount = repository.contentWordCount(surah, ayah)
                if (wordCount <= 0) continue
                if (wordAudioDownloader.isAyahCached(surah, ayah, wordCount)) continue
                wordAudioDownloader.downloadForAyah(surah, ayah, wordCount)
            }
        }
    }

    private var ayahStartedAtElapsedMs = 0L

    private fun resetAyahSessionState() {
        lastHandledMistakeWordIndex = -1
        isAdvancingAyah = false
        _isAutoPlayingMistake.value = false
        _wrongAyahFeedback.value = null
        _detectedWrongAyahId.value = null
        lastPartialLastToken = null
        lastPartialRepeat = 0
        lockedCorrectCount = 0
        mistakeCooldownUntilElapsedMs = 0L
        pendingMistakeKey = null
        pendingMistakeRepeats = 0
        correctionsPerWord.clear()
        // Re-sized on the next evaluation, once the ayah's word count is known.
        wordsEverCorrect = BooleanArray(0)
        spokeThisAyah = false
        lastSpeechAtElapsedMs = 0L
        ayahStartedAtElapsedMs = SystemClock.elapsedRealtime()
        lastEvaluationTranscript = ""
        sessionMistakeIndices.clear()
        sessionSpokenTokens.clear()
        _confirmedMistake.value = null
        _believedMistakeIndices.value = emptySet()
    }

    fun startRecording() {
        _packReady.value = true
        if (_isAutoPlayingMistake.value) return
        isAdvancingAyah = false
        _wrongAyahFeedback.value = null
        _detectedWrongAyahId.value = null
        if (_sessionReport.value != null) {
            // Starting again after a recap begins a new run rather than extending the old one.
            _sessionReport.value = null
            runOutcomes.clear()
        }
        _speechError.value = null
        // Prefer the on-device Arabic model when the phone has one, so a recitation keeps working
        // without a connection; the manager falls back to the network recogniser if it does not.
        speech.setPreferOnDevice(PlayerSettings.preferOfflineDeviceSpeech)
        speech.startListening()
    }

    fun stopRecording() {
        speech.stopListening()
        // Bank the ayah in progress, then recap the whole run — stopping half way through a
        // surah should still show what was recited up to that point.
        recordCurrentAyahOutcome()
        publishSessionReport(reachedEndOfSurah = false)
    }

    fun toggleRecording() {
        if (isListening.value) {
            stopRecording()
        } else {
            startRecording()
        }
    }

    fun playWord(wordIndex: Int) {
        val (surah, ayah) = _surahAndAyah.value
        wordPlayer.play(surah, ayah, wordIndex)
    }

    /**
     * Plays a word from any ayah, not just the current one — the run recap can list mistakes
     * from ayahs the student has already moved past.
     */
    fun playWordOf(surah: Int, ayah: Int, wordIndex: Int) {
        wordPlayer.play(surah, ayah, wordIndex)
    }

    fun dismissSessionReport() {
        _sessionReport.value = null
        runOutcomes.clear()
    }

    private var lastEvaluationTranscript: String = ""

    /**
     * The one door transcripts come in by, whatever produced them.
     *
     * Nothing is scored while a correction clip is playing (the student is listening, not
     * reciting), while the run is stepping to the next ayah, or before the pack is ready.
     */
    private fun onTranscript(
        text: String,
        committed: Boolean,
        alternatives: List<String> = emptyList(),
    ) {
        if (text.isBlank() || _isAutoPlayingMistake.value || isAdvancingAyah || !_packReady.value) {
            return
        }
        if (committed) {
            lastPartialLastToken = null
            lastPartialRepeat = 0
        }
        applyEvaluation(text, committed, alternatives)
    }

    /**
     * Feeds a transcript in as though the recognizer had produced it — same door, same guards.
     *
     * The coach's decisions — corroboration, locking a prefix, when an ayah is finished, what
     * reaches the recap — are the part that can be wrong, and they sit downstream of recognition
     * rather than inside it. Driving them from real audio makes them untestable in practice: it
     * needs a phone on Android 13 with a microphone, and CLAUDE.md records why the numbers that
     * come back cannot be trusted anyway — the same clips scored 84%, then 62%, then 53% in one
     * afternoon. `FullSurahRecitationTest` still does the acoustic run and asserts what only real
     * audio can show; this seam is for everything else.
     */
    @VisibleForTesting
    internal fun acceptTranscript(
        transcript: String,
        committed: Boolean = true,
        alternatives: List<String> = emptyList(),
    ) {
        onTranscript(transcript, committed, alternatives)
    }

    private fun applyEvaluation(
        transcript: String,
        committed: Boolean,
        alternatives: List<String> = emptyList(),
    ) {
        val words = _currentWords.value
        if (words.isEmpty()) return

        val transcriptChanged = transcript != lastEvaluationTranscript
        lastEvaluationTranscript = transcript

        // Intros are stripped in exactly one place — inside the evaluator, which has the
        // reference words needed to tell an introduction from an ayah that *is* the basmala.
        // Here we only need the tokens for stability, wrong-ayah detection and the report.
        val tokens = ArabicTextNormalizer.tokenize(
            RecitationIntroFilter.stripLeadingIntrosFromTranscript(
                transcript = transcript,
                ayahOpening = RecitationIntroFilter.openingTokensOf(words),
            ),
        )
        rememberSpoken(tokens)
        if (tokens.isNotEmpty()) {
            spokeThisAyah = true
            if (transcriptChanged || lastSpeechAtElapsedMs == 0L) {
                lastSpeechAtElapsedMs = SystemClock.elapsedRealtime()
            }
        }

        val last = tokens.lastOrNull()
        val lastTokenStable = if (committed) {
            true
        } else {
            if (last != null && last == lastPartialLastToken) {
                lastPartialRepeat++
            } else {
                lastPartialLastToken = last
                lastPartialRepeat = 0
            }
            // High confidence: the last token has stopped moving — the platform service revises
            // its own partials constantly, so nothing is judged until it settles.
            lastPartialRepeat >= RecitationTuning.MIN_STABLE_REPEATS
        }

        // Moving on by simply reciting the next ayah is how a student leaves an ayah the coach
        // cannot follow, and it happens *after* words have already matched — usually right after a
        // correction. That case cannot be served by the opening check below, which only inspects
        // the first tokens of the transcript: mid-ayah those still belong to the current ayah.
        if (lastTokenStable && hasMovedOnToNextAyah(tokens)) {
            recordCurrentAyahOutcome()
            nextAyah(startListening = true)
            return
        }

        if (lastTokenStable && lockedCorrectCount == 0) {
            val wrongAyahId = WrongAyahDetector.matchingOtherAyah(
                spokenTokens = tokens,
                currentGlobalId = _currentGlobalId.value,
                currentOpening = words.filter { !it.isEnd }
                    .take(3)
                    .flatMap { ArabicTextNormalizer.tokenize(it.text) },
                otherOpenings = learnedOpenings,
            )
            if (wrongAyahId != null) {
                val list = _learnedAyahs.value
                val currentIndex = list.indexOf(_currentGlobalId.value)
                val nextId = if (currentIndex in 0 until list.lastIndex) list[currentIndex + 1] else null
                if (wrongAyahId == nextId) {
                    // The user intentionally moved on to the next ayah by reciting it
                    recordCurrentAyahOutcome()
                    selectAyah(wrongAyahId, startListening = true)
                    return
                }
                val (surah, ayah) = AyahMapping.globalToSurahAyah(wrongAyahId)
                _detectedWrongAyahId.value = wrongAyahId
                _wrongAyahFeedback.value =
                    "That sounds like a different ayah ($surah:$ayah). Stay on this one, or change ayah."
                return
            }
        }

        val result = bestReading(
            words = words,
            transcripts = listOf(transcript) + alternatives,
            lastTokenStable = lastTokenStable,
            allowSkipMistakes = committed,
        )
        _evaluationResult.value = result
        if (result.correctCount > 0) {
            _wrongAyahFeedback.value = null
            _detectedWrongAyahId.value = null
        }

        val newLocked = result.evaluations.takeWhile { it.status == WordEvaluationStatus.CORRECT }.size
        if (newLocked > lockedCorrectCount) {
            lockedCorrectCount = newLocked
            if (lastHandledMistakeWordIndex >= 0 && lockedCorrectCount > lastHandledMistakeWordIndex) {
                lastHandledMistakeWordIndex = -1
            }
        }

        // Deliberately not recorded here. Every transient MISTAKE used to be banked into the
        // recap, so a decode artifact that vanished on the next pass still showed up as a word
        // the student got wrong — Al-Ikhlas 112:2 reported a mistake on a perfect reading. Only
        // corroborated mistakes are recorded now, at the point they are believed.

        val contentWordCount = words.count { !it.isEnd }
        if (wordsEverCorrect.size != contentWordCount) {
            wordsEverCorrect = BooleanArray(contentWordCount)
        }
        result.evaluations.forEach { evaluation ->
            if (evaluation.status == WordEvaluationStatus.CORRECT &&
                evaluation.wordIndex in wordsEverCorrect.indices
            ) {
                wordsEverCorrect[evaluation.wordIndex] = true
            }
        }

        // Said correctly since: stop calling it a mistake on screen. The recap keeps it — the
        // student did get it wrong once — but the live card has served its purpose.
        _confirmedMistake.value?.let { believed ->
            val nowCorrect = result.evaluations.getOrNull(believed.wordIndex)?.status ==
                WordEvaluationStatus.CORRECT
            if (nowCorrect) _confirmedMistake.value = null
        }

        // Every word correct at least once — across as many attempts and corrections as it took.
        if (contentWordCount > 0 && wordsEverCorrect.all { it }) {
            // Bank the ayah and keep going — no card, no acknowledgement. The recap comes at
            // the end of the surah so a revision run reads as one continuous recitation.
            recordCurrentAyahOutcome()
            if (PlayerSettings.autoAdvanceSuccess && !isAdvancingAyah) {
                isAdvancingAyah = true
                viewModelScope.launch {
                    kotlinx.coroutines.delay(END_OF_AYAH_PAUSE_MS)
                    if (isAtEndOfSurahRun()) {
                        // Stop the sources directly: stopRecording() would republish the recap
                        // as an interrupted run and lose the "surah complete" framing.
                        speech.stopListening()
                        publishSessionReport(reachedEndOfSurah = true)
                        isAdvancingAyah = false
                    } else {
                        nextAyah(startListening = true)
                    }
                }
            }
            return
        }

        val firstMistake = result.firstMistake ?: jumpedOverWord(result, committed)
        if (firstMistake == null) {
            // The student is on track; nothing is being corroborated any more.
            pendingMistakeKey = null
            pendingMistakeRepeats = 0
            return
        }
        if (firstMistake.wordIndex < lockedCorrectCount) return
        if (tokens.size <= lockedCorrectCount) return
        // Live path: only interrupt on high-confidence substitution (not skips).
        if (!committed && !lastTokenStable) return
        if (!committed && firstMistake.spokenWord == null) return
        // Still mid-word: a recognizer that has heard only the start of a longer word must not
        // be treated as a wrong word. The next word is checked too, because a dropped word can
        // leave the student legitimately part-way into the one after it.
        if (isStillMidWord(firstMistake.spokenWord, words, firstMistake.wordIndex)) return
        // Only a skipped word or an unmistakably different one is worth stopping for. Anything
        // closer is treated as the recogniser mishearing and passes without comment.
        if (!isConfidentMistake(firstMistake, words)) return

        // Corroborate before believing it. A decoding artifact names a different wrong word (or a
        // differently-misspelled one) on the next decode; a real mistake names the same one every
        // time. This sits above the playback guards on purpose, so the recap records the same
        // mistakes the coach believes even when correction audio is switched off.
        val mistakeKey = "${firstMistake.wordIndex}:${firstMistake.spokenWord.orEmpty()}"
        if (mistakeKey == pendingMistakeKey) {
            pendingMistakeRepeats++
        } else {
            pendingMistakeKey = mistakeKey
            pendingMistakeRepeats = 1
        }
        if (pendingMistakeRepeats < RecitationTuning.MISTAKE_CONFIRMATIONS) return
        pendingMistakeKey = null
        pendingMistakeRepeats = 0
        sessionMistakeIndices += firstMistake.wordIndex
        // Believed — so now, and only now, the screen may say so.
        _confirmedMistake.value = firstMistake
        _believedMistakeIndices.value = sessionMistakeIndices.toSet()

        val now = SystemClock.elapsedRealtime()
        if (now < mistakeCooldownUntilElapsedMs) return
        if (!PlayerSettings.autoPlayMistakeAudio) return
        if (_isAutoPlayingMistake.value || isAdvancingAyah) return
        // Correct a word twice at most, then let them carry on. Playing the same clip a third
        // time is nagging, and if two corrections have not landed the recogniser is as likely to
        // be the problem as the student. It still counts as a mistake in the report.
        val alreadyCorrected = correctionsPerWord.getOrDefault(firstMistake.wordIndex, 0)
        if (alreadyCorrected >= MAX_CORRECTIONS_PER_WORD) return
        correctionsPerWord[firstMistake.wordIndex] = alreadyCorrected + 1

        lastHandledMistakeWordIndex = firstMistake.wordIndex
        _isAutoPlayingMistake.value = true
        speech.pauseForCorrectionClip()
        val (surah, ayah) = _surahAndAyah.value
        viewModelScope.launch {
            wordPlayer.playAndAwait(surah, ayah, firstMistake.wordIndex)
            trimToLockedPrefix(words)
            mistakeCooldownUntilElapsedMs =
                SystemClock.elapsedRealtime() + RecitationTuning.MISTAKE_COOLDOWN_MS
            kotlinx.coroutines.delay(RecitationTuning.POST_CLIP_DELAY_MS)
            speech.resumeAfterCorrectionClip()
            _isAutoPlayingMistake.value = false
        }
    }

    /**
     * True when the student has audibly started the **next** learned ayah.
     *
     * This is the exit that needs no button: an ayah the recogniser cannot complete would otherwise
     * hold the run for ever, since nothing auto-advances any more. It deliberately reads the *end*
     * of the transcript rather than its opening — by the time someone moves on, the transcript
     * begins with the ayah they are leaving, so [WrongAyahDetector.matchingOtherAyah] (a prefix
     * check) can never see it.
     *
     * Only the immediate next ayah counts. Matching any learned ayah here would turn a repeated
     * phrase into a jump to somewhere unrelated, and the existing wrong-ayah advisory already
     * covers that case.
     */
    private fun hasMovedOnToNextAyah(spokenTokens: List<String>): Boolean {
        val list = _learnedAyahs.value
        val currentIndex = list.indexOf(_currentGlobalId.value)
        if (currentIndex < 0 || currentIndex >= list.lastIndex) return false
        val nextId = list[currentIndex + 1]
        val nextOpening = learnedOpenings.firstOrNull { it.globalId == nextId }?.tokens.orEmpty()
        if (nextOpening.size < NEXT_AYAH_OPENING_TOKENS) return false
        val opening = nextOpening.take(NEXT_AYAH_OPENING_TOKENS)
        // Never treat words of the current ayah as the next one's opening; short surahs repeat
        // phrasing heavily and that would cut the student off mid-ayah.
        val currentTokens = _currentWords.value.filter { !it.isEnd }
            .flatMap { ArabicTextNormalizer.tokenize(it.text) }
        if (opening.all { token -> currentTokens.any { ArabicTextNormalizer.isWordMatch(it, token) } }) {
            return false
        }
        // The opening must be the most recent thing said, not something buried mid-transcript —
        // several ayahs legitimately share words with the one after them. Matched through the
        // aligner because two written words can arrive as three tokens when the next ayah opens
        // with يَـٰٓأَيُّهَا, and then a fixed-size tail would never line up.
        return ArabicWordAligner.endsWithPrefix(nextOpening, NEXT_AYAH_OPENING_TOKENS, spokenTokens)
    }

    /**
     * Scores every reading the recognizer offered for the same audio and keeps the best one.
     *
     * The recognizer is asked for several hypotheses and only the first used to be read. That is
     * how أَلْهَىٰكُمُ came back as `الحاكم` and was called a mistake — ه against ح, one substitution
     * on a sound the student got right, with the correct reading sitting unread in the list.
     *
     * **This is evidence, not leniency, and the distinction is the whole reason it is safe.** An
     * alternative is another plausible transcription of the audio the student actually produced,
     * so preferring one that fits the ayah says "one reading of what you said is the right word".
     * The tempting shortcut — allowing two edits instead of one in [ArabicTextNormalizer] — is not
     * the same thing at all: it accepts any word that merely *looks* similar, and measured across
     * the Qur'an it would make 60 adjacent word-pairs match that must not, among them العزيز
     * against العليم, العليم against الحكيم and والشمس against والقمر. Those are the confusions a
     * memoriser actually makes, which is to say precisely what the coach exists to catch.
     *
     * Ranked by words settled, then by fewest mistakes; the recognizer's own first choice wins
     * ties, so nothing changes on audio it was already confident about.
     */
    private fun bestReading(
        words: List<AyahWord>,
        transcripts: List<String>,
        lastTokenStable: Boolean,
        allowSkipMistakes: Boolean,
    ): RecitationResult {
        var best: RecitationResult? = null
        for (candidate in transcripts) {
            if (candidate.isBlank()) continue
            val result = RecitationEvaluator.evaluateContinuing(
                words,
                candidate,
                lastTokenStable,
                lockedCorrectCount,
                allowSkipMistakes = allowSkipMistakes,
            )
            val incumbent = best
            if (incumbent == null ||
                result.correctCount > incumbent.correctCount ||
                (
                    result.correctCount == incumbent.correctCount &&
                        result.mistakeCount < incumbent.mistakeCount
                    )
            ) {
                best = result
            }
        }
        return best ?: RecitationEvaluator.evaluateContinuing(
            words,
            transcripts.firstOrNull().orEmpty(),
            lastTokenStable,
            lockedCorrectCount,
            allowSkipMistakes = allowSkipMistakes,
        )
    }

    /**
     * A word the student has audibly gone past without saying — reported as the skip it is.
     *
     * Without this a skipped word could never be corroborated, so it was never reported at all.
     * The first evaluation does flag the skip, and that same evaluation locks the words before it
     * as correct; on the next one the evaluator can align the tail cleanly from *after* the gap
     * (`RecitationEvaluator` prefers a walk with no mistake, for mic gaps), so the skip vanishes
     * and [RecitationTuning.MISTAKE_CONFIRMATIONS] is never reached. The word simply stayed
     * PENDING for ever and the ayah could not finish — the student pressed Next and the recap
     * never mentioned it. CLAUDE.md is unambiguous that a skip is the one mistake worth stopping
     * for, so the second sighting has to count.
     *
     * "Gone past" is read strictly: a PENDING word with a CORRECT word *after* it. Words pending
     * at the end of the ayah are simply not recited yet, which is not a mistake at all.
     *
     * Committed results only, matching `allowSkipMistakes` — a gap in a live partial is far more
     * often the microphone than the student.
     */
    private fun jumpedOverWord(result: RecitationResult, committed: Boolean): WordEvaluation? {
        if (!committed) return null
        val lastCorrect = result.evaluations.indexOfLast { it.status == WordEvaluationStatus.CORRECT }
        if (lastCorrect <= 0) return null
        val skipped = result.evaluations
            .take(lastCorrect)
            .firstOrNull { it.status == WordEvaluationStatus.PENDING }
            ?: return null
        return skipped.copy(
            status = WordEvaluationStatus.MISTAKE,
            spokenWord = null,
            feedback = "Skipped word '${skipped.originalWord.text}'",
        )
    }

    /**
     * Whether a flagged mistake is solid enough to stop the student for.
     *
     * Two things qualify. A **skip** is unambiguous — they are already saying a later word, so a
     * word really was missed, and no amount of mishearing invents that. A **substitution** only
     * qualifies when the word heard is properly different from the one expected: past
     * `isWordMatch`'s one-edit tolerance *and* past [RecitationTuning.NEAR_MISS_TOLERANCE].
     *
     * Everything in between — السِّرَاطَ for صِرَاطَ, رَحْمَانٍ for ٱلرَّحْمَـٰنِ — is the recogniser losing
     * a letter on a word the student said correctly, and interrupting for it is precisely what
     * makes an automated coach worse than none. Those pass silently; the word does not lock in, so
     * a genuine error there still surfaces in the report without breaking the recitation.
     */
    private fun isConfidentMistake(mistake: WordEvaluation, words: List<AyahWord>): Boolean {
        if (mistake.spokenWord == null) return true
        val expected = words.filter { !it.isEnd }.getOrNull(mistake.wordIndex)?.text ?: return false
        val expectedNorm = ArabicTextNormalizer.normalize(expected)
        val spokenNorm = ArabicTextNormalizer.normalize(mistake.spokenWord)
        if (expectedNorm.isEmpty() || spokenNorm.isEmpty()) return false
        val distance = ArabicTextNormalizer.levenshteinDistance(expectedNorm, spokenNorm)
        return distance > RecitationTuning.NEAR_MISS_TOLERANCE
    }

    /**
     * True when [spoken] is the beginning of the expected word at [wordIndex] or the one after
     * it — the student is still saying it, so there is nothing to correct yet.
     */
    private fun isStillMidWord(spoken: String?, words: List<AyahWord>, wordIndex: Int): Boolean {
        if (spoken.isNullOrBlank()) return false
        val contentWords = words.filter { !it.isEnd }
        for (i in wordIndex..(wordIndex + 1)) {
            val candidate = contentWords.getOrNull(i) ?: break
            if (ArabicTextNormalizer.isIncompletePrefix(spoken, candidate.text)) return true
        }
        return false
    }

    private fun rememberSpoken(tokens: List<String>) {
        if (tokens.isEmpty()) return
        // Keep a growing unique-enough memory of content tokens for the report.
        if (tokens.size >= sessionSpokenTokens.size) {
            sessionSpokenTokens.clear()
            sessionSpokenTokens.addAll(tokens)
        }
    }

    /**
     * Banks how the current ayah went. Called when it completes and again on stop, so an ayah
     * abandoned half-way still counts for what was actually recited. Re-recording the same ayah
     * (a retry) overwrites the earlier attempt rather than adding a second row.
     */
    private fun recordCurrentAyahOutcome() {
        val eval = _evaluationResult.value
        val words = _currentWords.value
        val contentWords = words.filter { !it.isEnd }
        val content = eval?.totalContentWords ?: contentWords.size
        if (content == 0) return
        val id = _currentGlobalId.value
        val (surah, ayah) = _surahAndAyah.value
        if (id <= 0) return

        val mistaken = sessionMistakeIndices.filter { it < content }.sorted()
        // Count from the cumulative record, not the last evaluation. The recap should say what the
        // student managed over the whole ayah — including words they got right, were corrected on,
        // and then said correctly — rather than whatever the final decode happened to contain.
        val correct = if (wordsEverCorrect.size == content) {
            wordsEverCorrect.indices.count { wordsEverCorrect[it] && it !in mistaken }
        } else {
            eval?.evaluations
                ?.count { it.status == WordEvaluationStatus.CORRECT && it.wordIndex !in mistaken }
                ?: 0
        }.coerceIn(0, (content - mistaken.size).coerceAtLeast(0))

        runOutcomes[id] = RecitationAyahOutcome(
            globalId = id,
            surah = surah,
            ayah = ayah,
            correctCount = correct,
            totalContentWords = content,
            mistakenWordIndices = mistaken,
        )
    }

    /**
     * True when there is no further learned ayah of this surah to move on to — either the next
     * learned ayah belongs to a different surah, or the list would wrap back to the beginning.
     */
    private fun isAtEndOfSurahRun(): Boolean {
        val list = _learnedAyahs.value
        if (list.isEmpty()) return true
        val index = list.indexOf(_currentGlobalId.value)
        if (index < 0 || index == list.lastIndex) return true
        val currentSurah = _surahAndAyah.value.first
        val (nextSurah, _) = AyahMapping.globalToSurahAyah(list[index + 1])
        return nextSurah != currentSurah
    }

    private fun publishSessionReport(reachedEndOfSurah: Boolean) {
        if (runOutcomes.isEmpty()) return
        _sessionReport.value = RecitationSessionReport(
            ayahs = runOutcomes.values.toList(),
            reachedEndOfSurah = reachedEndOfSurah,
        )
    }

    private fun trimToLockedPrefix(words: List<AyahWord>) {
        val lockedText = words.filter { !it.isEnd }
            .take(lockedCorrectCount)
            .flatMap { ArabicTextNormalizer.tokenize(it.text) }
            .joinToString(" ")
        speech.updateRecognizedText(lockedText)
        _recognizedText.value = lockedText
        lastPartialLastToken = null
        lastPartialRepeat = 0
        _evaluationResult.value = RecitationEvaluator.evaluateContinuing(
            words,
            lockedText,
            lastTokenStable = false,
            lockedCorrectCount = lockedCorrectCount,
            allowSkipMistakes = false,
        )
    }

    fun retryCurrentAyah() {
        resetAyahSessionState()
        // Re-reciting this ayah replaces its banked outcome instead of stacking a second row.
        runOutcomes.remove(_currentGlobalId.value)
        speech.stopListening()
        _sessionReport.value = null
        speech.updateRecognizedText("")
        _recognizedText.value = ""
        _evaluationResult.value = RecitationEvaluator.evaluate(_currentWords.value, "", committed = true)
        startRecording()
    }

    fun nextAyah(startListening: Boolean = false) {
        val list = _learnedAyahs.value
        if (list.isEmpty()) {
            selectAyah((_currentGlobalId.value % 6236) + 1, startListening)
            return
        }
        val currentIndex = list.indexOf(_currentGlobalId.value)
        val nextId = if (currentIndex >= 0) {
            list[(currentIndex + 1) % list.size]
        } else {
            list.firstOrNull { it > _currentGlobalId.value } ?: list.first()
        }
        selectAyah(nextId, startListening)
    }

    fun previousAyah(startListening: Boolean = false) {
        val list = _learnedAyahs.value
        if (list.isEmpty()) {
            val prev = if (_currentGlobalId.value > 1) _currentGlobalId.value - 1 else 6236
            selectAyah(prev, startListening)
            return
        }
        val currentIndex = list.indexOf(_currentGlobalId.value)
        val prevId = if (currentIndex >= 0) {
            list[(currentIndex - 1 + list.size) % list.size]
        } else {
            list.lastOrNull { it < _currentGlobalId.value } ?: list.last()
        }
        selectAyah(prevId, startListening)
    }

    override fun onCleared() {
        super.onCleared()
        speech.destroy()
        wordPlayer.release()
    }

    private companion object {
        /**
         * Breath between ayahs. Short and smooth so continuous recitation feels responsive.
         */
        const val END_OF_AYAH_PAUSE_MS = 300L

        /** Corrections for the same word in one ayah before the coach lets it go. */
        const val MAX_CORRECTIONS_PER_WORD = 2

        /**
         * Words of the next ayah that must be heard before the run moves on by itself. Two is
         * enough to be unambiguous without making the student recite half of it first.
         */
        const val NEXT_AYAH_OPENING_TOKENS = 2
    }
}
