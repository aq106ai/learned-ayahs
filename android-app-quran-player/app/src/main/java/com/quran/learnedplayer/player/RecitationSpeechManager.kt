package com.quran.learnedplayer.player

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.Process
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.quran.learnedplayer.data.DeviceRecognition
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.OutputStream
import kotlin.math.sqrt

/**
 * Microphone → transcript for Recite & review, using the platform speech service.
 *
 * **The app owns the microphone; the recogniser is only fed.** This is the whole design, and it
 * exists to fix the single loudest complaint about the feature: the mic audibly switching on and
 * off throughout a recitation. A speech service ends its session at every pause between phrases,
 * and the only way to keep listening is to start another one — each start reopening the mic, with
 * a chime and an indicator blink on most phones. `EXTRA_SEGMENTED_SESSION` was supposed to cure
 * that and is widely ignored.
 *
 * Since Android 13 there is a real fix: [RecognizerIntent.EXTRA_AUDIO_SOURCE] hands the recogniser
 * a pipe to read instead of letting it open the microphone. One [AudioRecord] stays open for the
 * whole session, and when the service ends its session we simply hand it a fresh pipe. Sessions
 * still end — we just stop paying for them. Nothing the user can hear changes.
 *
 * That is why Recite requires API 33 (see [DeviceRecognition.isSupported]): the pre-33 restart loop
 * produced exactly the experience this feature was rejected for, so it is not shipped at all.
 *
 * Replaces a bundled Quran-tuned Whisper model that ran through whisper.cpp. That was accurate on
 * clean audio and could not decode fast enough to correct a live reciter, even on current phones.
 */
