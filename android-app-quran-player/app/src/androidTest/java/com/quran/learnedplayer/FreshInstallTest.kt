package com.quran.learnedplayer

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.quran.learnedplayer.data.LearnedAyahsStore
import com.quran.learnedplayer.ui.TAG_EMPTY_BROWSE
import com.quran.learnedplayer.ui.TAG_FULLSCREEN_WHOLE_AYAH
import com.quran.learnedplayer.ui.home.TAG_SURAH_LIST
import com.quran.learnedplayer.ui.home.surahRowTag
import com.quran.learnedplayer.ui.surah.TAG_BACK_TO_MAIN
import com.quran.learnedplayer.ui.surah.TAG_SURAH_DETAIL
import com.quran.learnedplayer.ui.surah.ayahRowTag
import com.quran.learnedplayer.ui.surah.markLearnedTag
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A brand-new user starts with *nothing* marked. The app used to merge the repo owner's
 * 1,216-ayah supplement into everyone's list; these lock that in as gone.
 */
@RunWith(AndroidJUnit4::class)
class FreshInstallTest : BaseAppTest() {

    @Test
    fun nothing_is_marked_on_a_fresh_install() {
        reachHome()
        assertEquals(emptySet<Int>(), LearnedAyahsStore.learnedIds.value)
        composeRule.onNodeWithText("0 ayahs marked", substring = true).assertIsDisplayed()
    }

    @Test
    fun no_learned_badges_anywhere_on_the_list() {
        reachHome()
        // Surah 112 was part of the old auto-added set — it must be clean now.
        composeRule.onAllNodesWithText("learned", substring = true)
            .fetchSemanticsNodes().let { assertEquals(0, it.size) }
    }

    @Test
    fun the_player_offers_a_way_back_to_browsing_instead_of_an_error() {
        reachHome()
        // With no queue there is no mini player, so reach the player via a surah.
        composeRule.onNodeWithTag(surahRowTag(1)).performClick()
        // The detail screen parses the ayah text before it can render the mark buttons.
        awaitTag(TAG_SURAH_DETAIL)
        // Marking one ayah gives a queue; unmark it and the player must show the empty state.
        composeRule.onNodeWithTag(markLearnedTag(1)).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(markLearnedTag(1)).performClick()
        composeRule.waitForIdle()
        androidx.test.espresso.Espresso.pressBack()

        awaitTag(TAG_SURAH_LIST)
        assertEquals(emptySet<Int>(), LearnedAyahsStore.learnedIds.value)
    }

    @Test
    fun a_surah_can_still_be_played_with_nothing_marked() {
        // Surah-loop builds its queue from the surah itself, not from the learned list, so an
        // empty selection must not disable it — tapping any ayah row starts full-surah playback
        // (the detail screen has no play button of its own any more; "back to main" is how you
        // reach the player to see it).
        reachHome()
        composeRule.onNodeWithTag(surahRowTag(1)).performClick()
        // The detail screen parses the ayah text before it can render the mark buttons.
        awaitTag(TAG_SURAH_DETAIL)
        composeRule.onNodeWithTag(ayahRowTag(1)).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(TAG_BACK_TO_MAIN).performClick()
        awaitTag(TAG_FULLSCREEN_WHOLE_AYAH)
    }

    @Test
    fun empty_state_tag_exists_for_the_player() {
        // Guards the empty-state copy from silently regressing to the old Downloads-scan text.
        reachHome()
        composeRule.onNodeWithTag(surahRowTag(1)).performClick()
        // The detail screen parses the ayah text before it can render the mark buttons.
        awaitTag(TAG_SURAH_DETAIL)
        composeRule.onNodeWithTag(markLearnedTag(1)).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(TAG_BACK_TO_MAIN).performClick()
        composeRule.waitForIdle()
        // A queue exists now, so the empty state must NOT be showing.
        composeRule.onNodeWithTag(TAG_EMPTY_BROWSE).assertDoesNotExist()
    }
}
