package com.quran.learnedplayer.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.quran.learnedplayer.player.PlayerSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WordAudioDownloaderTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun setUp() {
        PlayerSettings.init(context)
        PlayerSettings.wordReciter = WordReciter.DEFAULT
    }

    @Test
    fun attachLocalPaths_only_when_file_exists_and_nonempty() {
        val downloader = WordAudioDownloader(context)
        val items = WordAudio.itemsFor(WordReciter.QURAN_COM, 1, 2, 2)
        val dest = downloader.localFileFor(items[0])
        dest.parentFile!!.mkdirs()
        dest.writeBytes(byteArrayOf(1, 2, 3, 4))

        val attached = downloader.attachLocalPathsSync(items)
        assertEquals(dest.absolutePath, attached[0].localPath)
        assertNull(attached[1].localPath)
        assertEquals(items[1].remoteUrl, attached[1].remoteUrl)
    }

    @Test
    fun localFileFor_lives_under_wbw_reciter_folder() {
        val downloader = WordAudioDownloader(context)
        val item = WordAudio.item(WordReciter.QURAN_COM, 1, 1, 0)
        val file = downloader.localFileFor(item)
        assertEquals("001_001_001.mp3", file.name)
        assertTrue(file.path.replace('\\', '/').contains("audio/wbw/${WordReciter.QURAN_COM.folder}"))
    }
}
