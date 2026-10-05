// Port of data/ArabicTextNormalizer.kt — keep in step with it; the rationale for every rule is
// documented there and in android-app-quran-player/CLAUDE.md ("Recite & review").
//
// Both sides of a comparison come through here: the Uthmani reference text (wasla, dagger alif,
// small marks) and speech-recogniser output (plain spelling, sometimes presentation-form
// ligatures or Arabic-Indic digits). Stripping marks is not enough — the two scripts also spell
// long vowels differently — so runs of alif collapse to one, and isWordMatch falls back to
// isLongVowelSpellingVariant, which forgives added/dropped ا/و/ي but never a different consonant.

const DIACRITICS = /[ً-ْٓ-ٟۖ-ۭـ]/g;
const ALIF_RUN = /ا{2,}/g;
const DIGITS = /[0-9٠-٩۰-۹]/g;
// Java's \s is ASCII-only; mirrored so the two apps tokenise identically.
const NON_ARABIC = /[^ء-ي \t\n\x0B\f\r]/g;
const WHITESPACE_RUN = /[ \t\n\x0B\f\r]+/g;
const REPEAT_RUN = /(.)\1{2,}/g;

const LONG_VOWELS = 'اوي';
const MAX_LONG_VOWEL_EDITS = 2;
const MIN_CONSONANTS_FOR_VOWEL_MATCH = 2;

const LAM_ALEF_LIGATURES = {
  'ﻵ': 'لا', 'ﻶ': 'لا', // lam-alef with madda
  'ﻷ': 'لا', 'ﻸ': 'لا', // lam-alef with hamza above
  'ﻹ': 'لا', 'ﻺ': 'لا', // lam-alef with hamza below
  'ﻻ': 'لا', 'ﻼ': 'لا', // plain lam-alef
};
const LIGATURE_RE = /[ﻵ-ﻼ]/g;

const CACHE = new Map();
const MAX_CACHED = 4096;

function normalizeUncached(text) {
  // Composed form first: engines differ on أ versus ا + combining hamza.
  let result = text.normalize('NFC');
  result = result.replace(LIGATURE_RE, (ch) => LAM_ALEF_LIGATURES[ch]);
  result = result
    .replace(/ٰ/g, 'ا') // dagger alif
    .replace(/ٱ/g, 'ا') // wasla
    .replace(/أ/g, 'ا')
    .replace(/إ/g, 'ا')
    .replace(/آ/g, 'ا')
    .replace(/ئ/g, 'ي')
    .replace(/ؤ/g, 'و')
    // Standalone hamza: انبئهم / انبءهم / أنبئهم must all compare equal.
    .replace(/ء/g, 'ا');
  result = result.replace(DIACRITICS, '');
  result = result.replace(/ى/g, 'ي'); // alef maksura -> yeh
  result = result.replace(/ة/g, 'ه'); // ta marbuta -> ha
  result = result.replace(DIGITS, '');
  result = result.replace(NON_ARABIC, '');
  // Collapse decoder repetition loops (سسسسسس -> سس) ...
  result = result.replace(REPEAT_RUN, '$1$1');
  // ...and alif all the way down to one: ءَا is the Uthmani spelling of آ.
  result = result.replace(ALIF_RUN, 'ا');
  return result.replace(WHITESPACE_RUN, ' ').trim();
}

export function normalize(text) {
  if (!text || !text.trim()) return '';
  const cached = CACHE.get(text);
  if (cached !== undefined) return cached;
  const normalized = normalizeUncached(text);
  if (CACHE.size >= MAX_CACHED) CACHE.clear();
  CACHE.set(text, normalized);
  return normalized;
}

/** Normalized word tokens, empty ones dropped. */
export function tokenize(text) {
  return normalize(text).split(' ').filter((t) => t.trim().length > 0);
}

/** True when `spoken` is a proper prefix of `expected` after normalization (an in-progress word). */
export function isIncompletePrefix(spoken, expected) {
  const s = normalize(spoken);
  const e = normalize(expected);
  if (!s || !e) return false;
  if (s === e) return false;
  return e.startsWith(s);
}

export function levenshteinDistance(s1, s2) {
  const dp = Array.from({ length: s2.length + 1 }, (_, i) => i);
  for (let i = 1; i <= s1.length; i++) {
    let prev = dp[0];
    dp[0] = i;
    for (let j = 1; j <= s2.length; j++) {
      const temp = dp[j];
      const cost = s1[i - 1] === s2[j - 1] ? 0 : 1;
      dp[j] = Math.min(dp[j] + 1, dp[j - 1] + 1, prev + cost);
      prev = temp;
    }
  }
  return dp[s2.length];
}

const isLongVowel = (ch) => LONG_VOWELS.includes(ch);

function consonantCount(word) {
  let n = 0;
  for (const ch of word) if (!/\s/.test(ch) && !isLongVowel(ch)) n++;
  return n;
}

/**
 * Two already-normalized words with the same consonants in the same order, differing only by up
 * to MAX_LONG_VOWEL_EDITS written long vowels. Substitution is never allowed.
 */
export function isLongVowelSpellingVariant(norm1, norm2) {
  if (norm1 === norm2) return true;
  if (
    consonantCount(norm1) < MIN_CONSONANTS_FOR_VOWEL_MATCH ||
    consonantCount(norm2) < MIN_CONSONANTS_FOR_VOWEL_MATCH
  ) {
    return false;
  }
  const over = MAX_LONG_VOWEL_EDITS + 1;
  let previous = new Array(norm2.length + 1).fill(0);
  for (let j = 1; j <= norm2.length; j++) {
    previous[j] = isLongVowel(norm2[j - 1]) ? Math.min(previous[j - 1] + 1, over) : over;
  }
  for (let i = 1; i <= norm1.length; i++) {
    const current = new Array(norm2.length + 1).fill(0);
    current[0] = isLongVowel(norm1[i - 1]) ? Math.min(previous[0] + 1, over) : over;
    for (let j = 1; j <= norm2.length; j++) {
      let best = over;
      if (norm1[i - 1] === norm2[j - 1]) best = previous[j - 1];
      if (isLongVowel(norm1[i - 1])) best = Math.min(best, previous[j] + 1);
      if (isLongVowel(norm2[j - 1])) best = Math.min(best, current[j - 1] + 1);
      current[j] = Math.min(best, over);
    }
    previous = current;
  }
  return previous[norm2.length] <= MAX_LONG_VOWEL_EDITS;
}

/** Two Arabic words match after normalization, with one edit of tolerance on longer words. */
export function isWordMatch(word1, word2, maxDistance = 1) {
  const n1 = normalize(word1);
  const n2 = normalize(word2);
  if (n1 === n2) return true;
  if (!n1 || !n2) return false;
  // One edit on a two-letter word is a different word (من / عن), whichever side is short.
  if (n1.length <= 2 || n2.length <= 2) return false;
  if (levenshteinDistance(n1, n2) <= maxDistance) return true;
  // Same word, other script. Checked last so it can only ever add matches.
  return isLongVowelSpellingVariant(n1, n2);
}
