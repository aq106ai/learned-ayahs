package com.quran.learnedplayer.data

import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap

/**
 * Normalizes Arabic Quranic Uthmani text and speech recognizer output to allow accurate,
 * diacritic-agnostic word matching between user recitation and reference ayahs.
 *
 * Both sides of the comparison come through here, and they arrive in different shapes: the
 * bundled Uthmani text uses Quranic orthography (wasla, dagger alif, small marks), while speech
 * engines emit plain modern spelling — and some emit Arabic presentation forms (the lam-alef
 * ligatures) or Arabic-Indic digits. Anything not folded here surfaces as a false mistake.
 *
 * **Stripping the marks is not enough: the two scripts also spell the letters differently.**
 * Measured against a plain-spelling (imlaei) text of the whole Qur'an, 70 ayahs still failed to
 * align word-for-word after normalization alone. Uthmani writes long vowels the plain script
 * leaves out and vice versa — ءَاتَىٰهُمْ / آتاهم, فَسْـَٔلْ / فاسأل, ٱلرِّبَوٰا۟ / الربا — and folding both
 * ء and the dagger alif to ا leaves a doubled alif where plain spelling has one. So two things
 * happen below that a diacritic-stripper would not do: runs of alif collapse to a single alif,
 * and [isWordMatch] falls back to [isLongVowelSpellingVariant], which forgives added or dropped
 * ا/و/ي but never a different consonant. Those two together take the 70 down to 8.
 */
object ArabicTextNormalizer {

    private val DIACRITICS_REGEX = Regex("[\\u064B-\\u0652\\u0653-\\u065F\\u06D6-\\u06ED\\u0640]")

    /**
     * Consecutive alifs -> one.
     *
     * Uthmani spells آ as ء + ا (ءَاتَىٰ), and both halves fold to ا above, so the word arrives one
     * letter longer than the plain spelling of the same word. Nothing in modern Arabic writes two
     * alifs in a row, so collapsing them costs nothing and removes a whole class of false mistake.
     */
    private val ALIF_RUN_REGEX = Regex("ا{2,}")

    /**
     * Letters that carry a long vowel. The two scripts disagree about *writing* these far more
     * often than the reciter says anything different, which is what [isLongVowelSpellingVariant]
     * is built on.
     */
    private const val LONG_VOWELS = "اوي"

    /**
     * Added or dropped long vowels forgiven by [isLongVowelSpellingVariant].
     *
     * Two covers the real spread — فَسْـَٔلْ ("فسل") against a recognizer's "فاسال" is two — while
     * three starts folding genuinely different words together.
     */
    private const val MAX_LONG_VOWEL_EDITS = 2

    /**
     * Consonants a word needs before [isLongVowelSpellingVariant] will judge it.
     *
     * Below this the consonant skeleton carries almost no information: إِلَّا ("الا") and أُو۟لُوا۟
     * ("اولوا") both reduce to a lone ل, and letting vowel spelling decide there would call them
     * the same word.
     */
    private const val MIN_CONSONANTS_FOR_VOWEL_MATCH = 2

    /**
     * Normalized forms, kept because the same words are asked about over and over.
     *
     * Both sides of every comparison come from tiny vocabularies — one ayah's words and one
     * transcript's tokens — and the aligner re-asks about the same pairs while trying each
     * alignment, on the main thread. Bounded and cleared wholesale rather than evicted entry by
     * entry: the keys come from a recognizer, so their variety is not to be trusted, and a cold
     * cache costs one pass and nothing else.
     */
    private val CACHE = ConcurrentHashMap<String, String>(512)

    private const val MAX_CACHED = 4096

    /** Digits in every script the recognizers emit; dropped before the Arabic-letter filter. */
    private val DIGITS_REGEX = Regex("[0-9\\u0660-\\u0669\\u06F0-\\u06F9]")

