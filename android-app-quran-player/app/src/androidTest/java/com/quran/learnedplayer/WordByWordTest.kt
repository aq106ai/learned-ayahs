package com.quran.learnedplayer

import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.quran.learnedplayer.data.AyahMapping
import com.quran.learnedplayer.player.PlaybackMode
import com.quran.learnedplayer.player.PlayerSettings
import com.quran.learnedplayer.player.RepeatMode
import com.quran.learnedplayer.service.PlayerStateHolder
import com.quran.learnedplayer.ui.TAG_EXPAND_PANEL
import com.quran.learnedplayer.ui.TAG_FULLSCREEN_NEXT
import com.quran.learnedplayer.ui.TAG_FULLSCREEN_PREVIOUS
import com.quran.learnedplayer.ui.TAG_FULLSCREEN_WHOLE_AYAH
import com.quran.learnedplayer.ui.TAG_TOGGLE_WORD_BY_WORD
import com.quran.learnedplayer.ui.TAG_WORD_COUNTER
import com.quran.learnedplayer.ui.TAG_WORD_PAGER
import com.quran.learnedplayer.ui.TAG_WORD_TRANSLATION
import com.quran.learnedplayer.ui.playbackModeTag
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The fullscreen reader and its word-by-word mode. These drive the UI only — no playback is
 * started, so the words come from the bundled assets and the pager sits at rest.
 */
@RunWith(AndroidJUnit4::class)
class WordByWordTest : BaseAppTest() {

    /**
     * Mark Ya-Sin so there is a queue to read: with nothing marked there is no playlist and no
     * mini player to open the reader from. The playlist therefore starts on 36:1, as before.
     */
    override fun seedLearnedAyahs(): Set<Int> =
        (1..83).map { AyahMapping.surahAyahToGlobal(36, it) }.toSet()

    private fun openFullscreenReader() {
        // The player is the default screen now, and IS the immersive reader — nothing to
        // navigate to, just wait for it to finish loading the seeded queue.
        awaitTag(TAG_TOGGLE_WORD_BY_WORD)
    }

    @Test
    fun fullscreen_reader_opens_and_can_toggle_to_word_by_word() {
        openFullscreenReader()
        composeRule.onNodeWithTag(TAG_TOGGLE_WORD_BY_WORD).assertIsDisplayed()

        composeRule.onNodeWithTag(TAG_TOGGLE_WORD_BY_WORD).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(TAG_WORD_PAGER).assertIsDisplayed()
        // The label flips so the user can get back to the whole-ayah view.
        composeRule.onNodeWithText("Whole ayah").assertIsDisplayed()
    }

    /**
     * Waits for the word reader to show [meaning] under its Arabic word. Waits rather than
     * asserting at once: the ayah's words load off the compose-idle clock and the pager settles
     * a frame later. The pager also keeps neighbouring pages composed but unplaced, so a match
     * only counts once it is actually on screen. On failure the reader's own tree is printed —
     * which page is showing, and where — since "not displayed" alone says nothing.
     */
    private fun assertMeaningShown(meaning: String) {
        val matcher = hasTestTag(TAG_WORD_TRANSLATION) and hasText(meaning)
        try {
            composeRule.waitUntil(10_000) {
                val nodes = composeRule.onAllNodes(matcher)
                nodes.fetchSemanticsNodes().indices.any { i ->
                    runCatching { nodes[i].assertIsDisplayed() }.isSuccess
                }
            }
        } catch (e: ComposeTimeoutException) {
            throw AssertionError(
                "\"$meaning\" is not on screen. Word reader:\n" +
                    composeRule.onNodeWithTag(TAG_WORD_PAGER, useUnmergedTree = true).printToString(),
                e,
            )
        }
    }

    @Test
    fun word_view_shows_an_english_translation_under_the_arabic() {
        openFullscreenReader()
        composeRule.onNodeWithTag(TAG_TOGGLE_WORD_BY_WORD).performClick()
        awaitTag(TAG_WORD_PAGER)
        // 36:1 is the single word "يسٓ" — "Ya Seen".
        assertMeaningShown("Ya Seen")
    }

