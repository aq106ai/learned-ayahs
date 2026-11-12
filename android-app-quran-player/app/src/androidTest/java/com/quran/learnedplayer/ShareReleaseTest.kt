package com.quran.learnedplayer

import android.content.ClipboardManager
import android.content.Intent
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.quran.learnedplayer.data.AyahMapping
import com.quran.learnedplayer.ui.TAG_FULLSCREEN_WHOLE_AYAH
import com.quran.learnedplayer.ui.TAG_SHARE
import com.quran.learnedplayer.ui.player.ShareImage
import com.quran.learnedplayer.ui.player.TAG_SHARE_TEXT_ARABIC
import com.quran.learnedplayer.ui.player.TAG_SHARE_TEXT_ENGLISH
import com.quran.learnedplayer.ui.player.TAG_SHARE_VIDEO
import com.quran.learnedplayer.ui.video.TAG_VIDEO_SHARE_TEXT
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ShareReleaseTest : BaseAppTest() {
    override fun seedLearnedAyahs(): Set<Int> = (1..4).map { AyahMapping.surahAyahToGlobal(112, it) }.toSet()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun copiedText(): String {
        var text = ""
        try {
            composeRule.waitUntil(20_000) {
                composeRule.runOnIdle {
                    text = composeRule.activity.getSystemService(ClipboardManager::class.java).primaryClip
                        ?.getItemAt(0)?.coerceToText(composeRule.activity)?.toString().orEmpty()
                }
                text.contains("112:")
            }
        } catch (e: ComposeTimeoutException) {
            throw AssertionError("Clipboard was '$text'; focus=${composeRule.activity.hasWindowFocus()}. " +
                composeRule.onRoot().printToString(), e)
        }
        return text
    }

    @Test
    fun player_copies_both_languages_or_either_language_alone() {
        awaitTag(TAG_FULLSCREEN_WHOLE_AYAH)
        for (omit in listOf(null, TAG_SHARE_TEXT_ENGLISH, TAG_SHARE_TEXT_ARABIC)) {
            composeRule.runOnIdle { context.getSystemService(ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText("", "")) }
            composeRule.onNodeWithTag("open_actions_menu").performClick()
            composeRule.onNodeWithTag(TAG_SHARE).performClick()
            if (omit != null) composeRule.onNodeWithTag(omit).performScrollTo().performClick()
            composeRule.onNodeWithTag("copy_text").performScrollTo().performClick()
            val text = copiedText()
            assertTrue(text.contains("112:1"))
            assertTrue("Arabic selection: $text", text.any { it in '\u0600'..'\u06ff' } == (omit != TAG_SHARE_TEXT_ARABIC))
            assertTrue("English selection: $text", text.contains("Say") == (omit != TAG_SHARE_TEXT_ENGLISH))
            // Copying closes the dialog a moment after the clipboard is set. Reopening before it
            // has gone reuses the old dialog, with the last round's language still switched off.
            composeRule.waitUntil(5_000) {
                composeRule.onAllNodes(androidx.compose.ui.test.hasTestTag(com.quran.learnedplayer.ui.player.TAG_SHARE_DIALOG)).fetchSemanticsNodes().isEmpty()
            }
        }
    }

    @Test
    fun video_range_can_be_copied_before_exporting() {
        awaitTag(TAG_FULLSCREEN_WHOLE_AYAH)
        composeRule.onNodeWithTag("open_actions_menu").performClick()
        composeRule.onNodeWithTag(TAG_SHARE).performClick()
        composeRule.onNodeWithTag(TAG_SHARE_VIDEO).performClick()
        composeRule.onNodeWithText("Whole surah").performScrollTo().performClick()
        composeRule.onNodeWithTag(TAG_VIDEO_SHARE_TEXT).performScrollTo().performClick()
        composeRule.onNodeWithTag("video_copy_text").performClick()
        assertTrue(copiedText().contains("112:1-4"))
    }

    @Test
    @Suppress("DEPRECATION")
    fun image_share_has_a_file_and_no_automatic_text() {
        val file = File(context.cacheDir, "images/test-intent.jpg").apply { parentFile?.mkdirs(); writeBytes(byteArrayOf(1)) }
        try {
            val chooser = ShareImage.shareIntent(context, file)
            val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
            assertTrue(send.hasExtra(Intent.EXTRA_STREAM))
            assertFalse(send.hasExtra(Intent.EXTRA_TEXT))
        } finally { file.delete() }
    }
}
