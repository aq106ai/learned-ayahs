package com.quran.learnedplayer

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.quran.learnedplayer.data.AyahMapping
import com.quran.learnedplayer.data.LearnedAyahsStore
import com.quran.learnedplayer.player.PlayerSettings
import com.quran.learnedplayer.ui.TAG_EMPTY_BROWSE
import com.quran.learnedplayer.ui.home.TAG_ADD_BY_DESCRIPTION
import com.quran.learnedplayer.ui.home.TAG_INTRO_DIALOG
import com.quran.learnedplayer.ui.home.TAG_INTRO_DISMISS
import com.quran.learnedplayer.ui.home.TAG_INTRO_NEXT
import com.quran.learnedplayer.ui.home.TAG_SURAH_LIST
import com.quran.learnedplayer.ui.home.surahRowTag
import com.quran.learnedplayer.ui.settings.TAG_DESCRIPTION_APPLY
import com.quran.learnedplayer.ui.settings.TAG_DESCRIPTION_FIELD
import com.quran.learnedplayer.ui.settings.TAG_DESCRIPTION_PREVIEW
import com.quran.learnedplayer.ui.surah.markLearnedTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IntroAndAddByDescriptionTest : BaseAppTest() {

    private fun openAddByDescription() {
        reachHome()
        composeRule.onNodeWithTag(TAG_ADD_BY_DESCRIPTION).performClick()
        awaitTag(TAG_DESCRIPTION_FIELD)
    }

    @Test
    fun quick_add_marks_the_ayahs_it_previews() {
        openAddByDescription()
        composeRule.onNodeWithTag(TAG_DESCRIPTION_FIELD).performTextInput("112, 2:255")
        composeRule.waitForIdle()

        // 4 ayahs of Al-Ikhlas + Ayat al-Kursi.
        composeRule.onNodeWithTag(TAG_DESCRIPTION_PREVIEW)
            .assertTextContains("Will mark 5 ayahs", substring = true)

        composeRule.onNodeWithTag(TAG_DESCRIPTION_APPLY).performClick()
        composeRule.waitForIdle()

        assertEquals(5, LearnedAyahsStore.learnedIds.value.size)
        assertTrue(AyahMapping.surahAyahToGlobal(2, 255) in LearnedAyahsStore.learnedIds.value)
    }

    @Test
    fun added_ayahs_show_as_ticked_in_the_surah_list() {
        openAddByDescription()
        composeRule.onNodeWithTag(TAG_DESCRIPTION_FIELD).performTextInput("112")
        composeRule.onNodeWithTag(TAG_DESCRIPTION_APPLY).performClick()
        composeRule.waitForIdle()
        // Typing raised the soft keyboard, which eats the first back press — dismiss it first.
        // Add by description is pushed straight from Home now, so one back press is enough.
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        composeRule.waitForIdle()
        androidx.test.espresso.Espresso.pressBack()

        awaitTag(TAG_SURAH_LIST)
        composeRule.onNodeWithTag(TAG_SURAH_LIST)
            .performScrollToNode(androidx.compose.ui.test.hasTestTag(surahRowTag(112)))
        composeRule.onNodeWithText("4 learned").assertIsDisplayed()

        composeRule.onNodeWithTag(surahRowTag(112)).performClick()
        composeRule.waitForIdle()
        // Every ayah of Al-Ikhlas is now marked.
        composeRule.onNodeWithText("4 ayahs · 4 learned").assertIsDisplayed()
    }

    @Test
    fun unreadable_input_is_reported_rather_than_silently_ignored() {
        openAddByDescription()
        composeRule.onNodeWithTag(TAG_DESCRIPTION_FIELD).performTextInput("112, wibble")
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(TAG_DESCRIPTION_PREVIEW)
            .assertTextContains("Ignored", substring = true)
    }

    @Test
    fun llm_json_can_be_pasted_straight_in() {
        openAddByDescription()
        composeRule.onNodeWithTag(TAG_DESCRIPTION_FIELD)
            .performTextInput("""{"format": "learned-ayahs", "ayahs": ["112", "2:255"]}""")
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(TAG_DESCRIPTION_APPLY).performClick()
        composeRule.waitForIdle()
        assertEquals(5, LearnedAyahsStore.learnedIds.value.size)
    }

    @Test
    fun the_first_run_guide_walks_three_pages_and_then_never_shows_again() {
        // BaseAppTest pre-dismisses the intro for every other test; opt back in here.
        PlayerSettings.seenIntro = false
        composeRule.activityRule.scenario.recreate()

        awaitTag(TAG_INTRO_DIALOG)
        composeRule.onNodeWithText("Sahih al-Bukhari 5033", substring = true).assertIsDisplayed()

        composeRule.onNodeWithTag(TAG_INTRO_NEXT).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Mark what you know").assertIsDisplayed()

        composeRule.onNodeWithTag(TAG_INTRO_NEXT).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Revise and read").assertIsDisplayed()

        composeRule.onNodeWithTag(TAG_INTRO_DISMISS).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(TAG_INTRO_DIALOG).assertDoesNotExist()
        assertTrue(PlayerSettings.seenIntro)

        // A relaunch must not show it again — and now that onboarding is done, it opens
        // straight on the player (empty here, since nothing was marked) rather than the picker.
        composeRule.activityRule.scenario.recreate()
        awaitTag(TAG_EMPTY_BROWSE)
        composeRule.onNodeWithTag(TAG_SURAH_LIST).assertDoesNotExist()
        composeRule.onNodeWithTag(TAG_INTRO_DIALOG).assertDoesNotExist()
    }

    @Test
    fun skipping_the_guide_on_the_first_page_also_marks_it_seen() {
        PlayerSettings.seenIntro = false
        composeRule.activityRule.scenario.recreate()

        awaitTag(TAG_INTRO_DIALOG)
        composeRule.onNodeWithText("Skip").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(TAG_INTRO_DIALOG).assertDoesNotExist()
        assertTrue(PlayerSettings.seenIntro)
    }
}
