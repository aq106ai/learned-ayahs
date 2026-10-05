package com.quran.learnedplayer.data

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LearnedAyahsStoreTest {

    @Before
    fun setUp() {
        LearnedAyahsStore.init(ApplicationProvider.getApplicationContext())
        LearnedAyahsStore.clear()
    }

    @Test
    fun toggle_marks_and_unmarks() {
        assertFalse(LearnedAyahsStore.isLearned(8))
        LearnedAyahsStore.toggle(8)
        assertTrue(LearnedAyahsStore.isLearned(8))
        assertEquals(setOf(8), LearnedAyahsStore.learnedIds.value)

        LearnedAyahsStore.toggle(8)
        assertFalse(LearnedAyahsStore.isLearned(8))
        assertTrue(LearnedAyahsStore.learnedIds.value.isEmpty())
    }

    @Test
    fun addAll_unions_without_duplicates() {
        LearnedAyahsStore.add(8)
        LearnedAyahsStore.addAll(listOf(8, 9, 10))
        assertEquals(setOf(8, 9, 10), LearnedAyahsStore.learnedIds.value)
    }

    @Test
    fun selection_survives_reinit() {
        LearnedAyahsStore.addAll(listOf(8, 300, 1000))
        // Re-init reads the persisted store back (simulates a fresh app launch).
        LearnedAyahsStore.init(ApplicationProvider.getApplicationContext())
        assertEquals(setOf(8, 300, 1000), LearnedAyahsStore.learnedIds.value)
    }

    @Test
    fun learnedCountForSurah_counts_only_that_surah() {
        // Surah 2 (Al-Baqarah) ayahs 1 and 2 are global ids 8 and 9 (surah 1 has 7 ayahs).
        LearnedAyahsStore.addAll(listOf(8, 9, 1)) // 1 belongs to surah 1
        assertEquals(2, LearnedAyahsStore.learnedCountForSurah(2))
        assertEquals(1, LearnedAyahsStore.learnedCountForSurah(1))
    }

    @Test
    fun out_of_range_ids_are_rejected() {
        // An id outside 1..6236 would make AyahMapping.globalToSurahAyah throw and crash the
        // home screen's learned badges, so the store must never keep one.
        LearnedAyahsStore.addAll(listOf(0, -5, 6237, 99999, 8))
        assertEquals(setOf(8), LearnedAyahsStore.learnedIds.value)

        LearnedAyahsStore.add(6236) // last valid ayah
        assertTrue(LearnedAyahsStore.isLearned(6236))
        LearnedAyahsStore.add(6237)
        assertFalse(LearnedAyahsStore.isLearned(6237))
    }

    @Test
    fun every_stored_id_maps_to_a_real_ayah() {
        LearnedAyahsStore.addAll(listOf(1, 8, 6236, 7000, -1))
        // The exact call the home screen makes for its learned badges.
        LearnedAyahsStore.learnedIds.value.forEach { id ->
            AyahMapping.globalToSurahAyah(id)
        }
    }
}
