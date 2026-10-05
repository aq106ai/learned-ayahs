package com.quran.learnedplayer.ui

import com.quran.learnedplayer.data.AyahMapping
import com.quran.learnedplayer.data.AyahTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistUiTest {

    private fun track(surah: Int, ayah: Int) = AyahTrack(
        index = 0,
        globalId = AyahMapping.surahAyahToGlobal(surah, ayah),
        surah = surah,
        ayah = ayah,
        filename = AyahMapping.ayahFilename(surah, ayah),
        remoteUrl = AyahMapping.remoteUrl(surah, ayah),
    )

    // Learned ayahs across two surahs — surah 112 (currently playing, full-surah) has none.
    private val master = listOf(track(2, 1), track(2, 5), track(36, 1))

    @Test
    fun a_currently_streaming_surah_with_no_learned_ayahs_still_gets_a_header() {
        val queue = (1..4).map { track(112, it) }
        val entries = buildExpandablePlaylistEntries(
            master = master,
            expandedSurahs = emptySet(),
            queue = queue,
            queueSurah = 112,
        )
        val header = entries.single { it.kind == PlaylistEntryKind.SURAH_HEADER && it.surah == 112 }
        assertEquals(4, header.ayahCount)
    }

    @Test
    fun expanding_that_surah_shows_the_full_streamed_queue_not_just_learned_ayahs() {
        val queue = (1..4).map { track(112, it) }
        val entries = buildExpandablePlaylistEntries(
            master = master,
            expandedSurahs = setOf(112),
            queue = queue,
            queueSurah = 112,
        )
        val rows = entries.filter { it.kind == PlaylistEntryKind.AYAH_ROW && it.surah == 112 }
        assertEquals(listOf(1, 2, 3, 4), rows.map { it.track?.ayah })
    }

    @Test
    fun learned_surahs_still_appear_in_surah_order_alongside_the_streaming_one() {
        val queue = (1..4).map { track(112, it) }
        val entries = buildExpandablePlaylistEntries(
            master = master,
            expandedSurahs = emptySet(),
            queue = queue,
            queueSurah = 112,
        )
        val headers = entries.filter { it.kind == PlaylistEntryKind.SURAH_HEADER }
        assertEquals(listOf(2, 36, 112), headers.map { it.surah })
    }

    @Test
    fun without_a_queue_surah_only_learned_surahs_appear() {
        val entries = buildExpandablePlaylistEntries(master = master, expandedSurahs = emptySet())
        val headers = entries.filter { it.kind == PlaylistEntryKind.SURAH_HEADER }
        assertEquals(listOf(2, 36), headers.map { it.surah })
        assertTrue(entries.none { it.surah == 112 })
    }
}
