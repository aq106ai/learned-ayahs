package com.quran.learnedplayer.ui

import com.quran.learnedplayer.data.AyahTrack

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

fun playlistSurahs(tracks: List<AyahTrack>): List<Int> =
    tracks.map { it.surah }.distinct()

/**
 * Full playlist as collapsible surah groups: every learned surah gets a header; only
 * surahs in [expandedSurahs] list their ayah rows. When [queueSurah] is set (full-surah
 * loop mode) that surah shows [queue] — the actual playing queue with every ayah —
 * instead of just the learned ones. [queueSurah] gets its own header even when it has no
 * learned ayahs at all (so it's absent from [master]) — otherwise streaming a surah you
 * haven't marked anything in makes the playlist show nothing for what's actually playing.
 */
fun buildExpandablePlaylistEntries(
    master: List<AyahTrack>,
    expandedSurahs: Set<Int>,
    queue: List<AyahTrack> = emptyList(),
    queueSurah: Int? = null,
): List<PlaylistEntry> {
    val entries = mutableListOf<PlaylistEntry>()
    val masterBySurah = master.withIndex().groupBy { it.value.surah }
    val surahs = (masterBySurah.keys + listOfNotNull(queueSurah)).toSortedSet()
    surahs.forEach { surah ->
        val indexed = masterBySurah[surah].orEmpty()
        val fullQueue = queue.takeIf { surah == queueSurah && it.isNotEmpty() }
        entries += PlaylistEntry(
            kind = PlaylistEntryKind.SURAH_HEADER,
            surah = surah,
            trackIndex = indexed.firstOrNull()?.index ?: -1,
            ayahCount = fullQueue?.size ?: indexed.size,
        )
        if (surah !in expandedSurahs) return@forEach
        if (fullQueue != null) {
            fullQueue.forEach { track ->
                entries += PlaylistEntry(
                    kind = PlaylistEntryKind.AYAH_ROW,
                    surah = surah,
                    track = track,
                )
            }
        } else {
            indexed.forEach { (index, track) ->
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
