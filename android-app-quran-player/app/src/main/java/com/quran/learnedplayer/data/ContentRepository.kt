package com.quran.learnedplayer.data

import android.content.Context
import com.quran.learnedplayer.player.PlayerSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * The translation and word meanings the user chose ([PlayerSettings.translationEdition],
 * [PlayerSettings.wordMeaningLanguage]), loaded or downloaded when needed.
 *
 * Saheeh International and the English word meanings are bundled, so Arabic + English always work
 * offline. Any other edition is fetched from Quran.com the first time it is needed, cleaned to
 * plain text ([ContentText]), checked for completeness and written under
 * `filesDir/content/` — whole, or not at all — so it works offline from then on. When a download
 * can't be had, Saheeh International is shown instead; the English meaning stays under any word
 * the chosen language has nothing for. The web app's `services/content.js` does the same.
 *
 * Blocking calls (network and disk) are marked; call them off the main thread.
 */
object ContentRepository {
    private const val USER_AGENT = "LearnedAyahsPlayer/1.0"
    private const val BUNDLED_SAHEEH = "en-saheeh"
    private const val SAHEEH_ASSET = "translation_en_saheeh.json"
    /** An edition may leave a handful of ayahs empty; a cut-short download may not. */
    private const val MAX_MISSING_AYAHS = 60

    private var appContext: Context? = null

    /** GET → body. Replaced in tests so nothing touches the network. */
    @Volatile var fetcher: (String) -> String = ::httpGet

    private val lock = Any()
    private val editions = HashMap<String, List<String?>>()
    /** "lang:surah" → "s:a" → meanings (null entries fall back to English). */
    private val meanings = HashMap<String, Map<String, List<String?>>>()

    private val _version = MutableStateFlow(0)
    /** Bumps whenever newly loaded content could change what is on screen. */
    val version: StateFlow<Int> = _version.asStateFlow()

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun catalog(): ContentCatalog? = appContext?.let { ContentCatalog.get(it) }

    private fun root(): File? = appContext?.let { File(it.filesDir, "content") }

    // -- whole-ayah translations -------------------------------------------------------------

    /** The chosen edition (or [id]), resolved through the catalog. */
    fun edition(id: String? = PlayerSettings.translationEdition): Edition? = catalog()?.translation(id)

    /** Already in memory or on disk, without touching the network. */
    fun isAvailableOffline(edition: Edition): Boolean =
        edition.bundled || synchronized(lock) { editions.containsKey(edition.id) } || editionFile(edition)?.isFile == true

    private fun editionFile(edition: Edition): File? = root()?.let { File(it, "translation/${edition.id}.json") }

    /** Blocking. Every ayah of [edition], index = global id − 1; throws if it can't be had. */
    fun load(edition: Edition): List<String?> {
        synchronized(lock) { editions[edition.id]?.let { return it } }
        val context = appContext ?: throw IOException("Not initialised")
        val list: List<String?> = if (edition.bundled) {
            val array = JSONArray(context.assets.open(SAHEEH_ASSET).bufferedReader().use { it.readText() })
            List(array.length()) { array.optString(it).ifEmpty { null } }
        } else {
            val file = editionFile(edition)!!
            readStringArray(file) ?: download(edition).also { writeAtomically(file, JSONArray(it).toString()) }
        }
        synchronized(lock) { editions[edition.id] = list }
        _version.value++
        return list
    }

