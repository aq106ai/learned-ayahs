package com.quran.learnedplayer

import android.graphics.Typeface
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.core.content.res.ResourcesCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.quran.learnedplayer.data.Reciter
import com.quran.learnedplayer.video.AudioPipeline
import com.quran.learnedplayer.video.ClipKind
import com.quran.learnedplayer.video.PcmResampler
import com.quran.learnedplayer.video.VideoAspect
import com.quran.learnedplayer.video.VideoExporter
import com.quran.learnedplayer.video.VideoRequest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * "Share as video" end to end on a device: the real decoder, encoder and muxer, fed the test
 * suite's own recordings of Al-Ikhlas so nothing is downloaded.
 */
@RunWith(AndroidJUnit4::class)
class VideoExportTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    /** Copies a recording out of the test APK's assets so MediaExtractor can open it as a file. */
    private fun recording(name: String): File =
        File(context.cacheDir, "test-$name").also { file ->
            if (!file.exists()) instrumentation.context.assets.open("recitation/$name").use { input ->
                file.outputStream().use { input.copyTo(it) }
            }
        }

    private fun source(kind: ClipKind, surah: Int, ayah: Int): File = when (kind) {
        // The test corpus has no separate intro clips; Al-Fatihah's first ayah stands in for both.
        ClipKind.AUDHU, ClipKind.BISMILLAH -> recording("001001.wav")
        ClipKind.AYAH -> recording(String.format(java.util.Locale.ROOT, "%03d%03d.wav", surah, ayah))
    }

    private val font: Typeface get() = ResourcesCompat.getFont(context, R.font.scheherazade_new)!!

    @Test
    fun a_recording_decodes_to_44_1_khz_stereo() {
        val pcm = AudioPipeline.decode(recording("112001.wav"))
        assertTrue("decoded something", pcm.isNotEmpty())
        assertEquals("stereo", 0, pcm.size % 2)
        val ms = PcmResampler.durationMs(pcm.size)
        assertTrue("112:1 is a few seconds long, was ${ms}ms", ms in 1_000..10_000)
    }

    @Test
    fun a_video_of_al_ikhlas_has_picture_and_sound_for_its_whole_length() = runBlocking {
        val request = VideoRequest(
            surah = 112, fromAyah = 1, toAyah = 4, reciter = Reciter.DEFAULT, aspect = VideoAspect.SQUARE,
            wordByWord = true, fullTranslation = true, opening = true, themeKey = null,
        )
        val output = File(context.cacheDir, "videos/test-al-ikhlas.mp4").apply { parentFile?.mkdirs(); delete() }
        val fps = 15
        var last = 0f
        VideoExporter(context, font).export(request, output, scale = 0.334f, fps = fps, sources = ::source) {
            assertTrue("progress only moves forward", it.fraction >= last - 0.001f)
            last = it.fraction
        }
        assertEquals(1f, last, 0.001f)

        val extractor = MediaExtractor().apply { setDataSource(output.absolutePath) }
        val formats = (0 until extractor.trackCount).map { extractor.getTrackFormat(it) }
        val video = formats.firstOrNull { it.getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_VIDEO_AVC }
        val audio = formats.firstOrNull { it.getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_AUDIO_AAC }
        assertNotNull("an H.264 track", video)
        assertNotNull("an AAC track", audio)
        assertEquals(360, video!!.getInteger(MediaFormat.KEY_WIDTH))
        assertEquals(360, video.getInteger(MediaFormat.KEY_HEIGHT))

        // The opening, Bismillah and four ayahs, at a known length: the picture lasts as long as the sound.
        val clips = request.clips().map { (kind, s, a) -> PcmResampler.durationMs(AudioPipeline.decode(source(kind, s, a)).size) }
        val expectedMs = 500 + clips.sum() + 400L * (clips.size - 1) + 1800
        val videoMs = video.getLong(MediaFormat.KEY_DURATION) / 1000
        val audioMs = audio!!.getLong(MediaFormat.KEY_DURATION) / 1000
        assertTrue("video ${videoMs}ms, expected about ${expectedMs}ms", kotlin.math.abs(videoMs - expectedMs) < 300)
        assertTrue("audio ${audioMs}ms, expected about ${expectedMs}ms", kotlin.math.abs(audioMs - expectedMs) < 300)

        // Every frame was encoded, with its own timestamp.
        val videoTrack = formats.indexOf(video)
        extractor.selectTrack(videoTrack)
        var frames = 0
        var previous = -1L
        while (extractor.sampleTrackIndex == videoTrack) {
            assertTrue("timestamps increase", extractor.sampleTime > previous || frames == 0)
            previous = extractor.sampleTime
            frames++
            extractor.advance()
        }
        extractor.release()
        val expectedFrames = expectedMs * fps / 1000
        assertTrue("$frames frames, expected about $expectedFrames", kotlin.math.abs(frames - expectedFrames) <= fps)
        output.delete()
    }

    @Test
    fun the_preview_is_drawn_at_the_shape_asked_for() {
        val request = VideoRequest(67, 1, 1, Reciter.DEFAULT, VideoAspect.PORTRAIT, true, true, false, "night")
        val preview = VideoExporter(context, font).preview(request, 270)
        assertEquals(270, preview.width)
        assertEquals(480, preview.height)
    }
}
