package com.quran.learnedplayer.video

/** Text widths, measured by the real fonts on a device and by a stub in tests. */
interface TextMeasurer {
    fun arabicWidth(text: String, sizePx: Float): Float
    fun glossWidth(text: String, sizePx: Float): Float
}

/**
 * One word of an ayah as the video shows it. [contentIndex] numbers the recited words from 0, the
 * way word timings do; the end-of-ayah number glyph has none (-1) and is never highlighted.
 */
data class LayoutWord(
    val arabic: String,
    val gloss: String?,
    val contentIndex: Int,
)

/** A word placed on a page: [x] is its left edge within the text box, [width] its column. */
data class PlacedWord(val word: LayoutWord, val line: Int, val x: Float, val width: Float)

/** What one screenful of an ayah holds, and which recited words it covers. */
data class TextPage(val words: List<PlacedWord>, val lines: Int) {
    /** First recited word on the page, or -1 if it holds only the end glyph. */
    val firstContentIndex: Int get() = words.firstOrNull { it.word.contentIndex >= 0 }?.word?.contentIndex ?: -1
    val contentCount: Int get() = words.count { it.word.contentIndex >= 0 }
}

data class AyahPages(
    val pages: List<TextPage>,
    val arabicSize: Float,
    val glossSize: Float,
    val lineHeight: Float,
    val showGloss: Boolean,
)

/**
 * Lays an ayah out right to left in a box, one word per column with its meaning under it when
 * word-by-word is on, and splits it into pages when it doesn't fit.
 *
 * It first shrinks the text, down to [MIN_SCALE] of the size asked for, to keep an ayah on one
 * screen; only past that does it page, because a page turn mid-ayah is harder to follow than
 * slightly smaller text. Lines are centred, as in a mushaf's short surahs.
 */
object AyahLayout {
    private val SCALES = floatArrayOf(1f, 0.9f, 0.8f, 0.72f, 0.66f)
    val MIN_SCALE = SCALES.last()

    fun layout(
        words: List<LayoutWord>,
        measurer: TextMeasurer,
        boxWidth: Float,
        boxHeight: Float,
        arabicSize: Float,
        showGloss: Boolean,
    ): AyahPages {
        var last: AyahPages? = null
        for (scale in SCALES) {
            val attempt = layoutAt(words, measurer, boxWidth, boxHeight, arabicSize * scale, showGloss)
            if (attempt.pages.size <= 1) return attempt
            last = attempt
        }
        return last!!
    }

    private fun layoutAt(
        words: List<LayoutWord>,
        measurer: TextMeasurer,
        boxWidth: Float,
        boxHeight: Float,
        arabicSize: Float,
        showGloss: Boolean,
    ): AyahPages {
        val glossSize = arabicSize * 0.36f
        val lineHeight = arabicSize * 1.75f + if (showGloss) glossSize * 1.5f else 0f
        val gap = arabicSize * 0.32f
        val linesPerPage = (boxHeight / lineHeight).toInt().coerceAtLeast(1)

        // Break into lines, right to left: each line is the words that fit across the box.
        val columns = words.map { word ->
            val arabic = measurer.arabicWidth(word.arabic, arabicSize)
            val gloss = if (showGloss && word.gloss != null) measurer.glossWidth(word.gloss, glossSize) else 0f
            word to maxOf(arabic, gloss).coerceAtMost(boxWidth)
        }
        val lines = mutableListOf<MutableList<Pair<LayoutWord, Float>>>()
        var used = 0f
        for (column in columns) {
            val need = if (lines.isEmpty() || lines.last().isEmpty()) column.second else gap + column.second
            if (lines.isEmpty() || used + need > boxWidth) {
                lines += mutableListOf(column)
                used = column.second
            } else {
                lines.last() += column
                used += need
            }
        }

        val pages = lines.chunked(linesPerPage).map { pageLines ->
            val placed = mutableListOf<PlacedWord>()
            pageLines.forEachIndexed { lineIndex, line ->
                val lineWidth = line.sumOf { it.second.toDouble() }.toFloat() + gap * (line.size - 1)
                // Right-to-left: the first word sits at the right end of the centred line.
                var right = (boxWidth + lineWidth) / 2f
                for ((word, width) in line) {
                    placed += PlacedWord(word, lineIndex, right - width, width)
                    right -= width + gap
                }
            }
            TextPage(placed, pageLines.size)
        }
        return AyahPages(pages.ifEmpty { listOf(TextPage(emptyList(), 0)) }, arabicSize, glossSize, lineHeight, showGloss)
    }

    /**
     * The page showing at [positionMs] into the ayah's audio. With word timings a page turns as
     * its first word is recited; without them, the ayah's length is shared out by word count.
     */
    fun pageAt(pages: AyahPages, positionMs: Long, segments: List<LongRange>?, durationMs: Long): Int {
        if (pages.pages.size <= 1) return 0
        val totalWords = pages.pages.sumOf { it.contentCount }.coerceAtLeast(1)
        var shown = 0
        var wordsBefore = 0
        pages.pages.forEachIndexed { index, page ->
            val first = page.firstContentIndex
            val startMs = when {
                index == 0 -> 0L
                segments != null && first in segments.indices -> segments[first].first
                else -> durationMs * wordsBefore / totalWords
            }
            if (positionMs >= startMs) shown = index
            wordsBefore += page.contentCount
        }
        return shown
    }

    /**
     * Splits a translation into [parts] pieces at word boundaries, in proportion, to go with an
     * ayah shown over that many pages. The English never lines up word for word with the Arabic,
     * so this keeps each piece about as long as its page's share of the ayah, no more.
     */
    fun splitTranslation(text: String, parts: Int): List<String> {
        if (parts <= 1) return listOf(text)
        val tokens = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.size < parts) return listOf(text) + List(parts - 1) { "" }
        return (0 until parts).map { part ->
            val from = tokens.size * part / parts
            val to = tokens.size * (part + 1) / parts
            tokens.subList(from, to).joinToString(" ")
        }
    }
}
