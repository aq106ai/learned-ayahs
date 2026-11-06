package com.quran.learnedplayer.video

import android.content.Context
import com.quran.learnedplayer.data.AyahMapping
import com.quran.learnedplayer.data.ContentRepository
import org.json.JSONObject

/**
 * What video export and the player's translation read: the ayah in the chosen translation
 * edition ([ContentRepository]; Saheeh International, from Tanzil.net, is bundled and the
 * fallback) and `video_themes.json` (each ayah's background theme, from
 * `.setup/make_video_themes.py`), loaded once per process.
 */
class VideoTexts(context: Context) {
    private val appContext = context.applicationContext

    init {
        ContentRepository.init(appContext)
    }

    /** Blocking (it may download the chosen edition the first time). */
    fun translation(surah: Int, ayah: Int): String? = ContentRepository.ayahTranslation(surah, ayah)

    fun themeKey(surah: Int, ayah: Int): String = synchronized(lock) {
        if (themeKeys == null) load(appContext)
        val keys = themeKeys ?: return VideoThemes.FALLBACK
        val index = themeIndex?.getOrNull(AyahMapping.surahAyahToGlobal(surah, ayah) - 1) ?: return VideoThemes.FALLBACK
        keys.getOrNull(index) ?: VideoThemes.FALLBACK
    }

    private companion object {
        val lock = Any()
        var themeKeys: List<String>? = null
        var themeIndex: IntArray? = null

        fun load(context: Context) {
            if (themeKeys == null) {
                runCatching {
                    val json = JSONObject(context.assets.open("video_themes.json").bufferedReader().use { it.readText() })
                    val keys = json.getJSONArray("themes")
                    val ayahs = json.getJSONArray("ayahs")
                    themeKeys = List(keys.length()) { keys.getString(it) }
                    themeIndex = IntArray(ayahs.length()) { ayahs.getInt(it) }
                }
            }
        }
    }
}
