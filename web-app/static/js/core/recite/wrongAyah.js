// Port of data/WrongAyahDetector.kt: notices when the spoken opening matches a different learned
// ayah than the one on screen. Counted in written words, not heard tokens, because 469 ayahs open
// with the vocative يا attached to the next word.

import { consumedByPrefix } from './aligner.js';

function prefixMatches(spoken, expected, count) {
  if (expected.length < count) return false;
  return consumedByPrefix(expected, count, spoken) >= 0;
}

/**
 * @param {string[]} spokenTokens
 * @param {number} currentGlobalId
 * @param {string[]} currentOpening
 * @param {Array<{globalId: number, tokens: string[]}>} otherOpenings
 * @returns {number|null}
 */
export function matchingOtherAyah(
  spokenTokens,
  currentGlobalId,
  currentOpening,
  otherOpenings,
  minSpokenTokens = 2,
) {
  if (spokenTokens.length < minSpokenTokens) return null;
  const compareCount = Math.max(Math.min(minSpokenTokens, currentOpening.length), 1);
  if (spokenTokens.length < compareCount) return null;
  if (prefixMatches(spokenTokens, currentOpening, compareCount)) return null;
  const hit = otherOpenings.find(
    (o) => o.globalId !== currentGlobalId && prefixMatches(spokenTokens, o.tokens, compareCount),
  );
  return hit ? hit.globalId : null;
}
