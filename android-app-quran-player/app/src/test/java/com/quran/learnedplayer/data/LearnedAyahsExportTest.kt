package com.quran.learnedplayer.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LearnedAyahsExportTest {

    private fun g(surah: Int, ayah: Int) = AyahMapping.surahAyahToGlobal(surah, ayah)

    private val sample = buildSet {
        addAll((1..4).map { g(112, it) })
        addAll((1..83).map { g(36, it) })
        add(g(2, 255))
    }

    @Test
    fun export_has_the_documented_shape() {
        val json = JSONObject(LearnedAyahsExport.export(sample))
        assertEquals("learned-ayahs", json.getString("format"))
        assertEquals(1, json.getInt("version"))
        assertEquals(sample.size, json.getInt("count"))
        assertTrue(json.getJSONArray("ayahs").length() > 0)
        assertTrue(json.getString("exportedAt").endsWith("Z"))
    }

    @Test
    fun export_import_round_trips_exactly() {
        assertEquals(sample, LearnedAyahsExport.parse(LearnedAyahsExport.export(sample)).ids)
    }

    @Test
    fun round_trips_an_owner_sized_selection() {
        // ~1842 ayahs, the size of the repo owner's real list.
        val big = buildSet {
            addAll((1..7).map { g(1, it) })
            addAll((1..286).map { g(2, it) })
            add(g(3, 55))
            addAll((1..83).map { g(36, it) })
            (70..114).forEach { s ->
                addAll((1..QuranConstants.VERSE_COUNTS[s - 1]).map { g(s, it) })
            }
        }
        assertEquals(big, LearnedAyahsExport.parse(LearnedAyahsExport.export(big)).ids)
    }

    @Test
    fun accepts_a_bare_array_of_ids() {
        assertEquals(setOf(1, 2, 3), LearnedAyahsExport.parse("[1, 2, 3]").ids)
    }

    @Test
    fun accepts_a_bare_array_of_refs() {
        assertEquals(setOf(g(2, 255)), LearnedAyahsExport.parse("""["2:255"]""").ids)
    }

    @Test
    fun accepts_the_legacy_learnedAyahs_key() {
        val json = """{"learnedAyahs": [1, 2]}"""
        assertEquals(setOf(1, 2), LearnedAyahsExport.parse(json).ids)
    }

    @Test
    fun out_of_range_ids_are_rejected_not_imported() {
        val r = LearnedAyahsExport.parse("[0, 6237, 99999, 5]")
        assertEquals(setOf(5), r.ids)
        assertTrue(r.unparsed.isNotEmpty())
    }

    @Test
    fun empty_selection_exports_and_reimports_cleanly() {
        assertTrue(LearnedAyahsExport.parse(LearnedAyahsExport.export(emptySet())).ids.isEmpty())
    }

    @Test
    fun suggested_filename_is_json() {
        assertTrue(LearnedAyahsExport.suggestedFileName().startsWith("LearnedAyahs-"))
        assertTrue(LearnedAyahsExport.suggestedFileName().endsWith(".json"))
    }
}
