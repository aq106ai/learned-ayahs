package com.quran.learnedplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArabicTextNormalizerTest {

    @Test
    fun normalize_stripsHarakatAndDiacritics() {
        val input = "ٱلْحَمْدُ لِلَّهِ رَبِّ ٱلْعَـٰلَمِينَ"
        val expected = "الحمد لله رب العالمين"
        val result = ArabicTextNormalizer.normalize(input)
        assertEquals(expected, result)
    }

    @Test
    fun normalize_normalizesWaslaAndHamzaForms() {
        val input = "أَحْسَنَ إِيمَانَ ٱلَّذِينَ آَمَنُوا"
        val expected = "احسن ايمان الذين امنوا"
        val result = ArabicTextNormalizer.normalize(input)
        assertEquals(expected, result)
    }

    @Test
    fun isWordMatch_matchesEquivalents() {
        assertTrue(ArabicTextNormalizer.isWordMatch("ٱلْحَمْدُ", "الحمد"))
        assertTrue(ArabicTextNormalizer.isWordMatch("ٱلرَّحْمَـٰنِ", "الرحمن"))
        assertTrue(ArabicTextNormalizer.isWordMatch("ٱلرَّحِيمِ", "الرحيم"))
        assertFalse(ArabicTextNormalizer.isWordMatch("ٱلْحَمْدُ", "العالمين"))
        assertTrue(ArabicTextNormalizer.isWordMatch("مُسْتَهْزِئُونَ", "مستهزيون"))
        assertTrue(ArabicTextNormalizer.isWordMatch("مُؤْمِنُونَ", "مومنون"))
    }

    /**
     * Presentation-form lam-alef is a single code point outside the Arabic letter block, so the
     * catch-all filter used to delete it — silently shortening the word into a mismatch.
     */
    @Test
    fun normalize_expandsLamAlefPresentationForms() {
        // U+FEFB lam-alef, U+FEF7 lam-alef with hamza above.
        assertEquals("ولا", ArabicTextNormalizer.normalize("وﻻ"))
        assertEquals("لا", ArabicTextNormalizer.normalize("ﻷ"))
        assertTrue(ArabicTextNormalizer.isWordMatch("وَلَا", "وﻻ"))
    }

    /** Hamza placement varies between engines and must never decide a match. */
    @Test
    fun normalize_foldsStandaloneHamza() {
        // Uthmani ٱلسَّمَآءِ carries madda + a standalone hamza; engines write a plain final ء.
        assertTrue(ArabicTextNormalizer.isWordMatch("ٱلسَّمَآءِ", "السماء"))
        assertEquals(
            ArabicTextNormalizer.normalize("السماء"),
            ArabicTextNormalizer.normalize("ٱلسَّمَآءِ"),
        )
    }

    /** Decomposed input (alef + combining hamza above) must fold like the composed أ. */
    @Test
    fun normalize_handlesDecomposedInput() {
        val decomposed = "أحسن"
        assertEquals("احسن", ArabicTextNormalizer.normalize(decomposed))
        assertEquals(
            ArabicTextNormalizer.normalize("أحسن"),
            ArabicTextNormalizer.normalize(decomposed),
        )
    }

    /** Ayah numbers, in either digit script, must not survive as word tokens. */
    @Test
    fun normalize_dropsDigitsInBothScripts() {
        assertEquals("الحمد", ArabicTextNormalizer.normalize("الحمد ٢"))
        assertEquals("الحمد", ArabicTextNormalizer.normalize("الحمد 2"))
    }

    @Test
    fun isIncompletePrefix_onlyForProperPrefixes() {
        assertTrue(ArabicTextNormalizer.isIncompletePrefix("الرح", "ٱلرَّحْمَٰنِ"))
        // A complete word is not "in progress".
        assertFalse(ArabicTextNormalizer.isIncompletePrefix("الرحمن", "ٱلرَّحْمَٰنِ"))
        // A different word entirely.
        assertFalse(ArabicTextNormalizer.isIncompletePrefix("رب", "ٱلرَّحْمَٰنِ"))
        assertFalse(ArabicTextNormalizer.isIncompletePrefix("", "ٱلرَّحْمَٰنِ"))
    }

    /**
     * Two-letter words are too short for edit-distance tolerance: one substitution turns مِن
     * into عَن, a different word. Diacritics still fall away, so a short word matches itself.
     */
    @Test
    fun isWordMatch_shortWordsRequireExactMatch() {
        assertFalse(ArabicTextNormalizer.isWordMatch("مِن", "عَن"))
        assertFalse(ArabicTextNormalizer.isWordMatch("رَبِّ", "رَدِّ"))
        assertTrue(ArabicTextNormalizer.isWordMatch("مِن", "من"))
        assertTrue(ArabicTextNormalizer.isWordMatch("رَبِّ", "رب"))
    }

    @Test
    fun isWordMatch_toleratesOneEditOnLongerWords() {
        assertTrue(ArabicTextNormalizer.isWordMatch("العالمين", "العلمين"))
        // Two edits, one of them a consonant (ص -> ظ): a different word, not a spelling of it.
        assertFalse(ArabicTextNormalizer.isWordMatch("الصابرين", "الظبرين"))
    }

    /**
     * Written long vowels are what the two scripts disagree about wholesale, so up to two of them
     * may come or go — but consonants never may.
     *
     * This is the rule that lets the Uthmani text be scored against a recognizer at all: فَسْـَٔلْ
     * carries no alifs and every engine says "فاسأل". Before it, `isWordMatch`'s one-edit budget
     * ran out on 70 ayahs, each of which read to the student as being corrected on a word they
     * had recited perfectly.
     */
    @Test
    fun isWordMatch_forgivesWrittenLongVowels_butNeverConsonants() {
        // Two alifs the Uthmani spelling does not write.
        assertTrue(ArabicTextNormalizer.isWordMatch("فَسْـَٔلْ", "فاسأل"))
        // ٱلْعَـٰلَمِينَ with the alif and the yeh both dropped: same consonants, same order.
        assertTrue(ArabicTextNormalizer.isWordMatch("العالمين", "العلمن"))
        // Three is past the budget — at that point the skeleton stops identifying the word.
        assertFalse(ArabicTextNormalizer.isWordMatch("المومنون", "لممنن"))
        // A word too short to have a skeleton worth trusting is not judged this way at all:
        // إِلَّا and أُو۟لُوا۟ are both a lone ل once the vowels are set aside.
        assertFalse(ArabicTextNormalizer.isWordMatch("إِلَّا", "أُو۟لُوا۟"))
    }

    /**
     * Uthmani spells آ as ء + ا, and both halves fold to ا here — so without collapsing the run,
     * ءَاتَىٰهُمْ arrives a letter longer than the آتاهم every recognizer emits.
     */
    @Test
    fun normalize_collapsesRunsOfAlif() {
        assertEquals("اتياهم", ArabicTextNormalizer.normalize("ءَاتَىٰهُمْ"))
        assertTrue(ArabicTextNormalizer.isWordMatch("ءَاتَىٰهُمْ", "آتاهم"))
        assertTrue(ArabicTextNormalizer.isWordMatch("ءَالَ", "آل"))
    }
}
