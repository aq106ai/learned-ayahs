package com.quran.learnedplayer

import androidx.test.platform.app.InstrumentationRegistry
import com.quran.learnedplayer.data.BookmarksStore
import com.quran.learnedplayer.data.LearnedAyahsStore
import com.quran.learnedplayer.data.PlaylistStore
import com.quran.learnedplayer.data.Reciter
import com.quran.learnedplayer.player.PlaybackMode
import com.quran.learnedplayer.player.PlayerSettings
import com.quran.learnedplayer.player.RepeatMode
import com.quran.learnedplayer.service.PlayerStateHolder

/**
 * Puts the app back to a fresh install's state, with the given ayahs marked. The stores and
 * [PlayerSettings] are process-wide singletons, so this must run before MainActivity launches —
 * [BaseAppTest] does it from a rule, and tests that launch the app themselves call it first.
 */
internal object AppReset {

    fun reset(seed: Set<Int>) {
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
        PlayerSettings.acceptedTermsVersion = PlayerSettings.TERMS_VERSION
        PlayerSettings.lastGlobalId = 0
        LearnedAyahsStore.addAll(seed)
        BookmarksStore.init(context)
        BookmarksStore.clear()
        PlaylistStore.latest = null
        PlayerStateHolder.resetState()
    }

    fun wakeAndUnlock() {
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("input keyevent KEYCODE_WAKEUP")
        android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() }
        val pfd2 = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("wm dismiss-keyguard")
        android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd2).use { it.readBytes() }
    }
}
