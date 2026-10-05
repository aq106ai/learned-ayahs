// Port of data/RecitationEvaluator.kt: scores a recognised transcript against an ayah's words.
// Reference words are {text, isEnd}; the end-of-ayah number glyph (isEnd) is never scored.

import { isIncompletePrefix, isWordMatch, tokenize } from './normalizer.js';
import { matchAt, consumedByPrefix } from './aligner.js';
import { openingTokensOf, stripLeadingIntrosFromTranscript } from './introFilter.js';

export const Status = Object.freeze({ PENDING: 'PENDING', CORRECT: 'CORRECT', MISTAKE: 'MISTAKE' });

const ev = (wordIndex, originalWord, status, spokenWord = null, feedback = null) => ({
  wordIndex,
  originalWord,
  status,
  spokenWord,
  feedback,
});

const accuracy = (correct, total) =>
  total > 0 ? Math.min(Math.max((correct / total) * 100, 0), 100) : 100;

function emptyResult() {
  return {
    evaluations: [],
    totalContentWords: 0,
    correctCount: 0,
    mistakeCount: 0,
    accuracyPercentage: 100,
    isComplete: false,
    firstMistake: null,
  };
}

function finish(evaluations, totalWords, correctCount, mistakeCount) {
  return {
    evaluations,
    totalContentWords: totalWords,
    correctCount,
    mistakeCount,
    accuracyPercentage: accuracy(correctCount, totalWords),
    isComplete: evaluations.length > 0 && evaluations.every((e) => e.status === Status.CORRECT),
    firstMistake: evaluations.find((e) => e.status === Status.MISTAKE) ?? null,
  };
}

/** One-shot evaluation. `committed: false` locks only a matching prefix and never flags mistakes. */
export function evaluate(referenceWords, spokenTranscript, committed = true) {
  const contentWords = referenceWords.filter((w) => !w.isEnd);
  if (!contentWords.length) return emptyResult();
  const tokens = tokenize(spokenTranscript);
  const texts = contentWords.map((w) => w.text);
  const evaluations = [];
  let spokenIndex = 0;
  let correctCount = 0;
  let mistakeCount = 0;

  if (!committed) {
    let broken = false;
    let i = 0;
    while (i < contentWords.length) {
      const step = broken ? null : matchAt(texts, i, tokens, spokenIndex);
      if (step) {
        const heard = tokens.slice(spokenIndex, spokenIndex + step.tokens).join(' ');
        for (let o = 0; o < step.words; o++) {
          evaluations.push(ev(i + o, contentWords[i + o], Status.CORRECT, heard));
          correctCount++;
        }
        i += step.words;
        spokenIndex += step.tokens;
      } else {
        broken = true;
        evaluations.push(ev(i, contentWords[i], Status.PENDING));
        i++;
      }
    }
    return { ...finish(evaluations, contentWords.length, correctCount, 0), isComplete: false };
  }

  let i = 0;
  while (i < contentWords.length) {
    const target = contentWords[i];
    if (spokenIndex < tokens.length) {
      const spokenToken = tokens[spokenIndex];
      const step = matchAt(texts, i, tokens, spokenIndex);
      if (step) {
        const heard = tokens.slice(spokenIndex, spokenIndex + step.tokens).join(' ');
        for (let o = 0; o < step.words; o++) {
          evaluations.push(ev(i + o, contentWords[i + o], Status.CORRECT, heard));
          correctCount++;
        }
        i += step.words;
        spokenIndex += step.tokens;
        continue;
      }
      if (matchAt(texts, i + 1, tokens, spokenIndex)) {
        evaluations.push(ev(i, target, Status.MISTAKE, null, `Skipped word '${target.text}'`));
      } else {
        evaluations.push(
          ev(i, target, Status.MISTAKE, spokenToken, `Expected '${target.text}', but heard '${spokenToken}'`),
        );
        spokenIndex++;
      }
      mistakeCount++;
    } else {
      evaluations.push(ev(i, target, Status.PENDING));
    }
    i++;
  }
  return finish(evaluations, contentWords.length, correctCount, mistakeCount);
}

