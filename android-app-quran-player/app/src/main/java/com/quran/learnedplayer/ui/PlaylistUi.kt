package com.quran.learnedplayer.ui

import com.quran.learnedplayer.ui.i18n.tx
import com.quran.learnedplayer.data.AyahTrack
import com.quran.learnedplayer.data.QueueBuilder
import com.quran.learnedplayer.data.QuranConstants

enum class PlaylistEntryKind {
    SURAH_HEADER,
    AYAH_ROW,
}

data class PlaylistEntry(
    val kind: PlaylistEntryKind,
    val surah: Int? = null,
    val track: AyahTrack? = null,
    val trackIndex: Int = -1,
    val ayahCount: Int = 0,
)

/** "3 sections", but "1 section" — worded this way wherever sections are counted. */
fun sectionCountLabel(count: Int): String =
    if (count == 1) tx("1 section") else tx("{0} sections", count)

fun playlistSurahs(tracks: List<AyahTrack>): List<Int> =
    tracks.map { it.surah }.distinct()

/**
 * Full playlist as collapsible surah groups: every learned surah gets a header; only
 * surahs in [expandedSurahs] list their ayah rows. When [queueSurah] is set (full-surah
 * loop mode) that surah shows [queue] — the actual playing queue with every ayah —
 * instead of just the learned ones. [queueSurah] gets its own header even when it has no
 * learned ayahs at all (so it's absent from [master]) — otherwise streaming a surah you
 * haven't marked anything in makes the playlist show nothing for what's actually playing.
 *
 * [allSurahs] (Full surah mode) heads every one of the 114 instead, so the surah being recited
 * can be changed from the playlist itself, and expands a surah to *all* of its ayahs: in that
 * mode any ayah plays, learned or not. Rows are built only for an expanded surah, so listing
 * them all costs 114 headers until one is opened.
 */
fun buildExpandablePlaylistEntries(
    master: List<AyahTrack>,
    expandedSurahs: Set<Int>,
    queue: List<AyahTrack> = emptyList(),
    queueSurah: Int? = null,
    allSurahs: Boolean = false,
): List<PlaylistEntry> {
    val entries = mutableListOf<PlaylistEntry>()
    val masterBySurah = master.withIndex().groupBy { it.value.surah }
    val surahs = when {
        allSurahs -> (1..QuranConstants.VERSE_COUNTS.size).toSortedSet()
        else -> (masterBySurah.keys + listOfNotNull(queueSurah)).toSortedSet()
    }
    surahs.forEach { surah ->
        val indexed = masterBySurah[surah].orEmpty()
        val fullQueue = queue.takeIf { surah == queueSurah && it.isNotEmpty() }
        entries += PlaylistEntry(
            kind = PlaylistEntryKind.SURAH_HEADER,
            surah = surah,
            trackIndex = indexed.firstOrNull()?.index ?: -1,
            ayahCount = fullQueue?.size
                ?: if (allSurahs) QuranConstants.VERSE_COUNTS[surah - 1] else indexed.size,
        )
        if (surah !in expandedSurahs) return@forEach
        when {
            fullQueue != null -> fullQueue.forEach { track ->
                entries += PlaylistEntry(
                    kind = PlaylistEntryKind.AYAH_ROW,
                    surah = surah,
                    track = track,
                )
            }
            allSurahs -> QueueBuilder.surahAllAyahs(surah).forEach { track ->
                entries += PlaylistEntry(
                    kind = PlaylistEntryKind.AYAH_ROW,
                    surah = surah,
                    track = track,
                )
            }
            else -> indexed.forEach { (index, track) ->
                entries += PlaylistEntry(
                    kind = PlaylistEntryKind.AYAH_ROW,
                    surah = surah,
                    track = track,
                    trackIndex = index,
                )
            }
        }
    }
    return entries
}

fun firstEntryIndexForSurah(entries: List<PlaylistEntry>, surah: Int): Int =
    entries.indexOfFirst { it.kind == PlaylistEntryKind.SURAH_HEADER && it.surah == surah }
        .takeIf { it >= 0 }
        ?: entries.indexOfFirst { it.track?.surah == surah }
