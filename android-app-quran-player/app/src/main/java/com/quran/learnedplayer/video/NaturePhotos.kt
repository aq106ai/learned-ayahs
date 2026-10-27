package com.quran.learnedplayer.video

import android.content.Context
import com.quran.learnedplayer.data.AtomicDownload
import org.json.JSONObject
import java.io.File
import java.io.IOException

/** One nature photo: CC0, from Wikimedia Commons, hosted in this project's repository. */
data class NaturePhoto(
    val id: String,
    val subject: String,
    val title: String,
    val themes: List<String>,
    val author: String,
    val licence: String,
    val source: String,
)

/** A group of photos of one subject (forest, waterfalls, …), offered like a drawn theme. */
class PhotoSubject(val key: String, val title: String, val photos: List<NaturePhoto>) {
    val themeKey: String get() = NaturePhotos.PREFIX + key
}

/**
 * The nature photos videos can use as backgrounds, listed in `assets/nature_photos.json`.
 *
 * The photos themselves are not in the app: each is downloaded the first time it's used, from the
 * project's repository (`backgrounds/nature/`, chosen and checked by
 * `backgrounds/fetch_nature_photos.py`), and kept in `filesDir/video-photos/`. Every one is CC0
 * from Wikimedia Commons; the app still names the photographer on the video.
 *
 * A photo subject is addressed like a drawn theme, by a key: [PREFIX] + the subject, so the
 * background plan and the picker treat both alike.
 */
class NaturePhotos(context: Context) {
    private val appContext = context.applicationContext
    private val dir = File(appContext.filesDir, "video-photos")

    val subjects: List<PhotoSubject> get() = load(appContext)

    fun subject(key: String): PhotoSubject? = subjects.firstOrNull { it.key == key.removePrefix(PREFIX) }

    /**
     * The subject for an ayah matched to the drawn theme [themeKey]: the first that lists it, or
     * else one of the calm, general ones, chosen by the theme so it stays the same each time.
     */
    fun subjectFor(themeKey: String): PhotoSubject? {
        val all = subjects.ifEmpty { return null }
        all.firstOrNull { themeKey in it.photos.first().themes }?.let { return it }
        val general = GENERAL.mapNotNull { key -> all.firstOrNull { it.key == key } }.ifEmpty { all }
        return general[Math.floorMod(themeKey.hashCode(), general.size)]
    }

    fun photo(themeKey: String, variant: Int): NaturePhoto? =
        subject(themeKey)?.photos?.let { it[Math.floorMod(variant, it.size)] }

    fun file(photo: NaturePhoto): File = File(dir, "${photo.id}.jpg")

    /** The photo on this phone, downloading it first if need be. */
    fun ensure(photo: NaturePhoto): File = fetch("${photo.id}.jpg")

    /** The small picture the theme picker shows, downloading it if need be; null when offline. */
    fun thumbnail(subject: PhotoSubject): File? = runCatching { fetch("${subject.photos.first().id}_thumb.jpg") }.getOrNull()

    private fun fetch(name: String): File {
        val file = File(dir, name)
        if (file.exists() && file.length() > 0) return file
        if (!AtomicDownload.toFile("$BASE_URL/$name", file, AtomicDownload::looksLikeJpeg)) {
            throw IOException("Couldn't download the background photo. Check your connection and try again.")
        }
        return file
    }

    /** The scene for [photo], one instance per photo so the renderer's cache recognises it. */
    fun scene(photo: NaturePhoto): SceneSpec = synchronized(scenes) {
        scenes.getOrPut(photo.id) {
            SceneSpec(SceneKind.PHOTO, intArrayOf(0xFF101418.toInt(), 0xFF101418.toInt(), 0xFF101418.toInt(), -1), emptySet(), file(photo).absolutePath)
        }
    }

    companion object {
        const val PREFIX = "photo:"
        const val BASE_URL = "https://raw.githubusercontent.com/aq106ai/learned-ayahs/main/backgrounds/nature"

        /** Subjects that suit an ayah whose theme no photo subject names. */
        private val GENERAL = listOf("sunrise", "mountain_lake", "green_hills", "ocean", "forest")

        fun isPhoto(themeKey: String?) = themeKey?.startsWith(PREFIX) == true

        private val scenes = HashMap<String, SceneSpec>()
        private var loaded: List<PhotoSubject>? = null

        @Synchronized
        private fun load(context: Context): List<PhotoSubject> = loaded ?: runCatching {
            val json = JSONObject(context.assets.open("nature_photos.json").bufferedReader().use { it.readText() })
            val array = json.getJSONArray("photos")
            val photos = List(array.length()) { i ->
                val o = array.getJSONObject(i)
                val themes = o.getJSONArray("themes")
                NaturePhoto(
                    id = o.getString("id"),
                    subject = o.getString("subject"),
                    title = o.getString("title"),
                    themes = List(themes.length()) { themes.getString(it) },
                    author = o.optString("author", "Unknown"),
                    licence = o.optString("licence", "CC0"),
                    source = o.optString("source", ""),
                )
            }
            photos.groupBy { it.subject }.map { (key, list) -> PhotoSubject(key, list.first().title, list) }
        }.getOrDefault(emptyList()).also { loaded = it }
    }
}
