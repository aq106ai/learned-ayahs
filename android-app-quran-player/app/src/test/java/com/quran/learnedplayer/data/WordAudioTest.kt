package com.quran.learnedplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WordAudioTest {

    @Test
    fun filename_is_1_based_and_zero_padded() {
        assertEquals("001_001_001.mp3", WordAudio.filename(1, 1, 0))
        assertEquals("002_255_007.mp3", WordAudio.filename(2, 255, 6))
        assertEquals("114_006_003.mp3", WordAudio.filename(114, 6, 2))
    }

    @Test
    fun remote_url_uses_quran_cdn_wbw() {
        assertEquals(
            "https://audio.qurancdn.com/wbw/001_002_001.mp3",
            WordAudio.remoteUrl(WordReciter.QURAN_COM, 1, 2, 0),
        )
    }

    @Test
    fun itemsFor_matches_content_word_count_and_media_ids() {
        val items = WordAudio.itemsFor(WordReciter.QURAN_COM, 1, 2, 4)
        assertEquals(4, items.size)
        assertEquals((0..3).toList(), items.map { it.wordIndex })
        assertTrue(items.all { it.globalId == AyahMapping.surahAyahToGlobal(1, 2) })
        assertEquals("2:1", items[1].mediaId)
        assertEquals("https://audio.qurancdn.com/wbw/001_002_004.mp3", items.last().remoteUrl)
    }

    @Test
    fun itemsFor_empty_when_no_content_words() {
        assertTrue(WordAudio.itemsFor(WordReciter.QURAN_COM, 1, 1, 0).isEmpty())
    }

    @Test
    fun withLocalPath_attaches_without_changing_url() {
        val item = WordAudio.item(WordReciter.QURAN_COM, 1, 2, 0)
        val attached = item.withLocalPath("/cache/001_002_001.mp3")
        assertEquals("/cache/001_002_001.mp3", attached.localPath)
        assertEquals(item.remoteUrl, attached.remoteUrl)
        assertEquals(item.mediaId, attached.mediaId)
    }

    @Test
    fun fromKey_roundtrips() {
        assertEquals(WordReciter.QURAN_COM, WordReciter.DEFAULT)
        assertEquals(WordReciter.DEFAULT, WordReciter.fromKey(null))
        WordReciter.entries.forEach { assertEquals(it, WordReciter.fromKey(it.name)) }
    }
}
