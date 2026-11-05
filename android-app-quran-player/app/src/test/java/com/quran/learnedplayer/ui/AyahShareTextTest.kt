package com.quran.learnedplayer.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class AyahShareTextTest {
    private val one = listOf(AyahShareText.Ayah(255, "ٱللَّهُ", "Allah"))
    private val two = listOf(
        AyahShareText.Ayah(1, "الأولى", "The first"),
        AyahShareText.Ayah(2, "الثانية", "The second"),
    )

    @Test
    fun arabic_then_english_then_the_reference() {
        assertEquals(
            "ٱللَّهُ\nAllah\n\nAl-Baqarah 2:255",
            AyahShareText.format("Al-Baqarah", 2, one, arabic = true, english = true),
        )
    }

    @Test
    fun either_language_can_be_left_out() {
        assertEquals("ٱللَّهُ\n\nAl-Baqarah 2:255", AyahShareText.format("Al-Baqarah", 2, one, arabic = true, english = false))
        assertEquals("Allah\n\nAl-Baqarah 2:255", AyahShareText.format("Al-Baqarah", 2, one, arabic = false, english = true))
    }

    @Test
    fun a_range_is_one_reference_after_every_ayah() {
        assertEquals(
            "الأولى\nThe first\n\nالثانية\nThe second\n\nAl-Ikhlas 112:1-2",
            AyahShareText.format("Al-Ikhlas", 112, two, arabic = true, english = true),
        )
    }
    @Test(expected = IllegalArgumentException::class)
    fun an_empty_range_is_rejected() {
        AyahShareText.format("Al-Ikhlas", 112, emptyList(), arabic = true, english = true)
    }

    @Test(expected = IllegalStateException::class)
    fun at_least_one_language_is_required() {
        AyahShareText.format("Al-Ikhlas", 112, one, arabic = false, english = false)
    }
}