    /**
     * Arabic Presentation Forms-B lam-alef ligatures -> the two letters they stand for. Some
     * recognizers (and pasted text) use these single code points, which the Arabic-only filter
     * below would otherwise delete outright, silently shortening the word.
     */
    private val LAM_ALEF_LIGATURES: Map<Char, String> = mapOf(
        'ﻵ' to "لا", 'ﻶ' to "لا", // lam-alef with madda
        'ﻷ' to "لا", 'ﻸ' to "لا", // lam-alef with hamza above
        'ﻹ' to "لا", 'ﻺ' to "لا", // lam-alef with hamza below
        'ﻻ' to "لا", 'ﻼ' to "لا", // plain lam-alef
    )

    /**
     * Normalizes an Arabic word or sentence:
     * 1. Unicode NFC, then expands lam-alef presentation ligatures.
     * 2. Converts Dagger Alif (U+0670), Wasla Alif (ٱ), and Alef with Hamza (أ, إ, آ) -> Alif (ا).
     * 3. Folds standalone Hamza (ء) into Alif so hamza placement never decides a match.
     * 4. Strips all harakat, tanween, sukun, shadda, tatweel, and Quranic marks.
     * 5. Normalizes Alef Maksura (ى) -> Yeh (ي).
     * 6. Normalizes Ta Marbuta (ة) -> Ha (ه).
     * 7. Drops digits, then anything else outside the Arabic letter block.
     * 8. Collapses runs of alif to one (see [ALIF_RUN_REGEX]).
     * 9. Trims and collapses multiple spaces.
     */
    fun normalize(text: String): String {
        if (text.isBlank()) return ""
        CACHE[text]?.let { return it }
        val normalized = normalizeUncached(text)
        if (CACHE.size >= MAX_CACHED) CACHE.clear()
        CACHE[text] = normalized
        return normalized
    }

    private fun normalizeUncached(text: String): String {
        // Composed form first: engines differ on whether they emit أ or ا + combining hamza,
        // and the character replacements below only recognise the composed spellings.
        var result = Normalizer.normalize(text, Normalizer.Form.NFC)

        if (result.any { LAM_ALEF_LIGATURES.containsKey(it) }) {
            result = buildString(result.length + 8) {
                for (ch in result) append(LAM_ALEF_LIGATURES[ch] ?: ch)
            }
        }

        // Replace Dagger Alif (U+0670), Wasla Alif (ٱ), and Alef variations with standard Alif (ا)
        result = result.replace('ٰ', 'ا')
            .replace('ٱ', 'ا')
            .replace('أ', 'ا')
            .replace('إ', 'ا')
            .replace('آ', 'ا')
            .replace('ئ', 'ي')
            .replace('ؤ', 'و')
            // Standalone hamza: انبئهم / انبءهم / أنبئهم must all compare equal.
            .replace('ء', 'ا')

        // Strip all harakat and Quranic marks
        result = DIACRITICS_REGEX.replace(result, "")

        // Normalize Alef Maksura (ى) to Yeh (ي)
        result = result.replace('ى', 'ي')

        // Normalize Ta Marbuta (ة) to Ha (ه)
        result = result.replace('ة', 'ه')

        // Digits are named explicitly rather than being swept up by the filter below, so that
        // an ayah number read aloud by the recognizer disappears for an obvious reason.
        result = DIGITS_REGEX.replace(result, "")

        // Remove non-Arabic punctuation / digits / Quranic glyphs except whitespace
        result = result.replace(Regex("[^\\u0621-\\u064A\\s]"), "")

        // Collapse spurious decoder repetition loops (e.g. سسسسسس -> س)
        result = result.replace(Regex("(.)\\1{2,}"), "$1$1")

        // ...and then alif specifically all the way down to one, which the rule above cannot do:
        // ءَا is the Uthmani spelling of آ, and both letters became ا a few lines up.
        result = ALIF_RUN_REGEX.replace(result, "ا")

        // Collapse multiple spaces
        return result.replace(Regex("\\s+"), " ").trim()
    }

    /**
     * Splits text into individual normalized word tokens, discarding empty elements.
     */
    fun tokenize(text: String): List<String> {
        return normalize(text)
            .split(" ")
            .filter { it.isNotBlank() }
    }

