package com.quran.learnedplayer.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Ayahs the user has pinned for quick access from the playlist panel, identified by global ayah
 * id — independent of [LearnedAyahsStore]: a bookmark doesn't have to be marked learned. Same
 * SharedPreferences-backed shape as [LearnedAyahsStore], its own prefs file.
 */
object BookmarksStore {
    private const val PREFS = "bookmarks"
    private const val KEY_IDS = "bookmark_global_ids"

    private val VALID_IDS = 1..LearnedAyahsStore.TOTAL_AYAHS

    private var prefs: SharedPreferences? = null

    private val _bookmarkedIds = MutableStateFlow<Set<Int>>(emptySet())
    val bookmarkedIds: StateFlow<Set<Int>> = _bookmarkedIds.asStateFlow()

    /** Call once from Application.onCreate to restore the persisted selection. */
    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _bookmarkedIds.value = readPersisted()
    }

    fun isBookmarked(globalId: Int): Boolean = _bookmarkedIds.value.contains(globalId)

    fun toggle(globalId: Int) {
        val current = _bookmarkedIds.value
        setIds(if (globalId in current) current - globalId else current + globalId)
    }

    fun clear() = setIds(emptySet())

    private fun setIds(ids: Set<Int>) {
        val valid = ids.filterTo(sortedSetOf()) { it in VALID_IDS }
        _bookmarkedIds.value = valid
        prefs?.edit()?.putString(KEY_IDS, valid.joinToString(","))?.apply()
    }

    private fun readPersisted(): Set<Int> {
        val raw = prefs?.getString(KEY_IDS, "") ?: ""
        if (raw.isBlank()) return emptySet()
        return raw.split(",")
            .mapNotNull { it.trim().toIntOrNull() }
            .filterTo(mutableSetOf()) { it in VALID_IDS }
    }
}
