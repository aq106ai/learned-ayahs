package com.quran.learnedplayer

import android.Manifest
import android.os.Build
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.rule.GrantPermissionRule
import com.quran.learnedplayer.data.LearnedAyahsStore
import com.quran.learnedplayer.data.PlaylistStore
import com.quran.learnedplayer.player.PlayerSettings
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
                AppReset.reset(seedLearnedAyahs())
                beforeLaunch()
                // Xiaomi/MIUI often leaves the activity behind a lock screen.
                AppReset.wakeAndUnlock()
                base.evaluate()
            }
        }
    }

    /**
     * Starting playback asks for the notification permission on Android 13+. A phone that ran the
     * app before has usually granted it already, but on a fresh device or emulator the system
     * dialog covers the activity mid-test and every later lookup fails with "No compose
     * hierarchies found". Granting it up front makes the tests independent of the device's history.
     */
    private val notificationPermission: TestRule =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            TestRule { base, _ -> base }
        }

    val composeRule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val chain: RuleChain =
        RuleChain.outerRule(notificationPermission).around(resetRule).around(composeRule)

    /**
     * Ayahs to mark before the activity launches. Empty by default, because that is what a real
     * new install looks like now — tests that need a playback queue (there is no mini player
     * without one) override this.
     */
    protected open fun seedLearnedAyahs(): Set<Int> = emptySet()

    /** Last-minute setup after the reset and before the activity launches, e.g. a saved position. */
    protected open fun beforeLaunch() {}

    /**
     * Waits for a node with [tag] to exist, tolerating async library/asset loading. On a timeout
     * the failure carries what *is* on screen, so a CI log says why rather than just "timed out".
     */
    protected fun awaitTag(tag: String, timeoutMs: Long = 20_000) {
        try {
            composeRule.waitUntil(timeoutMs) {
                composeRule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
            }
        } catch (e: ComposeTimeoutException) {
            val screen = runCatching {
                composeRule.onAllNodes(isRoot()).printToString(maxDepth = Int.MAX_VALUE)
            }.getOrElse { "(could not read the screen: $it)" }
            throw AssertionError("No node tagged '$tag' after ${timeoutMs}ms. On screen:\n$screen", e)
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
