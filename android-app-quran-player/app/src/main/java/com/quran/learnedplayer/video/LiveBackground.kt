package com.quran.learnedplayer.video

/** What the player can show behind the ayah, as stored in `PlayerSettings.playerBackground`. */
object PlayerBackgrounds {
    /** The app theme only, as before backgrounds existed. The default. */
    const val PLAIN = "plain"

    /** A drawn scene for each ayah, matched to what it is about, as in a shared video. */
    const val MATCHED = "matched"

    /** A nature photo for each ayah, matched the same way. */
    const val PHOTOS = "photos"

    /**
     * The theme key [choice] shows at an ayah whose matched drawn theme is [drawn]: null for
     * [PLAIN], the theme itself for any other key (a drawn theme or `photo:<subject>`).
     * [photoFor] maps a drawn theme to the key of the photo subject that suits it.
     */
    fun themeFor(choice: String, drawn: String, photoFor: (String) -> String?): String? = when (choice) {
        PLAIN -> null
        MATCHED -> drawn
        PHOTOS -> photoFor(drawn) ?: drawn
        else -> choice
    }
}

/** One background: a theme, and which of its variants. */
data class SceneKey(val themeKey: String, val variant: Int)

/** What to draw at a moment: [current], fading in over [previous] until [fade] reaches 1. */
data class BackgroundFrame(val current: SceneKey, val previous: SceneKey?, val fade: Float)

/**
 * The background behind the player as ayahs go by: the live counterpart of [BackgroundPlan],
 * with the same rules on a running clock instead of a video's timeline. A new theme only takes
 * over once the one showing has been up for [holdMs], so a run of short ayahs doesn't flicker,
 * and it fades in over [fadeMs]. Another variant of the theme already showing isn't a change.
 */
class LiveBackground(
    private val holdMs: Long = BackgroundPlan.MIN_HOLD_MS,
    private val fadeMs: Long = BackgroundPlan.CROSSFADE_MS,
) {
    private var current: SceneKey? = null
    private var previous: SceneKey? = null
    private var since = 0L

    /**
     * Asks for [wanted] at [nowMs]; call it again as time passes, since a change held back now
     * may be due later. [immediately] skips the hold, for when the user picks a background.
     */
    fun want(wanted: SceneKey, nowMs: Long, immediately: Boolean = false) {
        val shown = current
        when {
            shown == null -> {
                current = wanted
                previous = null
                since = nowMs - fadeMs // the first background is simply there, no fade
            }
            shown == wanted -> Unit
            immediately -> show(wanted, nowMs)
            shown.themeKey == wanted.themeKey -> Unit
            nowMs - since >= holdMs -> show(wanted, nowMs)
        }
    }

    fun at(nowMs: Long): BackgroundFrame? {
        val shown = current ?: return null
        val fade = ((nowMs - since).toFloat() / fadeMs).coerceIn(0f, 1f)
        return BackgroundFrame(shown, previous.takeIf { fade < 1f }, fade)
    }

    /** Forgets the background, for when the player goes back to plain. */
    fun clear() {
        current = null
        previous = null
    }

    private fun show(wanted: SceneKey, nowMs: Long) {
        previous = current
        current = wanted
        since = nowMs
    }
}
