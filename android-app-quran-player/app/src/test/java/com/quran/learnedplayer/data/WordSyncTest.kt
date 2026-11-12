package com.quran.learnedplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Word-highlight sync drives both the fullscreen highlight and the word-by-word pager. */
class WordSyncTest {

    private val segments = listOf(
        0L until 640L,
        840L until 1280L,
        1960L until 2320L,
    )

    @Test
    fun position_inside_a_segment_selects_that_word() {
        assertEquals(0, WordSync.activeWordIndex(100, segments, 3))
        assertEquals(1, WordSync.activeWordIndex(1000, segments, 3))
        assertEquals(2, WordSync.activeWordIndex(2000, segments, 3))
    }

    @Test
    fun gap_between_words_keeps_the_last_finished_word_active() {
        // 700ms falls in the silence between word 0 and word 1.
        assertEquals(0, WordSync.activeWordIndex(700, segments, 3))
    }

    @Test
    fun position_before_first_word_selects_nothing() {
        val delayed = listOf(500L until 900L)
        assertEquals(-1, WordSync.activeWordIndex(100, delayed, 1))
    }

    @Test
    fun empty_inputs_select_nothing() {
        assertEquals(-1, WordSync.activeWordIndex(100, emptyList(), 3))
        assertEquals(-1, WordSync.activeWordIndex(100, segments, 0))
    }

    @Test
    fun extra_segments_are_clamped_to_word_count() {
        // Defensive: a segment list longer than the word list must never select a word
        // that doesn't exist. The shipped assets are validated to match exactly.
        assertEquals(1, WordSync.activeWordIndex(5000, segments, 2))
    }


    @Test
    fun an_ayah_file_that_runs_on_past_its_timings_ends_with_the_ayah() {
        // Maher Al Muaiqly 2:258: timings end at 33.63 s in a 93.2 s file holding 2:259 too.
        val segments = listOf(0L..320L, 33_530L..33_630L)
        assertEquals(34_030L, WordSync.ayahEndMs(segments, 93_205L))
        // The usual few ms of trailing silence, or a few seconds, play out.
        assertEquals(null, WordSync.ayahEndMs(segments, 33_680L))
        assertEquals(null, WordSync.ayahEndMs(listOf(0L..32_789L), 36_545L))
        // A short ayah needs a proportionate overrun as well as five seconds.
        assertEquals(1_960L, WordSync.ayahEndMs(listOf(0L..1_560L), 9_000L))
        assertEquals(null, WordSync.ayahEndMs(emptyList(), 9_000L))
        assertEquals(null, WordSync.ayahEndMs(null, 9_000L))
    }
}
