package com.quran.learnedplayer.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The app's list of nature photos against the photos in the repository and the drawn themes.
 * Read as text, not through org.json, so the test runs on the plain JVM.
 */
class NaturePhotosTest {
    private fun file(vararg candidates: String): File = candidates.map(::File).first { it.exists() }

    private val manifest: String get() = file("src/main/assets/nature_photos.json", "app/src/main/assets/nature_photos.json").readText()

    /** Each photo's fields, as written by backgrounds/fetch_nature_photos.py. */
    private val photos: List<Map<String, String>>
        get() = Regex("\\{[^{}]*\"id\"[^{}]*\\}").findAll(manifest).map { match ->
            Regex("\"(\\w+)\": (\"(?:[^\"\\\\]|\\\\.)*\"|\\[[^]]*]|\\d+)").findAll(match.value)
                .associate { it.groupValues[1] to it.groupValues[2].trim('"') }
        }.toList()

    @Test
    fun every_photo_is_cc0_and_in_the_repository_with_its_thumbnail() {
        val dir = file("../../backgrounds/nature", "../backgrounds/nature", "backgrounds/nature")
        assertTrue("some photos are listed", photos.size >= 30)
        assertEquals("ids are unique", photos.size, photos.map { it["id"] }.toSet().size)
        for (photo in photos) {
            val id = photo.getValue("id")
            assertEquals(id, "CC0", photo["licence"])
            assertTrue(id, photo["source"]!!.startsWith("https://commons.wikimedia.org/"))
            assertTrue("$id.jpg is in backgrounds/nature", File(dir, "$id.jpg").exists())
            assertTrue("${id}_thumb.jpg is in backgrounds/nature", File(dir, "${id}_thumb.jpg").exists())
        }
    }

    @Test
    fun every_subject_has_a_few_photos_and_suits_themes_that_exist() {
        val drawn = VideoThemes.all.map { it.key }.toSet()
        for ((subject, list) in photos.groupBy { it.getValue("subject") }) {
            assertTrue(subject, list.size >= 2)
            val themes = Regex("\"(\\w+)\"").findAll(list.first().getValue("themes")).map { it.groupValues[1] }.toList()
            assertTrue(subject, themes.isNotEmpty() && themes.all { it in drawn })
        }
    }
}
