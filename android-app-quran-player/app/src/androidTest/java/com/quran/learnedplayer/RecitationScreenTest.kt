package com.quran.learnedplayer

import android.Manifest
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.quran.learnedplayer.data.AyahMapping
import com.quran.learnedplayer.player.PlayerSettings
import com.quran.learnedplayer.ui.TAG_OPEN_RECITATION
import com.quran.learnedplayer.ui.TAG_OPEN_SETTINGS
import com.quran.learnedplayer.ui.recitation.TAG_NEXT_AYAH
import com.quran.learnedplayer.ui.recitation.TAG_PREV_AYAH
import com.quran.learnedplayer.ui.recitation.TAG_RECITATION_SCREEN
import com.quran.learnedplayer.ui.settings.TAG_RECITE_BETA_TOGGLE
import com.quran.learnedplayer.ui.settings.TAG_SETTINGS_SCREEN
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecitationScreenTest : BaseAppTest() {

    @get:Rule
    val grantPermissionRule: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)

    override fun seedLearnedAyahs(): Set<Int> =
        setOf(AyahMapping.surahAyahToGlobal(1, 1))

    @Test
    fun openRecitationScreen_displaysRecitationHeaderAndControls() {
        // Recite is opt-in; BaseAppTest leaves it off so every other test sees the app as a new
        // user does. Tests that drive the screen turn it on for themselves.
        PlayerSettings.reciteBetaEnabled = true

        // Wait for player screen to load seeded queue
        awaitTag(TAG_OPEN_RECITATION)

        // Click "Recite & review" button above bottom play panel
        composeRule.onNodeWithTag(TAG_OPEN_RECITATION).performClick()

        // Verify Recitation screen is displayed
        awaitTag(TAG_RECITATION_SCREEN)
        composeRule.onNodeWithTag(TAG_RECITATION_SCREEN).assertIsDisplayed()

        // Verify Recitation navigation controls
        composeRule.onNodeWithTag(TAG_NEXT_AYAH).assertIsDisplayed()
        composeRule.onNodeWithTag(TAG_PREV_AYAH).assertIsDisplayed()

        // Verify Surah title is displayed
        composeRule.onNodeWithText("Al-Fatihah", substring = true).assertIsDisplayed()
    }

    /**
     * Settings offers Recite as an opt-in beta.
     *
     * Matched by tag, not by text: "Recite & review" appears in the section heading, the toggle
     * label and the word-clips button, so a substring matcher finds dozens of nodes.
     */
    @Test
    fun settings_offersReciteAsAnOptInBeta() {
        openSettings()
        composeRule.onNodeWithTag(TAG_RECITE_BETA_TOGGLE)
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("Beta", substring = false)
            .assertIsDisplayed()
    }

    /**
     * Recite is a beta the user opts in to, so a fresh install must not show it at all — the
     * entry point on the player only appears once it has been switched on in Settings.
     */
    @Test
    fun freshInstall_hidesReciteUntilOptedIn() {
        assertFalse(PlayerSettings.reciteBetaEnabled)
        composeRule.onAllNodes(hasTestTag(TAG_OPEN_RECITATION)).assertCountEquals(0)

        PlayerSettings.reciteBetaEnabled = true
        awaitTag(TAG_OPEN_RECITATION)
        composeRule.onNodeWithTag(TAG_OPEN_RECITATION).performClick()

        awaitTag(TAG_RECITATION_SCREEN)
        composeRule.onNodeWithTag(TAG_RECITATION_SCREEN).assertIsDisplayed()
        composeRule.onNodeWithTag(TAG_NEXT_AYAH).assertIsDisplayed()
    }

    private fun openSettings() {
        awaitTag(TAG_OPEN_SETTINGS)
        composeRule.onNodeWithTag(TAG_OPEN_SETTINGS).performClick()
        awaitTag(TAG_SETTINGS_SCREEN)
    }
}
