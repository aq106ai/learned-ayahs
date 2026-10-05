package com.quran.learnedplayer

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.quran.learnedplayer.data.BookmarksStore
import com.quran.learnedplayer.data.LearnedAyahsStore
import com.quran.learnedplayer.data.PlaylistStore
import com.quran.learnedplayer.data.Reciter
import com.quran.learnedplayer.player.PlaybackMode
import com.quran.learnedplayer.player.PlayerSettings
import com.quran.learnedplayer.player.RepeatMode
import com.quran.learnedplayer.service.PlayerStateHolder
import com.quran.learnedplayer.ui.TAG_EMPTY_BROWSE
import com.quran.learnedplayer.ui.TAG_MANAGE_AYAHS
import com.quran.learnedplayer.ui.TAG_OPEN_SURAH_PANEL
import com.quran.learnedplayer.ui.home.TAG_SURAH_LIST
import org.junit.Rule
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runners.model.Statement

/**
 * Shared setup for the end-to-end tests: every test starts from a clean, default state
 * (in-app selection, nothing marked).
 *
 * The reset must happen *before* MainActivity launches, hence the RuleChain rather than a
 * @Before: `PlayerStateHolder`, `PlaylistStore`, `PlayerSettings` and `LearnedAyahsStore` are
 * all process-wide singletons that outlive an activity, and `MainActivity.autoLoadLibrary`
 * skips loading when a playlist is already present. Without this, a leftover error from the
 * JSON-source test hides the ayah panel in later tests.
 *
 * These tests deliberately avoid starting playback — they exercise browsing, marking and
 * settings, which are fully offline (the bundled assets cover all 6236 ayahs).
 */
abstract class BaseAppTest {

    private val resetRule = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                val context = InstrumentationRegistry.getInstrumentation().targetContext
                // MainActivity shows a plain-View crash report instead of the UI whenever this
                // file exists, so one left behind — by an earlier crash or an earlier session,
                // since installs preserve app data — makes every later test fail with the
                // baffling "No compose hierarchies found in the app".
                java.io.File(context.filesDir, LearnedAyahsApp.CRASH_LOG).delete()
                PlayerSettings.init(context)
                LearnedAyahsStore.init(context)
                LearnedAyahsStore.clear()
                PlayerSettings.playbackMode = PlaybackMode.DEFAULT
                PlayerSettings.repeatMode = RepeatMode.DEFAULT
                PlayerSettings.revisionDelaySeconds = 0
                PlayerSettings.reciter = Reciter.DEFAULT
                PlayerSettings.showWordTranslations = true
                PlayerSettings.swipeToNavigate = true
                PlayerSettings.tapToAdvance = true
                PlayerSettings.appTheme = com.quran.learnedplayer.data.AppTheme.DEFAULT
                PlayerSettings.ayahOverflowMode = com.quran.learnedplayer.player.AyahOverflowMode.DEFAULT
                PlayerSettings.wordReciter = com.quran.learnedplayer.data.WordReciter.DEFAULT
                // Recite is opt-in beta and hidden by default; the tests that exercise it turn
                // it on explicitly, and every other test should see the app as a new user does.
                PlayerSettings.reciteBetaEnabled = false
                PlayerSettings.seekInsideAyahAudio = false
                PlayerSettings.useWordClips = false
                PlayerSettings.autoPlayMistakeAudio = true
                PlayerSettings.autoAdvanceSuccess = true
                // The first-run hadith would sit over every screen these tests drive;
                // IntroDialogTest covers it explicitly instead.
                PlayerSettings.seenIntro = true
                PlayerSettings.lastGlobalId = 0
                LearnedAyahsStore.addAll(seedLearnedAyahs())
                BookmarksStore.init(context)
                BookmarksStore.clear()
                PlaylistStore.latest = null
                PlayerStateHolder.resetState()
                // Xiaomi/MIUI often leaves the activity behind a lock screen.
                wakeAndUnlock()
                base.evaluate()
            }
        }
    }

    val composeRule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val chain: RuleChain = RuleChain.outerRule(resetRule).around(composeRule)

    private fun wakeAndUnlock() {
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("input keyevent KEYCODE_WAKEUP")
        android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() }
        val pfd2 = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("wm dismiss-keyguard")
        android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd2).use { it.readBytes() }
    }

    /**
     * Ayahs to mark before the activity launches. Empty by default, because that is what a real
     * new install looks like now — tests that need a playback queue (there is no mini player
     * without one) override this.
     */
    protected open fun seedLearnedAyahs(): Set<Int> = emptySet()

    /** Waits for a node with [tag] to exist, tolerating async library/asset loading. */
    protected fun awaitTag(tag: String, timeoutMs: Long = 20_000) {
        composeRule.waitUntil(timeoutMs) {
            composeRule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /**
     * Reaches the browse/mark screen (`TAG_SURAH_LIST`) regardless of what the app opened on.
     * With `seenIntro = true` (the default for every test but the intro-flow ones), the player is
     * now the default screen — most tests that actually exercise browsing/marking/settings need
     * this instead of assuming the picker is already showing after launch.
     */
    protected fun reachHome() {
        if (composeRule.onAllNodes(hasTestTag(TAG_SURAH_LIST)).fetchSemanticsNodes().isNotEmpty()) return
        if (composeRule.onAllNodes(hasTestTag(TAG_EMPTY_BROWSE)).fetchSemanticsNodes().isNotEmpty()) {
            composeRule.onNodeWithTag(TAG_EMPTY_BROWSE).performClick()
        } else {
            composeRule.onNodeWithTag(TAG_OPEN_SURAH_PANEL).performClick()
            awaitTag(TAG_MANAGE_AYAHS)
            composeRule.onNodeWithTag(TAG_MANAGE_AYAHS).performClick()
        }
        awaitTag(TAG_SURAH_LIST)
    }
}