class RecitationSpeechManager(private val context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _recognizedText = MutableStateFlow("")
    val recognizedText: StateFlow<String> = _recognizedText.asStateFlow()

    private val _committedText = MutableStateFlow("")
    val committedText: StateFlow<String> = _committedText.asStateFlow()

    /**
     * The other readings the recognizer offered for the utterance behind [committedText], as whole
     * transcripts — same session prefix, different tail.
     *
     * The session already asks for [RecognizerIntent.EXTRA_MAX_RESULTS] hypotheses and used to
     * keep only the first, which is how أَلْهَىٰكُمُ came back as `الحاكم` and was scored a mistake:
     * ه and ح are one substitution apart acoustically, and the reading the student actually gave
     * was sitting in the list unread.
     *
     * A plain field rather than a flow on purpose. The evaluator needs the alternatives that
     * belong to *this* transcript, and two flows updated in sequence give a collector no guarantee
     * about which pairing it observes; reading a field at the moment the transcript is handled has
     * no such race.
     */
    @Volatile
    var committedAlternatives: List<String> = emptyList()
        private set

    private val _rmsDb = MutableStateFlow(0f)
    val rmsDb: StateFlow<Float> = _rmsDb.asStateFlow()

    private val _errorState = MutableStateFlow<String?>(null)
    val errorState: StateFlow<String?> = _errorState.asStateFlow()

    val isAvailable: Boolean get() = DeviceRecognition.isSupported

    private var recognizer: SpeechRecognizer? = null
    private var captureThread: Thread? = null

    /**
     * The write end of the pipe the recogniser is currently reading.
     *
     * Swapped, never closed-and-left-null, when a session restarts: the capture thread keeps
     * writing throughout and simply lands in the new pipe.
     */
    @Volatile private var audioSink: OutputStream? = null

    /**
     * Our copy of the read end handed to the recogniser.
     *
     * Held until the *next* session replaces it rather than closed straight after
     * `startListening`. The intent crosses a binder to the speech service, which dups the
     * descriptor on its side; closing ours in the same breath risks racing that. Keeping it one
     * session longer costs a single file descriptor and removes the race entirely.
     */
    private var previousReadSide: ParcelFileDescriptor? = null

    @Volatile private var wantListening = false
    @Volatile private var pausedForClip = false

    /** Transcript from sessions already finished, which later partials are appended to. */
    @Volatile private var sessionPrefix = ""

    private var languageIndex = 0
    private var useOnDeviceRecognizer = false

    /**
     * Microphone sessions opened since recording started — which should be exactly one.
     *
     * The point of this class is that the number stays at 1 for a whole surah. Logging it is what
     * turns "the mic seems to cycle" into something checkable.
     */
    private var micSessions = 0

    /** Recogniser sessions started. These are free: they do not touch the microphone. */
    private var recognizerSessions = 0

    /**
     * Consecutive failures with nothing usable. A device with no working recognition service errors
     * instantly and forever, so the restart loop has to give up and say something actionable.
     */
    private var deadStarts = 0

    private val language: String
        get() = DeviceRecognition.ARABIC_LOCALES[
            languageIndex.coerceIn(DeviceRecognition.ARABIC_LOCALES.indices),
        ]

    fun startListening() {
        if (!isAvailable) {
            _errorState.value = "Recite needs Android 13 or newer on this phone."
            _isListening.value = false
            return
        }
        wantListening = true
        pausedForClip = false
        languageIndex = 0
        deadStarts = 0
        micSessions = 0
        recognizerSessions = 0
        sessionPrefix = ""
        committedAlternatives = emptyList()
        _recognizedText.value = ""
        _committedText.value = ""
        _errorState.value = null

        if (captureThread?.isAlive != true) {
            captureThread = Thread(::captureLoop, "recite-capture").also { it.start() }
        }
        mainHandler.post { startRecognizerSession() }
    }

    fun stopListening() {
        wantListening = false
        pausedForClip = false
        mainHandler.removeCallbacksAndMessages(null)
        captureThread?.interrupt()
        captureThread = null
        mainHandler.post {
            runCatching { recognizer?.cancel() }
            closeSink()
            closeHeldReadSide()
        }
        _isListening.value = false
    }

    fun clearError() {
        _errorState.value = null
    }

    fun updateRecognizedText(text: String) {
        sessionPrefix = text
        // The caller has rewritten the transcript, so readings of the utterance it replaced no
        // longer describe it.
        committedAlternatives = emptyList()
        _recognizedText.value = text
        // Keep committed in step, or a late full-utterance result can resurrect speech from before
        // the caller trimmed the transcript back to its locked prefix.
        _committedText.value = text
    }

    /**
     * The reference text is not used. The platform service takes no vocabulary or prompt hint, so
     * biasing happens in the evaluator instead, where a wrong guess costs nothing.
     */
    fun setExpectedText(uthmani: String, tokens: List<String>) = Unit

    /**
     * Stop feeding the recogniser while a correction clip plays, without closing the microphone.
     *
     * The capture thread keeps running and simply drops what it reads, so the clip coming out of
     * the speaker never reaches the recogniser and cannot be transcribed as if the student had said
     * it. [isListening] deliberately stays true: from the user's point of view the coach is still
     * listening, and flickering the indicator here was itself reported as the mic misbehaving.
     */
    fun pauseForCorrectionClip() {
        pausedForClip = true
        if (wantListening) _isListening.value = true
    }

    fun resumeAfterCorrectionClip() {
        if (!wantListening) {
            pausedForClip = false
            return
        }
        // A fresh recogniser session, so none of the clip's audio is still buffered inside it. This
        // costs nothing audible — the microphone is ours and stays open.
        mainHandler.post {
            runCatching { recognizer?.cancel() }
            pausedForClip = false
            _isListening.value = true
            startRecognizerSession()
        }
    }

    fun destroy() {
        wantListening = false
        mainHandler.removeCallbacksAndMessages(null)
        captureThread?.interrupt()
        captureThread = null
        mainHandler.post {
            runCatching { recognizer?.destroy() }
            recognizer = null
            closeSink()
            closeHeldReadSide()
        }
        _isListening.value = false
    }

    /**
     * Opens the microphone once and writes 16kHz mono PCM into whichever pipe is current.
     *
     * Runs for the whole recitation. Every session restart below swaps [audioSink] underneath this
     * loop; it neither knows nor cares.
     */
    @SuppressLint("MissingPermission")
    private fun captureLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        var record: AudioRecord? = null
        try {
            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            val bufSize = (minBuf * 4).coerceAtLeast(SAMPLE_RATE * 2)
            // VOICE_RECOGNITION over MIC: its AGC and noise suppression are tuned for exactly this,
            // and swapping them cost 7 points of word accuracy when it was measured.
            record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufSize,
            )
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                _errorState.value = "Could not open the microphone."
                _isListening.value = false
                return
            }

            val shorts = ShortArray(SAMPLE_RATE / 10)
            val bytes = ByteArray(shorts.size * 2)
            record.startRecording()
            micSessions++
            Log.d(TAG, "microphone opened (session #$micSessions) — stays open for the recitation")
            _isListening.value = true

            while (wantListening && !Thread.currentThread().isInterrupted) {
                val n = record.read(shorts, 0, shorts.size)
                if (n <= 0) continue

                var energy = 0.0
                for (i in 0 until n) {
                    val s = shorts[i].toDouble()
                    energy += s * s
                }
                _rmsDb.value = (sqrt(energy / n) / 2000f).toFloat().coerceIn(0f, 10f)

                // Drop audio rather than close the mic: see pauseForCorrectionClip.
                if (pausedForClip) continue

                for (i in 0 until n) {
                    val v = shorts[i].toInt()
                    bytes[i * 2] = (v and 0xFF).toByte()
                    bytes[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
                }
                // A closed pipe means the session ended between the check and the write; the next
                // session's sink picks up from here, so this is expected rather than an error.
                runCatching { audioSink?.write(bytes, 0, n * 2) }
            }
        } catch (_: InterruptedException) {
            // stopListening
        } catch (e: Exception) {
            _errorState.value = "Microphone error: ${e.localizedMessage}"
        } finally {
            runCatching { record?.stop() }
            runCatching { record?.release() }
            _isListening.value = false
            Log.d(TAG, "microphone closed after $recognizerSessions recogniser session(s)")
        }
    }

    /** Must run on the main thread — [SpeechRecognizer] requires it. */
    private fun startRecognizerSession() {
        if (!wantListening || pausedForClip) return
        try {
            ensureRecognizer()
            val pipe = ParcelFileDescriptor.createPipe()
            val readSide = pipe[0]
            val writeSide = pipe[1]
            closeSink()
            audioSink = ParcelFileDescriptor.AutoCloseOutputStream(writeSide)

            val intent = DeviceRecognition.arabicIntent(language).apply {
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                // Read our capture instead of opening the microphone. This one extra is the reason
                // the mic no longer cycles.
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, readSide)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, SAMPLE_RATE)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 4_000L)
                putExtra(
                    RecognizerIntent.EXTRA_SEGMENTED_SESSION,
                    RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                )
            }

            recognizer?.setRecognitionListener(listener)
            recognizer?.startListening(intent)
            recognizerSessions++
            // The previous session's descriptor is safely past marshalling by now; release it and
            // keep this one until the session after next. A recitation opens many sessions, so
            // these cannot simply be left open.
            runCatching { previousReadSide?.close() }
            previousReadSide = readSide
            _isListening.value = true
        } catch (e: Exception) {
            noteDeadStart("Could not start speech recognition: ${e.localizedMessage}")
        }
    }

    /**
     * On-device recognition is only reachable through a different factory, so preferring it means
     * rebuilding the recogniser rather than setting an intent extra.
     */
    private fun ensureRecognizer() {
        val wantOnDevice = useOnDeviceRecognizer &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        if (recognizer != null && wantOnDevice == recognizerIsOnDevice) return
        runCatching { recognizer?.destroy() }
        recognizer = if (wantOnDevice) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
            SpeechRecognizer.createSpeechRecognizer(context)
        }
        recognizerIsOnDevice = wantOnDevice
    }

    private var recognizerIsOnDevice = false

    /** Prefer the downloaded Arabic model over the network one, from the next session on. */
    fun setPreferOnDevice(preferOnDevice: Boolean) {
        useOnDeviceRecognizer = preferOnDevice
    }

    private fun closeSink() {
        val sink = audioSink
        audioSink = null
        runCatching { sink?.close() }
    }

    /** Releases the descriptor still held from the last session. Main thread only. */
    private fun closeHeldReadSide() {
        runCatching { previousReadSide?.close() }
        previousReadSide = null
    }

    private fun restartSession(delayMs: Long = 0L) {
        if (!wantListening || pausedForClip) return
        mainHandler.postDelayed({
            if (wantListening && !pausedForClip) startRecognizerSession()
        }, delayMs)
    }

    private fun noteDeadStart(fallbackMessage: String) {
        deadStarts++
        if (deadStarts < MAX_DEAD_STARTS) {
            restartSession(RESTART_DELAY_MS)
            return
        }
        wantListening = false
        _isListening.value = false
        _errorState.value = fallbackMessage
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: android.os.Bundle?) {
            deadStarts = 0
            if (wantListening) _isListening.value = true
        }

        override fun onBeginningOfSpeech() {
            deadStarts = 0
        }

        // The microphone level comes from our own capture, which is continuous; the service only
        // sees the audio we feed it and its RMS callback adds nothing.
        override fun onRmsChanged(rmsdB: Float) = Unit

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() {
            if (wantListening) _isListening.value = true
        }

        override fun onError(error: Int) {
            if (pausedForClip) return
            if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                wantListening = false
                _isListening.value = false
                _errorState.value = "Microphone permission required"
                return
            }
            // The microphone is ours and still open, so a failed recogniser session is invisible to
            // the user. Keep the indicator steady and quietly start another.
            if (wantListening) _isListening.value = true

            if (error == ERROR_LANGUAGE_NOT_SUPPORTED || error == ERROR_LANGUAGE_UNAVAILABLE) {
                if (languageIndex < DeviceRecognition.ARABIC_LOCALES.lastIndex) {
                    languageIndex++
                    restartSession(RESTART_DELAY_MS)
                    return
                }
                wantListening = false
                _isListening.value = false
                _errorState.value =
                    "Arabic speech recognition is not available on this phone. Add Arabic in your " +
                        "phone's voice input settings and try again."
                return
            }

            // Alive and simply heard nothing — normal between phrases.
            if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                deadStarts = 0
                restartSession()
                return
            }

            if (error == SpeechRecognizer.ERROR_CLIENT || error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
                noteDeadStart("Speech recognition error ($error)")
                return
            }

            // A network failure is worth one silent retry on the on-device model before it is
            // reported: the student may simply have walked out of signal mid-surah.
            val networkFailure = error == SpeechRecognizer.ERROR_NETWORK ||
                error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT ||
                error == SpeechRecognizer.ERROR_SERVER
            if (networkFailure && !useOnDeviceRecognizer &&
                SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
            ) {
                useOnDeviceRecognizer = true
                restartSession(RESTART_DELAY_MS)
                return
            }
            if (networkFailure) {
                _errorState.value = "No connection, and no offline Arabic model on this phone."
                restartSession(RESTART_DELAY_MS)
                return
            }
            restartSession(RESTART_DELAY_MS)
        }

        override fun onResults(results: android.os.Bundle?) {
            if (pausedForClip) return
            deadStarts = 0
            appendResult(results)
            if (wantListening) {
                _isListening.value = true
                restartSession()
            }
        }

        /** One utterance inside a segmented session; the session and the pipe stay open. */
        override fun onSegmentResults(segmentResults: android.os.Bundle) {
            if (pausedForClip) return
            deadStarts = 0
            appendResult(segmentResults)
            if (wantListening) _isListening.value = true
        }

        override fun onEndOfSegmentedSession() {
            if (wantListening) restartSession()
        }

        override fun onPartialResults(partialResults: android.os.Bundle?) {
            if (pausedForClip) return
            deadStarts = 0
            val latest = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                .orEmpty()
            if (latest.isNotBlank()) {
                _recognizedText.value = listOf(sessionPrefix, latest)
                    .filter { it.isNotBlank() }
                    .joinToString(" ")
            }
        }

        override fun onEvent(eventType: Int, params: android.os.Bundle?) = Unit
    }

    private fun appendResult(bundle: android.os.Bundle?) {
        val hypotheses = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.filter { it.isNotBlank() }
            .orEmpty()
        val latest = hypotheses.firstOrNull().orEmpty()
        if (latest.isBlank()) return
        // Built against the prefix as it was *before* this utterance, so every alternative is a
        // complete transcript differing only in the part that was just heard.
        val prefix = sessionPrefix
        committedAlternatives = hypotheses.drop(1).map { alternative ->
            listOf(prefix, alternative).filter { it.isNotBlank() }.joinToString(" ")
        }
        sessionPrefix = listOf(prefix, latest).filter { it.isNotBlank() }.joinToString(" ")
        _recognizedText.value = sessionPrefix
        _committedText.value = sessionPrefix
    }

    private companion object {
        const val TAG = "RecitationSpeech"

        const val SAMPLE_RATE = 16_000

        const val RESTART_DELAY_MS = 250L

        const val MAX_DEAD_STARTS = 4

        // SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED / ERROR_LANGUAGE_UNAVAILABLE are API 31+;
        // spelled out because the constants postdate this file's minSdk history.
        const val ERROR_LANGUAGE_NOT_SUPPORTED = 12
        const val ERROR_LANGUAGE_UNAVAILABLE = 13
    }
}