/**
 * Streaming path (kept for parity with the Android evaluator and its tests; the live coach uses
 * evaluateContinuing). Walks one word to one token.
 */
export function evaluateStreaming(referenceWords, spokenTranscript, lastTokenStable) {
  const contentWords = referenceWords.filter((w) => !w.isEnd);
  if (!contentWords.length) return emptyResult();
  const tokens = tokenize(spokenTranscript).filter((t) => t !== 'unk' && t !== '[unk]');
  const evaluations = [];
  let spokenIndex = 0;
  let correctCount = 0;
  let mistakeCount = 0;
  let stopped = false;
  for (let i = 0; i < contentWords.length; i++) {
    const target = contentWords[i];
    if (stopped || spokenIndex >= tokens.length) {
      evaluations.push(ev(i, target, Status.PENDING));
      continue;
    }
    const token = tokens[spokenIndex];
    const isLast = spokenIndex === tokens.length - 1;
    if (isWordMatch(target.text, token)) {
      evaluations.push(ev(i, target, Status.CORRECT, token));
      correctCount++;
      spokenIndex++;
      continue;
    }
    if (isLast && isIncompletePrefix(token, target.text)) {
      evaluations.push(ev(i, target, Status.PENDING));
      stopped = true;
      continue;
    }
    const next = contentWords[i + 1];
    if (next && isWordMatch(next.text, token)) {
      evaluations.push(ev(i, target, Status.MISTAKE, null, `Skipped word '${target.text}'`));
      mistakeCount++;
      stopped = true;
      continue;
    }
    if (isLast && !lastTokenStable) {
      evaluations.push(ev(i, target, Status.PENDING));
      stopped = true;
      continue;
    }
    evaluations.push(ev(i, target, Status.MISTAKE, token, `Expected '${target.text}', but heard '${token}'`));
    mistakeCount++;
    spokenIndex++;
    stopped = true;
  }
  return finish(evaluations, contentWords.length, correctCount, mistakeCount);
}

function correctIn(overlay) {
  let n = 0;
  for (const e of overlay.values()) if (e.status === Status.CORRECT) n++;
  return n;
}

/** Prefer a clean sync, then more words settled, then reach, then continuing from the lock. */
function betterThan(a, b) {
  if (a.hasMistake !== b.hasMistake) return !a.hasMistake;
  if (a.correctCount !== b.correctCount) return a.correctCount > b.correctCount;
  if (a.extendExclusive !== b.extendExclusive) return a.extendExclusive > b.extendExclusive;
  return a.alignStart > b.alignStart;
}

function walk(overlay, extendExclusive, hasMistake, alignStart) {
  return { overlay, extendExclusive, hasMistake, alignStart, correctCount: correctIn(overlay) };
}

function newAttemptTokens(contentWords, allTokens, locked) {
  if (locked === 0) return allTokens;
  // Tokens the locked words took — not `locked` once a written word was heard as two.
  const consumed = consumedByPrefix(contentWords.map((w) => w.text), locked, allTokens);
  return consumed >= 0 ? allTokens.slice(consumed) : allTokens;
}

