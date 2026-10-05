package com.quran.learnedplayer.data

/**
 * Reciters that have **per-word** MP3s (stream or cache), distinct from [Reciter] whose
 * everyayah files are whole ayahs with QUL timings.
 *
 * Only voices with a complete word-file set belong here — same honesty rule as ayah reciters.
 * Quran.com hosts isolated pronunciation clips at `audio.qurancdn.com/wbw/`, not the
 * Maher/Husary ayah recordings.
 */
enum class WordReciter(
    val displayName: String,
    val folder: String,
    val baseUrl: String,
) {
    QURAN_COM(
        displayName = "Word pronunciation (Quran.com)",
        folder = "quran_com_wbw",
        baseUrl = "https://audio.qurancdn.com/wbw",
    ),
    ;

    companion object {
        val DEFAULT = QURAN_COM

        fun fromKey(key: String?): WordReciter =
            entries.firstOrNull { it.name == key } ?: DEFAULT
    }
}

/**
 * One content-word audio clip. [wordIndex] is 0-based in this app's content-word list
 * (end-of-ayah glyphs excluded). The CDN uses a 1-based index in the filename.
 */
data class WordAudio(
    val surah: Int,
    val ayah: Int,
    val wordIndex: Int,
    val globalId: Int,
    val filename: String,
    val remoteUrl: String,
    val localPath: String? = null,
) {
    val mediaId: String get() = "$globalId:$wordIndex"

    fun withLocalPath(path: String): WordAudio = copy(localPath = path)

    companion object {
        fun filename(surah: Int, ayah: Int, wordIndex: Int): String =
            // Locale.ROOT: see AyahMapping.ayahFilename — locale digits would break every URL.
            String.format(java.util.Locale.ROOT, "%03d_%03d_%03d.mp3", surah, ayah, wordIndex + 1)

        fun remoteUrl(reciter: WordReciter, surah: Int, ayah: Int, wordIndex: Int): String =
            "${reciter.baseUrl}/${filename(surah, ayah, wordIndex)}"

        fun item(
            reciter: WordReciter,
            surah: Int,
            ayah: Int,
            wordIndex: Int,
        ): WordAudio {
            val globalId = AyahMapping.surahAyahToGlobal(surah, ayah)
            return WordAudio(
                surah = surah,
                ayah = ayah,
                wordIndex = wordIndex,
                globalId = globalId,
                filename = filename(surah, ayah, wordIndex),
                remoteUrl = remoteUrl(reciter, surah, ayah, wordIndex),
            )
        }

        /**
         * One clip per content word. [wordCount] must be this app's content-word count
         * (no end glyph). Empty when there is nothing honest to play.
         */
        fun itemsFor(
            reciter: WordReciter,
            surah: Int,
            ayah: Int,
            wordCount: Int,
        ): List<WordAudio> {
            if (wordCount <= 0) return emptyList()
            return (0 until wordCount).map { item(reciter, surah, ayah, it) }
        }
    }
}
