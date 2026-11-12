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

    /**
     * Where the ayah really ends in a recitation file that runs on well past its word timings, or
     * null to play the file to its end. EveryAyah's Maher Al Muaiqly 2:258 holds 2:258 and all of
     * 2:259; validated timings end within 50 ms of the file's end everywhere else, so an overrun
     * this large is never silence. The web engine has the same rule (player/engine.js ayahEndMs).
     */
    fun ayahEndMs(segments: List<LongRange>?, fileMs: Long): Long? {
        val last = segments?.lastOrNull()?.last ?: return null
        if (last <= 0L || fileMs <= 0L) return null
        val overrun = fileMs - last
        return if (overrun > OVERRUN_MIN_MS && overrun > last * OVERRUN_SHARE) last + OVERRUN_TAIL_MS else null
    }

    private const val OVERRUN_MIN_MS = 5_000L
    private const val OVERRUN_SHARE = 0.25
    private const val OVERRUN_TAIL_MS = 400L
}
