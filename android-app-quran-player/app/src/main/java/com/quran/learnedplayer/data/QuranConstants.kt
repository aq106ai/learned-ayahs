package com.quran.learnedplayer.data

object QuranConstants {
    val VERSE_COUNTS = intArrayOf(
        7, 286, 200, 176, 120, 165, 206, 75, 129, 109, 123, 111, 43, 52, 99, 128, 111, 110,
        98, 135, 112, 78, 118, 64, 77, 227, 93, 88, 69, 60, 34, 30, 73, 54, 45, 83, 182, 88,
        75, 85, 54, 53, 89, 59, 37, 35, 38, 29, 18, 45, 60, 49, 62, 55, 78, 96, 29, 22, 24,
        13, 14, 11, 11, 18, 12, 12, 30, 52, 52, 44, 28, 28, 20, 56, 40, 31, 50, 40, 46, 42,
        29, 19, 36, 25, 22, 17, 19, 26, 30, 20, 15, 21, 11, 8, 8, 19, 5, 8, 8, 11, 11, 8,
        3, 9, 5, 4, 7, 3, 6, 3, 5, 4, 5, 6,
    )

    const val AUDIO_HOST = "https://everyayah.com/data"

    /** everyayah.com directory for the selected reciter; also the audio cache directory name. */
    val RECITER_FOLDER: String
        get() = com.quran.learnedplayer.player.PlayerSettings.reciter.folder

    val BASE_URL: String get() = "$AUDIO_HOST/$RECITER_FOLDER"
    // Word timings are no longer fetched at runtime — they are generated per reciter at build
    // time (see .setup/make_timings.py) so they can be cleaned and validated before shipping.
    const val AUDHU_FILENAME = "audhubillah.mp3"
    const val AUDHU_URL = "https://everyayah.com/data/$AUDHU_FILENAME"
    const val BISMILLAH_FILENAME = "bismillah.mp3"
    const val BISMILLAH_URL = "https://everyayah.com/data/$BISMILLAH_FILENAME"

    /**
     * Surahs whose intro skips the standalone Bismillah clip — for two unrelated reasons:
     * surah 9 (At-Tawbah) is recited without Bismillah at all, and surah 1 (Al-Fatihah) counts
     * "Bismillah ir-Rahman ir-Raheem" as its own ayah 1, so playing the intro clip immediately
     * before that ayah's recitation would say it twice in a row.
     */
    val NO_BISMILLAH_INTRO_SURAHS = setOf(1, 9)
}

object AyahMapping {
    fun globalToSurahAyah(globalId: Int): Pair<Int, Int> {
        var remaining = globalId
        QuranConstants.VERSE_COUNTS.forEachIndexed { index, count ->
            if (remaining <= count) {
                return (index + 1) to remaining
            }
            remaining -= count
        }
        error("Invalid global ayah id: $globalId")
    }

    fun surahAyahToGlobal(surah: Int, ayah: Int): Int {
        var total = 0
        for (i in 0 until (surah - 1)) {
            total += QuranConstants.VERSE_COUNTS[i]
        }
        return total + ayah
    }

    fun ayahFilename(surah: Int, ayah: Int): String =
        "%03d%03d.mp3".format(surah, ayah)

    fun remoteUrl(surah: Int, ayah: Int): String =
        "${QuranConstants.BASE_URL}/${ayahFilename(surah, ayah)}"
}

/**
 * Builds the active playback queue for a given [com.quran.learnedplayer.player.PlaybackMode] /
 * [com.quran.learnedplayer.player.RepeatMode] pair.
 *
 * - REVISE / WORD_BY_WORD + repeat SURAH → only the learned ayahs of [surah].
 * - REVISE / WORD_BY_WORD + otherwise → the full learned (master) list.
 * - FULL_SURAH → every ayah of the surah (streamed, not just learned ones).
 */
object QueueBuilder {
    /** Distinct surahs that contain at least one learned ayah, in ascending order. */
    fun learnedSurahs(master: List<AyahTrack>): List<Int> =
        master.map { it.surah }.distinct().sorted()

    fun buildQueue(
        master: List<AyahTrack>,
        mode: com.quran.learnedplayer.player.PlaybackMode,
        repeatMode: com.quran.learnedplayer.player.RepeatMode,
        surah: Int,
    ): List<AyahTrack> = when (mode) {
        com.quran.learnedplayer.player.PlaybackMode.REVISE,
        com.quran.learnedplayer.player.PlaybackMode.WORD_BY_WORD,
        ->
            if (repeatMode == com.quran.learnedplayer.player.RepeatMode.SURAH) {
                master.filter { it.surah == surah }
            } else {
                master
            }

        com.quran.learnedplayer.player.PlaybackMode.FULL_SURAH -> surahAllAyahs(surah)
    }

    private fun surahAllAyahs(surah: Int): List<AyahTrack> {
        if (surah < 1 || surah > QuranConstants.VERSE_COUNTS.size) return emptyList()
        val count = QuranConstants.VERSE_COUNTS[surah - 1]
        return (1..count).map { ayah ->
            AyahTrack(
                index = ayah,
                globalId = AyahMapping.surahAyahToGlobal(surah, ayah),
                surah = surah,
                ayah = ayah,
                filename = AyahMapping.ayahFilename(surah, ayah),
                remoteUrl = AyahMapping.remoteUrl(surah, ayah),
            )
        }
    }

    /** Next/previous surah among those that contain at least one learned ayah — used by REVISE. */
    fun nextSurah(master: List<AyahTrack>, surah: Int): Int {
        val list = learnedSurahs(master)
        if (list.isEmpty()) return surah
        val idx = list.indexOf(surah)
        return if (idx == -1) list.first() else list[(idx + 1) % list.size]
    }

    fun prevSurah(master: List<AyahTrack>, surah: Int): Int {
        val list = learnedSurahs(master)
        if (list.isEmpty()) return surah
        val idx = list.indexOf(surah)
        return if (idx == -1) list.last() else list[(idx - 1 + list.size) % list.size]
    }

    /** Next/previous surah across all 114 — used by FULL_SURAH, which can stream any surah. */
    fun nextSurahAll(surah: Int): Int {
        val count = QuranConstants.VERSE_COUNTS.size
        if (surah !in 1..count) return 1
        return (surah % count) + 1
    }

    fun prevSurahAll(surah: Int): Int {
        val count = QuranConstants.VERSE_COUNTS.size
        if (surah !in 1..count) return count
        return ((surah - 2 + count) % count) + 1
    }
}
