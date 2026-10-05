package com.quran.learnedplayer.data

/**
 * Detects when the spoken opening matches a different learned ayah than the one on screen.
 */
object WrongAyahDetector {

    data class Opening(
        val globalId: Int,
        val tokens: List<String>,
    )

    /**
     * Returns the [Opening.globalId] of another learned ayah whose opening matches the
     * spoken prefix, or null if this still looks like [currentGlobalId] (or too little speech).
     */
    fun matchingOtherAyah(
        spokenTokens: List<String>,
        currentGlobalId: Int,
        currentOpening: List<String>,
        otherOpenings: List<Opening>,
        minSpokenTokens: Int = 2,
    ): Int? {
        if (spokenTokens.size < minSpokenTokens) return null
        val compareCount = minSpokenTokens.coerceAtMost(currentOpening.size).coerceAtLeast(1)
        if (spokenTokens.size < compareCount) return null
        if (prefixMatches(spokenTokens, currentOpening, compareCount)) return null
        return otherOpenings.firstOrNull { opening ->
            opening.globalId != currentGlobalId &&
                prefixMatches(spokenTokens, opening.tokens, compareCount)
        }?.globalId
    }

    /**
     * Counted in *written words*, not heard tokens: 469 ayahs open with the vocative يا attached
     * to the next word, which every recognizer says as two. Comparing index for index would let
     * "يا ايها الناس" fail to recognise its own ayah — and then match some other one.
     */
    private fun prefixMatches(spoken: List<String>, expected: List<String>, count: Int): Boolean {
        if (expected.size < count) return false
        return ArabicWordAligner.consumedByPrefix(expected, count, spoken) >= 0
    }
}
