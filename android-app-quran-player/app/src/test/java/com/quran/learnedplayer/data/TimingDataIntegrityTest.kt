package com.quran.learnedplayer.data

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Guards the shipped timing assets themselves.
 *
 * The bug this exists to prevent: the old `word_timings.json` copied QUL's raw segments, which
 * include a spurious ~70ms leading blip on ~80 ayahs. That shifted every word's highlight by one
 * and left the real final segment unused, so the last word never lit up at the right moment.
 * Nothing in the app could detect it — only the data can be checked.
 */
@RunWith(RobolectricTestRunner::class)
class TimingDataIntegrityTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val repo = QuranDataRepository(context)

    private fun contentWords(surah: Int, ayah: Int): Int = runBlocking {
        repo.wordsFor(surah, ayah).count { !it.isEnd }
    }

    @Test
    fun segment_counts_match_the_word_counts_for_every_reciter() {
        Reciter.entries.forEach { reciter ->
            var checked = 0
            for (surah in 1..114) {
                for (ayah in 1..QuranConstants.VERSE_COUNTS[surah - 1]) {
                    val segments = repo.timingsFor(reciter, surah, ayah) ?: continue
                    val words = contentWords(surah, ayah)
                    assertEquals(
                        "${reciter.displayName} $surah:$ayah has ${segments.size} segments " +
                            "for $words words",
                        words,
                        segments.size,
                    )
                    checked++
                }
            }
            assertTrue("${reciter.displayName} shipped almost no timings", checked > 6000)
        }
    }

    @Test
    fun segments_never_overlap_and_always_move_forward() {
        Reciter.entries.forEach { reciter ->
            for (surah in 1..114) {
                for (ayah in 1..QuranConstants.VERSE_COUNTS[surah - 1]) {
                    val segments = repo.timingsFor(reciter, surah, ayah) ?: continue
                    segments.forEachIndexed { i, seg ->
                        assertTrue(
                            "${reciter.displayName} $surah:$ayah word $i is empty: $seg",
                            seg.last >= seg.first,
                        )
                        if (i > 0) {
                            assertTrue(
                                "${reciter.displayName} $surah:$ayah word $i overlaps word ${i - 1}",
                                seg.first >= segments[i - 1].last,
                            )
                        }
                    }
                }
            }
        }
    }

    @Test
    fun the_ayah_that_exposed_the_off_by_one_is_now_aligned() {
        // 2:21 — QUL reports 12 segments for 11 words, the first being a 70ms blip that
        // overlaps the next. Cleaned, word 1 is the long "يَـٰٓأَيُّهَا" and the last word keeps
        // the final drawn-out segment instead of it being discarded.
        val segments = repo.timingsFor(Reciter.MAHER_AL_MUAIQLY, 2, 21)
        assertNotNull(segments)
        assertEquals(11, segments!!.size)
        assertEquals(40L, segments.first().first)
        assertEquals(11320L, segments.last().first)
    }

    @Test
    fun the_last_word_is_highlighted_while_it_is_being_recited() {
        // The user-visible symptom of the old bug: at the end of an ayah the highlight must be
        // on the final word, not stuck one short.
        Reciter.entries.forEach { reciter ->
            listOf(2 to 21, 112 to 1, 1 to 1).forEach { (surah, ayah) ->
                val segments = repo.timingsFor(reciter, surah, ayah) ?: return@forEach
                val words = contentWords(surah, ayah)
                val lastWord = segments.last()
                val middleOfLastWord = (lastWord.first + lastWord.last) / 2
                assertEquals(
                    "${reciter.displayName} $surah:$ayah: last word not active during its audio",
                    words - 1,
                    WordSync.activeWordIndex(middleOfLastWord, segments, words),
                )
            }
        }
    }

    @Test
    fun coverage_is_effectively_complete() {
        // Approximation is gone, so an ayah without timings simply doesn't highlight. Track how
        // many that is — it should be a rounding error, not a feature gap.
        Reciter.entries.forEach { reciter ->
            var have = 0
            for (surah in 1..114) {
                for (ayah in 1..QuranConstants.VERSE_COUNTS[surah - 1]) {
                    if (repo.timingsFor(reciter, surah, ayah) != null) have++
                }
            }
            assertTrue(
                "${reciter.displayName} covers only $have/6236 ayahs",
                have >= 6100,
            )
        }
    }
}
