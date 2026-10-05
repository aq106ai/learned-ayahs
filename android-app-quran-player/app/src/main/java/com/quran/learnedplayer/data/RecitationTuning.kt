package com.quran.learnedplayer.data

/**
 * Thresholds the recitation coach matches against.
 *
 * These were once per-engine ([RecitationEngine] with a `EngineTuning` per entry), because a
 * bundled Whisper model and the platform recogniser produce very different output: whisper emitted
 * one settled window decode at a time, the platform service streams partials that flap. The offline
 * engine was dropped in 1.8.0 — it could not decode fast enough to correct a live reciter even on
 * current hardware — so there is one recogniser and one set of numbers.
 *
 * Every value here exists to stop the coach interrupting a student who was right. That failure is
 * much worse than missing a real mistake: being corrected on a word you recited correctly destroys
 * trust in the whole feature, while a missed mistake merely leaves you where you were.
 */
object RecitationTuning {

    /**
     * Consecutive partials that must agree on the last token before it counts as settled.
     *
     * The platform service revises its own partials continuously — a word can appear, change and
     * change back within a second — so nothing is judged until it has stopped moving.
     */
    const val MIN_STABLE_REPEATS = 2

    /**
     * How many consecutive evaluations must name the **same** wrong word before the coach
     * interrupts.
     *
     * A recognition artifact moves between passes; a mistake the student actually made is in every
     * pass of that audio. Requiring agreement costs a fraction of a second and is the difference
     * between a coach and a nuisance.
     */
    const val MISTAKE_CONFIRMATIONS = 2

    /**
     * How far a heard word may sit from the expected one and still be forgiven as mishearing.
     *
     * `ArabicTextNormalizer.isWordMatch` already allows one edit, so this governs what happens
     * beyond that. A word the student genuinely got wrong is normally a *different word* — several
     * edits away, or a skip. One or two letters out is far more often the recogniser: it returns
     * السِّرَاطَ for صِرَاطَ and رَحْمَانٍ for ٱلرَّحْمَـٰنِ on flawless recitation.
     */
    const val NEAR_MISS_TOLERANCE = 2

    /** Quiet period after a correction clip before another word may interrupt. */
    const val MISTAKE_COOLDOWN_MS = 1_200L

    /** Pause after the clip finishes before the microphone is scored again. */
    const val POST_CLIP_DELAY_MS = 400L
}
