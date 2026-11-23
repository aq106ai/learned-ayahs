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

    // Full surah streams any surah, so the playlist heads all 114 and expands each to every
    // ayah it has — otherwise there is no way to reach a surah nothing is marked in from here.
    @Test
    fun full_surah_mode_heads_every_surah_and_still_shows_the_playing_queue() {
        val queue = (1..4).map { track(112, it) }
        val entries = buildExpandablePlaylistEntries(
            master = master,
            expandedSurahs = setOf(112),
            queue = queue,
            queueSurah = 112,
            allSurahs = true,
        )
        val headers = entries.filter { it.kind == PlaylistEntryKind.SURAH_HEADER }
        assertEquals(114, headers.size)
        assertEquals((1..114).toList(), headers.map { it.surah })
        // Headers carry the whole surah's length, not how much of it is learned.
        assertEquals(7, headers.first { it.surah == 1 }.ayahCount)
        assertEquals(286, headers.first { it.surah == 2 }.ayahCount)
        assertEquals(4, headers.first { it.surah == 112 }.ayahCount)
        val rows = entries.filter { it.kind == PlaylistEntryKind.AYAH_ROW }
        assertEquals(listOf(1, 2, 3, 4), rows.map { it.track?.ayah })
        assertTrue(rows.all { it.surah == 112 })
    }

    @Test
    fun expanding_an_unlearned_surah_lists_all_of_its_ayahs() {
        val entries = buildExpandablePlaylistEntries(
            master = master,
            expandedSurahs = setOf(1),
            queue = (1..4).map { track(112, it) },
            queueSurah = 112,
            allSurahs = true,
        )
        val rows = entries.filter { it.kind == PlaylistEntryKind.AYAH_ROW }
        assertEquals((1..7).toList(), rows.map { it.track?.ayah })
        assertEquals(AyahMapping.surahAyahToGlobal(1, 7), rows.last().track?.globalId)
    }

    // A learned surah expands to the whole surah too: in this mode its unlearned ayahs play.
    @Test
    fun expanding_a_learned_surah_in_full_surah_mode_is_not_limited_to_learned_ayahs() {
        val entries = buildExpandablePlaylistEntries(
            master = master,
            expandedSurahs = setOf(36),
            allSurahs = true,
        )
        val rows = entries.filter { it.kind == PlaylistEntryKind.AYAH_ROW }
        assertEquals(83, rows.size)
        assertEquals(listOf(1, 2, 3), rows.take(3).map { it.track?.ayah })
    }

    @Test
    fun without_a_queue_surah_only_learned_surahs_appear() {
        val entries = buildExpandablePlaylistEntries(master = master, expandedSurahs = emptySet())
        val headers = entries.filter { it.kind == PlaylistEntryKind.SURAH_HEADER }
        assertEquals(listOf(2, 36), headers.map { it.surah })
        assertTrue(entries.none { it.surah == 112 })
    }
}
