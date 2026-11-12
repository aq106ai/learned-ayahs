package com.quran.learnedplayer

import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.platform.app.InstrumentationRegistry
import com.quran.learnedplayer.data.AyahMapping
import com.quran.learnedplayer.player.*
import com.quran.learnedplayer.ui.*
import org.junit.Assert.*
import org.junit.Test

class ReaderLayoutTest : BaseAppTest() {
    override fun seedLearnedAyahs() = setOf(AyahMapping.surahAyahToGlobal(2, 282))
    override fun beforeLaunch() {
        PlayerSettings.uiLanguage = InstrumentationRegistry.getArguments().getString("readerLocale", "en")
        com.quran.learnedplayer.ui.i18n.UiStrings.use(InstrumentationRegistry.getInstrumentation().targetContext, PlayerSettings.uiLanguage)
        PlayerSettings.showAyahTranslation = true
        PlayerSettings.showWordTranslations = true
    }
    private fun capture(label: String) {
        composeRule.waitForIdle()
        val inst = InstrumentationRegistry.getInstrumentation()
        val config = inst.targetContext.resources.configuration
        val file = java.io.File(inst.targetContext.getExternalFilesDir(null), "reader-${config.screenWidthDp}-${config.screenHeightDp}-${config.fontScale}-$label.png")
        inst.uiAutomation.takeScreenshot()?.let { image ->
            file.outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            image.recycle()
        }
    }
    @Test fun longAyahStartsAtTopAndTranslationRemainsReachableInBothModes() {
        awaitTag(TAG_FULLSCREEN_WHOLE_AYAH)
        for (mode in AyahOverflowMode.entries) {
            composeRule.runOnIdle { PlayerSettings.ayahOverflowMode = mode }
            awaitTag("ayah_reader_scroll")
            composeRule.waitForIdle()
            val range = composeRule.onNodeWithTag("ayah_reader_scroll").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
            assertEquals(0f, range.value(), 1f)
            capture("${mode.name}-start")
            composeRule.onNodeWithTag(TAG_AYAH_TRANSLATION).performScrollTo().assertIsDisplayed()
            capture("${mode.name}-end")
        }
    }
    @Test fun wordMeaningsSwitchActuallyHidesWordPageTranslation() {
        awaitTag("open_actions_menu")
        tapMenuItem(TAG_TOGGLE_WORD_BY_WORD)
        awaitTag(TAG_WORD_PAGER)
        composeRule.runOnIdle { PlayerSettings.showWordTranslations = false }
        composeRule.onAllNodesWithTag(TAG_WORD_TRANSLATION).assertCountEquals(0)
        composeRule.runOnIdle { PlayerSettings.showWordTranslations = true }
        composeRule.waitUntil(5000) { composeRule.onAllNodesWithTag(TAG_WORD_TRANSLATION).fetchSemanticsNodes().isNotEmpty() }
        capture("word")
    }
    @Test fun modeAndActionsMenusRemainReachableWithoutStartingPlayback() {
        awaitTag("open_actions_menu")
        composeRule.onNodeWithTag("open_actions_menu").performScrollTo().performClick()
        composeRule.onNodeWithTag(playbackModeTag(PlaybackMode.WORD_BY_WORD)).assertDoesNotExist()
        composeRule.onNodeWithTag(playbackModeTag(PlaybackMode.FULL_SURAH)).assertIsDisplayed().performClick()
        assertEquals(PlaybackMode.FULL_SURAH, PlayerSettings.playbackMode)
        tapMenuItem(TAG_TOGGLE_WORD_BY_WORD)
        awaitTag(TAG_WORD_PAGER)
        assertEquals(PlaybackMode.FULL_SURAH, PlayerSettings.playbackMode)
        assertFalse(com.quran.learnedplayer.service.PlayerStateHolder.uiState.value.isPlaying)
        tapMenuItem("view_ayah")
        composeRule.onNodeWithTag(TAG_WORD_PAGER).assertDoesNotExist()
        composeRule.onNodeWithTag("open_actions_menu").performScrollTo().performClick()
        composeRule.onNodeWithTag(TAG_OPEN_TAFSIR).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(TAG_SHARE).performScrollTo().assertIsDisplayed()
        capture("actions")
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
    }
}
