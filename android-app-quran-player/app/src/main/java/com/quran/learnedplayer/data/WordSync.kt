package com.quran.learnedplayer.data

/**
 * Pure word-highlight sync logic: the active word is the segment containing the playback
 * position; during inter-word gaps the last finished word stays active. Words before the active
 * one are "done", after it "pending".
 *
 * There is deliberately **no estimated fallback**. Word timings either come from the reciter's
 * validated segments or the ayah simply isn't highlighted — a char-weighted guess drifts against
 * real recitation and highlights the wrong word, which is worse than no highlight at all.
 */
object WordSync {
    fun activeWordIndex(positionMs: Long, segments: List<LongRange>, wordCount: Int): Int {
        if (segments.isEmpty() || wordCount == 0) return -1
        val count = minOf(segments.size, wordCount)
        var activeIdx = -1
        for (i in 0 until count) {
            val start = segments[i].first
            val endExclusive = segments[i].last + 1
            if (positionMs >= start && positionMs < endExclusive) return i
            if (positionMs >= endExclusive) activeIdx = i
        }
        return activeIdx
    }

}
