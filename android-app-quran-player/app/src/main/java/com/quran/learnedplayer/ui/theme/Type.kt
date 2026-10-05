package com.quran.learnedplayer.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.sp
import com.quran.learnedplayer.R

/** Default ayah font size (sp) in the inline panel; fullscreen scales this up. */
const val ARABIC_BASE_SIZE = 26f

/** Line-height multiplier applied to any Arabic font size. */
const val ARABIC_LINE_HEIGHT_RATIO = 1.9f

/**
 * Scheherazade New (SIL Open Font License) — a Naskh face in the South-Asian typographic
 * tradition, used as the IndoPak-style default for Qur'anic Arabic. Verified to cover every
 * codepoint in the bundled Uthmani text, including the Qur'anic annotation marks
 * (U+0610..U+061A, U+06D6..U+06ED), so no glyph falls back to tofu.
 */
val QuranArabicFamily = FontFamily(
    Font(R.font.scheherazade_new, FontWeight.Normal),
)

/**
 * Base style for rendering ayah text. Naskh faces need generous line height — the marks sit
 * well above/below the baseline — hence the 1.9x ratio (the old system font used 1.8x).
 */
val QuranArabicStyle = TextStyle(
    fontFamily = QuranArabicFamily,
    fontSize = ARABIC_BASE_SIZE.sp,
    lineHeight = (ARABIC_BASE_SIZE * ARABIC_LINE_HEIGHT_RATIO).sp,
    textDirection = TextDirection.Rtl,
)

/** Arabic text style at an explicit [sizeSp], keeping the line-height ratio consistent. */
fun quranArabicStyle(sizeSp: Float): TextStyle = QuranArabicStyle.copy(
    fontSize = sizeSp.sp,
    lineHeight = (sizeSp * ARABIC_LINE_HEIGHT_RATIO).sp,
)
