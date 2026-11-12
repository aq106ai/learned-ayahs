package com.quran.learnedplayer.player

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WordSettingsMigrationTest {
    @Test fun legacyModeMigratesOnceWithoutReinterpretingAyahRepeat() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("player_settings", Context.MODE_PRIVATE).edit().clear()
            .putString("playback_mode", "WORD_BY_WORD").putString("repeat_mode", "AYAH")
            .putBoolean("use_word_clips", true).commit()
        PlayerSettings.init(context)
        assertEquals(PlaybackMode.REVISE, PlayerSettings.playbackMode)
        assertTrue(PlayerSettings.wordView)
        assertTrue(PlayerSettings.repeatWord)
        assertEquals(RepeatMode.OFF, PlayerSettings.repeatMode)
        PlayerSettings.repeatMode = RepeatMode.SURAH
        PlayerSettings.init(context)
        assertEquals(RepeatMode.SURAH, PlayerSettings.repeatMode)
        assertTrue(PlayerSettings.repeatWord)
    }
    @Test fun viewAloneDoesNotChangeAudio() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("player_settings", Context.MODE_PRIVATE).edit().clear().commit()
        PlayerSettings.init(context)
        PlayerSettings.wordView = true
        assertFalse(PlayerSettings.wordPlayback)
        PlayerSettings.repeatWord = true
        assertTrue(PlayerSettings.wordPlayback)
        // What plays does not depend on what is on screen: the word still repeats.
        PlayerSettings.wordView = false
        assertTrue(PlayerSettings.wordPlayback)
        PlayerSettings.repeatWord = false
        assertFalse(PlayerSettings.wordPlayback)
    }
}
