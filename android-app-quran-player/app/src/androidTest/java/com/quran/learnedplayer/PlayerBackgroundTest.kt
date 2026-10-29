package com.quran.learnedplayer

import android.accessibilityservice.AccessibilityService
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.quran.learnedplayer.data.AyahMapping
import com.quran.learnedplayer.player.PlayerSettings
import com.quran.learnedplayer.ui.TAG_AYAH_TRANSLATION
import com.quran.learnedplayer.ui.TAG_FULLSCREEN_WHOLE_AYAH
import com.quran.learnedplayer.ui.TAG_OPEN_BACKGROUND
import com.quran.learnedplayer.ui.TAG_PLAYER_BACKGROUND
import com.quran.learnedplayer.ui.TAG_READER_CARD
import com.quran.learnedplayer.ui.TAG_SHARE
import com.quran.learnedplayer.ui.player.TAG_BACKGROUND_SHEET
import com.quran.learnedplayer.ui.player.TAG_SHARE_DIALOG
import com.quran.learnedplayer.ui.player.TAG_SHARE_IMAGE
import com.quran.learnedplayer.ui.player.TAG_SHARE_VIDEO
import com.quran.learnedplayer.ui.player.backgroundChoiceTag
import com.quran.learnedplayer.ui.video.TAG_VIDEO_SCREEN
import com.quran.learnedplayer.video.PlayerBackgrounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The player's backgrounds and its Share button. None of it needs the network: the drawn scenes
 * and the translation are in the app, and nothing here starts playback.
 */
@RunWith(AndroidJUnit4::class)
class PlayerBackgroundTest : BaseAppTest() {

    override fun seedLearnedAyahs(): Set<Int> =
        (1..4).map { AyahMapping.surahAyahToGlobal(112, it) }.toSet()

    @Test
    fun the_player_is_plain_until_a_background_is_chosen() {
        awaitTag(TAG_FULLSCREEN_WHOLE_AYAH)
        assertTrue(composeRule.onAllNodesWithTag(TAG_PLAYER_BACKGROUND).fetchSemanticsNodes().isEmpty())
        assertTrue(composeRule.onAllNodesWithTag(TAG_AYAH_TRANSLATION).fetchSemanticsNodes().isEmpty())

        composeRule.onNodeWithTag(TAG_OPEN_BACKGROUND).performClick()
        awaitTag(TAG_BACKGROUND_SHEET)
        composeRule.onNodeWithTag(backgroundChoiceTag(PlayerBackgrounds.MATCHED)).performClick()
        composeRule.waitForIdle()

        assertEquals(PlayerBackgrounds.MATCHED, PlayerSettings.playerBackground)
        assertTrue("choosing a background brings the translation with it", PlayerSettings.showAyahTranslation)
        awaitTag(TAG_PLAYER_BACKGROUND)
        awaitTag(TAG_READER_CARD)
        // 112:1 in Saheeh International.
        composeRule.waitUntil(20_000) {
            composeRule.onAllNodesWithText("He is Allah", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun share_image_draws_the_ayah_as_a_picture() {
        val images = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "images")
        images.deleteRecursively()
        awaitTag(TAG_FULLSCREEN_WHOLE_AYAH)
        awaitTag(TAG_SHARE)
        composeRule.onNodeWithTag(TAG_SHARE).performClick()
        awaitTag(TAG_SHARE_DIALOG)
        composeRule.onNodeWithTag(TAG_SHARE_IMAGE).performClick()

        // Drawn off the main thread; then the system's share sheet opens over the app.
        val deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadline && images.listFiles().isNullOrEmpty()) Thread.sleep(250)
        val picture = images.listFiles()?.singleOrNull()
        assertTrue("an image of the ayah is written to share", picture != null && picture.length() > 10_000)
        assertTrue(picture!!.name.endsWith(".jpg"))

        // Close the system share sheet, so it isn't left over the next test's screen.
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        composeRule.waitForIdle()
    }

    @Test
    fun share_video_opens_the_video_maker() {
        awaitTag(TAG_FULLSCREEN_WHOLE_AYAH)
        awaitTag(TAG_SHARE)
        composeRule.onNodeWithTag(TAG_SHARE).performClick()
        awaitTag(TAG_SHARE_DIALOG)
        composeRule.onNodeWithTag(TAG_SHARE_VIDEO).performClick()
        awaitTag(TAG_VIDEO_SCREEN)
    }
}