    /**
     * The playlist opens on Ya-Sin 36:1, which is the single word "يسٓ" and so has nothing to
     * swipe between. Step to [ayah] and wait for it, so a failure here names the cause rather
     * than surfacing later as a mystery timeout.
     */
    private fun advanceToAyah(ayah: Int) {
        repeat(ayah - 1) { composeRule.onNodeWithTag(TAG_FULLSCREEN_NEXT).performClick() }
        composeRule.waitUntil(20_000) {
            composeRule.onAllNodesWithText("Ayah $ayah", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** Advances one word in the current reading direction (RTL by default: swipe right). */
    private fun swipeForward() =
        composeRule.onNodeWithTag(TAG_WORD_PAGER).performTouchInput { swipeRight() }

    private fun swipeBackward() =
        composeRule.onNodeWithTag(TAG_WORD_PAGER).performTouchInput { swipeLeft() }

    /**
     * Waits for the counter to show [position] rather than asserting instantly: the ayah's words
     * load on the IO dispatcher, which `waitForIdle` does not cover, so right after an ayah
     * change the reader can briefly still display the previous ayah's count.
     */
    private fun assertWord(position: String) =
        composeRule.waitUntil(20_000) {
            composeRule.onAllNodes(hasTestTag(TAG_WORD_COUNTER) and hasText(position, substring = true))
                .fetchSemanticsNodes().isNotEmpty()
        }

    @Test
    fun swiping_moves_between_words_following_arabic_reading_order() {
        openFullscreenReader()
        advanceToAyah(3) // 36:3 — "إِنَّكَ لَمِنَ ٱلْمُرْسَلِينَ", three words.

        composeRule.onNodeWithTag(TAG_TOGGLE_WORD_BY_WORD).performClick()
        awaitTag(TAG_WORD_PAGER)
        assertWord("1 / 3")

        // The default direction is right-to-left, so a rightward swipe advances.
        swipeForward()
        composeRule.waitForIdle()
        assertWord("2 / 3")

        swipeBackward()
        composeRule.waitForIdle()
        assertWord("1 / 3")
    }

    @Test
    fun swiping_does_nothing_when_swipe_to_navigate_is_off() {
        PlayerSettings.swipeToNavigate = false
        openFullscreenReader()
        advanceToAyah(3) // 36:3 — three words.

        composeRule.onNodeWithTag(TAG_TOGGLE_WORD_BY_WORD).performClick()
        awaitTag(TAG_WORD_PAGER)
        assertWord("1 / 3")

        swipeForward()
        composeRule.waitForIdle()
        // The pager's own scrolling is disabled; the counter must not move.
        assertWord("1 / 3")
    }

    @Test
    fun tap_steps_one_word_when_tap_to_advance_is_on() {
        openFullscreenReader()
        advanceToAyah(3) // 36:3 — three words.

        composeRule.onNodeWithTag(TAG_TOGGLE_WORD_BY_WORD).performClick()
        awaitTag(TAG_WORD_PAGER)
        assertWord("1 / 3")

        composeRule.onNodeWithTag(TAG_WORD_PAGER).performClick()
        composeRule.waitForIdle()
        assertWord("2 / 3")
    }

    @Test
    fun each_word_shows_its_own_meaning_as_you_swipe() {
        openFullscreenReader()
        advanceToAyah(3)
        composeRule.onNodeWithTag(TAG_TOGGLE_WORD_BY_WORD).performClick()
        awaitTag(TAG_WORD_PAGER)
        // Wait for 36:3's words to replace 36:1's — they load off the compose-idle clock.
        assertWord("1 / 3")

        // 36:3 word-by-word: "Indeed, you" / "(are) among" / "the Messengers".
        assertMeaningShown("Indeed, you")
        swipeForward()
        composeRule.waitForIdle()
        assertMeaningShown("(are) among")
    }

    /**
     * The notification, lock screen, Bluetooth and Android Auto all reach the transport through
     * `PlaybackService.skipNext/skipPrevious`, which consult `PlayerStateHolder.stepWord` first.
     * Driving that hook is the closest we can get to a headset press without one; the physical
     * Bluetooth path itself is only verifiable by hand.
     */
    @Test
    fun media_controls_step_words_while_the_word_reader_is_open() {
        openFullscreenReader()
        advanceToAyah(3)
        composeRule.onNodeWithTag(TAG_TOGGLE_WORD_BY_WORD).performClick()
        awaitTag(TAG_WORD_PAGER)
        assertWord("1 / 3")

        composeRule.runOnUiThread { assertTrue(PlayerStateHolder.stepWord(1)) }
        composeRule.waitForIdle()
        assertWord("2 / 3")

        composeRule.runOnUiThread { assertTrue(PlayerStateHolder.stepWord(-1)) }
        composeRule.waitForIdle()
        assertWord("1 / 3")
    }

    /**
     * Changing ayah blanks `ayahWords` while the next one loads, which drops the reader to its
     * loading branch. The word-step hook must survive that window: if it lapsed, a Next arriving
     * from a headset mid-load would fall through to a real ayah skip — and since every skip blanks
     * the words again, a run of presses races through ayahs instead of stepping words.
     */
    @Test
    fun media_controls_still_step_words_while_the_next_ayah_is_loading() {
        openFullscreenReader()
        advanceToAyah(3)
        composeRule.onNodeWithTag(TAG_TOGGLE_WORD_BY_WORD).performClick()
        awaitTag(TAG_WORD_PAGER)
        assertWord("1 / 3")

        // Reproduce the load window directly — the real one is too brief to race reliably.
        composeRule.runOnUiThread {
            PlayerStateHolder.updateAyahText(emptyList(), loading = true)
        }
        composeRule.waitForIdle()
        composeRule.runOnUiThread { assertTrue(PlayerStateHolder.stepWord(1)) }
    }

    /**
     * The counterpart boundary: words that have *settled* empty ("Ayah text unavailable") never
     * resolve by waiting, so the hook must decline the step and let it fall through to a real
     * ayah skip — otherwise every controller's Next/Previous is swallowed and the user is
     * trapped on that ayah until they close the reader.
     */
    @Test
    fun media_controls_fall_through_to_an_ayah_skip_when_text_is_unavailable() {
        openFullscreenReader()
        advanceToAyah(3)
        composeRule.onNodeWithTag(TAG_TOGGLE_WORD_BY_WORD).performClick()
        awaitTag(TAG_WORD_PAGER)
        // Let the real load settle first, or its completion would overwrite the forced
        // empty state below and re-arm the pager's step handler mid-assertion.
        assertWord("1 / 3")

        composeRule.runOnUiThread {
            PlayerStateHolder.updateAyahText(emptyList(), loading = false)
        }
        composeRule.waitForIdle()
        composeRule.runOnUiThread { assertFalse(PlayerStateHolder.stepWord(1)) }
    }

    @Test
    fun media_controls_move_ayahs_again_once_the_reader_is_closed() {
        openFullscreenReader()
        // Whole-ayah view: no word reader composed, so the hook must decline and let the
        // service do its normal ayah navigation.
        composeRule.runOnUiThread { assertFalse(PlayerStateHolder.stepWord(1)) }
    }

    @Test
    fun transport_buttons_step_words_while_the_word_reader_is_open() {
        openFullscreenReader()
        advanceToAyah(3)
        composeRule.onNodeWithTag(TAG_TOGGLE_WORD_BY_WORD).performClick()
        awaitTag(TAG_WORD_PAGER)
        assertWord("1 / 3")

        composeRule.onNodeWithTag(TAG_FULLSCREEN_NEXT).performClick()
        composeRule.waitForIdle()
        assertWord("2 / 3")

        composeRule.onNodeWithTag(TAG_FULLSCREEN_PREVIOUS).performClick()
        composeRule.waitForIdle()
        assertWord("1 / 3")
    }

    @Test
    fun swiping_past_the_last_word_moves_to_the_next_ayah() {
        openFullscreenReader()
        advanceToAyah(2) // 36:2 — two words.
        composeRule.onNodeWithTag(TAG_TOGGLE_WORD_BY_WORD).performClick()
        awaitTag(TAG_WORD_PAGER)
        // Wait for 36:2's words: right after an ayah change the pager can briefly still show
        // the previous ayah's, and a swipe then would cross the wrong boundary.
        assertWord("1 / 2")

        // Walk to the last word, then keep going — the reader should cross into 36:3.
        swipeForward()
        composeRule.waitForIdle()
        assertWord("2 / 2")

        swipeForward()
        composeRule.waitUntil(20_000) {
            composeRule.onAllNodesWithText("Ayah 3", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        // Lands on the first word of the new ayah.
        assertWord("1 / 3")
    }

    @Test
    fun swiping_back_past_the_first_word_moves_to_the_previous_ayah() {
        openFullscreenReader()
        advanceToAyah(3)
        composeRule.onNodeWithTag(TAG_TOGGLE_WORD_BY_WORD).performClick()
        awaitTag(TAG_WORD_PAGER)
        assertWord("1 / 3")

        swipeBackward()
        composeRule.waitUntil(20_000) {
            composeRule.onAllNodesWithText("Ayah 2", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        // Going backwards lands on the *last* word of the previous ayah.
        assertWord("2 / 2")
    }

    /**
     * Tap-to-advance used to be gated to a repeating mode only; it must now work regardless of
     * repeat setting. REVISE + repeat OFF is the app's default, so this exercises the
     * unification without switching anything else.
     */
    @Test
    fun tapping_the_whole_ayah_view_advances_with_repeat_off() {
        PlayerSettings.playbackMode = PlaybackMode.REVISE
        PlayerSettings.repeatMode = RepeatMode.OFF
        openFullscreenReader()
        composeRule.onNodeWithTag(TAG_FULLSCREEN_WHOLE_AYAH).performClick()
        composeRule.waitUntil(20_000) {
            composeRule.onAllNodesWithText("Ayah 2", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    /**
     * With swiping turned off a swipe must do nothing. It used to finish as a tap — tap-to-advance
     * is on — and step to the next ayah, so turning swiping off did not stop swipes moving on.
     */
    @Test
    fun a_swipe_is_not_a_tap_when_swipe_to_navigate_is_off() {
        PlayerSettings.playbackMode = PlaybackMode.REVISE
        PlayerSettings.repeatMode = RepeatMode.OFF
        PlayerSettings.swipeToNavigate = false
        openFullscreenReader()
        composeRule.onNodeWithTag(TAG_FULLSCREEN_WHOLE_AYAH).performTouchInput { swipeRight() }
        // A step would show within a frame or two (see the tap test above); allow it ample time.
        Thread.sleep(1_000)
        composeRule.waitForIdle()
        assertTrue(
            "the swipe stepped to the next ayah",
            composeRule.onAllNodesWithText("Ayah 2", substring = true).fetchSemanticsNodes().isEmpty(),
        )
    }

    @Test
    fun toggling_back_restores_the_whole_ayah_view() {
        openFullscreenReader()
        composeRule.onNodeWithTag(TAG_TOGGLE_WORD_BY_WORD).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(TAG_TOGGLE_WORD_BY_WORD).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(TAG_WORD_PAGER).assertDoesNotExist()
        composeRule.onNodeWithText("Word by word").assertIsDisplayed()
    }

    @Test
    fun word_by_word_playback_mode_opens_the_word_reader_without_the_overlay_toggle() {
        openFullscreenReader()
        awaitTag(TAG_EXPAND_PANEL)
        composeRule.onNodeWithTag(TAG_EXPAND_PANEL).performClick()
        composeRule.onNodeWithTag(playbackModeTag(PlaybackMode.WORD_BY_WORD)).performClick()
        awaitTag(TAG_WORD_PAGER)
        composeRule.onNodeWithTag(TAG_WORD_PAGER).assertIsDisplayed()
        composeRule.onNodeWithTag(TAG_TOGGLE_WORD_BY_WORD).assertDoesNotExist()
    }
}
