package com.quran.learnedplayer.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.quran.learnedplayer.player.PlayerSettings
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

/**
 * Downloads, offline reuse and fallbacks, against a stub of Quran.com ([ContentRepository.fetcher]),
 * so nothing touches the network.
 */
@RunWith(RobolectricTestRunner::class)
class ContentRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val quran = QuranDataRepository(context)
    private val requests = mutableListOf<String>()
    private val realFetcher = ContentRepository.fetcher

    /** Every ayah key, in order, from the bundled text. */
    private val keys: List<String> by lazy {
        (1..114).flatMap { s -> (1..QuranConstants.VERSE_COUNTS[s - 1]).map { a -> "$s:$a" } }
    }

    private fun stub(failTranslations: Boolean = false, dropAyahs: Int = 0, lang: String = "urdu") = { url: String ->
        requests += url
        val translation = Regex("/quran/translations/(\\d+)").find(url)
        val chapter = Regex("/verses/by_chapter/(\\d+)").find(url)
        when {
            translation != null -> {
                if (failTranslations) throw IOException("The download failed (503).")
                val id = translation.groupValues[1]
                JSONObject().put(
                    "translations",
                    JSONArray(keys.drop(dropAyahs).map { JSONObject().put("verse_key", it).put("text", "UR($id) $it<sup foot_note=9>1</sup>") }),
                ).toString()
            }
            chapter != null -> {
                val surah = chapter.groupValues[1].toInt()
                val page = Regex("page=(\\d+)").findAll(url).last().groupValues[1].toInt()
                val all = keys.filter { it.startsWith("$surah:") }
                val slice = all.drop((page - 1) * 50).take(50)
                JSONObject()
                    .put("verses", JSONArray(slice.map { key ->
                        val (s, a) = key.split(":").map(String::toInt)
                        JSONObject().put("verse_key", key).put("words", JSONArray(quran.wordsForSync(s, a).map { w ->
                            JSONObject().put("position", w.position).put("char_type_name", if (w.isEnd) "end" else "word")
                                .put("translation", JSONObject().put("text", "ur-$key-${w.position}").put("language_name", lang))
                        }))
                    }))
                    .put("pagination", JSONObject().put("next_page", if (page * 50 < all.size) page + 1 else JSONObject.NULL))
                    .toString()
            }
            else -> throw IOException("unexpected $url")
        }
    }

    @Before
    fun setUp() {
        PlayerSettings.init(context)
        ContentRepository.init(context)
        ContentRepository.clearDownloaded()
        ContentRepository.resetMemory()
        PlayerSettings.translationEdition = PlayerSettings.DEFAULT_TRANSLATION_EDITION
        PlayerSettings.wordMeaningLanguage = PlayerSettings.DEFAULT_WORD_MEANING_LANGUAGE
    }

    @After
    fun tearDown() {
        ContentRepository.fetcher = realFetcher
        ContentRepository.clearDownloaded()
        ContentRepository.resetMemory()
        PlayerSettings.translationEdition = PlayerSettings.DEFAULT_TRANSLATION_EDITION
        PlayerSettings.wordMeaningLanguage = PlayerSettings.DEFAULT_WORD_MEANING_LANGUAGE
    }

    @Test
    fun the_bundled_translation_needs_no_network() {
        ContentRepository.fetcher = { throw AssertionError("no network for the bundled edition") }
        assertTrue(ContentRepository.ayahTranslation(112, 1)!!.startsWith("Say, \"He is Allah"))
    }

    @Test
    fun a_chosen_edition_downloads_once_cleaned_and_is_used_offline_afterwards() {
        ContentRepository.fetcher = stub()
        PlayerSettings.translationEdition = "qc-54"
        assertEquals("UR(54) 112:1", ContentRepository.ayahTranslation(112, 1))
        assertEquals("UR(54) 114:6", ContentRepository.ayahTranslation(114, 6))
        assertEquals(1, requests.size)

        // A new process, no network: read from filesDir.
        ContentRepository.resetMemory()
        ContentRepository.fetcher = { throw IOException("offline") }
        assertTrue(ContentRepository.isAvailableOffline(ContentRepository.edition()!!))
        assertEquals("UR(54) 2:255", ContentRepository.ayahTranslation(2, 255))
    }

    @Test
    fun an_edition_that_cannot_download_shows_saheeh_and_is_not_kept() {
        ContentRepository.fetcher = stub(failTranslations = true)
        PlayerSettings.translationEdition = "qc-54"
        assertTrue(ContentRepository.ayahTranslation(112, 1)!!.startsWith("Say, \"He is Allah"))
        assertTrue(ContentRepository.prepare("qc-54").isFailure)
        assertFalse(ContentRepository.isAvailableOffline(ContentRepository.edition()!!))
    }

    @Test
    fun a_short_download_is_refused_rather_than_kept() {
        ContentRepository.fetcher = stub(dropAyahs = 500)
        assertTrue(ContentRepository.prepare("qc-54").isFailure)
        assertEquals(0L, ContentRepository.downloadedBytes())
    }

    @Test
    fun word_meanings_in_another_language_replace_the_english_once_fetched_and_survive_offline() {
        ContentRepository.fetcher = stub()
        PlayerSettings.wordMeaningLanguage = "ur"
        // Not fetched yet: the English stays.
        assertEquals("Say", quran.wordsForSync(112, 1).first().translation)
        assertTrue(ContentRepository.ensureMeanings(2) { s, a -> quran.wordsForSync(s, a) })
        assertTrue("Al-Baqarah takes several pages", requests.size >= 6)
        assertTrue(ContentRepository.ensureMeanings(112) { s, a -> quran.wordsForSync(s, a) })
        val words = quran.wordsForSync(112, 1)
        assertEquals(listOf("ur-112:1-1", "ur-112:1-2", "ur-112:1-3", "ur-112:1-4"), words.filter { !it.isEnd }.map { it.translation })
        assertEquals(null, words.last().translation)

        ContentRepository.resetMemory()
        ContentRepository.fetcher = { throw IOException("offline") }
        assertEquals("ur-112:1-1", quran.wordsForSync(112, 1).first().translation)
        // English again when English is chosen.
        PlayerSettings.wordMeaningLanguage = "en"
        assertEquals("Say", quran.wordsForSync(112, 1).first().translation)
    }

    @Test
    fun meanings_that_come_back_in_another_language_keep_the_english() {
        ContentRepository.fetcher = stub(lang = "english")
        PlayerSettings.wordMeaningLanguage = "ur"
        assertTrue(ContentRepository.ensureMeanings(112) { s, a -> quran.wordsForSync(s, a) })
        assertEquals("Say", quran.wordsForSync(112, 1).first().translation)
    }

    @Test
    fun clearing_removes_downloads_but_never_the_bundle() {
        ContentRepository.fetcher = stub()
        assertTrue(ContentRepository.prepare("qc-54").isSuccess)
        assertTrue(ContentRepository.downloadedBytes() > 0)
        ContentRepository.clearDownloaded()
        assertEquals(0L, ContentRepository.downloadedBytes())
        ContentRepository.fetcher = { throw IOException("offline") }
        assertTrue(ContentRepository.ayahTranslation(112, 1, "en-saheeh")!!.startsWith("Say"))
    }

    @Test
    fun saved_settings_default_to_what_shipped_before() {
        assertEquals("en-saheeh", PlayerSettings.translationEdition)
        assertEquals("en", PlayerSettings.wordMeaningLanguage)
        PlayerSettings.tafsirEdition = ""
        PlayerSettings.init(context)
        assertEquals("", PlayerSettings.tafsirEdition)
        PlayerSettings.translationEdition = "qc-85"
        PlayerSettings.init(context)
        assertEquals("qc-85", PlayerSettings.translationEdition)
    }
}
