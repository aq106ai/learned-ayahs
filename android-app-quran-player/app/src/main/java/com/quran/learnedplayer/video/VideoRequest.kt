package com.quran.learnedplayer.video

import com.quran.learnedplayer.data.QuranConstants
import com.quran.learnedplayer.data.Reciter

/** The shapes a video can be made in, at full-HD size. */
enum class VideoAspect(val label: String, val ratio: String, val width: Int, val height: Int) {
    PORTRAIT("Shorts, Reels, TikTok", "9:16", 1080, 1920),
    TALL("Feed post", "4:5", 1080, 1350),
    SQUARE("Square", "1:1", 1080, 1080),
    LANDSCAPE("YouTube", "16:9", 1920, 1080),
    ;

    /** This shape scaled by [factor], with even sides as H.264 encoders require. */
    fun size(factor: Float = 1f): Pair<Int, Int> =
        even(width * factor) to even(height * factor)

    private fun even(value: Float): Int = (value.toInt() / 2) * 2

    companion object {
        val DEFAULT = PORTRAIT
    }
}

/**
 * Everything the user chose for one video.
 *
 * [themeKey] null means "match each ayah" (from the bundled map, to a drawn theme or, with
 * [photos], a nature photo); otherwise every ayah gets that theme, drawn or a photo subject
 * ([NaturePhotos.PREFIX]). [opening] puts A'udhu before the ayahs. [bismillah] puts the
 * Bismillah clip before them too, including mid-surah; Al-Fatihah 1 and At-Tawbah never take it.
 * [endCard] holds a closing page with the Play Store link after the recitation.
 */
data class VideoRequest(
    val surah: Int,
    val fromAyah: Int,
    val toAyah: Int,
    val reciter: Reciter,
    val aspect: VideoAspect,
    val wordByWord: Boolean,
    val fullTranslation: Boolean,
    val opening: Boolean,
    val themeKey: String?,
    /** With [themeKey] null: match each ayah to a nature photo rather than a drawn theme. */
    val photos: Boolean = false,
    /** Print the sources under the card and "Made with Learned Ayahs" at the foot. Off: a clean picture. */
    val credits: Boolean = false,
    /**
     * Recite Bismillah before the ayahs. Defaults to on only where the old opening did: the range
     * starts at ayah 1 and the surah has a Bismillah of its own.
     */
    val bismillah: Boolean = opening && fromAyah == 1 && bismillahAllowed(surah, fromAyah),
    /** A closing page, after the recitation, pointing to the app on Google Play. */
    val endCard: Boolean = true,
) {
    init {
        require(surah in 1..114) { "No surah $surah" }
        val count = QuranConstants.VERSE_COUNTS[surah - 1]
        require(fromAyah in 1..count && toAyah in fromAyah..count) { "No ayahs $fromAyah-$toAyah in surah $surah" }
    }

    val ayahs: IntRange get() = fromAyah..toAyah

    /** Bismillah before the ayahs, except At-Tawbah and Al-Fatihah 1 (that ayah *is* the Bismillah). */
    val includesBismillahClip: Boolean
        get() = bismillah && bismillahAllowed(surah, fromAyah)

    /** The clips the video plays, in order, as (kind, surah, ayah); intros have ayah 0. */
    fun clips(): List<Triple<ClipKind, Int, Int>> = buildList {
        if (opening) add(Triple(ClipKind.AUDHU, surah, 0))
        if (includesBismillahClip) add(Triple(ClipKind.BISMILLAH, surah, 0))
        for (ayah in ayahs) add(Triple(ClipKind.AYAH, surah, ayah))
    }

    companion object {
        /** Long enough for every surah but the longest; beyond it, the file is too big to share. */
        const val MAX_DURATION_MS = 30L * 60 * 1000

        /** The app's page on Google Play, drawn on the video's closing card. */
        const val PLAY_STORE_URL = "https://play.google.com/store/apps/details?id=com.quran.learnedayahs"

        /**
         * A separate Bismillah clip is honest here. At-Tawbah has none. Al-Fatihah's first ayah
         * is the Bismillah, so the clip immediately before it would say it twice.
         */
        fun bismillahAllowed(surah: Int, fromAyah: Int): Boolean =
            surah != 9 && !(surah == 1 && fromAyah == 1)
    }
}
