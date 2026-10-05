// The portable "learned-ayahs" file shared with the Android app (port of LearnedAyahsExport.kt):
//
//   { "format": "learned-ayahs", "version": 1, "exportedAt": "2026-07-17T06:00:00Z",
//     "count": 1842, "ayahs": ["1", "2:255", "36:1-83"] }
//
// References rather than raw ids, so the file stays readable, hand-editable and writable by an
// LLM. Parsing is deliberately lenient: anything the reference grammar understands is accepted,
// including a bare list of ids or refs.
//
// The web app may add a "bookmarks" array (same reference grammar). The Android app reads only
// "ayahs", so files written here import there unchanged, and Android files import here.

import { TOTAL_AYAHS, isValidGlobalId } from './quran.js';
import { formatRefs, parseRefs } from './ayahRef.js';

export const FORMAT = 'learned-ayahs';
export const VERSION = 1;

const utcStamp = (now) => now.toISOString().replace(/\.\d{3}Z$/, 'Z');

/**
 * @param {Iterable<number>} ids learned global ids
 * @param {{now?: Date, bookmarks?: Iterable<number>}} [options]
 */
export function exportLearned(ids, { now = new Date(), bookmarks } = {}) {
  const set = new Set(ids);
  const doc = {
    format: FORMAT,
    version: VERSION,
    exportedAt: utcStamp(now),
    count: set.size,
    ayahs: formatRefs(set),
  };
  if (bookmarks) {
    const marks = new Set(bookmarks);
    if (marks.size) doc.bookmarks = formatRefs(marks);
  }
  return JSON.stringify(doc, null, 2);
}

/** Suggested download name, e.g. LearnedAyahs-2026-10-05.json (local date, like Android). */
export function suggestedFileName(now = new Date()) {
  const pad = (n) => String(n).padStart(2, '0');
  return `LearnedAyahs-${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}.json`;
}

function fromArray(array) {
  const ids = new Set();
  const unparsed = [];
  for (const value of array) {
    if (typeof value === 'number' && Number.isInteger(value)) {
      if (value >= 1 && value <= TOTAL_AYAHS) ids.add(value);
      else unparsed.push(String(value));
    } else if (typeof value === 'string') {
      const parsed = parseRefs(value);
      parsed.ids.forEach((id) => ids.add(id));
      unparsed.push(...parsed.unparsed);
    } else {
      unparsed.push(String(value));
    }
  }
  return { ids: new Set([...ids].sort((a, b) => a - b)), unparsed };
}

function tryJson(text) {
  try {
    return { ok: true, value: JSON.parse(text) };
  } catch {
    return { ok: false };
  }
}

const isPlainObject = (v) => v !== null && typeof v === 'object' && !Array.isArray(v);

/** Same contract as the Android parser: learned ids plus the tokens it could not read. */
export function parseLearned(text) {
  const json = tryJson(text);
  if (json.ok && isPlainObject(json.value)) {
    for (const key of ['ayahs', 'learnedAyahs']) {
      if (Array.isArray(json.value[key])) return fromArray(json.value[key]);
    }
  }
  if (json.ok && Array.isArray(json.value)) return fromArray(json.value);
  // Free text: a pasted LLM reply or a hand-typed list. The grammar strips JSON punctuation.
  return parseRefs(text);
}

/**
 * Learned ayahs from the Qur'an-app library export that the original desktop player read
 * (`quran_library … .json`): every non-deleted folder titled "… Every Learned Ayah …".
 */
function parseQuranLibrary(doc) {
  const folders = new Set(
    doc.folders
      .filter((f) => f && !f.is_deleted && String(f.title ?? '').includes('Every Learned Ayah'))
      .map((f) => f.id),
  );
  const ids = new Set();
  for (const item of doc.items) {
    if (item && !item.is_deleted && folders.has(item.folder_id) && isValidGlobalId(item.id)) {
      ids.add(item.id);
    }
  }
  return { ids: new Set([...ids].sort((a, b) => a - b)), unparsed: [] };
}

/**
 * Everything an import file can carry. Recognises, in order: this app's / the Android app's
 * export, a quran_library export, a bare array, and finally free text.
 *
 * @returns {{kind: string, learned: {ids: Set<number>, unparsed: string[]},
 *            bookmarks: Set<number>|null}}
 */
export function parseImport(text) {
  const json = tryJson(text);
  if (json.ok && isPlainObject(json.value)) {
    const doc = json.value;
    if (Array.isArray(doc.folders) && Array.isArray(doc.items)) {
      return { kind: 'quran-library', learned: parseQuranLibrary(doc), bookmarks: null };
    }
    const bookmarks = Array.isArray(doc.bookmarks) ? fromArray(doc.bookmarks).ids : null;
    return { kind: doc.format === FORMAT ? FORMAT : 'json', learned: parseLearned(text), bookmarks };
  }
  return { kind: json.ok ? 'list' : 'text', learned: parseLearned(text), bookmarks: null };
}

/** The prompt the "Add by description" screen copies; mirrors the Android wording. */
export function llmPrompt() {
  return [
    "I am tracking which ayahs of the Qur'an I have memorised.",
    '',
    'Convert my description below into JSON of exactly this shape, and reply with the JSON only:',
    '',
    '{',
    `  "format": "${FORMAT}",`,
    `  "version": ${VERSION},`,
    '  "ayahs": ["2:255", "36:1-83", "112", "78-114"]',
    '}',
    '',
    'Reference rules:',
    '- "2:255"    a single ayah (surah:ayah)',
    '- "36:1-83"  a range of ayahs within a surah',
    '- "112"      an entire surah',
    '- "78-114"   a range of entire surahs',
    'Use surah numbers (1-114), not names. Do not include ayahs I did not mention.',
    '',
    'My description:',
    '',
  ].join('\n');
}
