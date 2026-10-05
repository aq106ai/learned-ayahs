package com.quran.learnedplayer.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Persisted set of ayahs the user has marked "learned" inside the app, identified by global
 * ayah id (1..6236). This is the default learned source; the companion-app JSON import is
 * optional and can seed this set once.
 *
 * Backed by SharedPreferences (a comma-joined id list) to match the rest of the app's
 * persistence — there is no DataStore/Room anywhere in this project, and the set is small
 * (<= 6236 ints).
 */
object LearnedAyahsStore {
    private const val PREFS = "learned_selection"
    private const val KEY_IDS = "learned_global_ids"

    const val TOTAL_AYAHS = 6236
    private val VALID_IDS = 1..TOTAL_AYAHS

    private var prefs: SharedPreferences? = null

    private val _learnedIds = MutableStateFlow<Set<Int>>(emptySet())
    val learnedIds: StateFlow<Set<Int>> = _learnedIds.asStateFlow()

    /** Call once from Application.onCreate to restore the persisted selection. */
    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _learnedIds.value = readPersisted()
    }

    fun isLearned(globalId: Int): Boolean = _learnedIds.value.contains(globalId)

    /** Number of marked ayahs that fall inside [surah]. */
    fun learnedCountForSurah(surah: Int): Int =
        _learnedIds.value.count { AyahMapping.globalToSurahAyah(it).first == surah }

    fun toggle(globalId: Int) {
        val current = _learnedIds.value
        setIds(if (globalId in current) current - globalId else current + globalId)
    }

    fun add(globalId: Int) = setIds(_learnedIds.value + globalId)

    fun addAll(ids: Collection<Int>) = setIds(_learnedIds.value + ids)

    fun remove(globalId: Int) = setIds(_learnedIds.value - globalId)

    fun removeAll(ids: Collection<Int>) = setIds(_learnedIds.value - ids.toSet())

    fun clear() = setIds(emptySet())

    /**
     * Single choke point for every mutation. Ids are validated here because callers downstream
     * (the learned badges, the snapshot builder) map them with [AyahMapping.globalToSurahAyah],
     * which throws on anything outside 1..6236 — one bad id would otherwise crash the home
     * screen on launch.
     */
    private fun setIds(ids: Set<Int>) {
        val valid = ids.filterTo(sortedSetOf()) { it in VALID_IDS }
        _learnedIds.value = valid
        prefs?.edit()?.putString(KEY_IDS, valid.joinToString(","))?.apply()
    }

    private fun readPersisted(): Set<Int> {
        val raw = prefs?.getString(KEY_IDS, "") ?: ""
        if (raw.isBlank()) return emptySet()
        // Also filtered: the stored string could predate this validation or be hand-edited.
        return raw.split(",")
            .mapNotNull { it.trim().toIntOrNull() }
            .filterTo(mutableSetOf()) { it in VALID_IDS }
    }
}
