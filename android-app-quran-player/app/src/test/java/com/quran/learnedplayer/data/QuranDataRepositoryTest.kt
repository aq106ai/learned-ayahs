package com.quran.learnedplayer.data

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Verifies the bundled assets load offline and carry word-by-word translations. */
@RunWith(RobolectricTestRunner::class)
class QuranDataRepositoryTest {

    private val repo = QuranDataRepository(ApplicationProvider.getApplicationContext())

    @Test
    fun bundled_text_covers_previously_missing_ayahs() = runBlocking {
        // 2:255 (Ayat al-Kursi) was absent from the old partial asset.
        val words = repo.wordsFor(2, 255)
        assertFalse("2:255 must resolve from the bundle, not the network", words.isEmpty())
    }

    @Test
    fun words_carry_translations_and_end_marker_does_not() = runBlocking {
        val words = repo.wordsFor(112, 1)
        assertEquals(5, words.size)
        assertEquals(listOf("Say", "He", "(is) Allah", "the One"), words.filterNot { it.isEnd }.map { it.translation })

        val end = words.last()
        assertTrue("last word is the ayah-number glyph", end.isEnd)
        assertNull("end glyph carries no translation", end.translation)
    }

    @Test
    fun content_word_count_matches_word_audio_items() {
        val fixtures = listOf(
            Triple(1, 1, 4),
            Triple(1, 2, 4),
            Triple(112, 1, 4),
            Triple(114, 6, 3),
        )
        fixtures.forEach { (surah, ayah, expected) ->
            val count = repo.contentWordCount(surah, ayah)
            assertEquals("$surah:$ayah content words", expected, count)
            assertEquals(count, WordAudio.itemsFor(WordReciter.QURAN_COM, surah, ayah, count).size)
        }
        val kursi = repo.contentWordCount(2, 255)
        assertTrue(kursi > 20)
        assertEquals(kursi, WordAudio.itemsFor(WordReciter.QURAN_COM, 2, 255, kursi).size)
    }

    @Test
    fun every_content_word_of_a_long_ayah_has_a_translation() = runBlocking {
        val words = repo.wordsFor(2, 255).filterNot { it.isEnd }
        assertTrue(words.size > 20)
        words.forEach { assertNotNull("missing translation for '${it.text}'", it.translation) }
    }

    @Test
    fun timings_resolve_from_the_reciter_asset() = runBlocking {
        val segments = repo.timingsFor(112, 1)
        assertNotNull(segments)
        assertEquals(4, segments!!.size) // 4 content words
    }
}
