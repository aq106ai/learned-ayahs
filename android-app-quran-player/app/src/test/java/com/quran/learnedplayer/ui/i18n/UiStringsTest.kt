package com.quran.learnedplayer.ui.i18n

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.quran.learnedplayer.data.AppTheme
import com.quran.learnedplayer.data.TranslationStyle
import com.quran.learnedplayer.player.AyahOverflowMode
import com.quran.learnedplayer.player.PlaybackMode
import com.quran.learnedplayer.player.RepeatMode
import com.quran.learnedplayer.ui.common.SourcesAndDisclaimer
import com.quran.learnedplayer.video.VideoAspect
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Locale

/**
 * The interface language: tables, fallbacks, placeholders, and the labels that are translated at
 * run time (tx(label)), which `.setup/make_ui_strings.py --check` cannot see in the code.
 * The web's tests/unit/i18n.test.js holds the same rules.
 */
@RunWith(RobolectricTestRunner::class)
class UiStringsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @After
    fun english() = UiStrings.use(context, "en")

    private fun table(code: String): Map<String, String> {
        val json = JSONObject(context.assets.open("ui_strings_$code.json").bufferedReader().use { it.readText() })
        return json.keys().asSequence().associateWith { json.getString(it) }
    }

    @Test
    fun the_choice_wins_else_the_phone_else_english() {
        assertEquals("ur", UiStrings.resolve("ur", listOf(Locale.ENGLISH)))
        assertEquals("ar", UiStrings.resolve("", listOf(Locale.FRENCH, Locale.forLanguageTag("ar-EG"))))
        assertEquals("en", UiStrings.resolve("", listOf(Locale.FRENCH)))
        assertEquals("en", UiStrings.resolve("xx", emptyList()))
    }

    @Test
    fun english_is_the_key_missing_entries_fall_back_and_placeholders_fill() {
        UiStrings.use(context, "en")
        assertEquals("Settings", tx("Settings"))
        assertEquals("Ayah 3", tx("Ayah {0}", 3))
        UiStrings.use(context, "ur")
        assertTrue(UiStrings.isRtl)
        assertEquals("سیٹنگز", tx("Settings"))
        assertEquals("آیت 3", tx("Ayah {0}", 3))
        assertEquals("Nobody translated this", tx("Nobody translated this"))
        UiStrings.use(context, "ar")
        assertEquals("التفسير", tx("Tafsir"))
        UiStrings.use(context, "en")
        assertFalse(UiStrings.isRtl)
    }

    @Test
    fun both_tables_have_the_same_keys_and_placeholders() {
        val ur = table("ur")
        val ar = table("ar")
        assertEquals(ur.keys, ar.keys)
        assertTrue(ur.size > 500)
        val ph = Regex("\\{\\w+\\}")
        ur.forEach { (key, value) ->
            val want = ph.findAll(key).map { it.value }.sorted().toList()
            assertEquals(key, want, ph.findAll(value).map { it.value }.sorted().toList())
            assertEquals(key, want, ph.findAll(ar.getValue(key)).map { it.value }.sorted().toList())
        }
    }

    @Test
    fun labels_translated_at_run_time_are_in_every_table() {
        UiStrings.use(context, "en")
        val labels = AppTheme.entries.map { it.label } +
            PlaybackMode.entries.map { it.label } +
            RepeatMode.entries.map { it.label } +
            AyahOverflowMode.entries.map { it.label } +
            TranslationStyle.entries.map { it.label } +
            VideoAspect.entries.map { it.label }
        for (code in listOf("ur", "ar")) {
            val t = table(code)
            labels.forEach { assertTrue("$code: $it", t.containsKey(it)) }
        }
    }

    @Test
    fun the_sources_follow_a_language_change_in_the_same_process() {
        UiStrings.use(context, "en")
        val english = SourcesAndDisclaimer.INTRO
        UiStrings.use(context, "ur")
        assertNotEquals(english, SourcesAndDisclaimer.INTRO)
        assertEquals(table("ur").getValue(english), SourcesAndDisclaimer.INTRO)
    }
}
