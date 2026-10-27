package com.quran.learnedplayer.video

enum class ClipKind { AUDHU, BISMILLAH, AYAH }

/** One recitation clip in the video: where it starts and how long it plays, in ms. */
data class TimelineClip(
    val kind: ClipKind,
    val surah: Int,
    val ayah: Int,
    val startMs: Long,
    val durationMs: Long,
) {
    val endMs: Long get() = startMs + durationMs
}

/**
 * When each clip plays. Clips are laid end to end with [GAP_MS] of silence between them, after a
 * short [LEAD_IN_MS] so the first word isn't lost to a player that starts muted, and the last
 * clip is followed by [TAIL_MS] so it doesn't end on the final syllable.
 *
 * Between clips the screen keeps showing the clip that just finished: [clipAt] answers with the
 * last clip that has started.
 */
class VideoTimeline private constructor(val clips: List<TimelineClip>, val totalMs: Long) {

    fun clipAt(ms: Long): TimelineClip? {
        var found: TimelineClip? = null
        for (clip in clips) {
            if (clip.startMs <= ms) found = clip else break
        }
        return found ?: clips.firstOrNull()
    }

    class Builder {
        private val clips = mutableListOf<TimelineClip>()
        private var cursor = LEAD_IN_MS

        fun add(kind: ClipKind, surah: Int, ayah: Int, durationMs: Long): TimelineClip {
            if (clips.isNotEmpty()) cursor += GAP_MS
            return TimelineClip(kind, surah, ayah, cursor, durationMs).also {
                clips += it
                cursor = it.endMs
            }
        }

        /** Where the next clip's audio goes, once the gap before it is counted. */
        fun nextStartMs(): Long = if (clips.isEmpty()) cursor else cursor + GAP_MS

        fun build(): VideoTimeline = VideoTimeline(clips.toList(), cursor + TAIL_MS)
    }

    companion object {
        const val LEAD_IN_MS = 500L
        const val GAP_MS = 400L
        const val TAIL_MS = 1800L
    }
}
