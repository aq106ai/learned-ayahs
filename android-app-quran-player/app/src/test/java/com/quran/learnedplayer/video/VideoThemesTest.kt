package com.quran.learnedplayer.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The bundled theme map and translation against the drawn themes. Read as text, not through
 * org.json, so the test runs on the plain JVM.
 */
class VideoThemesTest {
    private fun asset(name: String): String {
        val candidates = listOf("src/main/assets/$name", "app/src/main/assets/$name")
        return candidates.map(::File).first { it.exists() }.readText()
    }

    @Test
    fun the_map_and_the_drawn_themes_have_the_same_keys() {
        val json = asset("video_themes.json")
        val keys = Regex("\"themes\":\\[(.*?)]").find(json)!!.groupValues[1]
            .split(",").map { it.trim('"') }
        assertEquals(keys.toSet(), VideoThemes.all.map { it.key }.toSet())
        assertEquals("theme keys are unique", VideoThemes.all.size, VideoThemes.all.map { it.key }.toSet().size)
        assertTrue("about fifty themes", VideoThemes.all.size >= 50)
    }

    @Test
    fun every_ayah_has_a_theme() {
        val json = asset("video_themes.json")
        val keys = Regex("\"themes\":\\[(.*?)]").find(json)!!.groupValues[1].split(",")
        val ayahs = Regex("\"ayahs\":\\[(.*?)]").find(json)!!.groupValues[1].split(",").map { it.toInt() }
        assertEquals(6236, ayahs.size)
        assertTrue(ayahs.all { it in keys.indices })
    }

    @Test
    fun every_theme_has_two_or_three_backgrounds_with_four_colours() {
        for (theme in VideoThemes.all) {
            assertTrue(theme.key, theme.variants.size in 2..3)
            assertTrue(theme.key, theme.variants.all { it.colors.size == 4 })
            assertTrue(theme.key, theme.title.isNotBlank())
        }
        assertTrue("over a hundred backgrounds", VideoThemes.all.sumOf { it.variants.size } >= 100)
    }

    @Test
    fun an_unknown_key_falls_back_and_variants_wrap() {
        assertEquals(VideoThemes.FALLBACK, VideoThemes.theme("no such theme").key)
        val night = VideoThemes.theme("night")
        assertEquals(night.variants[0], VideoThemes.variant("night", night.variants.size))
        assertEquals(night.variants.last(), VideoThemes.variant("night", -1))
    }

    @Test
    fun the_translation_covers_every_ayah() {
        val json = asset("translation_en_saheeh.json")
        assertTrue(json.startsWith("[\"In the name of Allah"))
        // 6236 strings: count the separators between them.
        assertEquals(6235, Regex("\",\"").findAll(json).count())
    }
}
