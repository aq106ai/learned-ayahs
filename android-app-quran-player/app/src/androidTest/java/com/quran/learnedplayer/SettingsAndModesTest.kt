package com.quran.learnedplayer

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.quran.learnedplayer.data.AppTheme
import com.quran.learnedplayer.data.AyahMapping
import com.quran.learnedplayer.data.Reciter
import com.quran.learnedplayer.player.PlaybackMode
import com.quran.learnedplayer.player.PlayerSettings
import com.quran.learnedplayer.player.RepeatMode
import com.quran.learnedplayer.ui.TAG_EXPAND_PANEL
import com.quran.learnedplayer.ui.TAG_OPEN_SETTINGS
import com.quran.learnedplayer.ui.repeatModeTag
import com.quran.learnedplayer.ui.revisionDelayTag
import com.quran.learnedplayer.ui.settings.TAG_SETTINGS_SCREEN
import com.quran.learnedplayer.ui.settings.TAG_SWIPE_TO_NAVIGATE_TOGGLE
import com.quran.learnedplayer.ui.settings.TAG_TAP_TO_ADVANCE_TOGGLE
import com.quran.learnedplayer.ui.settings.appThemeTag
import com.quran.learnedplayer.ui.settings.reciterTag
import com.quran.learnedplayer.ui.theme.DarkGreenPalette
import com.quran.learnedplayer.ui.theme.LightGreenPalette
import com.quran.learnedplayer.ui.theme.currentPalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsAndModesTest : BaseAppTest() {

    private fun openSettings() {
        awaitTag(TAG_OPEN_SETTINGS)
        composeRule.onNodeWithTag(TAG_OPEN_SETTINGS).performClick()
        awaitTag(TAG_SETTINGS_SCREEN)
    }

    @Test
    fun revision_delay_choice_persists() {
        // The pause control lives only on the player now; it only shows once repeat=Ayah
        // is selected, so the player is already the default screen here.
        awaitTag(TAG_EXPAND_PANEL)
        composeRule.onNodeWithTag(TAG_EXPAND_PANEL).performClick()
        composeRule.onNodeWithTag(repeatModeTag(RepeatMode.AYAH)).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(revisionDelayTag(5)).performClick()
        composeRule.waitForIdle()
        assertEquals(5, PlayerSettings.revisionDelaySeconds)
    }

    @Test
    fun default_reciter_is_maher_and_switching_persists() {
        openSettings()
        assertEquals(Reciter.MAHER_AL_MUAIQLY, PlayerSettings.reciter)

        composeRule.onNodeWithTag(reciterTag(Reciter.AL_HUSARY))
            .performScrollTo().performClick()
        composeRule.waitForIdle()

        assertEquals(Reciter.AL_HUSARY, PlayerSettings.reciter)
        // Audio URLs must follow the choice, or playback would still stream the old voice.
        assertTrue(AyahMapping.remoteUrl(1, 1).contains("Husary_128kbps"))
    }

    @Test
    fun theme_defaults_to_dark_and_switching_to_light_applies_and_persists() {
        openSettings()
        assertEquals(AppTheme.DARK, PlayerSettings.appTheme)
        assertEquals(DarkGreenPalette, currentPalette)

        composeRule.onNodeWithTag(appThemeTag(AppTheme.LIGHT)).performScrollTo().performClick()
        composeRule.waitForIdle()

        assertEquals(AppTheme.LIGHT, PlayerSettings.appTheme)
        // The palette is what every screen actually reads — it must follow the setting.
        assertEquals(LightGreenPalette, currentPalette)
    }

    @Test
    fun swipe_to_navigate_toggle_persists() {
        openSettings()
        assertTrue(PlayerSettings.swipeToNavigate)

        composeRule.onNodeWithTag(TAG_SWIPE_TO_NAVIGATE_TOGGLE).performScrollTo().performClick()
        composeRule.waitForIdle()
        assertEquals(false, PlayerSettings.swipeToNavigate)

        composeRule.onNodeWithTag(TAG_SWIPE_TO_NAVIGATE_TOGGLE).performScrollTo().performClick()
        composeRule.waitForIdle()
        assertTrue(PlayerSettings.swipeToNavigate)
    }

    @Test
    fun tap_to_advance_toggle_persists() {
        openSettings()
        assertTrue(PlayerSettings.tapToAdvance)

        composeRule.onNodeWithTag(TAG_TAP_TO_ADVANCE_TOGGLE).performScrollTo().performClick()
        composeRule.waitForIdle()
        assertEquals(false, PlayerSettings.tapToAdvance)

        composeRule.onNodeWithTag(TAG_TAP_TO_ADVANCE_TOGGLE).performScrollTo().performClick()
        composeRule.waitForIdle()
        assertTrue(PlayerSettings.tapToAdvance)
    }

    @Test
    fun playback_mode_and_repeat_mode_are_independent_axes() {
        // Guards the core feature set against the UI rework: two orthogonal enums, not four
        // flat modes.
        assertEquals(3, PlaybackMode.entries.size)
        assertEquals(
            listOf("Learned ayahs", "Full surah", "Word by word"),
            PlaybackMode.entries.map { it.label },
        )
        assertEquals(3, RepeatMode.entries.size)
        assertEquals(
            listOf("Off", "Surah", "Ayah"),
            RepeatMode.entries.map { it.label },
        )
    }
}
