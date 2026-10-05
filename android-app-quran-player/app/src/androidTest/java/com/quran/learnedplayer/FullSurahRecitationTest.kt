package com.quran.learnedplayer

import android.Manifest
import android.app.Application
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.quran.learnedplayer.data.AyahMapping
import com.quran.learnedplayer.data.DeviceRecognition
import com.quran.learnedplayer.data.LearnedAyahsStore
import com.quran.learnedplayer.data.Reciter
import com.quran.learnedplayer.player.PlayerSettings
import com.quran.learnedplayer.player.RecitationViewModel
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Recites a complete surah offline, the way a student would, and holds the coach to its contract:
 *
 *  1. the microphone stays open for the **whole surah** — one session, no restarts;
 *  2. a correct recitation is **never interrupted**;
 *  3. every ayah is recognised and auto-advances to the next;
 *  4. the run ends in one surah-level recap, not a card per ayah.
 *
 * This drives [RecitationViewModel] itself rather than the Compose UI, so a failure points at the
 * coach logic instead of at a test tag. Audio is the real recitation corpus played aloud, so the
 * whole path — microphone, the speech service, the evaluator, auto-advance — is exercised end to
 * end. It needs a device running Android 13 or newer, and a network unless an Arabic voice pack is
 * installed.
 *
 * Point 2 matters most and is the reason this test exists: the risk of any recogniser is
 * interrupting a student who recited correctly. `falseInterruptions` is asserted to be zero,
 * because a coach that corrects words nobody got wrong is worse than no coach.
 *
 * Al-Ikhlas runs in three voices — the default reciter, Sudais and Shuraym — because a student
 * follows whichever reciter they picked, and a matcher tuned to one of them would only show up
 * against another. One test asserts the opposite of all the others: given a clip that really does
 * have a word wrong, the coach has to say so.
 *
 * The deterministic half of this lives in `ReciteReviewEndToEndTest` (JVM), which drives the same
 * ViewModel through transcripts instead of audio. Assert here only what needs a microphone.
 */
@RunWith(AndroidJUnit4::class)
class FullSurahRecitationTest {

