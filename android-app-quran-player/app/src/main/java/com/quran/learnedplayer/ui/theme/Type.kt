package com.quran.learnedplayer.ui.theme

import androidx.annotation.FontRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.quran.learnedplayer.player.PlayerSettings
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

/**
 * The mushaf styles: one font per catalog id (`content_catalog.json` "mushafStyles"). Both are SIL
 * OFL and both cover every codepoint of quran_text.json (checked with fontTools, 81 of 81). Faces
 * that misdraw the Uthmani text — Digital Khatt's Madina breaks صِرَٰطَ at the dagger alif — are
 * not offered; see CLAUDE.md before adding one.
 */
object QuranFonts {
    const val SCHEHERAZADE = "uthmani-scheherazade"
    const val AMIRI = "uthmani-amiri"

    @FontRes
    fun fontRes(style: String): Int = when (style) {
        AMIRI -> R.font.amiri_quran
        else -> R.font.scheherazade_new
    }

    private val amiri = FontFamily(Font(R.font.amiri_quran, FontWeight.Normal))

    fun family(style: String): FontFamily = if (style == AMIRI) amiri else QuranArabicFamily
}

/** Arabic text style at an explicit [sizeSp] in the chosen mushaf style, keeping the line-height ratio. */
@Composable
fun quranArabicStyle(sizeSp: Float): TextStyle {
    val style by PlayerSettings.mushafStyleFlow.collectAsState()
    return QuranArabicStyle.copy(
        fontFamily = QuranFonts.family(style),
        fontSize = sizeSp.sp,
        lineHeight = (sizeSp * ARABIC_LINE_HEIGHT_RATIO).sp,
    )
}
