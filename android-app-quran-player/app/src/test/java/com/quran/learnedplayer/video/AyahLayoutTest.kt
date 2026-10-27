package com.quran.learnedplayer.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AyahLayoutTest {
    /** Every word is 100 px at size 100, scaling with the size. */
    private val measurer = object : TextMeasurer {
        override fun arabicWidth(text: String, sizePx: Float) = sizePx
        override fun glossWidth(text: String, sizePx: Float) = text.length * sizePx * 0.5f
    }

    private fun words(n: Int) = List(n) { LayoutWord("w$it", "gloss", it) } + LayoutWord("١", null, -1)

    @Test
    fun the_first_word_sits_at_the_right_end_of_the_line() {
        val pages = AyahLayout.layout(words(3), measurer, boxWidth = 1000f, boxHeight = 1000f, arabicSize = 100f, showGloss = false)
        val line = pages.pages.single().words
        assertEquals(listOf("w0", "w1", "w2", "١"), line.map { it.word.arabic })
        assertTrue("right to left", line.zipWithNext().all { (a, b) -> a.x > b.x })
        // The line is centred in the box.
        val left = line.minOf { it.x }
        val right = line.maxOf { it.x + it.width }
        assertEquals(1000f - right, left, 0.5f)
    }

    @Test
    fun a_long_ayah_shrinks_before_it_pages() {
        // At full size 4 lines of ~7 words fit; 30 words need 5 lines, so it shrinks to fit on one page.
        val pages = AyahLayout.layout(words(30), measurer, boxWidth = 1000f, boxHeight = 4 * 175f, arabicSize = 100f, showGloss = false)
        assertEquals(1, pages.pages.size)
        assertTrue(pages.arabicSize < 100f)
    }

    @Test
    fun a_very_long_ayah_is_split_into_pages_that_keep_every_word_once() {
        val pages = AyahLayout.layout(words(200), measurer, boxWidth = 1000f, boxHeight = 400f, arabicSize = 100f, showGloss = true)
        assertTrue(pages.pages.size > 1)
        assertEquals(AyahLayout.MIN_SCALE * 100f, pages.arabicSize, 0.01f)
        val all = pages.pages.flatMap { page -> page.words.map { it.word.contentIndex } }.filter { it >= 0 }
        assertEquals((0 until 200).toList(), all)
    }

    @Test
    fun a_word_meaning_wider_than_its_word_widens_the_column() {
        val wide = listOf(LayoutWord("w", "a much longer meaning", 0))
        val pages = AyahLayout.layout(wide, measurer, 2000f, 1000f, 100f, showGloss = true)
        assertTrue(pages.pages.single().words.single().width > 100f)
        val without = AyahLayout.layout(wide, measurer, 2000f, 1000f, 100f, showGloss = false)
        assertEquals(100f, without.pages.single().words.single().width, 0.01f)
    }

    @Test
    fun pages_turn_when_their_first_word_is_recited() {
        val pages = AyahLayout.layout(words(200), measurer, 1000f, 400f, 100f, showGloss = false)
        val segments = List(200) { (it * 100L) until (it * 100L + 90) }
        val second = pages.pages[1].firstContentIndex
        assertEquals(0, AyahLayout.pageAt(pages, segments[second].first - 1, segments, 20_000))
        assertEquals(1, AyahLayout.pageAt(pages, segments[second].first, segments, 20_000))
    }

    @Test
    fun without_timings_pages_share_the_ayah_by_word_count() {
        val pages = AyahLayout.layout(words(200), measurer, 1000f, 400f, 100f, showGloss = false)
        val count = pages.pages.size
        assertEquals(0, AyahLayout.pageAt(pages, 0, null, 10_000))
        assertEquals(count - 1, AyahLayout.pageAt(pages, 9_999, null, 10_000))
    }

    @Test
    fun a_translation_splits_at_word_boundaries_without_losing_words() {
        val text = "In the name of Allah, the Entirely Merciful, the Especially Merciful"
        val parts = AyahLayout.splitTranslation(text, 3)
        assertEquals(3, parts.size)
        assertEquals(text, parts.joinToString(" "))
        assertEquals(listOf(text), AyahLayout.splitTranslation(text, 1))
    }
}
