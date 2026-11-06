package com.quran.learnedplayer

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasTestTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.quran.learnedplayer.data.ContentRepository
import com.quran.learnedplayer.player.PlayerSettings
import com.quran.learnedplayer.ui.player.TAG_TAFSIR_ERROR
import com.quran.learnedplayer.ui.player.TAG_TAFSIR_LIST
import com.quran.learnedplayer.ui.player.TAG_TAFSIR_NONE
import com.quran.learnedplayer.ui.player.TAG_TAFSIR_RANGE
import com.quran.learnedplayer.ui.player.TAG_TAFSIR_RETRY
import com.quran.learnedplayer.ui.player.TAG_TAFSIR_SHEET
import com.quran.learnedplayer.ui.player.TAG_TAFSIR_SOURCE
import com.quran.learnedplayer.ui.player.TAG_TAFSIR_TEXT
import com.quran.learnedplayer.ui.player.TafsirSheet
import com.quran.learnedplayer.ui.player.tafsirChoiceTag
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

/** The player's tafsir sheet, against a stub of Quran.com: no network. */
@RunWith(AndroidJUnit4::class)
class TafsirSheetTest {
    @get:Rule val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val realFetcher = ContentRepository.fetcher
    private var failing = false

    @Before
    fun setUp() {
        AppReset.reset(emptySet())
        ContentRepository.init(context)
        ContentRepository.clearDownloaded()
        ContentRepository.fetcher = { url ->
            if (failing) throw IOException("The download failed (503).")
            val key = url.substringAfterLast("/")
            val group = if (key.startsWith("112:")) listOf("112:1", "112:2", "112:3", "112:4") else listOf(key)
            JSONObject().put(
                "tafsir",
                JSONObject()
                    .put("verses", JSONObject().apply { group.forEach { put(it, JSONObject()) } })
                    .put("text", "<h2>On $key</h2><p>First <strong>point</strong>.<script>x()</script></p>"),
            ).toString()
        }
    }

    @After
    fun tearDown() {
        ContentRepository.fetcher = realFetcher
        ContentRepository.clearDownloaded()
        PlayerSettings.tafsirEdition = ""
    }

    @Test
    fun the_chosen_tafsir_shows_with_its_source_and_the_ayahs_it_covers() {
        PlayerSettings.tafsirEdition = "qc-tafsir-169"
        compose.setContent { TafsirSheet(112, 2, onDismiss = {}) }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag(TAG_TAFSIR_TEXT).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(TAG_TAFSIR_SHEET).assertIsDisplayed()
        compose.onNodeWithText("Ibn Kathir (Abridged) by Hafiz Ibn Kathir, from Quran.com.").assertIsDisplayed()
        compose.onNodeWithTag(TAG_TAFSIR_RANGE).assertIsDisplayed()
        compose.onNodeWithText("This commentary covers 112:1–4 together.").assertIsDisplayed()
        compose.onNodeWithText("On 112:2").assertIsDisplayed()
        compose.onNodeWithText("First point.").assertIsDisplayed()
    }

    @Test
    fun with_none_chosen_the_list_is_offered_and_a_failure_can_be_retried() {
        failing = true
        compose.setContent { TafsirSheet(2, 255, onDismiss = {}) }
        compose.onNodeWithTag(TAG_TAFSIR_NONE).assertIsDisplayed()
        compose.onNodeWithTag(TAG_TAFSIR_LIST).performScrollToNode(hasTestTag(tafsirChoiceTag("qc-tafsir-169")))
        compose.onNodeWithTag(tafsirChoiceTag("qc-tafsir-169")).performClick()
        assertEquals("qc-tafsir-169", PlayerSettings.tafsirEdition)
        compose.waitUntil(10_000) { compose.onAllNodesWithTag(TAG_TAFSIR_ERROR).fetchSemanticsNodes().isNotEmpty() }
        failing = false
        compose.onNodeWithTag(TAG_TAFSIR_RETRY).performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag(TAG_TAFSIR_TEXT).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(TAG_TAFSIR_SOURCE).assertIsDisplayed()
        compose.onNodeWithTag(TAG_TAFSIR_RANGE).assertDoesNotExist()
    }
}
