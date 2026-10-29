package com.quran.learnedplayer.video

/** From [startMs] on, the video shows background [variant] of theme [themeKey]. */
data class BackgroundSlot(val startMs: Long, val themeKey: String, val variant: Int)

/**
 * Which background shows when.
 *
 * With a theme chosen, it's that theme throughout. Matched to the ayahs, each ayah asks for its
 * own theme, but the picture only changes once the current one has been up for [MIN_HOLD_MS], so
 * a run of short ayahs doesn't flicker between backgrounds. The opening clips take the first
 * ayah's theme. The variant within a theme follows the ayah, so the same theme isn't always the
 * same picture.
 */
object BackgroundPlan {
    const val MIN_HOLD_MS = 12_000L
    const val CROSSFADE_MS = 900L

    fun plan(
        clips: List<TimelineClip>,
        chosenTheme: String?,
        themeOf: (surah: Int, ayah: Int) -> String,
        variantSeed: (surah: Int, ayah: Int) -> Int,
    ): List<BackgroundSlot> {
        val ayahClips = clips.filter { it.kind == ClipKind.AYAH }
        val first = ayahClips.firstOrNull() ?: return listOf(BackgroundSlot(0, chosenTheme ?: VideoThemes.FALLBACK, 0))
        if (chosenTheme != null) {
            return listOf(BackgroundSlot(0, chosenTheme, variantSeed(first.surah, first.ayah)))
        }
        val slots = mutableListOf(BackgroundSlot(0, themeOf(first.surah, first.ayah), variantSeed(first.surah, first.ayah)))
        for (clip in ayahClips.drop(1)) {
            val current = slots.last()
            val wanted = themeOf(clip.surah, clip.ayah)
            if (wanted != current.themeKey && clip.startMs - current.startMs >= MIN_HOLD_MS) {
                slots += BackgroundSlot(clip.startMs, wanted, variantSeed(clip.surah, clip.ayah))
            }
        }
        return slots
    }

    /**
     * The slot showing at [ms], the one before it, and how far the crossfade between them has
     * got (1 = fully the current slot).
     */
    fun at(slots: List<BackgroundSlot>, ms: Long): Triple<BackgroundSlot, BackgroundSlot?, Float> {
        var index = 0
        for (i in slots.indices) if (slots[i].startMs <= ms) index = i
        val current = slots[index]
        val previous = slots.getOrNull(index - 1)
        val fade = if (previous == null) 1f else ((ms - current.startMs).toFloat() / CROSSFADE_MS).coerceIn(0f, 1f)
        return Triple(current, previous.takeIf { fade < 1f }, fade)
    }
}
