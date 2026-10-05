package com.quran.learnedplayer

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.quran.learnedplayer.ui.home.TAG_SEARCH_FIELD
import com.quran.learnedplayer.ui.home.TAG_SURAH_LIST
import com.quran.learnedplayer.ui.home.surahRowTag
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeBrowseTest : BaseAppTest() {

    @Test
    fun home_opens_on_the_surah_list_without_any_storage_permission() {
        // The whole point of the in-app default: no Downloads access, no error, still usable.
        reachHome()
        composeRule.onNodeWithTag(surahRowTag(1)).assertIsDisplayed()
        composeRule.onNodeWithText("Al-Fatihah").assertIsDisplayed()
    }

    @Test
    fun all_114_surahs_are_listed() {
        reachHome()
        composeRule.onNodeWithTag(TAG_SURAH_LIST)
            .performScrollToNode(hasTestTag(surahRowTag(114)))
        composeRule.onNodeWithTag(surahRowTag(114)).assertIsDisplayed()
        composeRule.onNodeWithText("An-Nas").assertIsDisplayed()
    }

    @Test
    fun search_filters_by_name() {
        reachHome()
        composeRule.onNodeWithTag(TAG_SEARCH_FIELD).performTextInput("Ikhlas")
        composeRule.onNodeWithTag(surahRowTag(112)).assertIsDisplayed()
        composeRule.onNodeWithTag(surahRowTag(1)).assertDoesNotExist()
    }

    @Test
    fun search_filters_by_number() {
        reachHome()
        composeRule.onNodeWithTag(TAG_SEARCH_FIELD).performTextInput("36")
        composeRule.onNodeWithTag(surahRowTag(36)).assertIsDisplayed()
        composeRule.onNodeWithTag(surahRowTag(1)).assertDoesNotExist()
    }
}
