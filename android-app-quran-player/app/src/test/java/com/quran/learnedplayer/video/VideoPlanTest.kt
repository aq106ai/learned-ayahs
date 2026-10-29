package com.quran.learnedplayer.video

import com.quran.learnedplayer.data.Reciter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The timeline, the clips a request plays, the background plan and the audio conversion. */
class VideoPlanTest {
    private fun request(surah: Int, from: Int, to: Int, opening: Boolean = true, theme: String? = null) = VideoRequest(
        surah, from, to, Reciter.DEFAULT, VideoAspect.PORTRAIT,
        wordByWord = true, fullTranslation = true, opening = opening, themeKey = theme,
    )

    @Test
    fun the_opening_is_audhu_then_bismillah_except_where_the_surah_has_none() {
        assertEquals(
            listOf(ClipKind.AUDHU, ClipKind.BISMILLAH, ClipKind.AYAH, ClipKind.AYAH),
            request(67, 1, 2).clips().map { it.first },
        )
        // Al-Fatihah's first ayah is the Bismillah; At-Tawbah has none.
        assertEquals(listOf(ClipKind.AUDHU, ClipKind.AYAH), request(1, 1, 1).clips().map { it.first })
        assertEquals(listOf(ClipKind.AUDHU, ClipKind.AYAH), request(9, 1, 1).clips().map { it.first })
        // Starting mid-surah, there is no Bismillah.
        assertEquals(listOf(ClipKind.AUDHU, ClipKind.AYAH), request(67, 5, 5).clips().map { it.first })
        assertEquals(listOf(ClipKind.AYAH), request(67, 1, 1, opening = false).clips().map { it.first })
    }

    @Test(expected = IllegalArgumentException::class)
    fun ayahs_outside_the_surah_are_refused() {
        request(112, 1, 5)
    }

    @Test
    fun clips_are_laid_end_to_end_with_gaps_and_the_screen_holds_the_last_one() {
        val timeline = VideoTimeline.Builder().apply {
            add(ClipKind.AYAH, 1, 1, 3000)
            add(ClipKind.AYAH, 1, 2, 2000)
        }.build()
        val (a, b) = timeline.clips
        assertEquals(VideoTimeline.LEAD_IN_MS, a.startMs)
        assertEquals(a.endMs + VideoTimeline.GAP_MS, b.startMs)
        assertEquals(b.endMs + VideoTimeline.TAIL_MS, timeline.totalMs)
        assertEquals(a, timeline.clipAt(0))
        assertEquals(a, timeline.clipAt(a.endMs + 10)) // in the gap
        assertEquals(b, timeline.clipAt(timeline.totalMs))
    }

    @Test
    fun a_chosen_theme_holds_for_the_whole_video() {
        val clips = (1..20).map { TimelineClip(ClipKind.AYAH, 2, it, it * 20_000L, 19_000) }
        val slots = BackgroundPlan.plan(clips, "sea", themeOf = { _, a -> if (a % 2 == 0) "night" else "dawn" }, variantSeed = { _, a -> a })
        assertEquals(listOf("sea"), slots.map { it.themeKey })
    }

    @Test
    fun matched_backgrounds_change_with_the_ayahs_but_not_faster_than_the_hold() {
        // Ten short ayahs alternating between two themes: changes are held for MIN_HOLD_MS.
        val clips = (1..10).map { TimelineClip(ClipKind.AYAH, 2, it, it * 4_000L, 3_500) }
        val slots = BackgroundPlan.plan(clips, null, themeOf = { _, a -> if (a % 2 == 0) "night" else "dawn" }, variantSeed = { _, a -> a })
        assertEquals(0L, slots.first().startMs)
        assertTrue(slots.zipWithNext().all { (x, y) -> y.startMs - x.startMs >= BackgroundPlan.MIN_HOLD_MS && x.themeKey != y.themeKey })
        assertTrue(slots.size in 2..4)
    }

    @Test
    fun a_new_background_fades_in_over_the_old_one() {
        val slots = listOf(BackgroundSlot(0, "dawn", 0), BackgroundSlot(20_000, "night", 1))
        val (current, previous, fade) = BackgroundPlan.at(slots, 20_000 + BackgroundPlan.CROSSFADE_MS / 2)
        assertEquals("night", current.themeKey)
        assertEquals("dawn", previous?.themeKey)
        assertEquals(0.5f, fade, 0.01f)
        assertNull(BackgroundPlan.at(slots, 30_000).second)
    }

    @Test
    fun audio_becomes_stereo_at_44_1_khz() {
        val mono = ShortArray(22_050) { (it % 100).toShort() }
        val out = PcmResampler.toStereo(mono, rate = 22_050, channels = 1)
        assertEquals(44_100 * 2, out.size)
        assertEquals(1000L, PcmResampler.durationMs(out.size))
        assertEquals(out[0], out[1]) // both channels carry the mono signal
        val stereo = shortArrayOf(1, 2, 3, 4)
        assertTrue(stereo.contentEquals(PcmResampler.toStereo(stereo, 44_100, 2)))
    }

    @Test
    fun every_size_is_even_as_h264_needs() {
        for (aspect in VideoAspect.entries) for (f in listOf(1f, 0.75f, 0.667f, 0.5f, 0.333f)) {
            val (w, h) = aspect.size(f)
            assertTrue("$aspect $f", w % 2 == 0 && h % 2 == 0)
        }
    }
}
