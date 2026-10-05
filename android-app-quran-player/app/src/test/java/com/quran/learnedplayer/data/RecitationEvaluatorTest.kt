package com.quran.learnedplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecitationEvaluatorTest {

    private val fatihaVerse2Words = listOf(
        AyahWord("ٱلْحَمْدُ", isEnd = false),
        AyahWord("لِلَّهِ", isEnd = false),
        AyahWord("رَبِّ", isEnd = false),
        AyahWord("ٱلْعَـٰلَمِينَ", isEnd = false),
        AyahWord("٢", isEnd = true),
    )

    @Test
    fun evaluate_perfectRecitation_returns100Percent() {
        val transcript = "الحمد لله رب العالمين"
        val result = RecitationEvaluator.evaluate(fatihaVerse2Words, transcript)

        assertEquals(4, result.totalContentWords)
        assertEquals(4, result.correctCount)
        assertEquals(0, result.mistakeCount)
        assertEquals(100f, result.accuracyPercentage, 0.1f)
        assertTrue(result.isComplete)
    }

    @Test
    fun evaluate_withMistake_detectsMistakeAndProvidesFeedback() {
        val result = RecitationEvaluator.evaluate(fatihaVerse2Words, "الحمد لله مالك العالمين")

        assertEquals(4, result.totalContentWords)
        assertEquals(3, result.correctCount)
        assertEquals(1, result.mistakeCount)
        assertEquals(75f, result.accuracyPercentage, 0.1f)
        assertNotNull(result.firstMistake)
        assertEquals(WordEvaluationStatus.MISTAKE, result.firstMistake?.status)
    }

    @Test
    fun evaluate_partialRecitation_marksRemainingPending() {
        val transcript = "الحمد لله"
        val result = RecitationEvaluator.evaluate(fatihaVerse2Words, transcript)

        assertEquals(4, result.totalContentWords)
        assertEquals(2, result.correctCount)
        assertEquals(0, result.mistakeCount)
        assertEquals(WordEvaluationStatus.CORRECT, result.evaluations[0].status)
        assertEquals(WordEvaluationStatus.CORRECT, result.evaluations[1].status)
        assertEquals(WordEvaluationStatus.PENDING, result.evaluations[2].status)
        assertEquals(WordEvaluationStatus.PENDING, result.evaluations[3].status)
    }

    @Test
    fun evaluate_skippedWord_flagsMistakeAtSkippedPosition() {
        val result = RecitationEvaluator.evaluate(fatihaVerse2Words, "الحمد رب العالمين")

        assertEquals(4, result.totalContentWords)
        assertEquals(3, result.correctCount)
        assertEquals(1, result.mistakeCount)
        assertEquals(WordEvaluationStatus.MISTAKE, result.evaluations[1].status)
    }

    @Test
    fun evaluate_partial_never_marks_mistakes() {
        val result = RecitationEvaluator.evaluate(fatihaVerse2Words, "الحمد لله مالك", committed = false)

        assertEquals(0, result.mistakeCount)
        assertEquals(false, result.isComplete)
        assertEquals(WordEvaluationStatus.CORRECT, result.evaluations[0].status)
        assertEquals(WordEvaluationStatus.CORRECT, result.evaluations[1].status)
        assertEquals(WordEvaluationStatus.PENDING, result.evaluations[2].status)
    }

    @Test
    fun evaluate_isComplete_requires_every_word_correct() {
        val incomplete = RecitationEvaluator.evaluate(fatihaVerse2Words, "الحمد لله رب")
        assertEquals(false, incomplete.isComplete)

        val withMistake = RecitationEvaluator.evaluate(fatihaVerse2Words, "الحمد لله مالك العالمين")
        assertEquals(false, withMistake.isComplete)

        val perfect = RecitationEvaluator.evaluate(fatihaVerse2Words, "الحمد لله رب العالمين")
        assertEquals(true, perfect.isComplete)
    }

    @Test
    fun evaluate_trailing_extra_asr_tokens_are_not_failure() {
        val result = RecitationEvaluator.evaluate(
            fatihaVerse2Words,
            "الحمد لله رب العالمين extra",
        )
        assertTrue(result.isComplete)
        assertEquals(0, result.mistakeCount)
    }

    @Test
    fun evaluate_folds_hamza_on_ya_and_waw() {
        val words = listOf(
            AyahWord("مُسْتَهْزِئُونَ"),
            AyahWord("مُؤْمِنُونَ"),
        )
        val result = RecitationEvaluator.evaluate(words, "مستهزيون مومنون")
        assertTrue(result.isComplete)
        assertEquals(0, result.mistakeCount)
    }

    @Test
    fun evaluate_empty_reference_is_not_complete() {
        val result = RecitationEvaluator.evaluate(emptyList(), "")
        assertFalse(result.isComplete)
    }

    @Test
    fun evaluateStreaming_inProgressPrefix_staysPending() {
        val result = RecitationEvaluator.evaluateStreaming(
            fatihaVerse2Words,
            "الح",
            lastTokenStable = true,
        )
        assertEquals(0, result.mistakeCount)
        assertEquals(WordEvaluationStatus.PENDING, result.evaluations[0].status)
        assertEquals(WordEvaluationStatus.PENDING, result.evaluations[1].status)
    }

    @Test
    fun evaluateStreaming_unstableWrongToken_staysPending() {
        val result = RecitationEvaluator.evaluateStreaming(
            fatihaVerse2Words,
            "الحمد لله مالك",
            lastTokenStable = false,
        )
        assertEquals(0, result.mistakeCount)
        assertEquals(WordEvaluationStatus.CORRECT, result.evaluations[0].status)
        assertEquals(WordEvaluationStatus.CORRECT, result.evaluations[1].status)
        assertEquals(WordEvaluationStatus.PENDING, result.evaluations[2].status)
    }

    @Test
    fun evaluateStreaming_stableWrongToken_isMistake() {
        val result = RecitationEvaluator.evaluateStreaming(
            fatihaVerse2Words,
            "الحمد لله مالك",
            lastTokenStable = true,
        )
        assertEquals(1, result.mistakeCount)
        assertEquals(WordEvaluationStatus.MISTAKE, result.evaluations[2].status)
        assertEquals(2, result.evaluations[2].wordIndex)
        assertNotNull(result.firstMistake)
    }

    @Test
    fun evaluateStreaming_stableSkip_flagsSkippedWord() {
        val result = RecitationEvaluator.evaluateStreaming(
            fatihaVerse2Words,
            "الحمد رب",
            lastTokenStable = true,
        )
        assertEquals(1, result.mistakeCount)
        assertEquals(WordEvaluationStatus.MISTAKE, result.evaluations[1].status)
        assertEquals(WordEvaluationStatus.CORRECT, result.evaluations[0].status)
    }

    @Test
    fun evaluateContinuing_fromMistake_completesRemaining() {
        val result = RecitationEvaluator.evaluateContinuing(
            fatihaVerse2Words,
            "رب العالمين",
            lastTokenStable = true,
            lockedCorrectCount = 2,
        )
        assertEquals(0, result.mistakeCount)
        assertEquals(WordEvaluationStatus.CORRECT, result.evaluations[0].status)
        assertEquals(WordEvaluationStatus.CORRECT, result.evaluations[1].status)
        assertEquals(WordEvaluationStatus.CORRECT, result.evaluations[2].status)
        assertEquals(WordEvaluationStatus.CORRECT, result.evaluations[3].status)
        assertTrue(result.isComplete)
    }

    @Test
    fun evaluateContinuing_oneWordLookback_extendsWithoutResettingPrefix() {
        val result = RecitationEvaluator.evaluateContinuing(
            fatihaVerse2Words,
            "لله رب",
            lastTokenStable = true,
            lockedCorrectCount = 2,
        )
        assertEquals(0, result.mistakeCount)
        assertEquals(WordEvaluationStatus.CORRECT, result.evaluations[0].status)
        assertEquals(WordEvaluationStatus.CORRECT, result.evaluations[1].status)
        assertEquals(WordEvaluationStatus.CORRECT, result.evaluations[2].status)
        assertEquals(WordEvaluationStatus.PENDING, result.evaluations[3].status)
        assertFalse(result.isComplete)
    }

    @Test
    fun evaluateContinuing_stableWrongToken_keepsLock() {
        val result = RecitationEvaluator.evaluateContinuing(
            fatihaVerse2Words,
            "مالك",
            lastTokenStable = true,
            lockedCorrectCount = 2,
        )
        assertEquals(1, result.mistakeCount)
        assertEquals(WordEvaluationStatus.CORRECT, result.evaluations[0].status)
        assertEquals(WordEvaluationStatus.CORRECT, result.evaluations[1].status)
        assertEquals(WordEvaluationStatus.MISTAKE, result.evaluations[2].status)
        assertEquals(2, result.evaluations[2].wordIndex)
        assertNotNull(result.firstMistake)
        assertFalse(result.isComplete)
    }

    @Test
    fun evaluateContinuing_lockedPrefixOnly_hasNoNewMistake() {
        val result = RecitationEvaluator.evaluateContinuing(
            fatihaVerse2Words,
            "الحمد لله",
            lastTokenStable = true,
            lockedCorrectCount = 2,
        )
        assertEquals(0, result.mistakeCount)
        assertEquals(WordEvaluationStatus.CORRECT, result.evaluations[0].status)
        assertEquals(WordEvaluationStatus.CORRECT, result.evaluations[1].status)
        assertEquals(WordEvaluationStatus.PENDING, result.evaluations[2].status)
        assertNull(result.firstMistake)
    }

    @Test
    fun evaluateContinuing_livePartial_doesNotFlagSkipAsMistake() {
        val result = RecitationEvaluator.evaluateContinuing(
            fatihaVerse2Words,
            "الحمد رب",
            lastTokenStable = true,
            lockedCorrectCount = 0,
            allowSkipMistakes = false,
        )
        assertEquals(0, result.mistakeCount)
        assertEquals(WordEvaluationStatus.CORRECT, result.evaluations[0].status)
        assertEquals(WordEvaluationStatus.PENDING, result.evaluations[1].status)
        assertNull(result.firstMistake)
    }

    @Test
    fun evaluateContinuing_midAyahResume_afterGap_extendsWithoutSkipMistake() {
        // User locked first two words, then mic gap, then resumes with "رب العالمين"
        val result = RecitationEvaluator.evaluateContinuing(
            fatihaVerse2Words,
            "رب العالمين",
            lastTokenStable = true,
            lockedCorrectCount = 2,
            allowSkipMistakes = false,
        )
        assertEquals(0, result.mistakeCount)
        assertEquals(WordEvaluationStatus.CORRECT, result.evaluations[0].status)
        assertEquals(WordEvaluationStatus.CORRECT, result.evaluations[1].status)
        assertEquals(WordEvaluationStatus.CORRECT, result.evaluations[2].status)
        assertEquals(WordEvaluationStatus.CORRECT, result.evaluations[3].status)
        assertTrue(result.isComplete)
    }

    @Test
    fun evaluateContinuing_unstableWrongToken_staysPending() {
        val result = RecitationEvaluator.evaluateContinuing(
            fatihaVerse2Words,
            "الحمد لله مالك",
            lastTokenStable = false,
            lockedCorrectCount = 0,
            allowSkipMistakes = false,
        )
        assertEquals(0, result.mistakeCount)
        assertEquals(WordEvaluationStatus.PENDING, result.evaluations[2].status)
    }

    @Test
    fun evaluateContinuing_stableWrongContentWord_isMistake() {
        val result = RecitationEvaluator.evaluateContinuing(
            fatihaVerse2Words,
            "الحمد لله مالك",
            lastTokenStable = true,
            lockedCorrectCount = 0,
            allowSkipMistakes = false,
        )
        assertEquals(1, result.mistakeCount)
        assertEquals(WordEvaluationStatus.MISTAKE, result.evaluations[2].status)
        assertEquals("مالك", result.evaluations[2].spokenWord)
        assertNotNull(result.firstMistake)
    }

    @Test
    fun evaluateContinuing_committedSkip_stillFlagsMistake() {
        val result = RecitationEvaluator.evaluateContinuing(
            fatihaVerse2Words,
            "الحمد رب",
            lastTokenStable = true,
            lockedCorrectCount = 0,
            allowSkipMistakes = true,
        )
        assertEquals(1, result.mistakeCount)
        assertEquals(WordEvaluationStatus.MISTAKE, result.evaluations[1].status)
        assertNull(result.evaluations[1].spokenWord)
    }

    @Test
    fun evaluateContinuing_introPrefix_leavesWordsPendingWithoutMistake() {
        val result = RecitationEvaluator.evaluateContinuing(
            fatihaVerse2Words,
            "اعوذ",
            lastTokenStable = true,
            lockedCorrectCount = 0,
            allowSkipMistakes = false,
        )
        assertEquals(0, result.mistakeCount)
        assertEquals(0, result.correctCount)
        assertEquals(WordEvaluationStatus.PENDING, result.evaluations[0].status)
        assertNull(result.firstMistake)
    }

    @Test
    fun evaluateContinuing_midAyahGap_preservesPendingAndLaterMatches() {
        val sixWordAyah = listOf(
            AyahWord("قُلْ", isEnd = false),
            AyahWord("أَعُوذُ", isEnd = false),
            AyahWord("بِرَبِّ", isEnd = false),
            AyahWord("ٱلْفَلَقِ", isEnd = false),
            AyahWord("مِن", isEnd = false),
            AyahWord("شَرِّ", isEnd = false),
            AyahWord("١", isEnd = true),
        )
        // User locked first 2 words, mic dropped during word 2, user spoke word 3 and 4
        val result = RecitationEvaluator.evaluateContinuing(
            sixWordAyah,
            "الفلق من",
            lastTokenStable = true,
            lockedCorrectCount = 2,
            allowSkipMistakes = false,
        )
        assertEquals(0, result.mistakeCount)
        assertEquals(WordEvaluationStatus.CORRECT, result.evaluations[0].status)
        assertEquals(WordEvaluationStatus.CORRECT, result.evaluations[1].status)
        assertEquals(WordEvaluationStatus.PENDING, result.evaluations[2].status)
        assertEquals(WordEvaluationStatus.CORRECT, result.evaluations[3].status)
        assertEquals(WordEvaluationStatus.CORRECT, result.evaluations[4].status)
        assertEquals(WordEvaluationStatus.PENDING, result.evaluations[5].status)
        assertNull(result.firstMistake)
    }
}
