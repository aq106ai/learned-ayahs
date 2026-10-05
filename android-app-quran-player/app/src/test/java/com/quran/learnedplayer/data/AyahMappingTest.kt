package com.quran.learnedplayer.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** Global-ayah-id math underpins every queue, the learned store, and audio filenames. */
class AyahMappingTest {

    @Test
    fun first_and_last_ayahs_map_correctly() {
        assertEquals(1 to 1, AyahMapping.globalToSurahAyah(1))
        assertEquals(1, AyahMapping.surahAyahToGlobal(1, 1))
        assertEquals(114 to 6, AyahMapping.globalToSurahAyah(6236))
        assertEquals(6236, AyahMapping.surahAyahToGlobal(114, 6))
    }

    @Test
    fun surah_boundaries_map_correctly() {
        // Surah 1 has 7 ayahs, so global 8 is the first ayah of surah 2.
        assertEquals(1 to 7, AyahMapping.globalToSurahAyah(7))
        assertEquals(2 to 1, AyahMapping.globalToSurahAyah(8))
    }

    @Test
    fun roundtrip_holds_for_every_ayah() {
        var global = 0
        for (surah in 1..114) {
            for (ayah in 1..QuranConstants.VERSE_COUNTS[surah - 1]) {
                global++
                assertEquals(global, AyahMapping.surahAyahToGlobal(surah, ayah))
                assertEquals(surah to ayah, AyahMapping.globalToSurahAyah(global))
            }
        }
        assertEquals("the Qur'an has 6236 ayahs", 6236, global)
    }

    @Test
    fun filenames_are_zero_padded_everyayah_names() {
        assertEquals("001001.mp3", AyahMapping.ayahFilename(1, 1))
        assertEquals("114006.mp3", AyahMapping.ayahFilename(114, 6))
        assertEquals("002255.mp3", AyahMapping.ayahFilename(2, 255))
    }
}
