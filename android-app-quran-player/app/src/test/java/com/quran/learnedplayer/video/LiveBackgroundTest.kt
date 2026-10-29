package com.quran.learnedplayer.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiveBackgroundTest {
    private val night = SceneKey("night", 1)
    private val rain = SceneKey("rain", 2)
    private val sea = SceneKey("sea", 3)

    @Test
    fun `nothing shows until a background is asked for`() {
        assertNull(LiveBackground().at(0))
    }

    @Test
    fun `the first background shows at once, without a fade`() {
        val live = LiveBackground()
        live.want(night, 5_000)
        assertEquals(BackgroundFrame(night, null, 1f), live.at(5_000))
    }

    @Test
    fun `a new theme waits for the hold, then fades in`() {
        val live = LiveBackground(holdMs = 12_000, fadeMs = 900)
        live.want(night, 0)
        live.want(rain, 4_000)
        assertEquals(night, live.at(4_000)!!.current)

        live.want(rain, 13_000)
        val frame = live.at(13_450)!!
        assertEquals(rain, frame.current)
        assertEquals(night, frame.previous)
        assertEquals(0.5f, frame.fade, 0.001f)
        assertEquals(BackgroundFrame(rain, null, 1f), live.at(14_000))
    }

    @Test
    fun `another variant of the same theme is not a change`() {
        val live = LiveBackground(holdMs = 0)
        live.want(night, 0)
        live.want(SceneKey("night", 7), 60_000)
        assertEquals(night, live.at(60_000)!!.current)
    }

    @Test
    fun `picking a background skips the hold`() {
        val live = LiveBackground(holdMs = 12_000, fadeMs = 900)
        live.want(night, 0)
        live.want(sea, 1_000, immediately = true)
        assertEquals(sea, live.at(1_000)!!.current)
        assertEquals(night, live.at(1_000)!!.previous)
    }

    @Test
    fun `clear goes back to nothing`() {
        val live = LiveBackground()
        live.want(night, 0)
        live.clear()
        assertNull(live.at(1_000))
    }

    @Test
    fun `each choice picks its theme`() {
        val photoFor = { drawn: String -> if (drawn == "night") "photo:night_sky" else null }
        assertNull(PlayerBackgrounds.themeFor(PlayerBackgrounds.PLAIN, "night", photoFor))
        assertEquals("night", PlayerBackgrounds.themeFor(PlayerBackgrounds.MATCHED, "night", photoFor))
        assertEquals("photo:night_sky", PlayerBackgrounds.themeFor(PlayerBackgrounds.PHOTOS, "night", photoFor))
        // No subject suits it: the drawn theme stands in.
        assertEquals("rain", PlayerBackgrounds.themeFor(PlayerBackgrounds.PHOTOS, "rain", photoFor))
        // A chosen theme holds whatever the ayah is about.
        assertEquals("photo:forest", PlayerBackgrounds.themeFor("photo:forest", "night", photoFor))
        assertEquals("sea", PlayerBackgrounds.themeFor("sea", "night", photoFor))
    }
}