    private fun download(edition: Edition): List<String?> {
        val catalog = catalog() ?: throw IOException("No catalog")
        val resource = edition.resourceId ?: throw IOException("${edition.name} has no Quran.com id")
        val body = JSONObject(fetcher("${catalog.api}/quran/translations/$resource?fields=verse_key"))
        val rows = body.getJSONArray("translations")
        val out = arrayOfNulls<String>(LearnedAyahsStore.TOTAL_AYAHS)
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            val parts = row.optString("verse_key").split(":")
            val surah = parts.getOrNull(0)?.toIntOrNull() ?: continue
            val ayah = parts.getOrNull(1)?.toIntOrNull() ?: continue
            if (surah !in 1..114 || ayah !in 1..QuranConstants.VERSE_COUNTS[surah - 1]) continue
            out[AyahMapping.surahAyahToGlobal(surah, ayah) - 1] = ContentText.plainTranslation(row.optString("text")).ifEmpty { null }
        }
        if (out.count { it == null } > MAX_MISSING_AYAHS) throw IOException("${edition.name} came back incomplete. Try again later.")
        return out.toList()
    }

    /** Blocking. Downloads (or loads) the edition now; Settings and the intro call this on a choice. */
    fun prepare(id: String?): Result<Edition> = runCatching {
        val edition = edition(id) ?: throw IOException("No catalog")
        load(edition)
        edition
    }

    /**
     * Blocking. One ayah in the chosen edition (or [id]); Saheeh International when that edition
     * can't be had. Null only if even the bundled text is unreadable.
     */
    fun ayahTranslation(surah: Int, ayah: Int, id: String? = PlayerSettings.translationEdition): String? {
        val index = AyahMapping.surahAyahToGlobal(surah, ayah) - 1
        val edition = edition(id)
        val chosen = edition?.let { runCatching { load(it) }.getOrNull() }
        if (chosen != null) return chosen.getOrNull(index)
        val saheeh = edition(BUNDLED_SAHEEH) ?: return null
        return runCatching { load(saheeh) }.getOrNull()?.getOrNull(index)
    }

    // -- tafsir -----------------------------------------------------------------------------------

    /** One tafsir entry: it may explain a group of ayahs together ([verses]). */
    data class Tafsir(val edition: Edition, val verses: List<String>, val blocks: List<TafsirText.Block>)

    private fun tafsirFile(edition: Edition, surah: Int, ayah: Int): File? =
        root()?.let { File(it, "tafsir/${edition.id}/${surah}_$ayah.json") }

    /**
     * Blocking. The chosen tafsir (or [id]) on one ayah, from disk or Quran.com (then kept).
     * The provider's JSON is what is stored; it only ever becomes [TafsirText] blocks.
     * Throws when none is chosen or it can't be had.
     */
    fun ayahTafsir(surah: Int, ayah: Int, id: String? = PlayerSettings.tafsirEdition): Tafsir {
        val catalog = catalog() ?: throw IOException("No catalog")
        val edition = catalog.tafsir(id) ?: throw IOException("Choose a tafsir first.")
        val file = tafsirFile(edition, surah, ayah) ?: throw IOException("Not initialised")
        val body = if (file.isFile) {
            file.readText()
        } else {
            val resource = edition.resourceId ?: throw IOException("${edition.name} has no Quran.com id")
            fetcher("${catalog.api}/tafsirs/$resource/by_ayah/$surah:$ayah").also { text ->
                JSONObject(text).getJSONObject("tafsir") // refuse anything that isn't a tafsir before keeping it
                writeAtomically(file, text)
            }
        }
        val t = JSONObject(body).getJSONObject("tafsir")
        val verses = t.optJSONObject("verses")?.keys()?.asSequence()?.toList().orEmpty().ifEmpty { listOf("$surah:$ayah") }
        return Tafsir(edition, verses, TafsirText.blocks(t.optString("text")))
    }

    // -- word meanings ---------------------------------------------------------------------------

    private fun meaningsFile(lang: String, surah: Int): File? = root()?.let { File(it, "words/$lang/$surah.json") }

    /**
     * [words] with the chosen language's meanings in place of the English, where they are in
     * memory or on disk. Never touches the network; [ensureMeanings] fetches.
     */
    fun withChosenMeanings(surah: Int, ayah: Int, words: List<AyahWord>, lang: String = PlayerSettings.wordMeaningLanguage): List<AyahWord> {
        if (lang == "en" || words.isEmpty() || appContext == null) return words
        val chosen = cachedMeanings(lang, surah)?.get("$surah:$ayah") ?: return words
        var content = 0
        return words.map { w ->
            if (w.isEnd) w else {
                val meaning = chosen.getOrNull(content++)
                if (meaning.isNullOrEmpty()) w else w.copy(translation = meaning)
            }
        }
    }

    private fun cachedMeanings(lang: String, surah: Int): Map<String, List<String?>>? {
        val key = "$lang:$surah"
        synchronized(lock) { meanings[key]?.let { return it } }
        val file = meaningsFile(lang, surah) ?: return null
        if (!file.isFile) return null
        val table = runCatching { parseMeanings(file.readText()) }.getOrNull() ?: return null
        synchronized(lock) { meanings[key] = table }
        return table
    }

    /**
     * Blocking. Makes one surah's meanings in [lang] available (from disk, or Quran.com).
     * Returns false when they can't be had; the English meanings stay.
     */
    fun ensureMeanings(surah: Int, lang: String = PlayerSettings.wordMeaningLanguage, quranData: (Int, Int) -> List<AyahWord>): Boolean {
        if (lang == "en") return true
        if (cachedMeanings(lang, surah) != null) return true
        val catalog = catalog() ?: return false
        if (catalog.wordMeanings.none { it.id == lang }) return false
        val languageName = catalog.languages[lang]?.name
        return runCatching {
            val table = LinkedHashMap<String, List<String?>>()
            var page: Int? = 1
            while (page != null) {
                val body = JSONObject(
                    fetcher(
                        "${catalog.api}/verses/by_chapter/$surah?words=true&language=${URLEncoder.encode(lang, "UTF-8")}" +
                            "&per_page=50&page=$page&word_fields=position",
                    ),
                )
                val verses = body.getJSONArray("verses")
                for (i in 0 until verses.length()) {
                    val verse = verses.getJSONObject(i)
                    val key = verse.getString("verse_key")
                    val (s, a) = key.split(":").map { it.toInt() }
                    val positions = quranData(s, a).filter { !it.isEnd }.map { it.position }
                    if (positions.isEmpty()) continue
                    val words = verse.getJSONArray("words").let { arr ->
                        List(arr.length()) { j ->
                            val w = arr.getJSONObject(j)
                            val t = w.optJSONObject("translation")
                            ContentText.ProviderWord(
                                position = w.optInt("position"),
                                isWord = w.optString("char_type_name") == "word",
                                text = t?.optString("text"),
                                languageName = t?.optString("language_name")?.ifEmpty { null },
                            )
                        }
                    }
                    table[key] = ContentText.alignWordMeanings(positions, words, languageName)
                }
                page = body.optJSONObject("pagination")?.let { p -> if (p.isNull("next_page")) null else p.optInt("next_page") }
            }
            if (table.size != QuranConstants.VERSE_COUNTS[surah - 1]) throw IOException("The word meanings came back incomplete.")
            writeAtomically(meaningsFile(lang, surah)!!, JSONObject(table.mapValues { (_, v) -> JSONArray(v.map { it ?: JSONObject.NULL }) }).toString())
            synchronized(lock) { meanings["$lang:$surah"] = table }
            _version.value++
            true
        }.getOrDefault(false)
    }

    // -- storage -----------------------------------------------------------------------------------

    /** Blocking. Bytes kept under filesDir/content (downloaded editions and meanings). */
    fun downloadedBytes(): Long = root()?.walkBottomUp()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L

    /** Blocking. Removes everything downloaded; the bundled text stays. */
    fun clearDownloaded() {
        root()?.deleteRecursively()
        synchronized(lock) {
            editions.keys.retainAll { it == BUNDLED_SAHEEH }
            meanings.clear()
        }
        _version.value++
    }

    /** Tests: forget what is in memory (the files stay). */
    internal fun resetMemory() {
        synchronized(lock) {
            editions.clear()
            meanings.clear()
        }
    }

    private fun readStringArray(file: File): List<String?>? {
        if (!file.isFile) return null
        return runCatching {
            val array = JSONArray(file.readText())
            if (array.length() != LearnedAyahsStore.TOTAL_AYAHS) return null
            List(array.length()) { if (array.isNull(it)) null else array.getString(it) }
        }.getOrNull()
    }

    private fun parseMeanings(json: String): Map<String, List<String?>> {
        val o = JSONObject(json)
        return o.keys().asSequence().associateWith { key ->
            val arr = o.getJSONArray(key)
            List(arr.length()) { if (arr.isNull(it)) null else arr.getString(it) }
        }
    }

    /** Whole or absent: a temporary file renamed into place. */
    private fun writeAtomically(file: File, text: String) {
        val dir = file.parentFile ?: return
        dir.mkdirs()
        val part = File.createTempFile("${file.name}.", ".part", dir)
        try {
            part.writeText(text)
            if (!part.renameTo(file)) {
                file.delete()
                if (!part.renameTo(file)) throw IOException("Could not save ${file.name}")
            }
        } finally {
            if (part.exists()) part.delete()
        }
    }

    private fun httpGet(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 25_000
            readTimeout = 60_000
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/json")
        }
        try {
            if (connection.responseCode !in 200..299) {
                throw IOException("The download failed (${connection.responseCode}). Check your connection and try again.")
            }
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}
