package com.quran.learnedplayer

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.quran.learnedplayer.data.AyahMapping
import com.quran.learnedplayer.data.LearnedAyahsStore
import com.quran.learnedplayer.player.PlayerSettings
import com.quran.learnedplayer.ui.home.TAG_SURAH_LIST
import com.quran.learnedplayer.ui.home.surahRowTag
import com.quran.learnedplayer.ui.home.surahSelectAllTag
import com.quran.learnedplayer.ui.surah.TAG_SELECT_ALL_AYAHS
import com.quran.learnedplayer.ui.surah.TAG_SURAH_DETAIL
import com.quran.learnedplayer.ui.surah.markLearnedTag
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The headline new feature: marking revision ayahs straight from the surah view. */
@RunWith(AndroidJUnit4::class)
class MarkLearnedTest : BaseAppTest() {

    private val ikhlasAyah1 = AyahMapping.surahAyahToGlobal(112, 1)

    private fun openSurah112() {
        reachHome()
        composeRule.onNodeWithTag(TAG_SURAH_LIST)
            .performScrollToNode(androidx.compose.ui.test.hasTestTag(surahRowTag(112)))
        composeRule.onNodeWithTag(surahRowTag(112)).performClick()
        awaitTag(TAG_SURAH_DETAIL)
    }

    @Test
    fun surah_detail_shows_every_ayah_with_arabic_text() {
        openSurah112()
        // Al-Ikhlas has 4 ayahs; text comes from the bundled asset, so this works offline.
        composeRule.onNodeWithTag(markLearnedTag(1)).assertIsDisplayed()
        composeRule.onNodeWithTag(markLearnedTag(4)).assertIsDisplayed()
        composeRule.onNodeWithText("4 ayahs · 0 learned").assertIsDisplayed()
    }

    @Test
    fun surah_list_shows_word_meanings_when_the_setting_is_on() {
        openSurah112()
        // 112:1 is "Say / He / (is) Allah / the One".
        composeRule.onNodeWithText("(is) Allah").assertIsDisplayed()
    }

    @Test
    fun turning_word_translations_off_hides_them_in_the_surah_list() {
        PlayerSettings.showWordTranslations = false
        openSurah112()
        composeRule.onAllNodesWithText("(is) Allah").assertCountEquals(0)
        // The Arabic itself is still there.
        composeRule.onNodeWithTag(markLearnedTag(1)).assertIsDisplayed()
    }

    @Test
    fun tapping_the_circle_marks_an_ayah_learned() {
        openSurah112()
        assertFalse(LearnedAyahsStore.isLearned(ikhlasAyah1))

        composeRule.onNodeWithTag(markLearnedTag(1)).performClick()
        composeRule.waitForIdle()

        assertTrue("selection must persist to the store", LearnedAyahsStore.isLearned(ikhlasAyah1))
        composeRule.onNodeWithText("4 ayahs · 1 learned").assertIsDisplayed()
    }

    @Test
    fun tapping_again_unmarks_it() {
        openSurah112()
        composeRule.onNodeWithTag(markLearnedTag(1)).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(markLearnedTag(1)).performClick()
        composeRule.waitForIdle()

        assertFalse(LearnedAyahsStore.isLearned(ikhlasAyah1))
        composeRule.onNodeWithText("4 ayahs · 0 learned").assertIsDisplayed()
    }

    @Test
    fun marked_ayahs_survive_an_activity_restart() {
        openSurah112()
        composeRule.onNodeWithTag(markLearnedTag(2)).performClick()
        composeRule.waitForIdle()
        val marked = AyahMapping.surahAyahToGlobal(112, 2)
        assertTrue(LearnedAyahsStore.isLearned(marked))

        // Re-read from disk the way a cold launch would.
        LearnedAyahsStore.init(InstrumentationRegistry.getInstrumentation().targetContext)
        assertTrue("must be persisted, not just in memory", LearnedAyahsStore.isLearned(marked))
    }

    @Test
    fun marking_shows_a_learned_badge_on_the_home_list() {
        openSurah112()
        composeRule.onNodeWithTag(markLearnedTag(1)).performClick()
        composeRule.waitForIdle()
        androidx.test.espresso.Espresso.pressBack()

        awaitTag(TAG_SURAH_LIST)
        composeRule.onNodeWithTag(TAG_SURAH_LIST)
            .performScrollToNode(androidx.compose.ui.test.hasTestTag(surahRowTag(112)))
        composeRule.onNodeWithText("1 learned").assertIsDisplayed()
    }

    /** Whole surahs are how people describe what they know; 30 taps to record one is a chore. */
    @Test
    fun surah_list_tick_marks_every_ayah_of_that_surah() {
        reachHome()
        composeRule.onNodeWithTag(TAG_SURAH_LIST)
            .performScrollToNode(androidx.compose.ui.test.hasTestTag(surahSelectAllTag(112)))
        composeRule.onNodeWithTag(surahSelectAllTag(112)).performClick()
        composeRule.waitForIdle()

        val all = (1..4).map { AyahMapping.surahAyahToGlobal(112, it) }
        all.forEach { assertTrue("112 must be fully marked", LearnedAyahsStore.isLearned(it)) }

        // A second tap clears it — but only because it is now complete.
        composeRule.onNodeWithTag(surahSelectAllTag(112)).performClick()
        composeRule.waitForIdle()
        all.forEach { assertFalse("second tap clears the surah", LearnedAyahsStore.isLearned(it)) }
    }

    /**
     * A partly-marked surah completes rather than clearing, so the tick can never silently discard
     * ayahs the user had already marked one at a time.
     */
    @Test
    fun surah_list_tick_completes_a_partly_marked_surah() {
        LearnedAyahsStore.addAll(setOf(ikhlasAyah1))
        reachHome()
        composeRule.onNodeWithTag(TAG_SURAH_LIST)
            .performScrollToNode(androidx.compose.ui.test.hasTestTag(surahSelectAllTag(112)))
        composeRule.onNodeWithTag(surahSelectAllTag(112)).performClick()
        composeRule.waitForIdle()

        (1..4).forEach {
            assertTrue(LearnedAyahsStore.isLearned(AyahMapping.surahAyahToGlobal(112, it)))
        }
    }

    @Test
    fun surah_detail_marks_and_clears_every_ayah() {
        openSurah112()
        composeRule.onNodeWithTag(TAG_SELECT_ALL_AYAHS).performClick()
        composeRule.waitForIdle()
        (1..4).forEach {
            assertTrue(LearnedAyahsStore.isLearned(AyahMapping.surahAyahToGlobal(112, it)))
        }

        composeRule.onNodeWithTag(TAG_SELECT_ALL_AYAHS).performClick()
        composeRule.waitForIdle()
        (1..4).forEach {
            assertFalse(LearnedAyahsStore.isLearned(AyahMapping.surahAyahToGlobal(112, it)))
        }
    }
}
