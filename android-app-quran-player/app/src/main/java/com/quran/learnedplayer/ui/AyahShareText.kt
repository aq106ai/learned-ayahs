package com.quran.learnedplayer.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import com.quran.learnedplayer.data.QuranDataRepository
import com.quran.learnedplayer.data.SurahNames
import com.quran.learnedplayer.video.VideoTexts

/**
 * An ayah, or a run of them, as plain text: the Arabic, then the English, then the
 * surah:ayah reference. Either language can be left out. Nothing else — no reciter
 * credit, no app line.
 */
object AyahShareText {
    data class Ayah(val number: Int, val arabic: String, val english: String?)

    fun format(surahName: String, surah: Int, ayahs: List<Ayah>, arabic: Boolean, english: Boolean): String {
        require(ayahs.isNotEmpty()) { "Choose at least one ayah" }
        check(arabic || english) { "Share at least the Arabic or the English" }
        val body = ayahs.joinToString("\n\n") { ayah ->
            buildList {
                if (arabic && ayah.arabic.isNotBlank()) add(ayah.arabic)
                if (english && !ayah.english.isNullOrBlank()) add(ayah.english)
            }.joinToString("\n")
        }.trim()
        val numbers = ayahs.map { it.number }
        val range = if (numbers.size == 1) "${numbers.first()}" else "${numbers.first()}-${numbers.last()}"
        val reference = "$surahName $surah:$range"
        return if (body.isEmpty()) reference else "$body\n\n$reference"
    }

    /** The ayahs [from]..[to] of [surah], from the bundled text. */
    fun of(context: Context, surah: Int, from: Int, to: Int, arabic: Boolean, english: Boolean): String {
        val quran = QuranDataRepository(context.applicationContext)
        val texts = VideoTexts(context.applicationContext)
        val ayahs = (from..to).map { ayah ->
            val words = quran.wordsForSync(surah, ayah).filter { !it.isEnd }.joinToString(" ") { it.text }
            if (arabic) check(words.isNotBlank()) { "The Arabic text is unavailable. Please try again." }
            val translated = if (english) texts.translation(surah, ayah) else null
            if (english) check(!translated.isNullOrBlank()) { "The English text is unavailable. Please try again." }
            Ayah(ayah, words, translated)
        }
        return format(SurahNames.name(surah), surah, ayahs, arabic, english)
    }

    fun copy(context: Context, text: String) {
        context.getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("Ayah text", text))
    }

    fun intent(context: Context, surah: Int, from: Int, to: Int, arabic: Boolean, english: Boolean): Intent {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, of(context, surah, from, to, arabic, english))
        }
        return Intent.createChooser(send, "Share text")
    }
}
