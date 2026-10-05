package com.quran.learnedplayer.data

/**
 * Strips leading a'udhu / bismillah phrases from ASR tokens so Recite never
 * scores them as ayah-content mistakes.
 *
 * The awkward case is that some ayahs *are* the intro: Al-Fatihah 1:1 is
 * بِسْمِ ٱللَّهِ ٱلرَّحْمَٰنِ ٱلرَّحِيمِ word for word, and An-Naml 27:30 contains it. Stripping
 * blindly there erases the ayah, so the student can recite it perfectly and never be scored.
 * Every entry point therefore takes the ayah's own opening and refuses to strip a phrase the
 * ayah itself begins with.
 */
object RecitationIntroFilter {

    /** Normalized token sequences that may appear before the ayah body. */
    private val INTRO_PHRASES: List<List<String>> = listOf(
        // أعوذ بالله السميع العليم من الشيطان الرجيم
        listOf("اعوذ", "بالله", "السميع", "العليم", "من", "الشيطان", "الرجيم"),
        listOf("اعوذ", "بالله", "السميع", "العليم", "من", "الشيطن", "الرجيم"),
        listOf("اعوذ", "بالله", "السميع", "العليم", "من", "الشيطان"),
        listOf("اعوذ", "بالله", "السميع", "العليم", "من", "الشيطن"),
        listOf("اعوذ", "بالله", "السميع", "العليم"),
        // أعوذ بالله العظيم من الشيطان الرجيم
        listOf("اعوذ", "بالله", "العظيم", "من", "الشيطان", "الرجيم"),
        listOf("اعوذ", "بالله", "العظيم", "من", "الشيطن", "الرجيم"),
        listOf("اعوذ", "بالله", "العظيم"),
        // أعوذ بالله من الشيطان الرجيم
        listOf("اعوذ", "بالله", "من", "الشيطان", "الرجيم"),
        listOf("اعوذ", "بالله", "من", "الشيطن", "الرجيم"),
        listOf("اعوذ", "بالله", "من", "الشيطان"),
        listOf("اعوذ", "بالله", "من", "الشيطن"),
        listOf("اعوذ", "بالله"),
        // أستعيذ بالله من الشيطان الرجيم
        listOf("استعيذ", "بالله", "من", "الشيطان", "الرجيم"),
        listOf("استعيذ", "بالله", "من", "الشيطن", "الرجيم"),
        listOf("استعيذ", "بالله", "من", "الشيطان"),
        listOf("استعيذ", "بالله"),
        // بسم الله الرحمن الرحيم
        listOf("بسم", "الله", "الرحمن", "الرحيم"),
        listOf("بسم", "الله", "الرحمن"),
        listOf("بسم", "الله"),
    ).sortedByDescending { it.size }

    /** Single words that can only be the start of an intro if not in the ayah opening. */
    private val INTRO_STARTERS: List<List<String>> = listOf(
        listOf("اعوذ"),
        listOf("استعيذ"),
        listOf("بسم"),
    )

    private val MAX_INTRO_PHRASE_LENGTH = INTRO_PHRASES.maxOf { it.size }

    /**
     * Drops any number of leading intro phrases from [tokens] (normalized Arabic).
     *
     * [ayahOpening] is the start of the ayah being recited, normalized the same way. A phrase
     * that the ayah itself opens with is left in place — it is content, not an introduction.
     */
    fun stripLeadingIntros(
        tokens: List<String>,
        ayahOpening: List<String> = emptyList(),
    ): List<String> {
        if (tokens.isEmpty()) return tokens
        var remaining = tokens
        var changed = true
        while (changed && remaining.isNotEmpty()) {
            changed = false
            for (phrase in INTRO_PHRASES) {
                if (!startsWithPhrase(remaining, phrase)) continue
                // The ayah opens with this phrase: what was heard is the ayah, not an intro.
                if (startsWithPhrase(ayahOpening, phrase)) continue
                remaining = remaining.drop(phrase.size)
                changed = true
                break
            }
        }

        // If the remaining sequence is just an in-progress intro prefix (and not the ayah's opening),
        // drop it so an incomplete Ta'awwudh / Basmala is not scored as a wrong word.
        if (remaining.isNotEmpty()) {
            for (phrase in (INTRO_PHRASES + INTRO_STARTERS)) {
                if (isPrefixOfPhrase(remaining, phrase)) {
                    if (!startsWithPhrase(ayahOpening, remaining)) {
                        return emptyList()
                    }
                    break
                }
            }
        }

        return remaining
    }

    /**
     * Transcript with leading intros removed (space-joined normalized tokens).
     *
     * @param ayahOpening the reciting ayah's own leading tokens, normalized — see
     * [stripLeadingIntros]. Pass empty only when there is no ayah in play.
     */
    fun stripLeadingIntrosFromTranscript(
        transcript: String,
        ayahOpening: List<String> = emptyList(),
    ): String {
        val tokens = ArabicTextNormalizer.tokenize(transcript)
            .filter { it != "unk" && it != "[unk]" }
        return stripLeadingIntros(tokens, ayahOpening).joinToString(" ")
    }

    /**
     * The leading normalized tokens of [referenceWords], enough to recognise the longest intro
     * phrase. Content words only — the end-of-ayah glyph is not spoken.
     */
    fun openingTokensOf(referenceWords: List<AyahWord>): List<String> =
        referenceWords.asSequence()
            .filter { !it.isEnd }
            .flatMap { ArabicTextNormalizer.tokenize(it.text).asSequence() }
            .take(MAX_INTRO_PHRASE_LENGTH)
            .toList()

    private fun startsWithPhrase(tokens: List<String>, phrase: List<String>): Boolean {
        if (tokens.size < phrase.size) return false
        for (i in phrase.indices) {
            if (!ArabicTextNormalizer.isWordMatch(phrase[i], tokens[i])) return false
        }
        return true
    }

    private fun isPrefixOfPhrase(tokens: List<String>, phrase: List<String>): Boolean {
        if (tokens.isEmpty() || tokens.size > phrase.size) return false
        for (i in tokens.indices) {
            if (!ArabicTextNormalizer.isWordMatch(phrase[i], tokens[i])) return false
        }
        return true
    }
}
