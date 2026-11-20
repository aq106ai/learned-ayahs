// Word timings for a clip nobody has timed: A'udhu, and Bismillah when the reciter has no 1:1
// timings. Used only for one-word-at-a-time videos. Port of the Android app's
// video/IntroWordTimings.kt; change both.
//
// An estimate, measured from the clip's own sound: find where the voice starts and ends; if the
// reciter pauses between every pair of words, the pauses are the boundaries; otherwise share the
// voiced span by each word's letters and move each boundary to the quietest frame nearby.

export const FRAME_MS = 20;
const VOICE_LEVEL = 0.12;
const MIN_VOICED_RUN = 3;
const MIN_PAUSE_FRAMES = 8;
const SNAP_FRAMES = 5;

/** Letters of [word] (Arabic letters and the wasla alif ٱ; marks dropped), at least 1. */
export function letterWeight(word) {
  return Math.max(1, (word.match(/[ء-يٱ]/g) || []).length);
}

/** Loudness per FRAME_MS frame of two channels of float PCM at [rate]. */
export function frameLevels(left, right, length, rate) {
  const perFrame = Math.floor((rate * FRAME_MS) / 1000);
  if (perFrame <= 0) return [];
  const count = Math.floor(length / perFrame);
  const levels = new Array(count);
  for (let f = 0; f < count; f++) {
    let energy = 0;
    for (let i = f * perFrame; i < (f + 1) * perFrame; i++) energy += left[i] * left[i] + right[i] * right[i];
    levels[f] = Math.sqrt(energy / (perFrame * 2));
  }
  return levels;
}

/** One [startMs, endMs] per word, or null when there is no voice to measure. */
export function estimateWordTimings(levels, weights) {
  const words = weights.length;
  if (!words || !levels.length) return null;
  const sorted = [...levels].sort((a, b) => a - b);
  const loud = sorted[Math.floor((sorted.length - 1) * 0.95)];
  if (!(loud > 0)) return null;
  const threshold = loud * VOICE_LEVEL;
  const voiced = levels.map((l) => l > threshold);
  const run = (from, to) => { for (let i = from; i <= to; i++) if (!voiced[i]) return false; return true; };

  let start = -1;
  for (let i = 0; i <= levels.length - MIN_VOICED_RUN; i++) if (run(i, i + MIN_VOICED_RUN - 1)) { start = i; break; }
  if (start < 0) return null;
  let end = -1;
  for (let i = levels.length - 1; i >= MIN_VOICED_RUN - 1; i--) if (run(i - MIN_VOICED_RUN + 1, i)) { end = i; break; }
  if (end - start + 1 < words) return null;

  const boundaries = pauseBoundaries(voiced, start, end, words - 1) ?? letterBoundaries(levels, start, end, weights);
  const starts = [start, ...boundaries];
  return starts.map((s, i) => [s * FRAME_MS, i < starts.length - 1 ? starts[i + 1] * FRAME_MS - 1 : (end + 1) * FRAME_MS]);
}

function pauseBoundaries(voiced, start, end, needed) {
  if (needed === 0) return [];
  const pauses = [];
  let i = start;
  while (i <= end) {
    if (voiced[i]) { i++; continue; }
    const from = i;
    while (i <= end && !voiced[i]) i++;
    if (i - from >= MIN_PAUSE_FRAMES && i <= end) pauses.push([i, i - from]);
  }
  if (pauses.length < needed) return null;
  // Longest pauses first; a stable sort keeps the earlier of equal pauses, as Kotlin's does.
  return pauses.map((p, k) => [...p, k]).sort((a, b) => b[1] - a[1] || a[2] - b[2]).slice(0, needed).map((p) => p[0]).sort((a, b) => a - b);
}

function letterBoundaries(levels, start, end, weights) {
  const total = weights.reduce((a, b) => a + b, 0);
  const span = end - start + 1;
  const result = [];
  let cumulative = 0;
  for (let j = 0; j < weights.length - 1; j++) {
    cumulative += weights[j];
    const estimate = start + Math.floor((span * cumulative) / total);
    const earliest = (result.length ? result[result.length - 1] : start) + 1;
    const latest = end - (weights.length - 1 - j - 1);
    const lo = Math.max(earliest, estimate - SNAP_FRAMES);
    const hi = Math.min(latest, estimate + SNAP_FRAMES);
    let snapped;
    if (lo <= hi) {
      snapped = lo;
      for (let f = lo + 1; f <= hi; f++) if (levels[f] < levels[snapped]) snapped = f;
    } else {
      snapped = Math.min(Math.max(estimate, earliest), latest);
    }
    result.push(snapped);
  }
  return result;
}
