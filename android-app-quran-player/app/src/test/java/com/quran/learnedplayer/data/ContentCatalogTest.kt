package com.quran.learnedplayer.data

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The bundled content catalog and its rules; the same cases as web-app/tests/unit/catalog.test.js. */
@RunWith(RobolectricTestRunner::class)
class ContentCatalogTest {
    private val catalog = ContentCatalog.get(ApplicationProvider.getApplicationContext())

    @Test
    fun ids_are_unique_and_every_edition_names_its_source_and_licence() {
        for (ids in listOf(catalog.translations.map { it.id }, catalog.tafsirs.map { it.id }, catalog.wordMeanings.map { it.id }, catalog.reciters.map { it.id })) {
            assertEquals(ids.size, ids.toSet().size)
        }
        catalog.translations.forEach { t ->
            assertTrue(t.id, t.id == "en-saheeh" || Regex("qc-\\d+").matches(t.id))
            assertTrue(t.id, t.name.isNotBlank() && t.author.isNotBlank() && t.source.isNotBlank() && t.licence.isNotBlank())
            assertTrue("${t.id}: ${t.language}", catalog.languages.containsKey(t.language))
            if (!t.bundled) assertTrue(t.id, t.resourceId != null)
        }
        catalog.tafsirs.forEach { t ->
            assertTrue(t.id, Regex("qc-tafsir-\\d+").matches(t.id) && t.licence.isNotBlank() && catalog.languages.containsKey(t.language))
        }
        // Only what already shipped is bundled: nothing new is redistributed.
        assertEquals(listOf("en-saheeh"), catalog.translations.filter { it.bundled }.map { it.id })
        assertEquals(listOf("en"), catalog.wordMeanings.filter { it.bundled }.map { it.id })
        assertTrue(catalog.translations.size > 100)
        assertTrue(catalog.tafsirs.size >= 20)
    }

    @Test
    fun the_reciters_are_the_five_shipped_ones_by_their_saved_keys() {
        assertEquals(
            Reciter.entries.map { listOf(it.name, it.folder, it.timingsAsset) },
            catalog.reciters.map { listOf(it.id, it.folder, it.timingsAsset) },
        )
        assertTrue(catalog.reciters.all { it.highlighting })
        assertEquals(Reciter.DEFAULT.name, catalog.defaultReciter)
    }

    @Test
    fun the_default_is_the_bundled_saheeh_and_every_language_has_a_recommended_edition() {
        assertEquals("en-saheeh", catalog.defaultTranslation)
        assertEquals("en", catalog.translationLanguages().first())
        assertEquals("en-saheeh", catalog.translationsIn("en").first().id)
        catalog.translationLanguages().forEach { code ->
            assertTrue(code, catalog.translationsIn(code).any { it.recommended })
        }
    }

    @Test
    fun saved_ids_resolve_with_fallbacks() {
        assertEquals("en-saheeh", catalog.translation("qc-999999").id)
        assertEquals("en-saheeh", catalog.translation(null).id)
        assertEquals("qc-54", catalog.translation("qc-54").id)
        assertNull(catalog.tafsir(""))
        assertNull(catalog.tafsir("qc-tafsir-0"))
        assertEquals("Ibn Kathir (Abridged)", catalog.tafsir("qc-tafsir-169")?.name)
        assertEquals("ur", catalog.wordMeanings("ur").id)
        assertEquals("en", catalog.wordMeanings("xx").id)
        val retired = ContentCatalog(
            catalog.api, catalog.defaultTranslation, catalog.defaultWordMeanings, catalog.defaultReciter, catalog.languages,
            catalog.translations, catalog.wordMeanings, catalog.tafsirs, catalog.reciters,
            retired = mapOf("qc-1" to "qc-54", "qc-2" to "qc-3", "qc-3" to "qc-2"),
        )
        assertEquals("qc-54", retired.translation("qc-1").id)
        assertEquals("a cycle does not hang", "en-saheeh", retired.translation("qc-2").id)
    }

