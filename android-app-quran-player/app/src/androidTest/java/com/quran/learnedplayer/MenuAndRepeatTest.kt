package com.quran.learnedplayer

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.quran.learnedplayer.data.AyahMapping
import com.quran.learnedplayer.player.PlaybackMode
import com.quran.learnedplayer.player.PlayerSettings
import com.quran.learnedplayer.player.PlayerViewModel
import com.quran.learnedplayer.player.RepeatMode
import com.quran.learnedplayer.ui.TAG_EXPAND_PANEL
import com.quran.learnedplayer.ui.TAG_FULLSCREEN_NEXT
import com.quran.learnedplayer.ui.TAG_FULLSCREEN_WHOLE_AYAH
import com.quran.learnedplayer.ui.TAG_NEXT_AYAH
import com.quran.learnedplayer.ui.TAG_PLAYER_RECITER
import com.quran.learnedplayer.ui.TAG_REPEAT_WORD
import com.quran.learnedplayer.ui.TAG_WORD_MEANINGS_MENU
import com.quran.learnedplayer.ui.TAG_WORD_PAGER
import com.quran.learnedplayer.ui.home.surahRowTag
import com.quran.learnedplayer.ui.playbackModeTag
import com.quran.learnedplayer.ui.repeatModeTag
import com.quran.learnedplayer.ui.settings.TAG_USE_WORD_CLIPS
import com.quran.learnedplayer.ui.surah.TAG_SURAH_DETAIL
import com.quran.learnedplayer.ui.surah.TAG_SURAH_REVISE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The three-dot menu's own choices and the footer's repeat row: word meanings on and off, no
 * "Revise Full Surah" for a surah learned in full, Repeat as one choice of four (Word among them,
 * with the whole ayah still on screen), Word clips in place of the reciter, and the surah
 * screen's play button.
 */
@RunWith(AndroidJUnit4::class)
class MenuAndRepeatTest : BaseAppTest() {
    // All of Al-Ikhlas (112), and one ayah of Al-Falaq (113).
    override fun seedLearnedAyahs(): Set<Int> =
        (1..4).map { AyahMapping.surahAyahToGlobal(112, it) }.toSet() + AyahMapping.surahAyahToGlobal(113, 1)

    private fun model() = ViewModelProvider(composeRule.activity)[PlayerViewModel::class.java]
    private fun state() = model().uiState.value

    @Test
    fun word_meanings_toggle_from_the_menu() {
        assertTrue(PlayerSettings.showWordTranslations)
        tapMenuItem(TAG_WORD_MEANINGS_MENU)
        assertFalse(PlayerSettings.showWordTranslations)
        tapMenuItem(TAG_WORD_MEANINGS_MENU)
        assertTrue(PlayerSettings.showWordTranslations)
    }

    @Test
    fun full_surah_is_only_offered_when_it_is_a_different_list() {
        awaitTag("open_actions_menu")
        composeRule.waitUntil(10_000) { state().currentTrack?.surah == 112 }
        composeRule.onNodeWithTag("open_actions_menu").performClick()
        awaitTag(TAG_WORD_MEANINGS_MENU)
        assertTrue(
            "all of 112 is learned, so Full Surah would be the same list",
            composeRule.onAllNodesWithTag(playbackModeTag(PlaybackMode.FULL_SURAH)).fetchSemanticsNodes().isEmpty(),
        )
        // Close the menu by choosing something harmless, then move to the partly learned surah.
        composeRule.onNodeWithTag(TAG_WORD_MEANINGS_MENU).performClick()
        composeRule.runOnIdle { model().jumpToSurah(113) }
        composeRule.waitUntil(10_000) { state().currentTrack?.surah == 113 }
        composeRule.onNodeWithTag("open_actions_menu").performClick()
        awaitTag(playbackModeTag(PlaybackMode.FULL_SURAH))
    }

