package com.quran.learnedplayer

import android.app.Application
import android.os.Build
import com.quran.learnedplayer.data.BookmarksStore
import com.quran.learnedplayer.data.LearnedAyahsStore
import com.quran.learnedplayer.player.PlayerSettings
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

class LearnedAyahsApp : Application() {
    override fun onCreate() {
        super.onCreate()
        PlayerSettings.init(this)
        LearnedAyahsStore.init(this)
        BookmarksStore.init(this)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                val version = runCatching {
                    @Suppress("DEPRECATION")
                    packageManager.getPackageInfo(packageName, 0).versionName
                }.getOrNull() ?: "?"
                File(filesDir, CRASH_LOG).writeText(
                    buildString {
                        append("Learned Ayahs $version\n")
                        append("Device: ${Build.MANUFACTURER} ${Build.MODEL}\n")
                        append("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
                        append("Time: ${java.time.Instant.now()}\n")
                        append("Thread: ${thread.name}\n\n")
                        append(sw.toString())
                    },
                )
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    companion object {
        const val CRASH_LOG = "last_crash.txt"
    }
}
