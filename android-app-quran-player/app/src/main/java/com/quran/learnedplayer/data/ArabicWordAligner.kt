package com.quran.learnedplayer.data

/**
 * Lines written words up against heard tokens when the two disagree about **where words end**.
 *
 * [ArabicTextNormalizer] settles how a word is spelled; this settles how many words there are.
 * The Uthmani text attaches the vocative يا to what follows — يَـٰٓأَيُّهَا, يَـٰقَوْمِ, يَـٰبَنِىٓ — and
 * every recognizer emits it as two words, "يا ايها". That is one written word against two heard
 * tokens, and a one-to-one walk cannot survive it: the ayah's first word is scored wrong, every
 * word after it is off by one, and the student is corrected on a perfect recitation. It is also
 * not a rare corner — measured against a plain-spelling text of the whole Qur'an it accounts for
 * **349 of the 364 ayahs** whose token counts differ at all, across 469 ayahs in total.
 *
 * The reverse happens too, less often: a recognizer runs a short particle into the next word. So
 * the aligner is symmetric, and [MAX_JOINED] caps it at two on either side. Three would let a
 * long ayah find a spurious alignment for almost any noise.
 *
 * This is deliberately *not* solved by pre-splitting the reference text. Word indices are the
 * app's currency — they address the correction clip in `WordAudioPlayer.play(surah, ayah, index)`,
 * the highlight in `RecitationScreen`, and the entries in the run recap — so the reference word
 * list must keep the same shape the rest of the app uses.
 */
object ArabicWordAligner {

    /** Written words joined to match one heard token, or heard tokens joined to match one word. */
    const val MAX_JOINED = 2

    /** One alignment move: how many written words and heard tokens it consumes. */
    data class Step(val words: Int, val tokens: Int)

    /**
     * The move that lines `words[wordIndex]` up with `tokens[tokenIndex]`, or null if none does.
     *
     * One-to-one is tried first, so a joined reading is only ever a fallback and can never
     * displace a plain match.
     */
    fun matchAt(
        words: List<String>,
        wordIndex: Int,
        tokens: List<String>,
        tokenIndex: Int,
    ): Step? {
        if (wordIndex !in words.indices || tokenIndex !in tokens.indices) return null
        val word = words[wordIndex]
        if (ArabicTextNormalizer.isWordMatch(word, tokens[tokenIndex])) return Step(1, 1)

        // One written word said as several tokens: يَـٰٓأَيُّهَا heard as "يا ايها".
        for (count in 2..MAX_JOINED) {
            if (tokenIndex + count > tokens.size) break
            val joined = tokens.subList(tokenIndex, tokenIndex + count).joinToString("")
            if (ArabicTextNormalizer.isWordMatch(word, joined)) return Step(1, count)
        }

        // Several written words heard as one token: a particle run into the word after it.
        for (count in 2..MAX_JOINED) {
            if (wordIndex + count > words.size) break
            val joined = words.subList(wordIndex, wordIndex + count).joinToString("")
            if (ArabicTextNormalizer.isWordMatch(joined, tokens[tokenIndex])) {
                return Step(count, 1)
            }
        }
        return null
    }

    /**
     * How many of [tokens] the first [wordCount] of [words] consume, or -1 if they do not align.
     *
     * Used where a fixed number of *words* has to be recognised in a stream of tokens — the
     * wrong-ayah opening check and "has the student started the next ayah" — neither of which can
     * assume the two counts are equal once يا is in play.
     */
    fun consumedByPrefix(words: List<String>, wordCount: Int, tokens: List<String>): Int {
        if (wordCount <= 0 || wordCount > words.size) return -1
        var wordIndex = 0
        var tokenIndex = 0
        while (wordIndex < wordCount) {
            val step = matchAt(words, wordIndex, tokens, tokenIndex) ?: return -1
            // A split would swallow words past the prefix being asked about; that is a different
            // question than the caller asked, so decline rather than answer it loosely.
            if (wordIndex + step.words > wordCount) return -1
            wordIndex += step.words
            tokenIndex += step.tokens
        }
        return tokenIndex
    }

    /** True when the **end** of [tokens] is the first [wordCount] words of [words]. */
    fun endsWithPrefix(words: List<String>, wordCount: Int, tokens: List<String>): Boolean {
        if (wordCount <= 0) return false
        for (tailSize in wordCount..(wordCount * MAX_JOINED)) {
            if (tailSize > tokens.size) break
            val tail = tokens.subList(tokens.size - tailSize, tokens.size)
            if (consumedByPrefix(words, wordCount, tail) == tailSize) return true
        }
        return false
    }
}
