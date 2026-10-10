package com.quran.learnedplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Locale

/**
 * Audio file names and the check that a download really is audio.
 *
 * The names are both cache keys and URL path segments, so they must be the same on every phone.
 * They used to be formatted with the default locale, and in an Arabic, Persian or Bengali locale
 * `%03d` writes that script's digits — every everyayah and Quran.com URL then 404'd and nothing
 * played.
 */
class AudioFileNamingTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun withLocale(tag: String, block: () -> Unit) {
        val saved = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag(tag))
            block()
        } finally {
            Locale.setDefault(saved)
        }
    }

    @Test
    fun file_names_use_ascii_digits_in_every_locale() {
        for (tag in listOf("en-US", "ar-EG", "fa-IR", "bn-BD", "ur-PK")) {
            withLocale(tag) {
                assertEquals(tag, "002255.mp3", AyahMapping.ayahFilename(2, 255))
                assertEquals(tag, "114006.mp3", AyahMapping.ayahFilename(114, 6))
                assertEquals(tag, "002_255_001.mp3", WordAudio.filename(2, 255, 0))
            }
        }
    }

    private fun file(vararg bytes: Int): File =
        tmp.newFile().apply { writeBytes(ByteArray(bytes.size) { bytes[it].toByte() }) }

    @Test
    fun audio_is_recognised_and_an_html_page_is_not() {
        assertTrue("ID3-tagged MP3", AtomicDownload.looksLikeAudio(file('I'.code, 'D'.code, '3'.code, 4)))
        assertTrue("bare MPEG frame", AtomicDownload.looksLikeAudio(file(0xFF, 0xFB, 0x90, 0x64)))
        assertTrue("WAV", AtomicDownload.looksLikeAudio(file('R'.code, 'I'.code, 'F'.code, 'F'.code)))
        assertTrue("Ogg", AtomicDownload.looksLikeAudio(file('O'.code, 'g'.code, 'g'.code, 'S'.code)))
        assertFalse("captive-portal page", AtomicDownload.looksLikeAudio(file('<'.code, 'h'.code, 't'.code, 'm'.code)))
        assertFalse("empty file", AtomicDownload.looksLikeAudio(file()))
    }
}