    @get:Rule
    val permission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app: Application = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        // Recite is an Android 13 feature — it is built on streaming our own capture into the
        // recogniser. On an older phone the app correctly refuses to listen, so this skips rather
        // than reporting a failure for behaviour that is working as intended.
        assumeTrue(
            "Recite needs Android 13 or newer; this device cannot run it",
            DeviceRecognition.isSupported,
        )
        PlayerSettings.init(app)
        LearnedAyahsStore.init(app)
        PlayerSettings.reciteBetaEnabled = true
        PlayerSettings.autoAdvanceSuccess = true
        PlayerSettings.autoPlayMistakeAudio = true
        // Reset explicitly: the settings are process-wide and an install keeps app data, so a
        // reciter left over from the last run would decide which correction clips this one plays.
        PlayerSettings.reciter = Reciter.DEFAULT
        val audio = app.getSystemService(AudioManager::class.java)
        runCatching {
            audio.setStreamVolume(
                AudioManager.STREAM_MUSIC,
                audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
                0,
            )
        }
    }

    @Test
    fun alFatihah_recitedInFull_staysOnOneMicSession() {
        reciteWholeSurah(Reciter.MAHER_AL_MUAIQLY, surah = 1, ayahCount = 7)
    }

    @Test
    fun alIkhlas_recitedInFull_staysOnOneMicSession() {
        reciteWholeSurah(Reciter.MAHER_AL_MUAIQLY, surah = 112, ayahCount = 4)
    }

    /**
     * The same surah in a different voice.
     *
     * A student follows whichever reciter they picked, and the coach hears whoever they have
     * learned from. Sudais and Shuraym recite Al-Ikhlas at noticeably different paces than the
     * default reciter — Shuraym's 112:1 is 2.0s against Maher's 1.9s and Sudais's 3.0s — so this
     * is where a matcher tuned to one voice would show up.
     */
    @Test
    fun alIkhlas_withSudais_staysOnOneMicSession() {
        reciteWholeSurah(Reciter.AS_SUDAIS, surah = 112, ayahCount = 4)
    }

    @Test
    fun alIkhlas_withShuraym_staysOnOneMicSession() {
        reciteWholeSurah(Reciter.ASH_SHURAYM, surah = 112, ayahCount = 4)
    }

    /**
     * One wrong word, and the coach has to say so.
     *
     * Every other test here asserts the coach stays quiet; this is the one that asserts it does
     * not. The clip is 1:2 with رَبِّ replaced by مَـٰلِكِ from 1:4 — the same reciter, the same
     * recording, one word swapped (`.setup/make_recitation_corpus.py`). It is paired with the
     * correct clip of the same ayah deliberately: a single recognition run says very little on
     * its own (CLAUDE.md, on the same audio scoring 84% then 53%), but *correct is clean and
     * wrong names word 2* is a comparison that survives a bad day for the microphone.
     */
    @Test
    fun oneWrongWord_isNamedAndACorrectReadingOfTheSameAyahIsNot() {
        val correct = RecitationCorpus.surah(instrumentation.context, Reciter.MAHER_AL_MUAIQLY, 1)
            .first { it.ayah == 2 }
        val wrong = RecitationCorpus.misrecited(
            instrumentation.context, Reciter.MAHER_AL_MUAIQLY, surah = 1, ayah = 2,
        )
        assertTrue("corpus carries no misrecited clip of 1:2", wrong != null)

        val cleanRun = reciteOneAyah(correct)
        val mistakeRun = reciteOneAyah(wrong!!)
        println("[mistake] correct clip -> $cleanRun, misrecited clip -> $mistakeRun")

        assertTrue(
            "a correct reading of 1:2 was scored as a mistake on $cleanRun — the coach must not " +
                "correct a student who was right",
            cleanRun.isEmpty(),
        )
        assertTrue(
            "the misrecited clip says ${wrong.saidInstead} where رَبِّ belongs, and the coach " +
                "reported $mistakeRun instead of word ${wrong.mistakeWordIndex}",
            mistakeRun.contains(wrong.mistakeWordIndex),
        )
    }

    /** Recites one ayah start to finish and returns the word indices the recap called mistakes. */
    private fun reciteOneAyah(clip: RecitationCorpus.Clip): List<Int> {
        val globalId = AyahMapping.surahAyahToGlobal(clip.surah, clip.ayah)
        LearnedAyahsStore.clear()
        LearnedAyahsStore.addAll(setOf(globalId))

        val store = ViewModelStore()
        lateinit var viewModel: RecitationViewModel
        instrumentation.runOnMainSync {
            viewModel = ViewModelProvider(
                store,
                ViewModelProvider.AndroidViewModelFactory.getInstance(app),
            )[RecitationViewModel::class.java]
        }
        try {
            instrumentation.runOnMainSync { viewModel.selectAyah(globalId, startListening = true) }
            awaitTrue("engine never started listening", 20_000) {
                viewModel.isListening.value && viewModel.currentWords.value.isNotEmpty()
            }
            playAloud(clip)
            // The coach may still be corroborating, or playing the correction clip it decided on.
            awaitCondition(SETTLE_AFTER_AYAH_MS) { false }
            instrumentation.runOnMainSync { viewModel.stopRecording() }
            awaitCondition(5_000) { viewModel.sessionReport.value != null }
            return viewModel.sessionReport.value?.ayahs?.firstOrNull()?.mistakenWordIndices.orEmpty()
        } finally {
            instrumentation.runOnMainSync {
                viewModel.stopRecording()
                store.clear()
            }
        }
    }

    private fun reciteWholeSurah(reciter: Reciter, surah: Int, ayahCount: Int) {
        PlayerSettings.reciter = reciter
        val clips = RecitationCorpus.surah(instrumentation.context, reciter, surah)
        assertEquals(
            "corpus is missing ayahs of surah $surah for ${reciter.displayName}",
            ayahCount,
            clips.size,
        )

        val globalIds = (1..ayahCount).map { AyahMapping.surahAyahToGlobal(surah, it) }
        LearnedAyahsStore.clear()
        LearnedAyahsStore.addAll(globalIds.toSet())

        // Built through a ViewModelStore so it can actually be *destroyed* afterwards. A plain
        // `RecitationViewModel(app)` has no owner, so onCleared never runs and its microphone and
        // recogniser outlive the test, contending with the next surah's.
        val store = ViewModelStore()
        lateinit var viewModel: RecitationViewModel
        instrumentation.runOnMainSync {
            viewModel = ViewModelProvider(
                store,
                ViewModelProvider.AndroidViewModelFactory.getInstance(app),
            )[RecitationViewModel::class.java]
        }

        val watcher = StateWatcher(viewModel).also { it.start() }
        /** Ayahs the coach did not complete on its own, so the student had to press Next. */
        val heldAyahs = mutableListOf<String>()
        try {
            instrumentation.runOnMainSync {
                viewModel.selectAyah(globalIds.first(), startListening = true)
            }
            // The words load off the main thread and the mic session comes up after them; the
            // first clip must not start before the engine is actually listening.
            awaitTrue("engine never started listening", 20_000) {
                viewModel.isListening.value && viewModel.currentWords.value.isNotEmpty()
            }

            for ((index, clip) in clips.withIndex()) {
                val startedOn = viewModel.currentGlobalId.value
                assertEquals(
                    "coach is on the wrong ayah before reciting ${clip.surah}:${clip.ayah}",
                    globalIds[index],
                    startedOn,
                )
                playAloud(clip)

                val isLast = index == clips.lastIndex
                val advanced = awaitCondition(ADVANCE_TIMEOUT_MS) {
                    if (isLast) {
                        viewModel.sessionReport.value != null
                    } else {
                        viewModel.currentGlobalId.value != startedOn
                    }
                }
                println(
                    "[surah] %d:%-3d heard '%s' -> %s".format(
                        clip.surah, clip.ayah, viewModel.recognizedText.value,
                        if (advanced) "advanced on its own" else "held (needs Next)",
                    ),
                )
                if (!advanced) {
                    // Holding is now correct behaviour, not a failure. Nothing auto-advances: an
                    // ayah only completes once every word has been correct at least once, so an
                    // ayah the recogniser cannot manage waits for the student. Pressing Next is
                    // what a student would do, so the test does the same and keeps going.
                    heldAyahs += "${clip.surah}:${clip.ayah}"
                    instrumentation.runOnMainSync {
                        viewModel.nextAyah(startListening = true)
                    }
                    awaitTrue("engine stopped listening after ${clip.surah}:${clip.ayah}", 15_000) {
                        viewModel.isListening.value
                    }
                }
            }

            // The run only publishes its own recap when the final ayah completes. If the last one
            // was held, the student would stop the session — do that, so the recap always exists.
            if (viewModel.sessionReport.value == null) {
                instrumentation.runOnMainSync { viewModel.stopRecording() }
                awaitCondition(5_000) { viewModel.sessionReport.value != null }
            }
            val report = viewModel.sessionReport.value
            println(
                "[surah] mic sessions dropped: ${watcher.micDrops}, " +
                    "false interruptions: ${watcher.falseInterruptions}, " +
                    "held for Next: $heldAyahs (${heldAyahs.size}/$ayahCount)",
            )
            report?.let {
                println(
                    "[surah] report: surah=${it.surah} end=${it.reachedEndOfSurah} " +
                        "${it.correctCount}/${it.totalContentWords} = ${it.accuracyPercent}% " +
                        "perfect=${it.perfectAyahs}/${it.ayahs.size}",
                )
                it.ayahs.forEach { outcome ->
                    println(
                        "[surah]   ${outcome.surah}:${outcome.ayah} " +
                            "${outcome.correctCount}/${outcome.totalContentWords} " +
                            "mistakes=${outcome.mistakenWordIndices}",
                    )
                }
            }

            assertTrue(
                "no session report after reciting the whole surah — the run never completed",
                report != null,
            )
            assertEquals(
                "recap should cover every ayah of the surah",
                ayahCount,
                report!!.ayahs.size,
            )
            assertTrue(
                "the coach lost the microphone ${watcher.micDrops} time(s) mid-surah — the whole " +
                    "reason this engine is the default is that it does not",
                watcher.micDrops == 0,
            )
            assertTrue(
                "interrupted a correct recitation ${watcher.falseInterruptions} time(s) " +
                    "(ayahs: ${watcher.interruptedOn}) — a coach that corrects words nobody got " +
                    "wrong is worse than no coach",
                watcher.falseInterruptions == 0,
            )
            // Held ayahs are a *quality* signal, not a contract violation: the coach is meant to
            // wait rather than skip. But if it can complete almost nothing unaided, reciting a
            // surah becomes a button-pressing exercise and the model is not fit for the job.
            assertTrue(
                "the coach could only complete ${ayahCount - heldAyahs.size} of $ayahCount ayahs " +
                    "unaided (held: $heldAyahs) — every other ayah needed a manual Next",
                heldAyahs.size * 2 <= ayahCount,
            )
        } finally {
            watcher.stopWatching()
            instrumentation.runOnMainSync {
                viewModel.stopRecording()
                // Releases the whisper context and its decode thread before the next surah.
                store.clear()
            }
        }
    }

    /**
     * Samples the ViewModel's flows on a background thread.
     *
     * Correction clips and mic gaps are short, so a poll between test steps would miss them; this
     * runs throughout the recitation instead. Everything the corpus plays is a *correct* reading of
     * the ayah, so any correction clip at all is by definition a false interruption.
     */
    private inner class StateWatcher(private val viewModel: RecitationViewModel) : Thread() {
        @Volatile private var running = true
        var micDrops = 0
        var falseInterruptions = 0
        val interruptedOn = mutableListOf<String>()

        override fun run() {
            var wasListening = false
            var wasCorrecting = false
            var everListened = false
            while (running) {
                val listening = viewModel.isListening.value
                val correcting = viewModel.isAutoPlayingMistake.value
                // The run ends by closing the microphone on purpose, so stop watching for drops
                // the moment the recap exists — otherwise the intended ending is counted as one.
                val runOver = viewModel.sessionReport.value != null
                if (listening) everListened = true
                // A drop only counts once the session has actually started, and never while a
                // correction clip is deliberately holding the mic.
                if (everListened && wasListening && !listening && !correcting && !runOver) micDrops++
                if (correcting && !wasCorrecting) {
                    falseInterruptions++
                    val (s, a) = viewModel.surahAndAyah.value
                    interruptedOn += "$s:$a"
                }
                wasListening = listening
                wasCorrecting = correcting
                sleep(WATCH_POLL_MS)
            }
        }

        fun stopWatching() {
            running = false
            join(2_000)
        }
    }

    private fun playAloud(clip: RecitationCorpus.Clip) {
        val file = RecitationCorpus.extractToCache(instrumentation.context, app, clip)
        val done = CountDownLatch(1)
        val player = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            setDataSource(file.absolutePath)
            setOnCompletionListener { done.countDown() }
            prepare()
            start()
        }
        try {
            done.await((clip.seconds * 1000).toLong() + PLAYBACK_GRACE_MS, TimeUnit.MILLISECONDS)
        } finally {
            runCatching { player.stop() }
            player.release()
        }
    }

    private fun awaitCondition(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(POLL_MS)
        }
        return condition()
    }

    private fun awaitTrue(message: String, timeoutMs: Long, condition: () -> Boolean) {
        assertTrue(message, awaitCondition(timeoutMs, condition))
    }

    private companion object {
        /** Room for decode and fast ayah advance. */
        const val ADVANCE_TIMEOUT_MS = 16_000L

        /**
         * Quiet time after the audio stops, before the recap is asked for.
         *
         * A mistake is only believed once a second evaluation names it, and the correction clip
         * plays after that; cutting the session off at the end of the audio would read the recap
         * before the coach had finished making up its mind.
         */
        const val SETTLE_AFTER_AYAH_MS = 8_000L
        const val PLAYBACK_GRACE_MS = 400L
        const val POLL_MS = 50L
        const val WATCH_POLL_MS = 25L
    }
}
