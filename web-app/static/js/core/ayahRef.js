// The reference grammar shared by the quick-add box, the export/import file and the LLM prompt.
// Port of data/AyahRef.kt — keep the two in step, since files move between the apps.
//
// Accepted tokens, comma- or newline-separated:
//   2:255          a single ayah
//   36:1-83        a range within a surah
//   112            a whole surah
//   78-114         a range of whole surahs
//   Al-Baqarah 255, Yasin 1-20, An-Nas   by name, with an optional ayah/range
//
// Surah names are matched loosely (case, spaces, hyphens and a leading article are ignored)
// because the input may be typed by hand or written by an LLM.

import {
  TOTAL_AYAHS,
  VERSE_COUNTS,
  globalToSurahAyah,
  surahAyahToGlobal,
  surahName,
} from './quran.js';

const NUMERIC_AYAH = /^(\d{1,3})\s*:\s*(\d{1,3})(?:\s*-\s*(\d{1,3}))?$/;
const SURAH_RANGE = /^(\d{1,3})\s*-\s*(\d{1,3})$/;
const WHOLE_SURAH = /^(\d{1,3})$/;
const NAMED = /^([A-Za-z][A-Za-z\-' ]*?)\s*(?:(\d{1,3})(?:\s*-\s*(\d{1,3}))?)?$/;

const JSON_KEYS = new Set(['format', 'version', 'count', 'exportedat', 'ayahs', 'learnedayahs']);
const TRIM_CHARS = new Set(['"', "'", '.', '·']);

function trimChars(s) {
  let start = 0;
  let end = s.length;
  while (start < end && TRIM_CHARS.has(s[start])) start++;
  while (end > start && TRIM_CHARS.has(s[end - 1])) end--;
  return s.slice(start, end);
}

/** Splits on separators and strips JSON punctuation, so a pasted LLM reply parses as-is. */
function tokenize(input) {
  return input
    .split(/[,\n\r;[\]{}]/)
    .map((t) => trimChars(t.trim()).trim())
    .filter((t) => t.length > 0)
    // Drop the JSON scaffolding around the ayah list itself.
    .filter((t) => {
      if (t.startsWith('"')) return false;
      if (!t.includes(':')) return true;
      const key = t.slice(0, t.indexOf(':')).trim().toLowerCase();
      return !JSON_KEYS.has(key);
    });
}

function addRange(surah, from, to, into) {
  if (surah < 1 || surah > 114) return false;
  const last = VERSE_COUNTS[surah - 1];
  // Clamped to the surah's real length, so "36:1-999" adds Ya-Sin rather than failing.
  const lo = Math.max(Math.min(from, to), 1);
  const hi = Math.min(Math.max(from, to), last);
  if (lo > last) return false;
  for (let ayah = lo; ayah <= hi; ayah++) into.add(surahAyahToGlobal(surah, ayah));
  return true;
}

function addWholeSurah(surah, into) {
  if (surah < 1 || surah > 114) return false;
  return addRange(surah, 1, VERSE_COUNTS[surah - 1], into);
}

/** "Al-Baqarah" / "al baqarah" / "Baqarah" all reduce to the same key. */
function normaliseName(name) {
  let key = name.toLowerCase().replace(/[^a-z]/g, '');
  for (const prefix of ['al', 'as', 'ash', 'an', 'ar', 'at', 'az']) {
    if (key.startsWith(prefix)) key = key.slice(prefix.length);
  }
  return key;
}

const NORMALISED_NAMES = Array.from({ length: 114 }, (_, i) => normaliseName(surahName(i + 1)));

function surahByName(raw) {
  const needle = normaliseName(raw);
  if (!needle) return null;
  const exact = NORMALISED_NAMES.indexOf(needle);
  if (exact >= 0) return exact + 1;
  const matches = [];
  NORMALISED_NAMES.forEach((name, i) => {
    if (name.includes(needle)) matches.push(i + 1);
  });
  return matches.length === 1 ? matches[0] : null;
}

function parseToken(token, into) {
  let m = token.match(NUMERIC_AYAH);
  if (m) return addRange(+m[1], +m[2], +(m[3] || m[2]), into);
  m = token.match(SURAH_RANGE);
  if (m) {
    let any = false;
    for (let s = +m[1]; s <= +m[2]; s++) any = addWholeSurah(s, into) || any;
    return any;
  }
  m = token.match(WHOLE_SURAH);
  if (m) return addWholeSurah(+m[1], into);
  m = token.match(NAMED);
  if (m) {
    const surah = surahByName(m[1]);
    if (surah == null) return false;
    if (!m[2]) return addWholeSurah(surah, into);
    return addRange(surah, +m[2], +(m[3] || m[2]), into);
  }
  return false;
}

/**
 * @returns {{ids: Set<number>, unparsed: string[]}} `unparsed` lists tokens that matched nothing,
 *   so the UI can say what it ignored rather than drop it silently.
 */
export function parseRefs(input) {
  const ids = new Set();
  const unparsed = [];
  for (const token of tokenize(String(input ?? ''))) {
    if (!parseToken(token, ids)) unparsed.push(token);
  }
  return { ids: new Set([...ids].sort((a, b) => a - b)), unparsed };
}

const range = (surah, from, to) => (from === to ? `${surah}:${from}` : `${surah}:${from}-${to}`);

/** Global ids as compact references: a whole surah collapses to `112`, runs to `36:1-83`. */
export function formatRefs(ids) {
  const bySurah = new Map();
  for (const id of ids) {
    if (!(Number.isInteger(id) && id >= 1 && id <= TOTAL_AYAHS)) continue;
    const [surah, ayah] = globalToSurahAyah(id);
    if (!bySurah.has(surah)) bySurah.set(surah, []);
    bySurah.get(surah).push(ayah);
  }
  const refs = [];
  for (const surah of [...bySurah.keys()].sort((a, b) => a - b)) {
    const ayahs = [...new Set(bySurah.get(surah))].sort((a, b) => a - b);
    if (ayahs.length === VERSE_COUNTS[surah - 1]) {
      refs.push(String(surah));
      continue;
    }
    let start = ayahs[0];
    let prev = start;
    for (const ayah of ayahs.slice(1)) {
      if (ayah === prev + 1) {
        prev = ayah;
        continue;
      }
      refs.push(range(surah, start, prev));
      start = ayah;
      prev = ayah;
    }
    refs.push(range(surah, start, prev));
  }
  return refs;
}
