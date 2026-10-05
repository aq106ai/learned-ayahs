package com.quran.learnedplayer.data

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The coach scores the **shipped Uthmani text** against what a speech recognizer actually says,
 * and the two scripts do not agree letter for letter or even word for word.
 *
 * Every transcript below is the plain (imlaei) spelling of that ayah with the harakat removed —
 * the shape recognition output arrives in. Each of the [ORTHOGRAPHY_FIXES] failed before
 * [ArabicWordAligner] and [ArabicTextNormalizer.isLongVowelSpellingVariant] existed: the student
 * recites correctly and is told they are wrong, usually on the first word, after which every
 * later word is misaligned too. Checked across the whole Qur'an the same way, 434 ayahs behaved
 * like this and 16 still do — spellings like رَءَا against "رأى" or ٱلَّـٰٓـِٔى against "اللائي",
 * neither of which can be folded without also folding words that really are different. (A
 * seventeenth, 2:181, was a data error — its ayah-number glyph was typed as a word — and is fixed;
 * see [ayah2181_canBeRecitedToTheEnd].)
 *
 * [UNCHANGED] are ayahs that always worked, kept so a future relaxation of the matcher cannot
 * quietly buy its recall by breaking the ordinary case.
 */
@RunWith(RobolectricTestRunner::class)
class UthmaniTranscriptMatchTest {

    private val repo = QuranDataRepository(ApplicationProvider.getApplicationContext())

    /**
     * Uthmani orthography the plain script writes differently. Grouped by what is going on:
     * the attached vocative يا (by far the most common — 469 ayahs), ءَا for آ, and the
     * long-vowel differences in فَسْـَٔلْ / ٱلرِّبَوٰا۟ / تَا۟يْـَٔسُوا۟.
     */
    private val ORTHOGRAPHY_FIXES = listOf(
        // يَـٰٓأَيُّهَا -> "يا أيها": one written word, two heard tokens.
        "2:21" to "يا أيها الناس اعبدوا ربكم الذي خلقكم والذين من قبلكم لعلكم تتقون",
        "2:153" to "يا أيها الذين آمنوا استعينوا بالصبر والصلاة إن الله مع الصابرين",
        "49:13" to "يا أيها الناس إنا خلقناكم من ذكر وأنثى وجعلناكم شعوبا وقبائل لتعارفوا " +
            "إن أكرمكم عند الله أتقاكم إن الله عليم خبير",
        "2:104" to "يا أيها الذين آمنوا لا تقولوا راعنا وقولوا انظرنا واسمعوا وللكافرين عذاب أليم",
        // Other attached vocatives: يَـٰقَوْمِ, يَـٰبَنِىَّ, يَـٰٓأَبَتِ, يَـٰمُوسَىٰ.
        "36:20" to "وجاء من أقصى المدينة رجل يسعى قال يا قوم اتبعوا المرسلين",
        "7:59" to "لقد أرسلنا نوحا إلى قومه فقال يا قوم اعبدوا الله ما لكم من إله غيره " +
            "إني أخاف عليكم عذاب يوم عظيم",
        "19:42" to "إذ قال لأبيه يا أبت لم تعبد ما لا يسمع ولا يبصر ولا يغني عنك شيئا",
        // ءَاتَىٰ / ءَاتَاكُم -> آتى / آتاكم: ء and the dagger alif both fold to ا, so without the
        // alif-run collapse the reference word arrives one letter longer than what was heard.
        "5:20" to "وإذ قال موسى لقومه يا قوم اذكروا نعمة الله عليكم إذ جعل فيكم أنبياء " +
            "وجعلكم ملوكا وآتاكم ما لم يؤت أحدا من العالمين",
        "11:28" to "قال يا قوم أرأيتم إن كنت على بينة من ربي وآتاني رحمة من عنده فعميت عليكم " +
            "أنلزمكموها وأنتم لها كارهون",
        // فَسْـَٔلْ -> "فاسأل": two long vowels the Uthmani spelling leaves out.
        "17:101" to "ولقد آتينا موسى تسع آيات بينات فاسأل بني إسرائيل إذ جاءهم فقال له فرعون " +
            "إني لأظنك يا موسى مسحورا",
        // ٱلرِّبَوٰا۟ -> "الربا".
        "2:278" to "يا أيها الذين آمنوا اتقوا الله وذروا ما بقي من الربا إن كنتم مؤمنين",
    )

