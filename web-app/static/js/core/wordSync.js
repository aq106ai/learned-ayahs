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
