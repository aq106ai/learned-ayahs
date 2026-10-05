package com.quran.learnedplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reference grammar is shared by the quick-add box, the export file and the LLM prompt, so a
 * break here silently corrupts someone's learned list. Covered thoroughly on purpose.
 */
class AyahRefTest {

    private fun g(surah: Int, ayah: Int) = AyahMapping.surahAyahToGlobal(surah, ayah)

    @Test
    fun single_ayah() {
        val r = AyahRef.parse("2:255")
        assertEquals(setOf(g(2, 255)), r.ids)
        assertTrue(r.unparsed.isEmpty())
    }

    @Test
    fun ayah_range_within_a_surah() {
        val r = AyahRef.parse("36:1-5")
        assertEquals((1..5).map { g(36, it) }.toSet(), r.ids)
    }

    @Test
    fun whole_surah_by_number() {
        val r = AyahRef.parse("112")
        assertEquals((1..4).map { g(112, it) }.toSet(), r.ids)
    }

    @Test
    fun range_of_whole_surahs() {
        val r = AyahRef.parse("113-114")
        assertEquals(5 + 6, r.ids.size)
        assertTrue(g(113, 1) in r.ids && g(114, 6) in r.ids)
    }

    @Test
    fun by_name_whole_and_range() {
        assertEquals((1..4).map { g(112, it) }.toSet(), AyahRef.parse("Al-Ikhlas").ids)
        assertEquals(setOf(g(2, 255)), AyahRef.parse("Al-Baqarah 255").ids)
        assertEquals((1..3).map { g(36, it) }.toSet(), AyahRef.parse("Ya-Sin 1-3").ids)
    }

    @Test
    fun names_are_matched_loosely() {
        // Case, spaces, hyphens and a leading article should not matter.
        val expected = (1..4).map { g(112, it) }.toSet()
        listOf("al-ikhlas", "AL IKHLAS", "Ikhlas", "ikhlas").forEach {
            assertEquals(it, expected, AyahRef.parse(it).ids)
        }
    }

    @Test
    fun several_tokens_at_once() {
        val r = AyahRef.parse("2:255, 112, 36:1-3")
        assertEquals(1 + 4 + 3, r.ids.size)
        assertTrue(r.unparsed.isEmpty())
    }

    @Test
    fun newlines_are_separators_too() {
        val r = AyahRef.parse("2:255\n112\n36:1-3")
        assertEquals(1 + 4 + 3, r.ids.size)
    }

    @Test
    fun ranges_are_clamped_to_the_real_surah_length() {
        // Al-Ikhlas has 4 ayahs; asking for 999 should give the surah, not fail.
        val r = AyahRef.parse("112:1-999")
        assertEquals((1..4).map { g(112, it) }.toSet(), r.ids)
    }

    @Test
    fun rubbish_is_reported_rather_than_silently_dropped() {
        val r = AyahRef.parse("2:255, wibble, 900, 112")
        assertTrue(g(2, 255) in r.ids)
        assertTrue(g(112, 1) in r.ids)
        assertTrue("surah 900 does not exist", r.unparsed.isNotEmpty())
    }

    @Test
    fun format_collapses_runs_and_whole_surahs() {
        val ids = buildSet {
            addAll((1..4).map { g(112, it) })      // whole surah
            addAll((1..3).map { g(36, it) })       // a run
            add(g(2, 255))                          // a single
        }
        assertEquals(listOf("2:255", "36:1-3", "112"), AyahRef.format(ids))
    }

    @Test
    fun format_then_parse_round_trips() {
        val ids = buildSet {
            addAll((1..286).map { g(2, it) })
            add(g(3, 55))
            addAll((1..83).map { g(36, it) })
            addAll(listOf(g(18, 1), g(18, 2), g(18, 10)))
            addAll((1..6).map { g(114, it) })
        }
        val refs = AyahRef.format(ids)
        val back = AyahRef.parse(refs.joinToString(","))
        assertEquals(ids, back.ids)
        assertTrue(back.unparsed.isEmpty())
    }

    @Test
    fun round_trips_the_whole_quran() {
        val all = (1..6236).toSet()
        assertEquals(all, AyahRef.parse(AyahRef.format(all).joinToString(",")).ids)
    }

    @Test
    fun json_from_an_llm_can_be_pasted_whole() {
        val reply = """
            {
              "format": "learned-ayahs",
              "version": 1,
              "ayahs": ["2:255", "36:1-3", "112"]
            }
        """.trimIndent()
        val r = AyahRef.parse(reply)
        assertEquals(1 + 3 + 4, r.ids.size)
    }
}
