package com.quran.learnedplayer.ui.video

import com.quran.learnedplayer.data.Reciter
import com.quran.learnedplayer.video.PlayerBackgrounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Share → Video starts the video maker looking like the player. */
class VideoOptionsTest {
    private val base = VideoOptions(surah = 67, fromAyah = 1, toAyah = 1, reciter = Reciter.DEFAULT)

    @Test
    fun `a plain player still gets a background, matched to the ayahs`() {
        val options = base.likeThePlayer(PlayerBackgrounds.PLAIN, translation = false, wordMeanings = true)
        assertNull(options.themeKey)
        assertFalse(options.photos)
        assertFalse(options.fullTranslation)
        assertTrue(options.wordByWord)
    }

    @Test
    fun `matched photos stay photos`() {
        val options = base.likeThePlayer(PlayerBackgrounds.PHOTOS, translation = true, wordMeanings = false)
        assertNull(options.themeKey)
        assertTrue(options.photos)
        assertTrue(options.fullTranslation)
        assertFalse(options.wordByWord)
    }

    @Test
    fun `a chosen theme or photo subject carries over`() {
        assertEquals("night", base.likeThePlayer("night", true, true).themeKey)
        assertFalse(base.likeThePlayer("night", true, true).photos)
        val photo = base.likeThePlayer("photo:forest", true, true)
        assertEquals("photo:forest", photo.themeKey)
        assertTrue(photo.photos)
    }
}
