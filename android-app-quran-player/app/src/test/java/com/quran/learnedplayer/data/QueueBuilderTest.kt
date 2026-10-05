package com.quran.learnedplayer.data

import com.quran.learnedplayer.player.PlaybackMode
import com.quran.learnedplayer.player.RepeatMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PlaybackMode] × [RepeatMode] are the two independent axes the app's playback modes reduce
 * to — these lock in the queue each combination builds so the UI rework can't quietly change
 * playback behaviour.
 */
class QueueBuilderTest {

    private fun track(surah: Int, ayah: Int) = AyahTrack(
        index = 0,
        globalId = AyahMapping.surahAyahToGlobal(surah, ayah),
        surah = surah,
        ayah = ayah,
        filename = AyahMapping.ayahFilename(surah, ayah),
        remoteUrl = AyahMapping.remoteUrl(surah, ayah),
    )

    // Learned ayahs across three surahs.
    private val master = listOf(
        track(2, 1), track(2, 5), track(2, 255),
        track(36, 1), track(36, 2),
        track(112, 1),
    )

    @Test
    fun word_by_word_mode_uses_the_same_queue_as_revise() {
        assertEquals(
            QueueBuilder.buildQueue(master, PlaybackMode.REVISE, RepeatMode.OFF, 2),
            QueueBuilder.buildQueue(master, PlaybackMode.WORD_BY_WORD, RepeatMode.OFF, 2),
        )
        assertEquals(
            QueueBuilder.buildQueue(master, PlaybackMode.REVISE, RepeatMode.SURAH, 2),
            QueueBuilder.buildQueue(master, PlaybackMode.WORD_BY_WORD, RepeatMode.SURAH, 2),
        )
    }

    @Test
    fun revise_plays_the_master_list_regardless_of_repeat_ayah() {
        assertEquals(master, QueueBuilder.buildQueue(master, PlaybackMode.REVISE, RepeatMode.OFF, 2))
        assertEquals(master, QueueBuilder.buildQueue(master, PlaybackMode.REVISE, RepeatMode.AYAH, 2))
    }

    @Test
    fun full_surah_streams_every_ayah_of_the_surah_not_just_learned() {
        val queue = QueueBuilder.buildQueue(master, PlaybackMode.FULL_SURAH, RepeatMode.OFF, 112)
        assertEquals(4, queue.size) // Al-Ikhlas has 4 ayahs; only 1 was learned
        assertEquals(listOf(1, 2, 3, 4), queue.map { it.ayah })
        assertTrue(queue.all { it.surah == 112 })
    }

    @Test
    fun revise_with_repeat_surah_keeps_only_that_surahs_learned_ayahs() {
        val queue = QueueBuilder.buildQueue(master, PlaybackMode.REVISE, RepeatMode.SURAH, 2)
        assertEquals(listOf(1, 5, 255), queue.map { it.ayah })
    }

    @Test
    fun repeat_mode_is_independent_of_playback_mode() {
        // Full surah can combine with any repeat setting too — the queue only depends on mode.
        val offQueue = QueueBuilder.buildQueue(master, PlaybackMode.FULL_SURAH, RepeatMode.OFF, 112)
        val ayahQueue = QueueBuilder.buildQueue(master, PlaybackMode.FULL_SURAH, RepeatMode.AYAH, 112)
        val surahQueue = QueueBuilder.buildQueue(master, PlaybackMode.FULL_SURAH, RepeatMode.SURAH, 112)
        assertEquals(offQueue, ayahQueue)
        assertEquals(offQueue, surahQueue)
    }

    @Test
    fun learnedSurahs_are_distinct_and_sorted() {
        assertEquals(listOf(2, 36, 112), QueueBuilder.learnedSurahs(master))
    }

    @Test
    fun surah_navigation_wraps_around_learned_surahs() {
        assertEquals(36, QueueBuilder.nextSurah(master, 2))
        assertEquals(112, QueueBuilder.nextSurah(master, 36))
        assertEquals(2, QueueBuilder.nextSurah(master, 112)) // wraps
        assertEquals(112, QueueBuilder.prevSurah(master, 2)) // wraps
        assertEquals(2, QueueBuilder.prevSurah(master, 36))
    }

    @Test
    fun surah_navigation_wraps_around_all_114_surahs() {
        assertEquals(2, QueueBuilder.nextSurahAll(1))
        assertEquals(114, QueueBuilder.nextSurahAll(113))
        assertEquals(1, QueueBuilder.nextSurahAll(114)) // wraps
        assertEquals(114, QueueBuilder.prevSurahAll(1)) // wraps
        assertEquals(1, QueueBuilder.prevSurahAll(2))
    }

    @Test
    fun full_surah_for_unknown_surah_is_empty() {
        assertTrue(QueueBuilder.buildQueue(master, PlaybackMode.FULL_SURAH, RepeatMode.OFF, 0).isEmpty())
        assertTrue(QueueBuilder.buildQueue(master, PlaybackMode.FULL_SURAH, RepeatMode.OFF, 115).isEmpty())
    }
}