    @Test
    fun every_mushaf_style_has_a_bundled_font_and_unknown_ids_draw_the_default() {
        assertEquals(
            listOf(com.quran.learnedplayer.ui.theme.QuranFonts.SCHEHERAZADE, com.quran.learnedplayer.ui.theme.QuranFonts.AMIRI),
            catalog.mushafStyles.map { it.id },
        )
        catalog.mushafStyles.forEach { assertTrue(it.id, it.licence.contains("Open Font License")) }
        assertEquals(com.quran.learnedplayer.R.font.amiri_quran, com.quran.learnedplayer.ui.theme.QuranFonts.fontRes("uthmani-amiri"))
        assertEquals(com.quran.learnedplayer.R.font.scheherazade_new, com.quran.learnedplayer.ui.theme.QuranFonts.fontRes("gone"))
        assertEquals("uthmani-scheherazade", catalog.mushafStyle("gone")?.id)
    }

    @Test
    fun right_to_left_languages_are_marked() {
        listOf("ar", "ur", "fa").forEach { assertTrue(it, catalog.isRtl(it)) }
        listOf("en", "id", "bn").forEach { assertFalse(it, catalog.isRtl(it)) }
    }

    @Test
    fun search_finds_editions_by_name_author_and_language() {
        fun ids(q: String) = catalog.search(q).map { it.id }
        assertTrue(ids("junagarhi").contains("qc-54"))
        assertTrue(ids("urdu").contains("qc-54"))
        assertTrue(ids("اردو").contains("qc-54"))
        assertTrue(ids("HALEEM").contains("qc-85"))
        assertEquals(catalog.translations.size, ids("").size)
        assertEquals("ur", catalog.tafsirsFor("ur").first().language)
    }

    @Test
    fun the_style_is_the_two_switches_both_ways() {
        TranslationStyle.entries.forEach {
            assertEquals(it, TranslationStyle.of(it.showWordTranslations, it.showAyahTranslation))
        }
        assertEquals(TranslationStyle.NONE, TranslationStyle.of(showWordTranslations = false, showAyahTranslation = false))
    }

    @Test
    fun provider_text_becomes_plain_text() {
        assertEquals("الم", ContentText.plainTranslation("الم<sup foot_note=160812>1</sup>"))
        assertEquals(
            "the Ever-Living, the Self-Sustaining.",
            ContentText.plainTranslation("the Ever-Living,<sup foot_note=196031>1</sup> the Self-Sustaining."),
        )
        assertEquals(
            "Allah & His Messenger's alert(1)word",
            ContentText.plainTranslation("<i>Allah</i> &amp; His Messenger&#39;s <script>alert(1)</script>word"),
        )
        assertEquals("a b c", ContentText.plainTranslation("a<br/>b  c"))
        assertEquals("", ContentText.plainTranslation(null))
        assertEquals("Saheeh International · Tanzil.net", catalog.translation("en-saheeh").credit)
    }

    @Test
    fun word_meanings_align_by_position_so_2_181s_end_glyph_never_shifts_them() {
        val words = QuranDataRepository(ApplicationProvider.getApplicationContext()).wordsForSync(2, 181)
        val content = words.filter { !it.isEnd }
        // Quran.com calls every one a word, including the one our text marks as the end.
        val provider = words.map { ContentText.ProviderWord(it.position, isWord = true, text = "m${it.position}", languageName = "urdu") }
        assertEquals(content.map { "m${it.position}" }, ContentText.alignWordMeanings(content.map { it.position }, provider, "Urdu"))
    }

    @Test
    fun a_word_with_nothing_in_the_chosen_language_is_null() {
        val provider = listOf(
            ContentText.ProviderWord(1, true, "سب تعریف", "urdu"),
            ContentText.ProviderWord(2, true, "(be) to Allah", "english"),
            ContentText.ProviderWord(3, true, "", "urdu"),
            ContentText.ProviderWord(5, false, "(۲)", "urdu"),
        )
        assertEquals(listOf("سب تعریف", null, null, null), ContentText.alignWordMeanings(listOf(1, 2, 3, 4), provider, "Urdu"))
    }
}
