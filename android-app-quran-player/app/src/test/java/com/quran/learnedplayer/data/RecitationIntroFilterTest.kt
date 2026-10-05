package com.quran.learnedplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecitationIntroFilterTest {

    @Test
    fun strip_audhuAndBismillah_leavesContent() {
        val spoken = "اعوذ بالله من الشيطان الرجيم بسم الله الرحمن الرحيم الحمد لله رب العالمين"
        val stripped = RecitationIntroFilter.stripLeadingIntrosFromTranscript(spoken)
        assertEquals("الحمد لله رب العالمين", stripped)
    }

    @Test
    fun strip_longAudhu_withSamiAlAlim() {
        val spoken = "اعوذ بالله السميع العليم من الشيطان الرجيم قل هو الله احد"
        val stripped = RecitationIntroFilter.stripLeadingIntrosFromTranscript(spoken)
        assertEquals("قل هو الله احد", stripped)
    }

    @Test
    fun strip_audhu_withAzeem() {
        val spoken = "اعوذ بالله العظيم من الشيطان الرجيم قل هو الله احد"
        val stripped = RecitationIntroFilter.stripLeadingIntrosFromTranscript(spoken)
        assertEquals("قل هو الله احد", stripped)
    }

    @Test
    fun strip_astaizuVariant() {
        val spoken = "استعيذ بالله من الشيطان الرجيم قل هو الله احد"
        val stripped = RecitationIntroFilter.stripLeadingIntrosFromTranscript(spoken)
        assertEquals("قل هو الله احد", stripped)
    }

    @Test
    fun strip_introPrefixOnly_returnsEmpty_whenNotAyahOpening() {
        val ayahWords = listOf(
            AyahWord("قُلْ", isEnd = false),
            AyahWord("هُوَ", isEnd = false),
            AyahWord("ٱللَّهُ", isEnd = false),
            AyahWord("أَحَدٌ", isEnd = false),
        )
        val opening = RecitationIntroFilter.openingTokensOf(ayahWords)
        val strippedAudhu = RecitationIntroFilter.stripLeadingIntrosFromTranscript("اعوذ", opening)
        assertEquals("", strippedAudhu)
        val strippedBismillah = RecitationIntroFilter.stripLeadingIntrosFromTranscript("بسم", opening)
        assertEquals("", strippedBismillah)
    }

    @Test
    fun strip_shortBismillahOnly() {
        val stripped = RecitationIntroFilter.stripLeadingIntrosFromTranscript("بسم الله الحمد لله")
        assertEquals("الحمد لله", stripped)
    }

    @Test
    fun strip_noIntro_unchanged() {
        val stripped = RecitationIntroFilter.stripLeadingIntrosFromTranscript("الحمد لله رب")
        assertEquals("الحمد لله رب", stripped)
    }

    @Test
    fun evaluate_withIntro_matchesFatihaWithoutIntroMistakes() {
        val words = listOf(
            AyahWord("ٱلْحَمْدُ", isEnd = false),
            AyahWord("لِلَّهِ", isEnd = false),
            AyahWord("رَبِّ", isEnd = false),
            AyahWord("ٱلْعَـٰلَمِينَ", isEnd = false),
            AyahWord("٢", isEnd = true),
        )
        val transcript =
            "اعوذ بالله من الشيطان الرجيم بسم الله الرحمن الرحيم الحمد لله رب العالمين"
        val result = RecitationEvaluator.evaluateContinuing(
            words,
            transcript,
            lastTokenStable = true,
            lockedCorrectCount = 0,
            allowSkipMistakes = true,
        )
        assertEquals(0, result.mistakeCount)
        assertTrue(result.isComplete)
        assertEquals(4, result.correctCount)
    }

    /**
     * Al-Fatihah 1:1 *is* the basmala. Stripping it as an introduction erased the whole
     * transcript, so a perfectly recited 1:1 could never be scored — and 1:1 is the ayah the
     * instrumented Recite test seeds.
     */
    @Test
    fun evaluate_fatiha1_1_isScored_notStrippedAsIntro() {
        val result = RecitationEvaluator.evaluateContinuing(
            FATIHA_1_1,
            "بسم الله الرحمن الرحيم",
            lastTokenStable = true,
            lockedCorrectCount = 0,
            allowSkipMistakes = true,
        )
        assertEquals(0, result.mistakeCount)
        assertTrue(result.isComplete)
        assertEquals(4, result.correctCount)
    }

    /** The a'udhu before 1:1 is still an introduction; only the ayah's own words survive. */
    @Test
    fun evaluate_fatiha1_1_withAudhuBefore_stillScores() {
        val result = RecitationEvaluator.evaluateContinuing(
            FATIHA_1_1,
            "اعوذ بالله من الشيطان الرجيم بسم الله الرحمن الرحيم",
            lastTokenStable = true,
            lockedCorrectCount = 0,
            allowSkipMistakes = true,
        )
        assertEquals(0, result.mistakeCount)
        assertTrue(result.isComplete)
    }

    @Test
    fun strip_ayahOwnOpening_isNotTreatedAsIntro() {
        val opening = RecitationIntroFilter.openingTokensOf(FATIHA_1_1)
        val stripped = RecitationIntroFilter.stripLeadingIntrosFromTranscript(
            transcript = "بسم الله الرحمن الرحيم",
            ayahOpening = opening,
        )
        assertEquals("بسم الله الرحمن الرحيم", stripped)
    }

    /** Without an ayah in play the old blind behaviour is preserved. */
    @Test
    fun strip_withoutAyahOpening_stripsAsBefore() {
        val stripped = RecitationIntroFilter.stripLeadingIntrosFromTranscript("بسم الله الرحمن الرحيم")
        assertEquals("", stripped)
    }

    /**
     * The evaluator is the only place intros are stripped. If a caller strips first and the
     * evaluator strips again, an ayah opening with بسم الله loses those words twice over —
     * this pins the transcript-in / same-result-out contract that prevents it.
     */
    @Test
    fun evaluate_isIdempotent_whenIntroAlreadyAbsent() {
        val withIntro = RecitationEvaluator.evaluateContinuing(
            FATIHA_1_1,
            "اعوذ بالله بسم الله الرحمن الرحيم",
            lastTokenStable = true,
            lockedCorrectCount = 0,
            allowSkipMistakes = true,
        )
        val withoutIntro = RecitationEvaluator.evaluateContinuing(
            FATIHA_1_1,
            "بسم الله الرحمن الرحيم",
            lastTokenStable = true,
            lockedCorrectCount = 0,
            allowSkipMistakes = true,
        )
        assertEquals(withoutIntro.correctCount, withIntro.correctCount)
        assertEquals(withoutIntro.mistakeCount, withIntro.mistakeCount)
    }

    private companion object {
        val FATIHA_1_1 = listOf(
            AyahWord("بِسْمِ", isEnd = false),
            AyahWord("ٱللَّهِ", isEnd = false),
            AyahWord("ٱلرَّحْمَٰنِ", isEnd = false),
            AyahWord("ٱلرَّحِيمِ", isEnd = false),
            AyahWord("١", isEnd = true),
        )
    }
}
