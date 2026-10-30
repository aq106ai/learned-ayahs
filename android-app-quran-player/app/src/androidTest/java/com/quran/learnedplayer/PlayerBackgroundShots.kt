package com.quran.learnedplayer

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.quran.learnedplayer.data.AppTheme
import com.quran.learnedplayer.data.AyahMapping
import com.quran.learnedplayer.player.PlayerSettings
import com.quran.learnedplayer.ui.TAG_FULLSCREEN_WHOLE_AYAH
import com.quran.learnedplayer.ui.TAG_PLAYER_BACKGROUND
import com.quran.learnedplayer.ui.player.ShareImage
import com.quran.learnedplayer.video.PlayerBackgrounds
import com.quran.learnedplayer.video.VideoAspect
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Not a test: screenshots of the player with each kind of background, and the shared image in
 * each shape, for a person to look at. `store/capture-video-samples.sh` runs it after
 * [VideoSamples] (whose folder it adds to) and the instrumented test runs leave it out. The
 * photos are the ones that script copies onto the device.
 */
@RunWith(AndroidJUnit4::class)
class PlayerBackgroundShots : BaseAppTest() {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val out = File(context.filesDir, "video-samples").apply { mkdirs() }

    override fun seedLearnedAyahs(): Set<Int> =
        (1..5).map { AyahMapping.surahAyahToGlobal(67, it) }.toSet()

    @Test
    fun shots() {
        awaitTag(TAG_FULLSCREEN_WHOLE_AYAH)
        shot("player_plain")

        PlayerSettings.showAyahTranslation = true
        PlayerSettings.playerBackground = PlayerBackgrounds.MATCHED
        awaitTag(TAG_PLAYER_BACKGROUND)
        shot("player_matched")

        PlayerSettings.playerBackground = "photo:sunrise"
        shot("player_photo_sunrise", settleMs = 3_000)

        PlayerSettings.playerBackground = PlayerBackgrounds.PHOTOS
        PlayerSettings.appTheme = AppTheme.LIGHT // the background keeps the text light regardless
        shot("player_photos_light_theme", settleMs = 3_000)

        PlayerSettings.playerBackground = PlayerBackgrounds.PLAIN
        shot("player_plain_light_theme")

        runBlocking {
            for (aspect in VideoAspect.entries) {
                val options = ShareImage.options(67, 1, aspect)
                ShareImage.make(context, options, atMs = null)
                    .copyTo(File(out, "share_image_67_1_${aspect.ratio.replace(':', 'x')}.jpg"), overwrite = true)
            }
        }
    }

    private fun shot(name: String, settleMs: Long = 1_500) {
        // The test holds the app's frame clock: its effects (the background's own loop among
        // them) only move as the clock is advanced, so a plain sleep would photograph a frozen
        // screen. Advance it alongside real time, which the off-thread loading needs.
        var waited = 0L
        while (waited < settleMs) {
            Thread.sleep(100)
            composeRule.mainClock.advanceTimeBy(100)
            waited += 100
        }
        composeRule.waitForIdle()
        Thread.sleep(300) // let the last frame reach the screen
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        File(out, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
