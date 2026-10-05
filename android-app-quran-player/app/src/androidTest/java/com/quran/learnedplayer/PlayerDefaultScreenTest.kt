package com.quran.learnedplayer

import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.quran.learnedplayer.data.AyahMapping
import com.quran.learnedplayer.data.BookmarksStore
import com.quran.learnedplayer.player.PlaybackMode
import com.quran.learnedplayer.player.PlayerSettings
import com.quran.learnedplayer.ui.TAG_FULLSCREEN_NEXT
import com.quran.learnedplayer.ui.TAG_FULLSCREEN_WHOLE_AYAH
import com.quran.learnedplayer.ui.TAG_OPEN_PLAYLIST_PANEL
import com.quran.learnedplayer.ui.TAG_PLAYLIST_BOOKMARKS
import com.quran.learnedplayer.ui.bookmarkRowTag
import com.quran.learnedplayer.ui.bookmarkToggleTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 4: the player is the default screen once onboarding is done, remembers the last ayah
 * across launches, and the playlist panel supports bookmarking any ayah for quick access.
 */
@RunWith(AndroidJUnit4::class)
class PlayerDefaultScreenTest : BaseAppTest() {

    override fun seedLearnedAyahs(): Set<Int> =
        (1..4).map { AyahMapping.surahAyahToGlobal(112, it) }.toSet()

    @Test
    fun the_player_opens_directly_with_a_learned_list_and_seen_intro() {
        // No reachHome() here on purpose: this is the whole point of the change — with
        // onboarding done, launch lands straight on the reader, not the picker.
        awaitTag(TAG_FULLSCREEN_WHOLE_AYAH)
    }

    @Test
    fun the_last_played_ayah_is_remembered_across_a_restart() {
        awaitTag(TAG_FULLSCREEN_WHOLE_AYAH)
        composeRule.onNodeWithTag(TAG_FULLSCREEN_NEXT).performClick()
        composeRule.onNodeWithTag(TAG_FULLSCREEN_NEXT).performClick()
        composeRule.waitUntil(20_000) {
            composeRule.onAllNodesWithText("Ayah 3", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitForIdle()

        val expected = AyahMapping.surahAyahToGlobal(112, 3)
        // Re-read from disk the way a cold launch would (same idiom as
        // MarkLearnedTest.marked_ayahs_survive_an_activity_restart).
        PlayerSettings.init(InstrumentationRegistry.getInstrumentation().targetContext)
        assertEquals("last position must be persisted, not just in memory", expected, PlayerSettings.lastGlobalId)
    }

    @Test
    fun bookmarking_an_ayah_from_the_playlist_lets_you_jump_back_to_it() {
        awaitTag(TAG_FULLSCREEN_WHOLE_AYAH)
        composeRule.onNodeWithTag(TAG_OPEN_PLAYLIST_PANEL).performClick()

        val bookmarkedGlobalId = AyahMapping.surahAyahToGlobal(112, 3)
        awaitTag(bookmarkToggleTag(bookmarkedGlobalId))
        composeRule.onNodeWithTag(bookmarkToggleTag(bookmarkedGlobalId)).performClick()
        composeRule.waitForIdle()
        assertTrue(BookmarksStore.isBookmarked(bookmarkedGlobalId))

        awaitTag(TAG_PLAYLIST_BOOKMARKS)
        composeRule.onNodeWithTag(bookmarkRowTag(bookmarkedGlobalId)).performClick()
        composeRule.waitForIdle()

        composeRule.waitUntil(20_000) {
            composeRule.onAllNodesWithText("Ayah 3", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    /**
     * Jumping to a bookmark must not rewrite the mode the user chose. An *unlearned* bookmark is
     * the case that bites: it can only be reached by streaming its surah, and the jump used to
     * persist FULL_SURAH — so one tap silently and permanently moved the user off learned-ayahs
     * mode. The session may borrow full-surah to show it; the saved preference may not change.
     */
    @Test
    fun jumping_to_an_unlearned_bookmark_keeps_the_chosen_playback_mode() {
        PlayerSettings.playbackMode = PlaybackMode.REVISE
        awaitTag(TAG_FULLSCREEN_WHOLE_AYAH)

        // 2:255 is deliberately outside the seeded learned list (surah 112).
        val unlearned = AyahMapping.surahAyahToGlobal(2, 255)
        BookmarksStore.toggle(unlearned)
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(TAG_OPEN_PLAYLIST_PANEL).performClick()
        awaitTag(TAG_PLAYLIST_BOOKMARKS)
        composeRule.onNodeWithTag(bookmarkRowTag(unlearned)).performClick()
        composeRule.waitForIdle()

        assertEquals(
            "a bookmark jump must not overwrite the saved playback mode",
            PlaybackMode.REVISE,
            PlayerSettings.playbackMode,
        )
    }
}