    /** Ayahs whose two spellings already lined up. These must not regress. */
    private val UNCHANGED = listOf(
        "1:1" to "بسم الله الرحمن الرحيم",
        "1:2" to "الحمد لله رب العالمين",
        "1:7" to "صراط الذين أنعمت عليهم غير المغضوب عليهم ولا الضالين",
        "112:1" to "قل هو الله أحد",
        "112:2" to "الله الصمد",
        "112:3" to "لم يلد ولم يولد",
        "112:4" to "ولم يكن له كفوا أحد",
        "93:1" to "والضحى",
        "114:1" to "قل أعوذ برب الناس",
        "2:255" to "الله لا إله إلا هو الحي القيوم لا تأخذه سنة ولا نوم له ما في السماوات " +
            "وما في الأرض من ذا الذي يشفع عنده إلا بإذنه يعلم ما بين أيديهم وما خلفهم " +
            "ولا يحيطون بشيء من علمه إلا بما شاء وسع كرسيه السماوات والأرض ولا يئوده " +
            "حفظهما وهو العلي العظيم",
    )

    @Test
    fun uthmaniOrthography_scoresPlainTranscriptsAsCorrect() {
        assertAllComplete(ORTHOGRAPHY_FIXES)
    }

    @Test
    fun ordinaryAyahs_stillScoreAsCorrect() {
        assertAllComplete(UNCHANGED)
    }

    /**
     * The matcher is looser, not blind. A single substituted word must still be named — and named
     * at the right index, which is the part the alignment used to get wrong even when it noticed
     * something was off.
     */
    @Test
    fun oneWrongWord_isStillFlagged_atTheRightIndex() = runBlocking {
        // 2:21 word 3 (0-based) is رَبَّكُمُ; the student says رَبِّي instead.
        val words = repo.wordsFor(2, 21)
        val transcript = "يا أيها الناس اعبدوا ربي الذي خلقكم والذين من قبلكم لعلكم تتقون"

        val result = RecitationEvaluator.evaluateContinuing(
            referenceWords = words,
            spokenTranscript = transcript,
            lastTokenStable = true,
            lockedCorrectCount = 0,
        )

        val mistake = result.firstMistake
        assertEquals("only the substituted word is wrong", 1, result.mistakeCount)
        assertEquals("رَبَّكُمُ is the 4th content word", 3, mistake?.wordIndex)
        assertEquals("ربي", mistake?.spokenWord)
    }

    /**
     * What the residue costs, stated rather than hidden.
     *
     * 12:87 has يَا۟يْـَٔسُ, which normalizes to `يايس` — one consonant and three long vowels — against
     * a recognizer's `ييأس` -> `ييأس`. The two are a vowel transposition apart, and
     * [ArabicTextNormalizer.isLongVowelSpellingVariant] declines to judge a word with fewer than
     * two consonants because at that length إِلَّا and أُو۟لُوا۟ also look alike.
     *
     * That leaves the word unmatched — but **not** an interruption: the gap is inside
     * [RecitationTuning.NEAR_MISS_TOLERANCE], which is exactly the band `isConfidentMistake`
     * refuses to stop the student for. The ayah does not complete on its own and the student
     * presses Next; nobody is corrected on a word they recited correctly. Every one of the sixteen
     * ayahs that still misalign behaves this way, and that is the property worth holding onto.
     */
    @Test
    fun residualSpellingGaps_doNotReachTheInterruptThreshold() = runBlocking {
        val words = repo.wordsFor(12, 87)
        val transcript = "يا بني اذهبوا فتحسسوا من يوسف وأخيه ولا تيأسوا من روح الله " +
            "إنه لا ييأس من روح الله إلا القوم الكافرون"

        val result = RecitationEvaluator.evaluateContinuing(
            referenceWords = words,
            spokenTranscript = transcript,
            lastTokenStable = true,
            lockedCorrectCount = 0,
        )

        val mistake = result.firstMistake
        assertEquals("only يَا۟يْـَٔسُ fails to align", 13, mistake?.wordIndex)
        assertTrue(
            "the ayah before it still scores — the walk must not collapse to 0 correct",
            result.correctCount >= 13,
        )

        val expected = ArabicTextNormalizer.normalize(mistake!!.originalWord.text)
        val heard = ArabicTextNormalizer.normalize(mistake.spokenWord.orEmpty())
        assertTrue(
            "a residual spelling gap must stay inside the near-miss band, or the coach " +
                "interrupts a correct recitation",
            ArabicTextNormalizer.levenshteinDistance(expected, heard) <=
                RecitationTuning.NEAR_MISS_TOLERANCE,
        )
    }

