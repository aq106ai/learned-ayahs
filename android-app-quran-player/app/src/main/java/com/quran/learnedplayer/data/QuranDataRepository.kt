package com.quran.learnedplayer.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class AyahWord(
    val text: String,
    val isEnd: Boolean = false,
    /** English word-by-word meaning; null for the end-of-ayah number glyph. */
    val translation: String? = null,
)

/**
 * The bundled Qur'an text, word meanings and per-reciter word timings.
 *
 * The caches are process-wide and guarded by one lock. The text alone is ~5 MB of JSON; the
 * player, the playback service and Recite each hold a repository, and with per-instance caches it
 * was parsed up to three times (memory the app can't spare on a small phone). Unsynchronized,
 * the "loaded" flag was also set before parsing finished, so a second caller during the parse saw
 * an empty cache and fell through to the network — or reported zero words.
 */
class QuranDataRepository(context: Context) {
    private val appContext = context.applicationContext

    private companion object {
        val lock = Any()
        val textCache = HashMap<String, List<AyahWord>>()
        /** Timings per reciter — each recording has its own, so they can never be shared. */
        val timingCache = HashMap<Reciter, Map<String, List<LongRange>>>()
        /** English word translations per verse key, aligned to content words ("end" excluded). */
        val translationCache = HashMap<String, List<String>>()
        var bundledTextLoaded = false
        var bundledTranslationsLoaded = false
    }

    /** Parses the bundled text and meanings now, off the main thread, so later lookups are instant. */
    fun warmUp() {
        synchronized(lock) { loadBundledTextIfNeeded() }
    }

    suspend fun wordsFor(surah: Int, ayah: Int): List<AyahWord> {
        val key = verseKey(surah, ayah)
        synchronized(lock) {
            textCache[key]?.let { return it }
            loadBundledTextIfNeeded()
            textCache[key]?.let { return it }
        }
        // The bundle covers all 6236 ayahs, so this is a last-resort path only.
        val fetched = fetchWordsFromNetwork(surah, ayah)
        return synchronized(lock) {
            withTranslations(key, fetched).also { if (it.isNotEmpty()) textCache[key] = it }
        }
    }

    /** Same as [wordsFor] but never hits the network — empty if the bundle has no row. */
    fun wordsForSync(surah: Int, ayah: Int): List<AyahWord> = synchronized(lock) {
        val key = verseKey(surah, ayah)
        textCache[key] ?: run {
            loadBundledTextIfNeeded()
            textCache[key] ?: emptyList()
        }
    }

    fun contentWordCount(surah: Int, ayah: Int): Int =
        wordsForSync(surah, ayah).count { !it.isEnd }

    /**
     * Exact per-word segments for the current reciter, or null when we don't have trustworthy
     * timings for this ayah.
     *
     * Null means "don't highlight" — never guess. The bundled assets are generated per reciter
     * and validated at build time (segment count matches the word count, strictly ordered, no
     * overlaps), so a non-null result can be trusted word-for-word.
     */
    suspend fun timingsFor(surah: Int, ayah: Int): List<LongRange>? =
        timingsFor(com.quran.learnedplayer.player.PlayerSettings.reciter, surah, ayah)

    fun timingsFor(reciter: Reciter, surah: Int, ayah: Int): List<LongRange>? =
        loadTimingsIfNeeded(reciter)[verseKey(surah, ayah)]

    private fun loadTimingsIfNeeded(reciter: Reciter): Map<String, List<LongRange>> =
        synchronized(lock) {
            timingCache.getOrPut(reciter) {
                buildMap {
                    runCatching {
                        appContext.assets.open(reciter.timingsAsset).use { stream ->
                            val json = JSONObject(stream.bufferedReader().readText())
                            json.keys().forEach { key ->
                                put(key, parseTimingSegments(json.getJSONObject(key).getJSONArray("segments")))
                            }
                        }
                    }
                }
            }
        }