    @Test
    fun repeat_is_one_choice_of_four_and_word_keeps_the_whole_ayah_on_screen() {
        awaitTag(TAG_EXPAND_PANEL)
        composeRule.onNodeWithTag(TAG_EXPAND_PANEL).performClick()
        // Several learned ayahs in this surah: the chip says what "Surah" repeats.
        composeRule.onNodeWithTag(repeatModeTag(RepeatMode.SURAH)).performScrollTo().assertTextEquals("Surah learned ayahs")

        composeRule.onNodeWithTag(repeatModeTag(RepeatMode.AYAH)).performScrollTo().performClick()
        composeRule.waitForIdle()
        assertEquals(RepeatMode.AYAH, PlayerSettings.repeatMode)

        composeRule.onNodeWithTag(TAG_REPEAT_WORD).performScrollTo().performClick()
        composeRule.waitUntil(5_000) { state().wordPlayback }
        assertTrue(PlayerSettings.repeatWord)
        assertEquals("Word replaces the other repeat choices", RepeatMode.OFF, PlayerSettings.repeatMode)
        // What is on screen is not changed by what plays.
        assertFalse(PlayerSettings.wordView)
        composeRule.onNodeWithTag(TAG_FULLSCREEN_WHOLE_AYAH).assertIsDisplayed()
        assertTrue(composeRule.onAllNodesWithTag(TAG_WORD_PAGER).fetchSemanticsNodes().isEmpty())

        composeRule.onNodeWithTag(repeatModeTag(RepeatMode.SURAH)).performScrollTo().performClick()
        composeRule.waitUntil(5_000) { !state().wordPlayback }
        assertFalse(PlayerSettings.repeatWord)
        assertEquals(RepeatMode.SURAH, PlayerSettings.repeatMode)
    }

    @Test
    fun while_words_play_next_steps_a_word_and_the_outer_button_an_ayah() {
        awaitTag(TAG_EXPAND_PANEL)
        assertTrue(composeRule.onAllNodesWithTag(TAG_NEXT_AYAH).fetchSemanticsNodes().isEmpty())
        composeRule.onNodeWithTag(TAG_EXPAND_PANEL).performClick()
        composeRule.onNodeWithTag(TAG_REPEAT_WORD).performScrollTo().performClick()
        composeRule.waitUntil(5_000) { state().wordPlayback }
        awaitTag(TAG_NEXT_AYAH)
        val ayah = state().currentTrack!!.globalId
        val word = state().currentWordIndex
        composeRule.onNodeWithTag(TAG_FULLSCREEN_NEXT).performClick()
        composeRule.waitUntil(5_000) { state().currentWordIndex == word + 1 }
        assertEquals("Next steps a word, not an ayah", ayah, state().currentTrack!!.globalId)
        composeRule.onNodeWithTag(TAG_NEXT_AYAH).performClick()
        composeRule.waitUntil(5_000) { state().currentTrack!!.globalId != ayah }
        assertEquals(0, state().currentWordIndex)
        composeRule.runOnIdle { model().pause() }
    }

    @Test
    fun word_clips_take_the_place_of_the_reciter() {
        awaitTag(TAG_EXPAND_PANEL)
        composeRule.onNodeWithTag(TAG_EXPAND_PANEL).performClick()
        awaitTag(TAG_PLAYER_RECITER)
        composeRule.onNodeWithTag(TAG_USE_WORD_CLIPS).performScrollTo().performClick()
        composeRule.waitUntil(5_000) { state().wordPlayback }
        assertTrue(PlayerSettings.useWordClips)
        assertTrue(composeRule.onAllNodesWithTag(TAG_PLAYER_RECITER).fetchSemanticsNodes().isEmpty())
        composeRule.onNodeWithTag(TAG_USE_WORD_CLIPS).performScrollTo().performClick()
        composeRule.waitUntil(5_000) { !state().wordPlayback }
        awaitTag(TAG_PLAYER_RECITER)
    }

    @Test
    fun the_surah_screen_play_button_opens_revise_on_that_surah() {
        reachHome()
        composeRule.onNodeWithTag(surahRowTag(1)).performClick()
        awaitTag(TAG_SURAH_DETAIL)
        composeRule.onNodeWithTag(TAG_SURAH_REVISE).performClick()
        awaitTag(TAG_EXPAND_PANEL)
        composeRule.waitUntil(10_000) { state().currentTrack?.surah == 1 && state().mode == PlaybackMode.FULL_SURAH }
        assertEquals(1, state().currentTrack?.ayah)
        composeRule.runOnIdle { model().pause() }
    }
}
