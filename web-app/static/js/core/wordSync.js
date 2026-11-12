// Word-highlight sync (port of data/WordSync.kt). The active word is the segment containing the
// playback position; in the gap between words the last finished word stays active.
//
// There is deliberately no estimated fallback: words are highlighted from the reciter's validated
// segments, or not at all. A guessed timing drifts against real recitation and highlights the
// wrong word, which is worse than no highlight.

/**
 * @param {number} positionMs
 * @param {Array<[number, number]>} segments [startMs, endMs) per content word — the timing
 *   assets' pairs, end exclusive, exactly as the Android app reads them (`start until end`)
 * @param {number} wordCount
 */
export function activeWordIndex(positionMs, segments, wordCount) {
  if (!segments || !segments.length || !wordCount) return -1;
  const count = Math.min(segments.length, wordCount);
  let active = -1;
  for (let i = 0; i < count; i++) {
    const start = segments[i][0];
    const endExclusive = segments[i][1];
    if (positionMs >= start && positionMs < endExclusive) return i;
    if (positionMs >= endExclusive) active = i;
  }
  return active;
}

// A recitation file that runs on well past its ayah's word timings holds more than that ayah:
// EveryAyah's Maher Al Muaiqly 2:258 is 2:258 and all of 2:259 in one file. Validated timings end
// within 50 ms of the file's end everywhere else, so an overrun this large is never silence.
const OVERRUN_MIN_MS = 5000;
const OVERRUN_SHARE = 0.25;
// Where such an ayah is cut: a breath after its last word.
const OVERRUN_TAIL_MS = 400;

/**
 * Where the ayah in this file really ends, when the file runs on past it (see OVERRUN_MIN_MS), or
 * null to play the file to its end. Same rule as Android's WordSync.ayahEndMs.
 */
export function ayahEndMs(segments, fileMs) {
  const last = segments?.at(-1)?.[1];
  if (!last || !Number.isFinite(fileMs) || fileMs <= 0) return null;
  const overrun = fileMs - last;
  return overrun > OVERRUN_MIN_MS && overrun > last * OVERRUN_SHARE ? last + OVERRUN_TAIL_MS : null;
}
