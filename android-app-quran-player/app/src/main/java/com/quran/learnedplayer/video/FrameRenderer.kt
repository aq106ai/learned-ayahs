package com.quran.learnedplayer.video

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.quran.learnedplayer.data.SurahNames
import com.quran.learnedplayer.data.WordSync
import kotlin.math.min

/** What one clip shows: its words, their timings for this reciter, and its translation. */
class ClipText(
    val words: List<LayoutWord>,
    /** Per-word timings for the chosen reciter; null means don't highlight (never guessed). */
    val segments: List<LongRange>?,
    val translation: String?,
    /** "Al-Mulk · 67:3", or the intro's name. */
    val reference: String,
)

/**
 * Draws one frame of the video: the background, then a translucent card holding the Arabic, with
 * the word being recited lit up, the word meanings under each word and the translation when they
 * are on, then the reference above and the attribution below.
 *
 * Long ayahs are split into pages ([AyahLayout]); a page turns as its first word is recited.
 */
class FrameRenderer(
    private val width: Int,
    private val height: Int,
    private val request: VideoRequest,
    private val timeline: VideoTimeline,
    private val slots: List<BackgroundSlot>,
    private val textFor: (TimelineClip) -> ClipText,
    arabicTypeface: Typeface,
    private val latinTypeface: Typeface = Typeface.DEFAULT,
) {
    private val w = width.toFloat()
    private val h = height.toFloat()
    private val u = min(width, height) / 1080f
    private val landscape = width > height
    private val scenes = BackgroundScenes(width, height)

    private val arabicPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        typeface = arabicTypeface
        textAlign = Paint.Align.CENTER
    }
    private val glossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = latinTypeface
        textAlign = Paint.Align.CENTER
    }
    private val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = latinTypeface }
    private val translationPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = latinTypeface; color = TRANSLATION }
    private val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val measurer = object : TextMeasurer {
        override fun arabicWidth(text: String, sizePx: Float): Float {
            arabicPaint.textSize = sizePx
            return arabicPaint.measureText(text)
        }

        override fun glossWidth(text: String, sizePx: Float): Float {
            glossPaint.textSize = sizePx
            return glossPaint.measureText(text)
        }
    }

    // The card and the boxes inside it.
    private val card: RectF = when {
        landscape -> RectF(w * 0.08f, h * 0.15f, w * 0.92f, h * 0.84f)
        height > width * 1.5f -> RectF(w * 0.06f, h * 0.17f, w * 0.94f, h * 0.83f)
        else -> RectF(w * 0.06f, h * 0.13f, w * 0.94f, h * 0.85f)
    }
    private val pad = 36f * u
    private val translationShare = if (request.fullTranslation) (if (landscape) 0.3f else 0.32f) else 0f
    private val arabicBox = RectF(card.left + pad, card.top + pad, card.right - pad, card.bottom - pad - card.height() * translationShare)
    private val translationBox = RectF(card.left + pad * 1.2f, arabicBox.bottom + 10f * u, card.right - pad * 1.2f, card.bottom - pad)
    private val arabicSize = when {
        landscape -> 64f * u
        height > width * 1.5f -> 82f * u
        else -> 72f * u
    }

    private val pagesCache = HashMap<TimelineClip, Pair<ClipText, AyahPages>>()
    private val translationCache = HashMap<Triple<TimelineClip, Int, Int>, StaticLayout>()

    fun draw(canvas: Canvas, ms: Long) {
        val (slot, previous, fade) = BackgroundPlan.at(slots, ms)
        if (previous != null) scenes.draw(canvas, VideoThemes.variant(previous.themeKey, previous.variant), ms)
        scenes.draw(canvas, VideoThemes.variant(slot.themeKey, slot.variant), ms, (fade * 255).toInt())

        val clip = timeline.clipAt(ms) ?: return
        val (text, pages) = pagesCache.getOrPut(clip) {
            val t = textFor(clip)
            t to AyahLayout.layout(t.words, measurer, arabicBox.width(), arabicBox.height(), arabicSize, request.wordByWord)
        }
        val position = (ms - clip.startMs).coerceIn(0, clip.durationMs)
        val pageIndex = AyahLayout.pageAt(pages, position, text.segments, clip.durationMs)
        val page = pages.pages[pageIndex]
        val active = if (ms in clip.startMs until clip.endMs && text.segments != null) {
            WordSync.activeWordIndex(position, text.segments, text.words.count { it.contentIndex >= 0 })
        } else -1

        drawCard(canvas)
        drawArabic(canvas, pages, page, active)
        if (request.fullTranslation && text.translation != null) {
            drawTranslation(canvas, clip, text.translation, pageIndex, pages.pages.size)
        }
        drawLabels(canvas, text)
    }

    fun release() = scenes.release()

    private fun drawCard(canvas: Canvas) {
        cardPaint.color = Color.argb(118, 4, 8, 10)
        canvas.drawRoundRect(card, 34f * u, 34f * u, cardPaint)
        cardPaint.style = Paint.Style.STROKE
        cardPaint.strokeWidth = 2f * u
        cardPaint.color = Color.argb(70, 255, 236, 190)
        canvas.drawRoundRect(card, 34f * u, 34f * u, cardPaint)
        cardPaint.style = Paint.Style.FILL
    }

    private fun drawArabic(canvas: Canvas, pages: AyahPages, page: TextPage, active: Int) {
        val used = page.lines * pages.lineHeight
        val top = arabicBox.top + (arabicBox.height() - used) / 2f
        arabicPaint.textSize = pages.arabicSize
        glossPaint.textSize = pages.glossSize
        for (placed in page.words) {
            val lineTop = top + placed.line * pages.lineHeight
            val baseline = lineTop + pages.arabicSize * 1.2f
            val centre = arabicBox.left + placed.x + placed.width / 2f
            val isActive = placed.word.contentIndex >= 0 && placed.word.contentIndex == active
            val isEnd = placed.word.contentIndex < 0
            arabicPaint.color = when {
                isActive -> HIGHLIGHT
                isEnd -> END_GLYPH
                else -> Color.WHITE
            }
            arabicPaint.setShadowLayer(8f * u, 0f, 2f * u, Color.argb(160, 0, 0, 0))
            canvas.drawText(placed.word.arabic, centre, baseline, arabicPaint)
            if (pages.showGloss && placed.word.gloss != null) {
                glossPaint.color = if (isActive) HIGHLIGHT else GLOSS
                canvas.drawText(placed.word.gloss, centre, baseline + pages.arabicSize * 0.48f + pages.glossSize, glossPaint)
            }
        }
    }

    private fun drawTranslation(canvas: Canvas, clip: TimelineClip, translation: String, pageIndex: Int, pageCount: Int) {
        val layout = translationCache.getOrPut(Triple(clip, pageIndex, pageCount)) {
            val part = AyahLayout.splitTranslation(translation, pageCount)[pageIndex]
            fitLayout(part, translationBox.width().toInt(), translationBox.height())
        }
        canvas.save()
        canvas.translate(translationBox.left, translationBox.top + (translationBox.height() - layout.height) / 2f)
        layout.draw(canvas)
        canvas.restore()
    }

    /** The translation at the largest size, down to a floor, that fits its box. */
    private fun fitLayout(text: String, boxWidth: Int, boxHeight: Float): StaticLayout {
        var size = (if (landscape) 34f else 38f) * u
        var layout: StaticLayout
        do {
            translationPaint.textSize = size
            layout = StaticLayout.Builder.obtain(text, 0, text.length, translationPaint, boxWidth)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setLineSpacing(0f, 1.18f)
                .build()
            size *= 0.92f
        } while (layout.height > boxHeight && size > 18f * u)
        return layout
    }

    private fun drawLabels(canvas: Canvas, text: ClipText) {
        labelPaint.textAlign = Paint.Align.CENTER
        labelPaint.color = Color.WHITE
        labelPaint.textSize = 40f * u
        labelPaint.setShadowLayer(8f * u, 0f, 2f * u, Color.argb(170, 0, 0, 0))
        canvas.drawText(text.reference, w / 2, card.top - 42f * u, labelPaint)

        labelPaint.textSize = 26f * u
        labelPaint.color = Color.argb(225, 255, 255, 255)
        val credits = buildList {
            add("Recitation: ${request.reciter.displayName} · everyayah.com")
            if (request.fullTranslation) add("Translation: Saheeh International · tanzil.net")
            if (request.wordByWord) add("Word meanings: Quran.com")
        }
        var y = card.bottom + 50f * u
        for (line in credits) {
            canvas.drawText(line, w / 2, y, labelPaint)
            y += 34f * u
        }
        labelPaint.color = Color.argb(150, 255, 255, 255)
        labelPaint.textSize = 22f * u
        canvas.drawText("Made with Learned Ayahs", w / 2, h - 34f * u, labelPaint)
    }

    companion object {
        val HIGHLIGHT = Color.rgb(255, 214, 102)
        val GLOSS = Color.argb(215, 214, 232, 222)
        val END_GLYPH = Color.argb(200, 255, 236, 190)
        val TRANSLATION = Color.argb(240, 240, 244, 240)

        fun reference(clip: TimelineClip): String = when (clip.kind) {
            ClipKind.AUDHU -> "Isti'adhah"
            ClipKind.BISMILLAH -> "Basmalah"
            ClipKind.AYAH -> "${SurahNames.name(clip.surah)} · ${clip.surah}:${clip.ayah}"
        }
    }
}
