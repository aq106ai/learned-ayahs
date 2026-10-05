package com.quran.learnedplayer.player

import android.app.Application
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.quran.learnedplayer.data.AyahMapping
import com.quran.learnedplayer.data.LearnedAyahsStore
import com.quran.learnedplayer.data.PlaylistStore
import com.quran.learnedplayer.data.QuranDataRepository
import com.quran.learnedplayer.data.RecitationTuning
import com.quran.learnedplayer.data.Reciter
import com.quran.learnedplayer.data.WordAudio
import com.quran.learnedplayer.data.WordReciter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Recite & review driven end to end, once per shipped reciter: a whole surah recited correctly,
 * then the same surah with one word wrong.
 *
 * It drives [RecitationViewModel] through `acceptTranscript`, so everything downstream of the
 * microphone is real — the intro filter, the evaluator, corroboration, the locked prefix,
 * auto-advance between ayahs, and the run recap. What it replaces is the acoustic layer, which
 * `FullSurahRecitationTest` covers on a device and which cannot be judged reliably anyway (see
 * CLAUDE.md on the engine comparison that scored the same audio 84%, 62% and 53% in one
 * afternoon). The transcripts are the plain undiacritized Arabic a recognizer actually returns,
 * not the Uthmani text the app scores it against — which is the whole difficulty.
 *
 * **Every reciter is run, including the two added most recently.** Choosing a reciter changes the
 * audio URL, the cache directory and the timing asset, and nothing in the coach — so the point of
 * looping is to hold that line: recitation quality must not become reciter-dependent by accident.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ReciteReviewEndToEndTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    private val repository = QuranDataRepository(app)

    private var store: ViewModelStore? = null

    /** Al-Ikhlas, as a recognizer would return it. */
    private val alIkhlas = listOf(
        112 to "قل هو الله أحد",
        112 to "الله الصمد",
        112 to "لم يلد ولم يولد",
        112 to "ولم يكن له كفوا أحد",
    ).mapIndexed { index, (surah, text) -> Triple(surah, index + 1, text) }

    @Before
    fun setUp() {
        PlayerSettings.init(app)
        LearnedAyahsStore.init(app)
        PlayerSettings.reciteBetaEnabled = true
        PlayerSettings.autoAdvanceSuccess = true
        // The coach still records the mistake; it just does not reach for ExoPlayer to play a
        // correction clip, which has no business running in a JVM test.
        PlayerSettings.autoPlayMistakeAudio = false
        PlaylistStore.latest = null
        Dispatchers.setMain(Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        LearnedAyahsStore.clear()
        PlayerSettings.reciter = Reciter.DEFAULT
    }

    @Test
    fun everyReciter_recitesAlIkhlasWithoutBeingInterrupted() {
        for (reciter in Reciter.entries) {
            PlayerSettings.reciter = reciter
            val report = runSurah(alIkhlas)

            assertNotNull("${reciter.displayName}: the run never produced a recap", report)
            assertEquals(
                "${reciter.displayName}: the recap should cover every ayah of the surah",
                4,
                report!!.ayahs.size,
            )
            assertEquals(
                "${reciter.displayName}: a correct recitation was scored as a mistake on " +
                    report.ayahs.filter { it.mistakenWordIndices.isNotEmpty() }
                        .joinToString { "${it.surah}:${it.ayah}${it.mistakenWordIndices}" },
                0,
                report.mistakeCount,
            )
            assertEquals("${reciter.displayName}: accuracy", 100, report.accuracyPercent)
            assertTrue(
                "${reciter.displayName}: the run should end by reaching the end of the surah",
                report.reachedEndOfSurah,
            )
        }
    }

    /**
     * One substituted word — the ordinary memorisation slip this feature exists for.
     *
     * ٱلْحَمْدُ لِلَّهِ **رَبِّ** ٱلْعَـٰلَمِينَ recited with مَـٰلِكِ from 1:4 in its place. The coach must name
     * that word and only that word, and the words around it must still count as recited.
     */
    @Test
    fun everyReciter_namesOneWrongWordAndNothingElse() {
        for (reciter in Reciter.entries) {
            PlayerSettings.reciter = reciter
            val viewModel = start(setOf(AyahMapping.surahAyahToGlobal(1, 2)))
            try {
                awaitWords(viewModel)
                // Said twice, because a single evaluation is never believed: a decode artifact
                // moves between passes and a real mistake does not.
                repeat(2) { viewModel.acceptTranscript("الحمد لله مالك العالمين") }

                viewModel.stopRecording()
                val report = await("${reciter.displayName}: no recap after stopping") {
                    viewModel.sessionReport.value
                }!!

                val outcome = report.ayahs.single()
                assertEquals(
                    "${reciter.displayName}: only رَبِّ (word 2) is wrong",
                    listOf(2),
                    outcome.mistakenWordIndices,
                )
                // Two, not three: the walk stops at the wrong word, so ٱلْعَـٰلَمِينَ after it is not
                // credited until the student gets past رَبِّ. The recap describes what was actually
                // settled, which is the point of `wordsEverCorrect` being cumulative.
                assertEquals(
                    "${reciter.displayName}: the words before the mistake still count",
                    2,
                    outcome.correctCount,
                )
                assertEquals(4, outcome.totalContentWords)
            } finally {
                release()
            }
        }
    }

    /**
     * A dropped word is the other half of "a small mistake", and it is the one the coach is
     * allowed to be sure about: the student is already saying a later word, so something really
     * was missed.
     */
    @Test
    fun aSkippedWord_isNamedAtTheWordThatWasMissed() {
        PlayerSettings.reciter = Reciter.DEFAULT
        val viewModel = start(setOf(AyahMapping.surahAyahToGlobal(112, 4)))
        try {
            awaitWords(viewModel)
            // وَلَمْ يَكُن **لَّهُۥ** كُفُوًا أَحَدٌۢ, with لَّهُۥ left out.
            repeat(2) { viewModel.acceptTranscript("ولم يكن كفوا أحد") }

            viewModel.stopRecording()
            val report = await("no recap after stopping") { viewModel.sessionReport.value }!!
            assertEquals(listOf(2), report.ayahs.single().mistakenWordIndices)
        } finally {
            release()
        }
    }

    /**
     * The failure this whole change is about, at the level the user meets it.
     *
     * 2:21 opens يَـٰٓأَيُّهَا — one word in the Uthmani text, "يا أيها" out of every recognizer. The
     * coach used to score the ayah wrong from its first word and stay wrong for the rest of it.
     */
    @Test
    fun anAyahOpeningWithTheAttachedVocative_isRecitedCleanly() {
        PlayerSettings.reciter = Reciter.DEFAULT
        val viewModel = start(setOf(AyahMapping.surahAyahToGlobal(2, 21)))
        try {
            awaitWords(viewModel)
            viewModel.acceptTranscript(
                "يا أيها الناس اعبدوا ربكم الذي خلقكم والذين من قبلكم لعلكم تتقون",
            )

            val result = viewModel.evaluationResult.value
            assertNotNull(result)
            assertEquals("2:21 has 11 content words", 11, result!!.totalContentWords)
            assertEquals("every word was recited", 11, result.correctCount)
            assertEquals(0, result.mistakeCount)
        } finally {
            release()
        }
    }

    /**
     * The failure from the phone, fixed by reading the recognizer's other hypotheses.
     *
     * At-Takathur 102:1 opens أَلْهَىٰكُمُ and the recognizer's first choice was `الحاكم` — ه heard as
     * ح, one substitution on a sound the student got right. The app asked for five hypotheses and
     * read one, so the student was told they were wrong on a perfect recitation.
     */
    @Test
    fun aMishearingIsRescuedByTheRecognizersOtherReadings() {
        PlayerSettings.reciter = Reciter.DEFAULT
        val viewModel = start(setOf(AyahMapping.surahAyahToGlobal(102, 1)))
        try {
            awaitWords(viewModel)
            viewModel.acceptTranscript(
                transcript = "الحاكم التكاثر",
                alternatives = listOf("ألهاكم التكاثر", "الحاكم التكاثر"),
            )

            val result = viewModel.evaluationResult.value
            assertNotNull(result)
            assertEquals("102:1 has 2 content words", 2, result!!.totalContentWords)
            assertEquals("both words were recited", 2, result.correctCount)
            assertEquals(0, result.mistakeCount)
        } finally {
            release()
        }
    }

    /**
     * The half that keeps the feature honest: alternatives are evidence about *this* audio, not a
     * licence to find some reading that fits.
     *
     * The student really did say مَـٰلِكِ where رَبِّ belongs. Every hypothesis is a rendering of that
     * same wrong word, so none of them rescues it and the mistake still lands on word 2. Were this
     * implemented as "accept anything close enough", العزيز and العليم would start matching — 60
     * such pairs sit next to each other in the Qur'an, and they are exactly what a memoriser slips
     * on.
     */
    @Test
    fun alternativesDoNotRescueAWordTheStudentActuallyGotWrong() {
        PlayerSettings.reciter = Reciter.DEFAULT
        val viewModel = start(setOf(AyahMapping.surahAyahToGlobal(1, 2)))
        try {
            awaitWords(viewModel)
            repeat(2) {
                viewModel.acceptTranscript(
                    transcript = "الحمد لله مالك العالمين",
                    alternatives = listOf(
                        "الحمد لله ملك العالمين",
                        "الحمد لله مالكي العالمين",
                        "الحمد لله مالك العالمين",
                    ),
                )
            }

            viewModel.stopRecording()
            val report = await("no recap after stopping") { viewModel.sessionReport.value }!!
            assertEquals(
                "رَبِّ was genuinely misrecited; no reading of that audio says رَبِّ",
                listOf(2),
                report.ayahs.single().mistakenWordIndices,
            )
        } finally {
            release()
        }
    }

    /**
     * The screenshot bug: a word the coach declined to report was still shown as a mistake.
     *
     * With no alternative to rescue it, `الحاكم` for أَلْهَىٰكُمُ stays unmatched — but it is two
     * edits away, inside [RecitationTuning.NEAR_MISS_TOLERANCE], so `isConfidentMistake` refuses
     * to stop the student. The evaluation still carries a MISTAKE, and the screen used to read
     * that directly: red word, "Mistake Detected", Listen Reciter / Retry. Nothing may reach the
     * student unless the coach believes it.
     */
    @Test
    fun aNearMissIsNeverShownToTheStudentAsAMistake() {
        PlayerSettings.reciter = Reciter.DEFAULT
        val viewModel = start(setOf(AyahMapping.surahAyahToGlobal(102, 1)))
        try {
            awaitWords(viewModel)
            repeat(3) { viewModel.acceptTranscript("الحاكم التكاثر") }

            assertNotNull(
                "the raw evaluation does flag it — that is what the screen must not read",
                viewModel.evaluationResult.value?.firstMistake,
            )
            assertEquals(
                "a near miss must never become a 'Mistake Detected' card",
                null,
                viewModel.confirmedMistake.value,
            )
            assertEquals(
                "and no word may be painted red for it",
                emptySet<Int>(),
                viewModel.believedMistakeIndices.value,
            )
        } finally {
            release()
        }
    }

    /** A mistake the coach does believe still reaches the screen. */
    @Test
    fun aBelievedMistakeIsShownToTheStudent() {
        PlayerSettings.reciter = Reciter.DEFAULT
        val viewModel = start(setOf(AyahMapping.surahAyahToGlobal(1, 2)))
        try {
            awaitWords(viewModel)
            repeat(2) { viewModel.acceptTranscript("الحمد لله مالك العالمين") }

            val shown = viewModel.confirmedMistake.value
            assertNotNull("a corroborated, properly wrong word must be shown", shown)
            assertEquals(2, shown!!.wordIndex)
            assertEquals(setOf(2), viewModel.believedMistakeIndices.value)
        } finally {
            release()
        }
    }

    /** With no alternatives offered, scoring is exactly what it was. */
    @Test
    fun withoutAlternatives_theFirstReadingIsUsedUnchanged() {
        PlayerSettings.reciter = Reciter.DEFAULT
        val viewModel = start(setOf(AyahMapping.surahAyahToGlobal(102, 1)))
        try {
            awaitWords(viewModel)
            viewModel.acceptTranscript("الحاكم التكاثر")

            val result = viewModel.evaluationResult.value
            assertNotNull(result)
            assertEquals("the misheard word cannot be settled on its own", 0, result!!.correctCount)
        } finally {
            release()
        }
    }

    // --- harness -----------------------------------------------------------------------------

    /** Recites a whole surah, one ayah at a time, and returns the recap the run produced. */
    private fun runSurah(ayahs: List<Triple<Int, Int, String>>): RecitationSessionReport? {
        val ids = ayahs.map { (surah, ayah, _) -> AyahMapping.surahAyahToGlobal(surah, ayah) }
        val viewModel = start(ids.toSet())
        try {
            awaitWords(viewModel)
            for ((index, entry) in ayahs.withIndex()) {
                val (surah, ayah, transcript) = entry
                assertEquals(
                    "the coach is on the wrong ayah before reciting $surah:$ayah",
                    ids[index],
                    viewModel.currentGlobalId.value,
                )
                viewModel.acceptTranscript(transcript)

                if (index < ayahs.lastIndex) {
                    await("the coach never moved on from $surah:$ayah") {
                        viewModel.currentGlobalId.value.takeIf { it == ids[index + 1] }
                    }
                    awaitWords(viewModel)
                }
            }
            return await("the run never published a recap") { viewModel.sessionReport.value }
        } finally {
            release()
        }
    }

    /**
     * Built through a [ViewModelStore] so it can actually be destroyed afterwards: a plain
     * `RecitationViewModel(app)` has no owner, so `onCleared` never runs and its speech manager
     * outlives the test into the next reciter's run.
     */
    private fun start(learned: Set<Int>): RecitationViewModel {
        LearnedAyahsStore.clear()
        LearnedAyahsStore.addAll(learned)
        // Pre-fill the word-clip cache so nothing in this test reaches the network: the
        // downloader returns any file that already exists and is non-empty.
        learned.forEach { seedWordClipCache(it) }
        val store = ViewModelStore().also { this.store = it }
        return ViewModelProvider(
            store,
            ViewModelProvider.AndroidViewModelFactory.getInstance(app),
        )[RecitationViewModel::class.java].also { it.loadLearnedAyahs() }
    }

    private fun release() {
        store?.clear()
        store = null
    }

    private fun seedWordClipCache(globalId: Int) {
        val (surah, ayah) = AyahMapping.globalToSurahAyah(globalId)
        val root = File(app.filesDir, "audio/wbw/${WordReciter.DEFAULT.folder}").apply { mkdirs() }
        // One file per content word is exactly what `isAyahCached` stats, so the downloader finds
        // the ayah complete and never opens a connection.
        for (index in 0 until repository.contentWordCount(surah, ayah)) {
            File(root, WordAudio.filename(surah, ayah, index)).apply {
                if (!exists()) writeBytes(ByteArray(1))
            }
        }
    }

    private fun awaitWords(viewModel: RecitationViewModel) {
        await("the ayah text never loaded") {
            viewModel.currentWords.value.takeIf { it.isNotEmpty() }
        }
    }

    /**
     * Polls rather than using a test scheduler: the ViewModel loads its text on `Dispatchers.IO`
     * and pauses between ayahs on a real delay, neither of which a virtual clock controls.
     */
    private fun <T : Any> await(message: String, value: () -> T?): T? {
        val deadline = System.currentTimeMillis() + AWAIT_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            value()?.let { return it }
            Thread.sleep(POLL_MS)
        }
        val last = value()
        assertNotNull(message, last)
        return last
    }

    private companion object {
        const val AWAIT_TIMEOUT_MS = 10_000L
        const val POLL_MS = 10L
    }
}
