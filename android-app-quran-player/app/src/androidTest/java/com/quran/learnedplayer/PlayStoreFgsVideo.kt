package com.quran.learnedplayer

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.quran.learnedplayer.data.AyahMapping
import com.quran.learnedplayer.data.AyahTrack
import com.quran.learnedplayer.data.LibraryRepository
import com.quran.learnedplayer.data.QuranConstants
import com.quran.learnedplayer.player.PlayerSettings
import com.quran.learnedplayer.service.PlayerStateHolder
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import java.io.File

/**
 * Not a test: records the video Google Play asks for with the foreground-service declaration —
 * recitation the user starts, carrying on in the background and with the screen off, controlled
 * from the notification and the lock screen. `store/capture-video.sh` runs it; it is left out of
 * the instrumented test runs.
 *
 * It launches the app itself and taps through accessibility, as a person would, rather than
 * using the Compose test rule: under that rule the app only redraws when the test advances the
 * rule's clock, and a recording made that way shows the app lagging behind its own audio.
 *
 * Android's screen recorder has no sound, so while it records this logs, ten times a second,
 * which clip is playing and how far into it — then downloads those clips through the app's own
 * downloader. `store/render/render-video.cjs` rebuilds the soundtrack from that log.
 */
@RunWith(AndroidJUnit4::class)
class PlayStoreFgsVideo {

    @get:Rule
    val notificationPermission: TestRule =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            TestRule { base, _ -> base }
        }

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation
    private val app get() = instrumentation.targetContext.packageName
    private val events = JSONArray()
    private val samples = java.util.concurrent.ConcurrentLinkedQueue<JSONObject>()
    @Volatile private var startedAt = 0L
    @Volatile private var recording = false

    private fun now() = SystemClock.elapsedRealtime() - startedAt

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command))
            .use { it.readBytes().decodeToString() }

    private fun event(name: String) {
        events.put(JSONObject().put("t", now()).put("event", name))
    }

    private fun hold(ms: Long) = Thread.sleep(ms)

    /**
     * Logs what is playing ten times a second, on its own thread, so that a slow step — a tap
     * that takes a while to land — never leaves a hole in the log.
     */
    private fun startSampling() = Thread {
        while (recording) {
            val s = PlayerStateHolder.uiState.value
            samples.add(
                JSONObject()
                    .put("t", now())
                    .put("playing", s.isPlaying)
                    .put("intro", s.introLabel ?: JSONObject.NULL)
                    .put("globalId", s.currentTrack?.globalId ?: 0)
                    .put("positionMs", s.positionMs),
            )
            Thread.sleep(100)
        }
    }.apply { isDaemon = true; start() }

    @Test
    fun record() {
        AppReset.reset((1..QuranConstants.VERSE_COUNTS[67 - 1]).map { AyahMapping.surahAyahToGlobal(67, it) }.toSet())
        PlayerSettings.lastGlobalId = AyahMapping.surahAyahToGlobal(67, 1)
        AppReset.wakeAndUnlock()
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }

        ActivityScenario.launch(MainActivity::class.java).use {
            // Ready when the app's own state has 67:1 with its words — not judged by what
            // accessibility can see, which is only how the taps are made.
            val ready = waitUntil(30_000) {
                PlayerStateHolder.uiState.value.let {
                    it.currentTrack?.globalId == AyahMapping.surahAyahToGlobal(67, 1) && it.ayahWords.isNotEmpty()
                }
            }
            check(ready) { "The reader never showed Al-Mulk 67:1. Accessibility saw: ${visibleText(app)}" }
            Thread.sleep(2_000)

            val recorder = automation.executeShellCommand(
                "screenrecord --size 720x1600 --bit-rate 3000000 --time-limit 170 $VIDEO",
            )
            startedAt = SystemClock.elapsedRealtime()
            recording = true
            val sampler = startSampling()
            hold(2_500)

            event("play")
            if (!tap(app, "Play")) {
                // A headset's play button reaches the same service; the tap is what reads best.
                event("play_by_media_key: ${visibleText(app)}")
                shell("input keyevent KEYCODE_MEDIA_PLAY")
            }
            hold(17_000)

            event("home")
            shell("input keyevent KEYCODE_HOME")
            hold(3_500)

            event("shade")
            shell("cmd statusbar expand-notifications")
            hold(3_500)
            event("pause")
            tap(SYSTEM_UI, "Pause")
            hold(3_000)
            event("resume")
            tap(SYSTEM_UI, "Play")
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
            shell("am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n $app/${MainActivity::class.java.name}")
            hold(7_000)
            event("end")
            recording = false
            sampler.join()

            shell("pkill -INT screenrecord")
            Thread.sleep(3_000)
            recorder.close()
        }
        saveTimeline()
    }

    /** The windows of [packageName] that accessibility can see. */
    private fun roots(packageName: String): List<AccessibilityNodeInfo> =
        automation.windows.mapNotNull { it.root }.filter { it.packageName?.toString() == packageName }

    /**
     * The first node in [packageName]'s windows that [matches], found by walking the tree:
     * Compose exposes its UI as virtual nodes, which findAccessibilityNodeInfosByText may not search.
     * Slow on a big View tree such as System UI's, so [tap] tries the native search first.
     */
    private fun find(packageName: String, matches: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        val queue = ArrayDeque(roots(packageName))
        var visited = 0
        while (queue.isNotEmpty() && visited++ < 5_000) {
            val node = queue.removeFirst()
            if (matches(node)) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let(queue::addLast)
        }
        return null
    }

    private fun labelled(label: String): (AccessibilityNodeInfo) -> Boolean =
        { it.text?.toString() == label || it.contentDescription?.toString() == label }

    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val until = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < until) {
            if (condition()) return true
            Thread.sleep(200)
        }
        return false
    }

    /** What accessibility can read in [packageName]'s windows, for a failure to report. */
    private fun visibleText(packageName: String): String {
        val seen = mutableListOf<String>()
        find(packageName) { node ->
            (node.text ?: node.contentDescription)?.toString()?.takeIf { it.isNotBlank() }?.let(seen::add)
            false
        }
        val windows = automation.windows.joinToString { "${it.root?.packageName}" }
        return "windows [$windows], text ${seen.take(40)}"
    }

    /** Taps the control labelled [label] in [packageName]'s windows, as a person would. */
    private fun tap(packageName: String, label: String): Boolean {
        var node = roots(packageName).firstNotNullOfOrNull { root ->
            root.findAccessibilityNodeInfosByText(label).firstOrNull(labelled(label))
        } ?: find(packageName, labelled(label))
        while (node != null && !node.isClickable) node = node.parent
        val tapped = node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
        if (!tapped) event("missed_$label")
        return tapped
    }

    /** The log, plus the clips it names, downloaded the way the app downloads them. */
    private fun saveTimeline() {
        val context = instrumentation.targetContext
        val repository = LibraryRepository(context)
        val clips = JSONObject()
        val ids = samples.map { it.getInt("globalId") }.filter { it > 0 }.distinct()
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
            JSONObject().put("events", events).put("samples", JSONArray(samples.toList()))
                .put("clips", clips).toString(1),
        )
    }

    private companion object {
        const val VIDEO = "/data/local/tmp/fgs.mp4"
        const val SYSTEM_UI = "com.android.systemui"
    }
}
