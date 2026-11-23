package com.quran.learnedplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Web port: `web-app/tests/unit/ayahSections.test.js`. */
class AyahSectionsTest {

    private fun g(surah: Int, ayah: Int) = AyahMapping.surahAyahToGlobal(surah, ayah)

    private fun ids(surah: Int, from: Int, to: Int) = (from..to).map { g(surah, it) }

    /** surah, index, total, from, to, count — the whole record bar the ids. */
    private fun shape(sections: List<AyahSection>) =
        sections.map { listOf(it.surah, it.index, it.total, it.from, it.to, it.count) }

    @Test
    fun a_section_is_one_unbroken_run_counted_per_surah() {
        // Three stretches marked in Al-Baqarah, one in Taha: three sections and one, not 10 and 2.
        val learned = (ids(2, 1, 3) + g(2, 10) + ids(2, 255, 257) + ids(20, 1, 2)).toSet()
        assertEquals(
            listOf(
                listOf(2, 1, 3, 1, 3, 3),
                listOf(2, 2, 3, 10, 10, 1),
                listOf(2, 3, 3, 255, 257, 3),
                listOf(20, 1, 1, 1, 2, 2),
            ),
            shape(AyahSections.of(learned)),
        )
        assertEquals(mapOf(2 to 3, 20 to 1), AyahSections.bySurah(learned).mapValues { it.value.size })
    }

    @Test
    fun a_section_carries_the_global_ids_its_ends_sit_on() {
        val section = AyahSections.of(ids(2, 255, 257).toSet()).single()
        assertEquals(g(2, 255), section.firstGlobalId)
        assertEquals(g(2, 257), section.lastGlobalId)
        assertEquals("2:255-257", AyahSections.key(section))
        assertNull(AyahSections.key(null))
    }

    @Test
    fun eighteen_stretches_in_al_baqarah_are_eighteen_sections() {
        // Every third ayah from 1: 18 single-ayah sections with a gap between each.
        val starts = (0 until 18).map { 1 + it * 3 }
        val sections = AyahSections.of(starts.map { g(2, it) }.toSet())
        assertEquals(18, sections.size)
        assertEquals((1..18).toList(), sections.map { it.index })
        assertTrue(sections.all { it.total == 18 && it.count == 1 && it.from == it.to })
    }

    @Test
    fun a_surah_learned_in_full_is_one_section_though_its_ref_is_the_bare_surah() {
        val learned = ids(112, 1, 4).toSet()
        assertEquals(listOf("112"), AyahRef.format(learned))
        assertEquals(listOf(listOf(112, 1, 1, 1, 4, 4)), shape(AyahSections.of(learned)))
    }

    @Test
    fun nothing_learned_is_no_sections_and_junk_ids_are_ignored() {
        assertEquals(emptyList<AyahSection>(), AyahSections.of(emptySet()))
        assertEquals(emptyMap<Int, List<AyahSection>>(), AyahSections.bySurah(emptySet()))
        val learned = setOf(0, -4, 6237, 99999, g(2, 255))
        assertEquals(listOf(listOf(2, 1, 1, 255, 255, 1)), shape(AyahSections.of(learned)))
    }

    @Test
    fun sections_come_surah_by_surah_and_ayah_by_ayah() {
        // Fed in deliberately out of order.
        val learned = linkedSetOf(g(36, 1), g(2, 20), g(112, 1), g(2, 1))
        assertEquals(
            listOf(2 to 1, 2 to 20, 36 to 1, 112 to 1),
            AyahSections.of(learned).map { it.surah to it.from },
        )
    }

    @Test
    fun at_finds_the_section_an_ayah_sits_in_and_nothing_for_an_unlearned_one() {
        val learned = (ids(2, 255, 257) + ids(20, 1, 2)).toSet()
        val baqarah = AyahSections.of(learned).first()
        assertEquals(baqarah, AyahSections.at(learned, g(2, 255)))
        assertEquals(baqarah, AyahSections.at(learned, g(2, 256)))
        assertEquals(baqarah, AyahSections.at(learned, g(2, 257)))
        assertNull(AyahSections.at(learned, g(2, 258)))
        assertNull(AyahSections.at(learned, g(36, 1)))
        // Anything that isn't an ayah id at all, rather than throwing out of globalToSurahAyah.
        assertNull(AyahSections.at(learned, 0))
        assertNull(AyahSections.at(learned, 6237))
    }

    @Test
    fun refs_and_sections_agree_on_where_a_run_breaks() {
        // The one difference is the whole surah, which only the formatter collapses.
        val learned = (ids(2, 1, 5) + g(2, 20) + ids(112, 1, 4)).toSet()
        assertEquals(listOf("2:1-5", "2:20", "112"), AyahRef.format(learned))
        assertEquals(
            listOf(1 to 5, 20 to 20, 1 to 4),
            AyahSections.of(learned).map { it.from to it.to },
        )
    }
}
