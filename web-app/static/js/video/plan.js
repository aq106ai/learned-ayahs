// When each clip plays and which background shows when. Port of the Android app's
// video/VideoTimeline.kt, BackgroundPlan.kt and LiveBackground.kt; pure, unit-tested.

export const LEAD_IN_MS = 500;
export const GAP_MS = 400;
export const TAIL_MS = 1800;
export const MIN_HOLD_MS = 12_000;
export const CROSSFADE_MS = 900;

/**
 * Clips laid end to end with [GAP_MS] of silence between them, after a short [LEAD_IN_MS] so the
 * first word isn't lost, and [TAIL_MS] after the last so it doesn't end on the final syllable.
 * Clips: {kind: 'AUDHU'|'BISMILLAH'|'AYAH', surah, ayah, startMs, durationMs}.
 */
export function buildTimeline(entries) {
  const clips = [];
  let cursor = LEAD_IN_MS;
  for (const e of entries) {
    if (clips.length) cursor += GAP_MS;
    clips.push({ ...e, startMs: cursor, endMs: cursor + e.durationMs });
    cursor += e.durationMs;
  }
  return { clips, totalMs: cursor + TAIL_MS };
}

/** Between clips the screen keeps showing the clip that just finished. */
export function clipAt(timeline, ms) {
  let found = null;
  for (const clip of timeline.clips) {
    if (clip.startMs <= ms) found = clip;
    else break;
  }
  return found ?? timeline.clips[0] ?? null;
}

/**
 * Which background shows when: a chosen theme throughout, or each ayah's own, changing only once
 * the current one has been up for [MIN_HOLD_MS]. Slots: {startMs, themeKey, variant}.
 */
export function planBackgrounds(clips, chosenTheme, themeOf, variantSeed, fallback) {
  const ayahs = clips.filter((c) => c.kind === 'AYAH');
  const first = ayahs[0];
  if (!first) return [{ startMs: 0, themeKey: chosenTheme ?? fallback, variant: 0 }];
  if (chosenTheme) return [{ startMs: 0, themeKey: chosenTheme, variant: variantSeed(first.surah, first.ayah) }];
  const slots = [{ startMs: 0, themeKey: themeOf(first.surah, first.ayah), variant: variantSeed(first.surah, first.ayah) }];
  for (const clip of ayahs.slice(1)) {
    const current = slots[slots.length - 1];
    const wanted = themeOf(clip.surah, clip.ayah);
    if (wanted !== current.themeKey && clip.startMs - current.startMs >= MIN_HOLD_MS) {
      slots.push({ startMs: clip.startMs, themeKey: wanted, variant: variantSeed(clip.surah, clip.ayah) });
    }
  }
  return slots;
}

/** The slot showing at [ms], the one before it while crossfading, and how far (1 = done). */
export function backgroundAt(slots, ms) {
  let index = 0;
  for (let i = 0; i < slots.length; i++) if (slots[i].startMs <= ms) index = i;
  const current = slots[index];
  const previous = slots[index - 1] ?? null;
  const fade = previous ? Math.min(Math.max((ms - current.startMs) / CROSSFADE_MS, 0), 1) : 1;
  return { current, previous: fade < 1 ? previous : null, fade };
}

/** What the player can show behind the ayah (the `playerBackground` setting). */
export const PlayerBackgrounds = Object.freeze({
  PLAIN: 'plain',
  MATCHED: 'matched',
  PHOTOS: 'photos',
  /** The theme key [choice] shows at an ayah whose matched drawn theme is [drawn]; null for plain. */
  themeFor(choice, drawn, photoFor) {
    if (choice === 'plain') return null;
    if (choice === 'matched') return drawn;
    if (choice === 'photos') return photoFor(drawn) ?? drawn;
    return choice;
  },
});

/**
 * The background behind the player as ayahs go by: [planBackgrounds]'s rules on a running clock.
 * A new theme waits for the hold, then fades in; another variant of the same theme isn't a change.
 * Keys are {themeKey, variant}.
 */
export class LiveBackground {
  constructor(holdMs = MIN_HOLD_MS, fadeMs = CROSSFADE_MS) {
    this.holdMs = holdMs;
    this.fadeMs = fadeMs;
    this.current = null;
    this.previous = null;
    this.since = 0;
  }

  want(wanted, nowMs, immediately = false) {
    const shown = this.current;
    const same = shown && shown.themeKey === wanted.themeKey && shown.variant === wanted.variant;
    if (!shown) {
      this.current = wanted;
      this.previous = null;
      this.since = nowMs - this.fadeMs;
    } else if (same) {
      // nothing to do
    } else if (immediately || (shown.themeKey !== wanted.themeKey && nowMs - this.since >= this.holdMs)) {
      this.previous = shown;
      this.current = wanted;
      this.since = nowMs;
    }
  }

  at(nowMs) {
    if (!this.current) return null;
    const fade = Math.min(Math.max((nowMs - this.since) / this.fadeMs, 0), 1);
    return { current: this.current, previous: fade < 1 ? this.previous : null, fade };
  }

  clear() {
    this.current = null;
    this.previous = null;
  }
}
