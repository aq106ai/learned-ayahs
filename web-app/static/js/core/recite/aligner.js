// Port of data/ArabicWordAligner.kt: lines written words up against heard tokens when the two
// disagree about where words end. The Uthmani text attaches the vocative يا to the next word
// (يَـٰٓأَيُّهَا) while every recogniser says two words; the reverse (a particle run into the next
// word) happens too. Word indices are the app's currency, so the reference list is never split.

import { isWordMatch } from './normalizer.js';

export const MAX_JOINED = 2;

/**
 * The move that lines words[wordIndex] up with tokens[tokenIndex], or null. One-to-one is tried
 * first, so a joined reading can never displace a plain match.
 * @returns {{words: number, tokens: number} | null}
 */
export function matchAt(words, wordIndex, tokens, tokenIndex) {
  if (wordIndex < 0 || wordIndex >= words.length || tokenIndex < 0 || tokenIndex >= tokens.length) {
    return null;
  }
  const word = words[wordIndex];
  if (isWordMatch(word, tokens[tokenIndex])) return { words: 1, tokens: 1 };
  for (let count = 2; count <= MAX_JOINED; count++) {
    if (tokenIndex + count > tokens.length) break;
    const joined = tokens.slice(tokenIndex, tokenIndex + count).join('');
    if (isWordMatch(word, joined)) return { words: 1, tokens: count };
  }
  for (let count = 2; count <= MAX_JOINED; count++) {
    if (wordIndex + count > words.length) break;
    const joined = words.slice(wordIndex, wordIndex + count).join('');
    if (isWordMatch(joined, tokens[tokenIndex])) return { words: count, tokens: 1 };
  }
  return null;
}

/** How many tokens the first `wordCount` words consume, or -1 if they do not align. */
export function consumedByPrefix(words, wordCount, tokens) {
  if (wordCount <= 0 || wordCount > words.length) return -1;
  let wordIndex = 0;
  let tokenIndex = 0;
  while (wordIndex < wordCount) {
    const step = matchAt(words, wordIndex, tokens, tokenIndex);
    if (!step) return -1;
    // A split that swallows words past the prefix answers a different question; decline.
    if (wordIndex + step.words > wordCount) return -1;
    wordIndex += step.words;
    tokenIndex += step.tokens;
  }
  return tokenIndex;
}

/** True when the *end* of `tokens` is the first `wordCount` words of `words`. */
export function endsWithPrefix(words, wordCount, tokens) {
  if (wordCount <= 0) return false;
  for (let tailSize = wordCount; tailSize <= wordCount * MAX_JOINED; tailSize++) {
    if (tailSize > tokens.length) break;
    const tail = tokens.slice(tokens.length - tailSize);
    if (consumedByPrefix(words, wordCount, tail) === tailSize) return true;
  }
  return false;
}
