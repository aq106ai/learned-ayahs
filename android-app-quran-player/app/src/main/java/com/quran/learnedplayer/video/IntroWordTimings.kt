package com.quran.learnedplayer.video

import kotlin.math.sqrt

/**
 * Word timings for a clip nobody has timed: A'udhu, and Bismillah when the chosen reciter has no
 * validated timings for Al-Fatihah 1:1. Used only for one-word-at-a-time videos, which need to
 * know which word is being said; every other layout still leaves an untimed clip unhighlighted.
 *
 * **An estimate, measured from the clip's own sound.** First the voice: the first and last
 * stretch loud enough to be speech, so the opening and closing silence don't stretch the first
 * and last words. Then, if the reciter pauses between every pair of words, the pauses are the
 * boundaries. Otherwise the voiced span is shared out by each word's letters (the split the timing
 * audit found more reliable than loudness dips; see CLAUDE.md), and each boundary is moved to the
 * quietest moment within [SNAP_FRAMES] of where the letters put it, which is usually the joint
 * between two words in connected recitation.
 *
 * The web app has the same function (`web-app/static/js/video/introTimings.js`); change both.
 */
object IntroWordTimings {
    const val FRAME_MS = 20L

    /** Below this share of the clip's loud level a frame is silence. */
    private const val VOICE_LEVEL = 0.12

    /** Consecutive voiced frames that count as speech starting (or ending), not a click. */
    private const val MIN_VOICED_RUN = 3

    /** A silence this long inside the recitation is a pause between words. */
    private const val MIN_PAUSE_FRAMES = 8

    /** How far (each way) a letter-estimated boundary may move to the quietest frame. */
    private const val SNAP_FRAMES = 5

    /** Letters of [word] (Arabic letters and the wasla alif ٱ; marks dropped), at least 1. */
    fun letterWeight(word: String): Int =
        word.count { it in '\u0621'..'\u064A' || it == '\u0671' }.coerceAtLeast(1)

    /** Loudness per [FRAME_MS] frame of interleaved [channels] PCM at [rate]. */
    fun frameLevels(pcm: ShortArray, rate: Int, channels: Int): DoubleArray {
        val perFrame = (rate * FRAME_MS / 1000).toInt() * channels
        if (perFrame <= 0) return DoubleArray(0)
        val count = pcm.size / perFrame
        return DoubleArray(count) { f ->
            var energy = 0.0
            val from = f * perFrame
            for (i in from until from + perFrame) {
                val s = pcm[i].toDouble()
                energy += s * s
            }
            sqrt(energy / perFrame)
        }
    }

    /**
     * One range per word, in ms from the start of the clip, or null when the clip has no voice to
     * measure or fewer frames than words.
     */
    fun estimate(levels: DoubleArray, weights: List<Int>): List<LongRange>? {
        val words = weights.size
        if (words == 0 || levels.isEmpty()) return null
        val sorted = levels.sorted()
        val loud = sorted[((sorted.size - 1) * 0.95).toInt()]
        if (loud <= 0.0) return null
        val threshold = loud * VOICE_LEVEL
        val voiced = BooleanArray(levels.size) { levels[it] > threshold }

        val start = (0..levels.size - MIN_VOICED_RUN).firstOrNull { i ->
            (i until i + MIN_VOICED_RUN).all { voiced[it] }
        } ?: return null
        val end = (levels.size - 1 downTo MIN_VOICED_RUN - 1).first { i ->
            (i - MIN_VOICED_RUN + 1..i).all { voiced[it] }
        }
        if (end - start + 1 < words) return null

        val boundaries = pauseBoundaries(voiced, start, end, words - 1)
            ?: letterBoundaries(levels, start, end, weights)
        val starts = listOf(start) + boundaries
        return starts.indices.map { i ->
            val from = starts[i] * FRAME_MS
            val to = if (i < starts.lastIndex) starts[i + 1] * FRAME_MS - 1 else (end + 1) * FRAME_MS
            from..to
        }
    }

    /** Where the voice comes back after each pause, if there is a pause between every two words. */
    private fun pauseBoundaries(voiced: BooleanArray, start: Int, end: Int, needed: Int): List<Int>? {
        if (needed == 0) return emptyList()
        val pauses = mutableListOf<Pair<Int, Int>>() // (frame the voice returns, pause length)
        var i = start
        while (i <= end) {
            if (voiced[i]) {
                i++
                continue
            }
            val from = i
            while (i <= end && !voiced[i]) i++
            if (i - from >= MIN_PAUSE_FRAMES && i <= end) pauses += i to (i - from)
        }
        if (pauses.size < needed) return null
        return pauses.sortedByDescending { it.second }.take(needed).map { it.first }.sorted()
    }

    private fun letterBoundaries(levels: DoubleArray, start: Int, end: Int, weights: List<Int>): List<Int> {
        val total = weights.sum().toDouble()
        val span = end - start + 1
        val result = mutableListOf<Int>()
        var cumulative = 0
        for (j in 0 until weights.lastIndex) {
            cumulative += weights[j]
            val estimate = start + (span * cumulative / total).toInt()
            val earliest = (result.lastOrNull() ?: start) + 1
            // Leave at least one frame for every word still to come.
            val latest = end - (weights.lastIndex - j - 1)
            val lo = maxOf(earliest, estimate - SNAP_FRAMES)
            val hi = minOf(latest, estimate + SNAP_FRAMES)
            val snapped = if (lo <= hi) (lo..hi).minByOrNull { levels[it] }!! else estimate.coerceIn(earliest, latest)
            result += snapped
        }
        return result
    }
}