    private fun verseKey(surah: Int, ayah: Int) = "$surah:$ayah"

    /** Call with [lock] held. Marks the text loaded only once it is. */
    private fun loadBundledTextIfNeeded() {
        if (bundledTextLoaded) return
        // Translations must be in memory first so every bundled ayah is cached with its
        // meanings attached — the cache is read directly on later lookups.
        loadBundledTranslationsIfNeeded()
        runCatching {
            appContext.assets.open("quran_text.json").use { stream ->
                val json = JSONObject(stream.bufferedReader().readText())
                json.keys().forEach { key ->
                    val wordsArray = json.getJSONObject(key).getJSONArray("words")
                    textCache[key] = withTranslations(key, parseWordsArray(wordsArray))
                }
            }
        }.onSuccess { bundledTextLoaded = true } // a failed parse is retried by the next caller
    }

    /** Call with [lock] held. */
    private fun loadBundledTranslationsIfNeeded() {
        if (bundledTranslationsLoaded) return
        runCatching {
            appContext.assets.open("word_translations.json").use { stream ->
                val json = JSONObject(stream.bufferedReader().readText())
                json.keys().forEach { key ->
                    val arr = json.getJSONArray(key)
                    translationCache[key] = (0 until arr.length()).map { arr.optString(it, "") }
                }
            }
        }.onSuccess { bundledTranslationsLoaded = true }
    }

    /** Attaches each content word's English meaning; the end-of-ayah glyph keeps none. */
    private fun withTranslations(key: String, words: List<AyahWord>): List<AyahWord> {
        val translations = translationCache[key]?.takeIf { it.isNotEmpty() } ?: return words
        var contentIndex = 0
        return words.map { word ->
            if (word.isEnd) {
                word
            } else {
                val meaning = translations.getOrNull(contentIndex)
                contentIndex++
                if (meaning.isNullOrEmpty()) word else word.copy(translation = meaning)
            }
        }
    }

    private fun parseWordsArray(wordsArray: JSONArray): List<AyahWord> {
        val words = mutableListOf<AyahWord>()
        for (i in 0 until wordsArray.length()) {
            val word = wordsArray.getJSONObject(i)
            val isEnd = word.optString("char_type") == "end"
            words += AyahWord(
                text = word.optString("text", ""),
                isEnd = isEnd,
            )
        }
        return words
    }

    private fun parseTimingSegments(segments: JSONArray): List<LongRange> {
        val ranges = mutableListOf<LongRange>()
        for (i in 0 until segments.length()) {
            val seg = segments.getJSONArray(i)
            val start = seg.getInt(0).toLong()
            val end = seg.getInt(1).toLong()
            ranges += start until end
        }
        return ranges
    }

    private fun fetchWordsFromNetwork(surah: Int, ayah: Int): List<AyahWord> {
        val url = URL(
            "https://api.qurancdn.com/api/qdc/verses/by_key/$surah:$ayah" +
                "?words=true&word_fields=text_uthmani",
        )
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 25_000
            readTimeout = 25_000
            setRequestProperty("User-Agent", "LearnedAyahsPlayer/1.0.14")
        }
        return try {
            connection.inputStream.bufferedReader().use { reader ->
                val data = JSONObject(reader.readText())
                val wordsArray = data.getJSONObject("verse").getJSONArray("words")
                parseWordsArray(
                    JSONArray().apply {
                        for (i in 0 until wordsArray.length()) {
                            val w = wordsArray.getJSONObject(i)
                            put(
                                JSONObject().apply {
                                    put("text", w.optString("text_uthmani", w.optString("text", "")))
                                    put(
                                        "char_type",
                                        if (w.optString("char_type_name") == "end") "end" else "word",
                                    )
                                },
                            )
                        }
                    },
                )
            }
        } catch (_: Exception) {
            emptyList()
        } finally {
            connection.disconnect()
        }
    }


}
