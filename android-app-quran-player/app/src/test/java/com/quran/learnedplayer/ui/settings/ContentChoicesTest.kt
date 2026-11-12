package com.quran.learnedplayer.ui.settings

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.quran.learnedplayer.data.ContentCatalog
import com.quran.learnedplayer.data.ContentRepository
import com.quran.learnedplayer.data.Reciter
import com.quran.learnedplayer.data.TranslationStyle
import com.quran.learnedplayer.player.PlayerSettings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

/**
 * The translation / tafsir / reciter picker the intro and Settings share, on the JVM. The
 * network is stubbed to fail, which also checks the fallback message.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class ContentChoicesTest {
    @get:Rule val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val realFetcher = ContentRepository.fetcher
    private var reciterPicked: Reciter? = null

    @Before
    fun setUp() {
        PlayerSettings.init(context)
        ContentRepository.init(context)
        ContentRepository.clearDownloaded()
        ContentRepository.fetcher = { throw IOException("The download failed (503).") }
        PlayerSettings.translationEdition = PlayerSettings.DEFAULT_TRANSLATION_EDITION
        PlayerSettings.wordMeaningLanguage = PlayerSettings.DEFAULT_WORD_MEANING_LANGUAGE
        PlayerSettings.tafsirEdition = ""
        PlayerSettings.showWordTranslations = true
        PlayerSettings.showAyahTranslation = false
    }

    @After
    fun tearDown() {
        ContentRepository.fetcher = realFetcher
        PlayerSettings.translationEdition = PlayerSettings.DEFAULT_TRANSLATION_EDITION
        PlayerSettings.wordMeaningLanguage = PlayerSettings.DEFAULT_WORD_MEANING_LANGUAGE
        PlayerSettings.tafsirEdition = ""
    }

    private fun show(includeStyle: Boolean = true) {
        compose.setContent {
            // As in the intro and Settings, the block sits in something that scrolls.
            Column(Modifier.verticalScroll(rememberScrollState())) {
                ContentChoices(includeStyle = includeStyle, onReciter = { reciterPicked = it })
            }
        }
    }

    @Test
    fun saheeh_is_the_default_and_works_offline() {
        show()
        compose.onNodeWithText("Saheeh International", substring = true).assertIsDisplayed()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTagText(TAG_CONTENT_STATUS, "works offline")
        }
    }

    @Test
    fun choosing_urdu_picks_an_urdu_edition_and_urdu_word_meanings_and_says_when_it_cannot_download() {
        // The pick itself (the list is a dialog, which Robolectric never lets settle: the clicks
        // are in the instrumented ContentChoicesDeviceTest).
        chooseTranslationLanguage(ContentCatalog.get(context), "ur")
        assertTrue(PlayerSettings.translationEdition.startsWith("qc-"))
        assertEquals("ur", PlayerSettings.wordMeaningLanguage)
        // A language without word meanings leaves them alone.
        chooseTranslationLanguage(ContentCatalog.get(context), "am")
        assertEquals("ur", PlayerSettings.wordMeaningLanguage)

        PlayerSettings.translationEdition = "qc-54"
        show()
        compose.onNodeWithText("Junagarhi", substring = true).assertIsDisplayed()
        // The stubbed provider is down: say so, and that Saheeh is shown meanwhile.
        compose.waitUntil(5_000) { compose.onAllNodesWithTagText(TAG_CONTENT_STATUS, "Saheeh International is shown") }
    }

    @Test
    fun independent_switches_allow_both_off_and_retain_language() {
        show()
        val language = PlayerSettings.wordMeaningLanguage
        compose.onNodeWithTag(TAG_SETTINGS_AYAH_TRANSLATION).performScrollTo().performClick()
        assertTrue(PlayerSettings.showWordTranslations && PlayerSettings.showAyahTranslation)
        compose.onNodeWithTag(TAG_WORD_TRANSLATIONS_TOGGLE).performScrollTo().performClick()
        assertFalse(PlayerSettings.showWordTranslations)
        compose.onNodeWithTag(TAG_CONTENT_WORD_LANGUAGE).assertDoesNotExist()
        compose.onNodeWithTag(TAG_SETTINGS_AYAH_TRANSLATION).performScrollTo().performClick()
        assertFalse(PlayerSettings.showAyahTranslation)
        assertEquals(language, PlayerSettings.wordMeaningLanguage)
        compose.onNodeWithTag(TAG_WORD_TRANSLATIONS_TOGGLE).performScrollTo().performClick()
        compose.onNodeWithTag(TAG_CONTENT_WORD_LANGUAGE).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun a_chosen_tafsir_is_named_with_its_source() {
        PlayerSettings.tafsirEdition = "qc-tafsir-169"
        show()
        compose.onNodeWithText("Ibn Kathir (Abridged) by Hafiz Ibn Kathir, from Quran.com", substring = true).performScrollTo().assertIsDisplayed()
        PlayerSettings.tafsirEdition = ""
        compose.onNodeWithText("Commentary on the current ayah", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun the_reciter_goes_through_the_callback() {
        // Through the view model in the app (PlayerViewModel.setReciter rebuilds the playlist).
        show()
        compose.onNodeWithTag(contentReciterTag(Reciter.ABDUL_BASIT)).performScrollTo().performClick()
        assertEquals(Reciter.ABDUL_BASIT, reciterPicked)
    }

    @Test
    fun settings_leaves_the_style_to_its_own_switches() {
        show(includeStyle = false)
        compose.onNodeWithTag(contentStyleTag(TranslationStyle.BOTH)).assertDoesNotExist()
        compose.onNodeWithTag(TAG_CONTENT_EDITION).assertIsDisplayed()
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTagText(tag: String, text: String): Boolean =
        onAllNodes(androidx.compose.ui.test.hasTestTag(tag) and androidx.compose.ui.test.hasText(text, substring = true))
            .fetchSemanticsNodes().isNotEmpty()
}