    /**
     * 2:181's ayah-number glyph used to be typed `"word"` in the text asset, so the coach waited
     * for the student to recite "١٨١" — it normalizes to nothing and could never match, and the
     * ayah could never finish. It is the end marker now; the ayah completes whether the recognizer
     * writes بعدما as one word or two.
     */
    @Test
    fun ayah2181_canBeRecitedToTheEnd() = runBlocking {
        val words = repo.wordsFor(2, 181)
        assertEquals("13 words, then the end marker", 13, words.count { !it.isEnd })
        assertTrue("the ayah number is the end marker", words.last().isEnd)
        for (transcript in listOf(
            "فمن بدله بعدما سمعه فإنما إثمه على الذين يبدلونه إن الله سميع عليم",
            "فمن بدله بعد ما سمعه فإنما إثمه على الذين يبدلونه إن الله سميع عليم",
        )) {
            val result = RecitationEvaluator.evaluateContinuing(
                referenceWords = words,
                spokenTranscript = transcript,
                lastTokenStable = true,
                lockedCorrectCount = 0,
            )
            assertEquals(transcript, 13, result.correctCount)
            assertEquals(transcript, 0, result.mistakeCount)
        }
    }

    /** A dropped word is a skip, and the skip has to name the word that went missing. */
    @Test
    fun droppedWord_isFlaggedAsSkip() = runBlocking {
        val words = repo.wordsFor(2, 21)
        // ٱلنَّاسُ (index 1) is missing entirely.
        val transcript = "يا أيها اعبدوا ربكم الذي خلقكم والذين من قبلكم لعلكم تتقون"

        val result = RecitationEvaluator.evaluateContinuing(
            referenceWords = words,
            spokenTranscript = transcript,
            lastTokenStable = true,
            lockedCorrectCount = 0,
        )

        val mistake = result.firstMistake
        assertEquals(1, mistake?.wordIndex)
        assertEquals("a skip names no heard word", null, mistake?.spokenWord)
        assertTrue(mistake?.feedback?.startsWith("Skipped word") == true)
    }

    private fun assertAllComplete(cases: List<Pair<String, String>>) = runBlocking {
        val failures = mutableListOf<String>()
        for ((key, transcript) in cases) {
            val (surah, ayah) = key.split(":").map { it.toInt() }
            val words = repo.wordsFor(surah, ayah)
            assertTrue("$key must resolve from the bundled text", words.isNotEmpty())

            val result = RecitationEvaluator.evaluateContinuing(
                referenceWords = words,
                spokenTranscript = transcript,
                lastTokenStable = true,
                lockedCorrectCount = 0,
            )
            if (!result.isComplete) {
                val wrong = result.evaluations
                    .filter { it.status != WordEvaluationStatus.CORRECT }
                    .joinToString(", ") { "[${it.wordIndex}] ${it.originalWord.text} <- ${it.spokenWord}" }
                failures += "$key ${result.correctCount}/${result.totalContentWords}: $wrong"
            }
        }
        assertEquals(
            "ayahs recited correctly but not scored correct:\n" + failures.joinToString("\n"),
            emptyList<String>(),
            failures,
        )
    }
}
