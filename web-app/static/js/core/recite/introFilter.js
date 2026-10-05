// Port of data/RecitationIntroFilter.kt: strips leading a'udhu / basmala phrases from recognised
// tokens so they are never scored as mistakes. Some ayahs *are* the intro (Al-Fatihah 1:1), so
// every entry point takes the ayah's own opening and refuses to strip a phrase it begins with.
// Intros are stripped in exactly one place (evaluateContinuing); a second strip re-breaks 1:1.

import { isWordMatch, tokenize } from './normalizer.js';

const INTRO_PHRASES = [
  ['اعوذ', 'بالله', 'السميع', 'العليم', 'من', 'الشيطان', 'الرجيم'],
  ['اعوذ', 'بالله', 'السميع', 'العليم', 'من', 'الشيطن', 'الرجيم'],
  ['اعوذ', 'بالله', 'السميع', 'العليم', 'من', 'الشيطان'],
  ['اعوذ', 'بالله', 'السميع', 'العليم', 'من', 'الشيطن'],
  ['اعوذ', 'بالله', 'السميع', 'العليم'],
  ['اعوذ', 'بالله', 'العظيم', 'من', 'الشيطان', 'الرجيم'],
  ['اعوذ', 'بالله', 'العظيم', 'من', 'الشيطن', 'الرجيم'],
  ['اعوذ', 'بالله', 'العظيم'],
  ['اعوذ', 'بالله', 'من', 'الشيطان', 'الرجيم'],
  ['اعوذ', 'بالله', 'من', 'الشيطن', 'الرجيم'],
  ['اعوذ', 'بالله', 'من', 'الشيطان'],
  ['اعوذ', 'بالله', 'من', 'الشيطن'],
  ['اعوذ', 'بالله'],
  ['استعيذ', 'بالله', 'من', 'الشيطان', 'الرجيم'],
  ['استعيذ', 'بالله', 'من', 'الشيطن', 'الرجيم'],
  ['استعيذ', 'بالله', 'من', 'الشيطان'],
  ['استعيذ', 'بالله'],
  ['بسم', 'الله', 'الرحمن', 'الرحيم'],
  ['بسم', 'الله', 'الرحمن'],
  ['بسم', 'الله'],
].sort((a, b) => b.length - a.length); // stable, like Kotlin's sortedByDescending

const INTRO_STARTERS = [['اعوذ'], ['استعيذ'], ['بسم']];
const MAX_INTRO_PHRASE_LENGTH = Math.max(...INTRO_PHRASES.map((p) => p.length));

function startsWithPhrase(tokens, phrase) {
  if (tokens.length < phrase.length) return false;
  for (let i = 0; i < phrase.length; i++) {
    if (!isWordMatch(phrase[i], tokens[i])) return false;
  }
  return true;
}

function isPrefixOfPhrase(tokens, phrase) {
  if (!tokens.length || tokens.length > phrase.length) return false;
  for (let i = 0; i < tokens.length; i++) {
    if (!isWordMatch(phrase[i], tokens[i])) return false;
  }
  return true;
}

/** Drops any number of leading intro phrases from normalized `tokens`. */
export function stripLeadingIntros(tokens, ayahOpening = []) {
  if (!tokens.length) return tokens;
  let remaining = tokens;
  let changed = true;
  while (changed && remaining.length) {
    changed = false;
    for (const phrase of INTRO_PHRASES) {
      if (!startsWithPhrase(remaining, phrase)) continue;
      // The ayah opens with this phrase: what was heard is the ayah, not an intro.
      if (startsWithPhrase(ayahOpening, phrase)) continue;
      remaining = remaining.slice(phrase.length);
      changed = true;
      break;
    }
  }
  // An in-progress intro (and not the ayah's own opening) is dropped, not scored as a wrong word.
  if (remaining.length) {
    for (const phrase of [...INTRO_PHRASES, ...INTRO_STARTERS]) {
      if (isPrefixOfPhrase(remaining, phrase)) {
        if (!startsWithPhrase(ayahOpening, remaining)) return [];
        break;
      }
    }
  }
  return remaining;
}

export function stripLeadingIntrosFromTranscript(transcript, ayahOpening = []) {
  const tokens = tokenize(transcript).filter((t) => t !== 'unk' && t !== '[unk]');
  return stripLeadingIntros(tokens, ayahOpening).join(' ');
}

/** The leading normalized tokens of the ayah's content words — enough to recognise any intro. */
export function openingTokensOf(referenceWords) {
  const out = [];
  for (const word of referenceWords) {
    if (word.isEnd) continue;
    for (const token of tokenize(word.text)) {
      if (out.length >= MAX_INTRO_PHRASE_LENGTH) return out;
      out.push(token);
    }
  }
  return out;
}