    /**
     * True when [spoken] is a proper prefix of [expected] after normalization — an in-progress
     * word, not a substitution.
     */
    fun isIncompletePrefix(spoken: String, expected: String): Boolean {
        val spokenNorm = normalize(spoken)
        val expectedNorm = normalize(expected)
        if (spokenNorm.isEmpty() || expectedNorm.isEmpty()) return false
        if (spokenNorm == expectedNorm) return false
        return expectedNorm.startsWith(spokenNorm)
    }

    /**
     * Checks if two Arabic words match after normalization, with optional Levenshtein tolerance.
     */
    fun isWordMatch(word1: String, word2: String, maxDistance: Int = 1): Boolean {
        val norm1 = normalize(word1)
        val norm2 = normalize(word2)
        if (norm1 == norm2) return true
        if (norm1.isEmpty() || norm2.isEmpty()) return false

        // For short words (length <= 2), exact match is required. Deliberately `||`: one edit
        // on a two-letter word is a different word (من / عن), whichever side is short. This also
        // holds the line for the vowel rule below — مِن against ءَامَنَ ("امن") is one added alif,
        // and there are eleven places in the Qur'an where those two words sit side by side.
        if (norm1.length <= 2 || norm2.length <= 2) return false

        if (levenshteinDistance(norm1, norm2) <= maxDistance) return true

        // Same word, other script. Checked last so it can only ever add matches.
        return isLongVowelSpellingVariant(norm1, norm2)
    }

    /**
     * True when two **already-normalized** words are the same consonants in the same order and
     * differ only by up to [MAX_LONG_VOWEL_EDITS] written long vowels.
     *
     * This is the Uthmani/plain-spelling difference stated as a rule: فَسْـَٔلْ normalizes to `فسل`
     * and a recognizer says `فاسال`; ءَاتَىٰهُمْ becomes `اتياهم` against a heard `اتاهم`. In each
     * case every consonant survives, in order, and only ا/و/ي come and go. Substitution is not
     * allowed at all — a different consonant is a different word, which is the whole point of
     * scoring the recitation.
     */
    fun isLongVowelSpellingVariant(norm1: String, norm2: String): Boolean {
        if (norm1 == norm2) return true
        if (consonantCount(norm1) < MIN_CONSONANTS_FOR_VOWEL_MATCH ||
            consonantCount(norm2) < MIN_CONSONANTS_FOR_VOWEL_MATCH
        ) {
            return false
        }

        // Levenshtein with substitution forbidden and indels allowed only on long vowels. `over`
        // stands in for "already past the budget" so the table needs no Int.MAX_VALUE arithmetic.
        val over = MAX_LONG_VOWEL_EDITS + 1
        var previous = IntArray(norm2.length + 1)
        for (j in 1..norm2.length) {
            previous[j] = if (isLongVowel(norm2[j - 1])) minOf(previous[j - 1] + 1, over) else over
        }
        for (i in 1..norm1.length) {
            val current = IntArray(norm2.length + 1)
            current[0] = if (isLongVowel(norm1[i - 1])) minOf(previous[0] + 1, over) else over
            for (j in 1..norm2.length) {
                var best = over
                if (norm1[i - 1] == norm2[j - 1]) best = previous[j - 1]
                if (isLongVowel(norm1[i - 1])) best = minOf(best, previous[j] + 1)
                if (isLongVowel(norm2[j - 1])) best = minOf(best, current[j - 1] + 1)
                current[j] = minOf(best, over)
            }
            previous = current
        }
        return previous[norm2.length] <= MAX_LONG_VOWEL_EDITS
    }

    private fun isLongVowel(ch: Char): Boolean = LONG_VOWELS.indexOf(ch) >= 0

    private fun consonantCount(word: String): Int = word.count { !it.isWhitespace() && !isLongVowel(it) }

    /**
     * Computes edit distance between two strings.
     */
    fun levenshteinDistance(s1: String, s2: String): Int {
        val dp = IntArray(s2.length + 1) { it }
        for (i in 1..s1.length) {
            var prev = dp[0]
            dp[0] = i
            for (j in 1..s2.length) {
                val temp = dp[j]
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + cost)
                prev = temp
            }
        }
        return dp[s2.length]
    }
}
