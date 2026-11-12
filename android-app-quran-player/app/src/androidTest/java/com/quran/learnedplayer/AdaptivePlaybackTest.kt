package com.quran.learnedplayer

import androidx.compose.ui.test.*
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.quran.learnedplayer.data.*
import com.quran.learnedplayer.player.*
import com.quran.learnedplayer.ui.*
import com.quran.learnedplayer.ui.settings.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AdaptivePlaybackTest : BaseAppTest() {
    override fun seedLearnedAyahs() = setOf(AyahMapping.surahAyahToGlobal(112, 2), AyahMapping.surahAyahToGlobal(112, 3))
    override fun beforeLaunch() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val data = QuranDataRepository(context)
        val wav = ByteBuffer.allocate(44 + 16000 * 2 * 30).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(capacity()-8); put("WAVEfmt ".toByteArray())
            putInt(16); putShort(1); putShort(1); putInt(16000); putInt(32000)
            putShort(2); putShort(16); put("data".toByteArray()); putInt(capacity()-44)
        }.array()
        for (track in QueueBuilder.buildQueue(emptyList(), PlaybackMode.FULL_SURAH, RepeatMode.OFF, 112)) {
            AudioDownloader(context).localFileFor(track).apply { parentFile!!.mkdirs(); writeBytes(wav) }
            WordAudio.itemsFor(PlayerSettings.wordReciter, 112, track.ayah, data.contentWordCount(112, track.ayah)).forEach {
                WordAudioDownloader(context).localFileFor(it).apply { parentFile!!.mkdirs(); writeBytes(wav) }
            }
        }
    }
    private fun model() = ViewModelProvider(composeRule.activity)[PlayerViewModel::class.java]
    private fun change(action: (PlayerViewModel) -> Unit) = composeRule.runOnIdle { action(model()) }

    /**
     * The cached files here are 30 s of silence, far past 112:2's word timings (about 1.6 s): the
     * shape of EveryAyah's Maher Al Muaiqly 2:258, which holds 2:259 too. The ayah must end where
     * its words do, with the slider spanning the ayah, and move on to the next ayah.
     */
    @Test fun anAyahFileThatRunsOnEndsWithTheAyah() {
        awaitTag(TAG_EXPAND_PANEL)
        assertEquals(AyahMapping.surahAyahToGlobal(112, 2), model().uiState.value.currentTrack!!.globalId)
        change { it.playWithServiceForUi() }
        composeRule.waitUntil(15000) { model().uiState.value.isPlaying && model().uiState.value.durationMs > 0 }
        val shown = model().uiState.value.durationMs
        assertTrue("the slider spans the ayah, not the file ($shown ms)", shown in 1500L..4000L)
        composeRule.waitUntil(8000) { model().uiState.value.currentTrack?.globalId == AyahMapping.surahAyahToGlobal(112, 3) }
        change { it.pause() }
    }

    @Test fun cachedAudioTransitionsKeepQueueAndPausedState() {
        awaitTag(TAG_EXPAND_PANEL)
        change { it.playWithServiceForUi() }
        composeRule.waitUntil(15000) { model().uiState.value.isPlaying }
        change { it.pause() }
        composeRule.waitUntil(5000) { !model().uiState.value.isPlaying }
        val original = model().uiState.value.currentTrack!!.globalId
        for (mode in listOf(PlaybackMode.FULL_SURAH, PlaybackMode.REVISE)) {
            change { it.setMode(mode); it.setWordView(true); it.setUseWordClips(true); it.setRepeatWord(true) }
            composeRule.waitUntil(5000) { model().uiState.value.mode == mode && model().uiState.value.wordPlayback }
            assertEquals(original, model().uiState.value.currentTrack!!.globalId)
            assertEquals(if (mode == PlaybackMode.FULL_SURAH) 4 else 2, model().uiState.value.tracks.size)
            assertFalse(model().uiState.value.isPlaying)
            change { it.setWordView(false); it.setUseWordClips(false); it.setRepeatWord(false) }
            composeRule.waitUntil(5000) { !model().uiState.value.wordPlayback }
            assertFalse(model().uiState.value.isPlaying)
            assertEquals(original, model().uiState.value.currentTrack!!.globalId)
        }
    }

    @Test fun failedWordClipStopsWithoutSkippingOrChangingQueue() {
        awaitTag(TAG_EXPAND_PANEL)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val broken = WordAudioDownloader(context).localFileFor(WordAudio.item(PlayerSettings.wordReciter, 112, 2, 0))
        broken.writeText("deliberately invalid audio fixture")
        change { it.setWordView(true); it.setUseWordClips(true); it.playWithServiceForUi() }
        composeRule.waitUntil(15000) { model().uiState.value.error != null }
        assertFalse(model().uiState.value.isPlaying)
        assertEquals(AyahMapping.surahAyahToGlobal(112, 2), model().uiState.value.currentTrack!!.globalId)
        assertEquals(0, model().uiState.value.currentWordIndex)
        change { it.setWordView(false); it.setUseWordClips(false); it.setRepeatWord(false) }
        composeRule.waitUntil(5000) { !model().uiState.value.wordPlayback && model().uiState.value.error == null }
        assertFalse(model().uiState.value.isPlaying)
        change { it.playWithServiceForUi() }
        composeRule.waitUntil(15000) { model().uiState.value.isPlaying }
        change { it.pause() }
    }

    @Test fun playingTransitionsAndRecreationKeepCurrentAyah() {
        awaitTag(TAG_EXPAND_PANEL)
        change { it.playWithServiceForUi() }
        composeRule.waitUntil(15000) { model().uiState.value.isPlaying }
        val original = model().uiState.value.currentTrack!!.globalId
        for (clips in listOf(true, false, true)) {
            change { it.setWordView(true); it.setRepeatWord(true); it.setUseWordClips(clips) }
            composeRule.waitUntil(15000) { model().uiState.value.isPlaying && model().uiState.value.wordPlayback }
            assertEquals(original, model().uiState.value.currentTrack!!.globalId)
            change { it.setWordView(false); it.setUseWordClips(false); it.setRepeatWord(false) }
            composeRule.waitUntil(15000) { model().uiState.value.isPlaying && !model().uiState.value.wordPlayback }
        }
        change { it.pause() }
        composeRule.activityRule.scenario.recreate()
        awaitTag(TAG_EXPAND_PANEL)
        assertEquals(original, model().uiState.value.currentTrack!!.globalId)
        assertFalse(model().uiState.value.isPlaying)
    }

    private fun capture(label: String) {
        composeRule.waitForIdle()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val config = context.resources.configuration
        val file = java.io.File(context.getExternalFilesDir(null), "adaptive-${config.screenWidthDp}-${config.screenHeightDp}-${config.fontScale}-$label.png")
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { bitmap ->
            file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    @Test fun controlsAndTranslationSwitchesRemainReachable() {
        awaitTag("open_actions_menu")
        tapMenuItem(TAG_TOGGLE_WORD_BY_WORD)
        composeRule.onNodeWithTag(TAG_EXPAND_PANEL).performClick()
        composeRule.onNodeWithTag("open_actions_menu").performScrollTo().performClick()
        composeRule.onNodeWithTag(playbackModeTag(PlaybackMode.FULL_SURAH)).assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("repeat_word_toggle").performScrollTo().assertIsDisplayed().performClick()
        capture("player")
        composeRule.onNodeWithTag("open_actions_menu").performScrollTo().performClick()
        composeRule.onNodeWithTag(TAG_OPEN_SETTINGS).performClick()
        composeRule.onNodeWithTag(TAG_WORD_TRANSLATIONS_TOGGLE).performScrollTo().assertIsDisplayed().performClick()
        assertFalse(PlayerSettings.showWordTranslations)
        composeRule.onNodeWithTag(TAG_SETTINGS_AYAH_TRANSLATION).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(TAG_CONTENT_WORD_LANGUAGE).assertDoesNotExist()
        capture("settings")
    }
}
