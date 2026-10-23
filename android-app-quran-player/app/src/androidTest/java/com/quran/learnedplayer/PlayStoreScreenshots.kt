package com.quran.learnedplayer

import android.graphics.Bitmap
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.quran.learnedplayer.data.AppTheme
import com.quran.learnedplayer.data.AyahMapping
import com.quran.learnedplayer.data.QuranConstants
import com.quran.learnedplayer.player.PlayerSettings
import com.quran.learnedplayer.service.PlayerStateHolder
import com.quran.learnedplayer.ui.TAG_EXPAND_PANEL
import com.quran.learnedplayer.ui.TAG_FULLSCREEN_NEXT
import com.quran.learnedplayer.ui.TAG_FULLSCREEN_WHOLE_AYAH
import com.quran.learnedplayer.ui.TAG_OPEN_SETTINGS
import com.quran.learnedplayer.ui.TAG_TOGGLE_WORD_BY_WORD
import com.quran.learnedplayer.ui.TAG_WORD_PAGER
import com.quran.learnedplayer.ui.home.TAG_ADD_BY_DESCRIPTION
import com.quran.learnedplayer.ui.home.TAG_SURAH_LIST
import com.quran.learnedplayer.ui.home.surahRowTag
import com.quran.learnedplayer.ui.settings.TAG_DESCRIPTION_FIELD
import com.quran.learnedplayer.ui.settings.TAG_DESCRIPTION_PREVIEW
import com.quran.learnedplayer.ui.settings.TAG_SETTINGS_SCREEN
import com.quran.learnedplayer.ui.surah.TAG_SURAH_DETAIL
import com.quran.learnedplayer.ui.surah.markLearnedTag
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Not a test: drives the app to the screens shown on its Play Store listing and saves a
 * screenshot of each, which `store/capture-screenshots.sh` pulls off the device. It is left out
 * of the instrumented test runs; the "Store screenshots" workflow runs it.
 *
 * Each shot is its own test, so one that cannot reach its screen does not cost the others. The
 * reader is shown paused part-way through an ayah — a playback position is set directly — which
 * highlights a word without streaming audio and keeps every run identical.
 */
@RunWith(AndroidJUnit4::class)
class PlayStoreScreenshots : BaseAppTest() {

    /** Someone who knows Al-Fatiha, Juz Amma, Ya-Sin and Al-Mulk, and has started on others. */
    override fun seedLearnedAyahs(): Set<Int> = buildSet {
        fun surah(s: Int, ayahs: IntRange = 1..QuranConstants.VERSE_COUNTS[s - 1]) =
            ayahs.forEach { add(AyahMapping.surahAyahToGlobal(s, it)) }
        surah(1)
        surah(2, 1..5)
        add(AyahMapping.surahAyahToGlobal(2, 255))
        surah(36)
        surah(55, 1..4)
        surah(67)
        (78..114).forEach { surah(it) }
    }

    override fun beforeLaunch() {
        PlayerSettings.lastGlobalId = AyahMapping.surahAyahToGlobal(67, 1)
    }

    @Test
    fun shot1_reader() {
        openReaderAt(ayah = 1, positionMs = 2_200) // "ٱلْمُلْكُ" — the Dominion
        capture("01_reader")
    }

    @Test
    fun shot2_word_by_word() {
        openReaderAt(ayah = 1, positionMs = 2_200)
        composeRule.onNodeWithTag(TAG_TOGGLE_WORD_BY_WORD).performClick()
        awaitTag(TAG_WORD_PAGER)
        // Step to the fourth word, the one the paused position highlights.
        repeat(3) {
            composeRule.onNodeWithTag(TAG_WORD_PAGER).performClick()
            composeRule.waitForIdle()
        }
        softWaitForText("(is) the Dominion")
        capture("02_word_by_word")
    }

    @Test
    fun shot3_playback_options() {
        openReaderAt(ayah = 1, positionMs = 2_200)
        composeRule.onNodeWithTag(TAG_EXPAND_PANEL).performClick()
        capture("03_playback_options")
    }

    @Test
    fun shot4_surah_list() {
        awaitTag(TAG_FULLSCREEN_WHOLE_AYAH)
        reachHome()
        softWaitForText("7 learned")
        capture("04_surah_list")
    }

    @Test
    fun shot5_mark_ayahs() {
        awaitTag(TAG_FULLSCREEN_WHOLE_AYAH)
        reachHome()
        composeRule.onNodeWithTag(TAG_SURAH_LIST).performScrollToNode(hasTestTag(surahRowTag(55)))
        composeRule.onNodeWithTag(surahRowTag(55)).performClick()
        awaitTag(TAG_SURAH_DETAIL)
        awaitTag(markLearnedTag(6))
        capture("05_mark_ayahs")
    }

    @Test
    fun shot6_add_by_description() {
        awaitTag(TAG_FULLSCREEN_WHOLE_AYAH)
        reachHome()
        composeRule.onNodeWithTag(TAG_ADD_BY_DESCRIPTION).performClick()
        awaitTag(TAG_DESCRIPTION_FIELD)
        composeRule.onNodeWithTag(TAG_DESCRIPTION_FIELD).performTextInput("Al-Kahf, Al-Waqiah, 2:285-286")
        awaitTag(TAG_DESCRIPTION_PREVIEW)
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        capture("06_add_by_description")
    }

    @Test
    fun shot7_reciters() {
        awaitTag(TAG_OPEN_SETTINGS)
        composeRule.onNodeWithTag(TAG_OPEN_SETTINGS).performClick()
        awaitTag(TAG_SETTINGS_SCREEN)
        capture("07_reciters")
    }

    @Test
    fun shot8_light_theme() {
        composeRule.runOnUiThread { PlayerSettings.appTheme = AppTheme.LIGHT }
        openReaderAt(ayah = 1, positionMs = 0)
        composeRule.onNodeWithTag(TAG_FULLSCREEN_NEXT).performClick()
        softWaitForText("Al-Mulk · Ayah 2")
        softWaitForText("death")
        composeRule.runOnUiThread { PlayerStateHolder.updateProgress(1_400, 8_600) } // "ٱلْمَوْتَ"
        capture("08_light_theme")
    }

    /** The player on Al-Mulk [ayah], its words loaded, paused at [positionMs] into the ayah. */
    private fun openReaderAt(ayah: Int, positionMs: Long) {
        awaitTag(TAG_FULLSCREEN_WHOLE_AYAH)
        softWaitForText("Al-Mulk · Ayah $ayah")
        softWaitForText("in Whose Hand")
        if (positionMs > 0) {
            composeRule.runOnUiThread { PlayerStateHolder.updateProgress(positionMs, 6_100) }
        }
    }

    /** Waits for [text] to appear, but carries on without it: a slightly different shot beats none. */
    private fun softWaitForText(text: String, timeoutMs: Long = 15_000) {
        runCatching {
            composeRule.waitUntil(timeoutMs) {
                composeRule.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()
            }
        }
    }

    /**
     * Saves the whole screen, status bar included (the workflow puts the emulator in demo mode for
     * a tidy one), to the app's files dir. Waits out animations first — the panel sliding open,
     * the pager settling, the keyboard going away.
     */
    private fun capture(name: String) {
        composeRule.waitForIdle()
        Thread.sleep(1_500)
        composeRule.waitForIdle()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val bitmap = (1..3).firstNotNullOfOrNull { automation.takeScreenshot() }
            ?: error("No screenshot for $name")
        val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "screenshots")
        dir.mkdirs()
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
