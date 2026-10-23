package com.quran.learnedplayer

import android.accessibilityservice.AccessibilityServiceInfo
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.quran.learnedplayer.data.AyahMapping
import com.quran.learnedplayer.data.AyahTrack
import com.quran.learnedplayer.data.LibraryRepository
import com.quran.learnedplayer.data.QuranConstants
import com.quran.learnedplayer.player.PlayerSettings
import com.quran.learnedplayer.service.PlayerStateHolder
import com.quran.learnedplayer.ui.TAG_FULLSCREEN_WHOLE_AYAH
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Not a test: records the video Google Play asks for with the foreground-service declaration —
 * recitation the user starts, carrying on in the background and with the screen off, controlled
 * from the notification and the lock screen. `store/capture-video.sh` runs it; it is left out of
 * the instrumented test runs.
 *
 * Android's screen recorder has no sound, so while it records this logs, ten times a second,
 * which clip is playing and how far into it — then downloads those clips through the app's own
 * downloader. `store/render/render-video.cjs` rebuilds the soundtrack from that log.
 */
@RunWith(AndroidJUnit4::class)
class PlayStoreFgsVideo : BaseAppTest() {

    override fun seedLearnedAyahs(): Set<Int> =
        (1..QuranConstants.VERSE_COUNTS[67 - 1]).map { AyahMapping.surahAyahToGlobal(67, it) }.toSet()

    override fun beforeLaunch() {
        PlayerSettings.lastGlobalId = AyahMapping.surahAyahToGlobal(67, 1)
    }

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation
    private val events = JSONArray()
    private val samples = JSONArray()
    private var startedAt = 0L

    private fun now() = SystemClock.elapsedRealtime() - startedAt

    private fun shell(command: String): String =
        android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command))
            .use { it.readBytes().decodeToString() }

    private fun event(name: String) {
        events.put(JSONObject().put("t", now()).put("event", name))
    }

    /**
     * Waits [ms] while sampling what is playing, ten times a second. It also moves Compose's
     * clock along with real time: under the test rule the UI only redraws when the test advances
     * that clock, so a plain sleep would record the app frozen on whatever it showed last.
     */
    private fun hold(ms: Long) {
        val until = now() + ms
        var advanced = now()
        while (now() < until) {
            val elapsed = now() - advanced
            if (elapsed > 0) {
                composeRule.mainClock.advanceTimeBy(elapsed, ignoreFrameDuration = true)
                advanced += elapsed
            }
            val s = PlayerStateHolder.uiState.value
            samples.put(
                JSONObject()
                    .put("t", now())
                    .put("playing", s.isPlaying)
                    .put("intro", s.introLabel ?: JSONObject.NULL)
                    .put("globalId", s.currentTrack?.globalId ?: 0)
                    .put("positionMs", s.positionMs),
            )
            Thread.sleep(100)
        }
    }

    @Test
    fun record() {
        awaitTag(TAG_FULLSCREEN_WHOLE_AYAH)
        composeRule.waitUntil(15_000) {
            composeRule.onAllNodes(hasText("in Whose Hand", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        val recorder = automation.executeShellCommand(
            "screenrecord --size 720x1600 --bit-rate 3000000 --time-limit 170 $VIDEO",
        )
        startedAt = SystemClock.elapsedRealtime()
        hold(2_500)

        event("play")
        composeRule.onNode(hasText("Play") and hasClickAction()).performClick()
        hold(17_000)

        event("home")
        shell("input keyevent KEYCODE_HOME")
        hold(3_500)

        event("shade")
        shell("cmd statusbar expand-notifications")
        hold(3_500)
        event("pause")
        tapSystemUi("Pause")
        hold(3_000)
        event("resume")
        tapSystemUi("Play")
        hold(3_500)
        shell("cmd statusbar collapse")
        hold(1_000)

        event("screen_off")
        shell("input keyevent KEYCODE_SLEEP")
        hold(8_000)
        event("screen_on")
        shell("input keyevent KEYCODE_WAKEUP")
        hold(4_500)

        event("back_to_app")
        shell("wm dismiss-keyguard")
        val app = instrumentation.targetContext.packageName
        shell("am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n $app/${MainActivity::class.java.name}")
        hold(6_000)
        event("end")

        shell("pkill -INT screenrecord")
        Thread.sleep(3_000)
        recorder.close()
        saveTimeline()
    }

    /** Taps the System UI control described as [label] — the media player's play/pause. */
    private fun tapSystemUi(label: String) {
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        val roots = automation.windows.mapNotNull { it.root }
            .filter { it.packageName?.toString() == "com.android.systemui" }
        for (root in roots) {
            val node = root.findAccessibilityNodeInfosByText(label).firstOrNull {
                it.contentDescription?.toString() == label || it.text?.toString() == label
            }
            var target = node
            while (target != null && !target.isClickable) target = target.parent
            if (target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return
        }
        event("missed_$label")
    }

    /** The log, plus the clips it names, downloaded the way the app downloads them. */
    private fun saveTimeline() {
        val context = instrumentation.targetContext
        val repository = LibraryRepository(context)
        val clips = JSONObject()
        val ids = (0 until samples.length()).map { samples.getJSONObject(it).getInt("globalId") }
            .filter { it > 0 }.distinct()
        runBlocking {
            repository.cacheIntroClips()
            for (id in ids) {
                val (surah, ayah) = AyahMapping.globalToSurahAyah(id)
                val track = AyahTrack(
                    index = 0, globalId = id, surah = surah, ayah = ayah,
                    filename = AyahMapping.ayahFilename(surah, ayah),
                    remoteUrl = AyahMapping.remoteUrl(surah, ayah),
                )
                repository.cacheTrack(track)
                clips.put(id.toString(), "audio/${QuranConstants.RECITER_FOLDER}/${track.filename}")
            }
        }
        clips.put("A'udhu Billah", "audio/${QuranConstants.RECITER_FOLDER}/${QuranConstants.AUDHU_FILENAME}")
        clips.put("Bismillah", "audio/${QuranConstants.RECITER_FOLDER}/${QuranConstants.BISMILLAH_FILENAME}")
        val dir = File(context.filesDir, "video").apply { mkdirs() }
        File(dir, "timeline.json").writeText(
            JSONObject().put("events", events).put("samples", samples).put("clips", clips).toString(1),
        )
    }

    private companion object {
        const val VIDEO = "/data/local/tmp/fgs.mp4"
    }
}
