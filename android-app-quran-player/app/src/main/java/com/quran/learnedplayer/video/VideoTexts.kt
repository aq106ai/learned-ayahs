package com.quran.learnedplayer.video

import android.content.Context
import com.quran.learnedplayer.data.AyahMapping
import org.json.JSONArray
import org.json.JSONObject

/**
 * The two bundled assets only video export uses, loaded once per process:
 * `translation_en_saheeh.json` (Saheeh International, from Tanzil.net, one string per ayah) and
 * `video_themes.json` (each ayah's background theme). Both come from
 * `.setup/make_video_themes.py`.
 */
class VideoTexts(context: Context) {
    private val appContext = context.applicationContext

    fun translation(surah: Int, ayah: Int): String? = synchronized(lock) {
        val all = translations ?: load(appContext).let { translations }
        all?.getOrNull(AyahMapping.surahAyahToGlobal(surah, ayah) - 1)
    }

    fun themeKey(surah: Int, ayah: Int): String = synchronized(lock) {
        if (themeKeys == null) load(appContext)
        val keys = themeKeys ?: return VideoThemes.FALLBACK
        val index = themeIndex?.getOrNull(AyahMapping.surahAyahToGlobal(surah, ayah) - 1) ?: return VideoThemes.FALLBACK
        keys.getOrNull(index) ?: VideoThemes.FALLBACK
    }

    private companion object {
        val lock = Any()
        var translations: List<String>? = null
        var themeKeys: List<String>? = null
        var themeIndex: IntArray? = null

        fun load(context: Context) {
            if (translations == null) {
                translations = runCatching {
                    val array = JSONArray(context.assets.open("translation_en_saheeh.json").bufferedReader().use { it.readText() })
                    List(array.length()) { array.getString(it) }
                }.getOrNull()
            }
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