function walkAlign(contentWords, texts, tokens, alignStart, locked, lastTokenStable, allowSkipMistakes) {
  const overlay = new Map();
  let spokenIndex = 0;
  let i = alignStart;
  while (i < contentWords.length && spokenIndex < tokens.length) {
    const token = tokens[spokenIndex];
    const target = contentWords[i];
    const isLast = spokenIndex === tokens.length - 1;
    const step = matchAt(texts, i, tokens, spokenIndex);
    if (step) {
      const heard = tokens.slice(spokenIndex, spokenIndex + step.tokens).join(' ');
      for (let o = 0; o < step.words; o++) {
        overlay.set(i + o, ev(i + o, contentWords[i + o], Status.CORRECT, heard));
      }
      spokenIndex += step.tokens;
      i += step.words;
      continue;
    }
    if (isLast && isIncompletePrefix(token, target.text)) {
      if (i < locked) return walk(overlay, i, false, alignStart);
      overlay.set(i, ev(i, target, Status.PENDING));
      return walk(overlay, i, false, alignStart);
    }
    if (matchAt(texts, i + 1, tokens, spokenIndex)) {
      if (i < locked) return null;
      if (!allowSkipMistakes) {
        // Live path: a possible mic gap / mid-ayah sync, not a skip.
        overlay.set(i, ev(i, target, Status.PENDING));
        return walk(overlay, i, false, alignStart);
      }
      overlay.set(i, ev(i, target, Status.MISTAKE, null, `Skipped word '${target.text}'`));
      return walk(overlay, i, true, alignStart);
    }
    if (isLast && !lastTokenStable) {
      if (i < locked) return null;
      overlay.set(i, ev(i, target, Status.PENDING));
      return walk(overlay, i, false, alignStart);
    }
    if (i < locked) return null;
    overlay.set(i, ev(i, target, Status.MISTAKE, token, `Expected '${target.text}', but heard '${token}'`));
    return walk(overlay, i, true, alignStart);
  }
  return walk(overlay, i, false, alignStart);
}

/**
 * Sticky-prefix evaluation used by the live coach. Words [0, lockedCorrectCount) stay CORRECT;
 * new tokens may align anywhere in [0, locked] (lookback) or at a later word when the stream
 * resumes mid-ayah. `allowSkipMistakes: false` (live partials) never flags a skip.
 *
 * The only place intros are stripped: callers pass the raw transcript, so the ayah's own opening
 * can veto the strip (Al-Fatihah 1:1 *is* the basmala).
 */
export function evaluateContinuing(
  referenceWords,
  spokenTranscript,
  lastTokenStable,
  lockedCorrectCount,
  allowSkipMistakes = true,
) {
  const contentWords = referenceWords.filter((w) => !w.isEnd);
  if (!contentWords.length) return emptyResult();
  const locked = Math.min(Math.max(lockedCorrectCount, 0), contentWords.length);
  const stripped = stripLeadingIntrosFromTranscript(spokenTranscript, openingTokensOf(referenceWords));
  const allTokens = tokenize(stripped).filter((t) => t !== 'unk' && t !== '[unk]');
  const tokens = newAttemptTokens(contentWords, allTokens, locked);

  const sticky = contentWords.map((w, i) => ev(i, w, i < locked ? Status.CORRECT : Status.PENDING));
  const texts = contentWords.map((w) => w.text);

  if (tokens.length) {
    let best = null;
    const starts = [];
    for (let s = 0; s <= locked; s++) starts.push(s);
    const first = tokens[0];
    for (let i = locked + 1; i < contentWords.length; i++) {
      if (matchAt(texts, i, tokens, 0) || isIncompletePrefix(first, contentWords[i].text)) starts.push(i);
    }
    for (const start of starts) {
      const w = walkAlign(contentWords, texts, tokens, start, locked, lastTokenStable, allowSkipMistakes);
      if (w && (!best || betterThan(w, best))) best = w;
    }
    if (best) {
      for (const [index, e] of best.overlay) if (index >= locked) sticky[index] = e;
    } else if (lastTokenStable && locked < contentWords.length) {
      const target = contentWords[locked];
      sticky[locked] = ev(
        locked,
        target,
        Status.MISTAKE,
        first,
        `Expected '${target.text}', but heard '${first}'`,
      );
    }
  }

  const correct = sticky.filter((e) => e.status === Status.CORRECT).length;
  const mistakes = sticky.filter((e) => e.status === Status.MISTAKE).length;
  return finish(sticky, sticky.length, correct, mistakes);
}
