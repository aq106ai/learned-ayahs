// Ported from LearnedAyahsExportTest.kt, plus the web app's additions (bookmarks, the old
// desktop player's quran_library export) and a compatibility check against the Android parser's
// contract.
import test from 'node:test';
import assert from 'node:assert/strict';
import { VERSE_COUNTS, surahAyahToGlobal as g } from '../../static/js/core/quran.js';
import {
  exportLearned,
  llmPrompt,
  parseImport,
  parseLearned,
  suggestedFileName,
} from '../../static/js/core/learnedExport.js';

const range = (s, from, to) => Array.from({ length: to - from + 1 }, (_, i) => g(s, from + i));
const sample = new Set([...range(112, 1, 4), ...range(36, 1, 83), g(2, 255)]);

test('export has the documented shape', () => {
  const json = JSON.parse(exportLearned(sample));
  assert.equal(json.format, 'learned-ayahs');
  assert.equal(json.version, 1);
  assert.equal(json.count, sample.size);
  assert.ok(json.ayahs.length > 0);
  assert.match(json.exportedAt, /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$/);
});

test('export/import round-trips exactly', () => {
  assert.deepEqual(parseLearned(exportLearned(sample)).ids, sample);
});

test('round-trips an owner-sized selection', () => {
  const big = new Set([...range(1, 1, 7), ...range(2, 1, 286), g(3, 55), ...range(36, 1, 83)]);
  for (let s = 70; s <= 114; s++) range(s, 1, VERSE_COUNTS[s - 1]).forEach((id) => big.add(id));
  assert.deepEqual(parseLearned(exportLearned(big)).ids, big);
});

test('accepts a bare array of ids, a bare array of refs, and the legacy key', () => {
  assert.deepEqual(parseLearned('[1, 2, 3]').ids, new Set([1, 2, 3]));
  assert.deepEqual(parseLearned('["2:255"]').ids, new Set([g(2, 255)]));
  assert.deepEqual(parseLearned('{"learnedAyahs": [1, 2]}').ids, new Set([1, 2]));
});

test('out-of-range ids are rejected, not imported', () => {
  const r = parseLearned('[0, 6237, 99999, 5]');
  assert.deepEqual(r.ids, new Set([5]));
  assert.ok(r.unparsed.length > 0);
});

test('an empty selection exports and re-imports cleanly', () => {
  assert.equal(parseLearned(exportLearned([])).ids.size, 0);
});

test('suggested filename is json', () => {
  assert.match(suggestedFileName(), /^LearnedAyahs-\d{4}-\d{2}-\d{2}\.json$/);
});

test('bookmarks ride along without disturbing the fields the Android app reads', () => {
  const text = exportLearned(sample, { bookmarks: [g(2, 255), g(18, 10)] });
  const json = JSON.parse(text);
  // The Android parser reads "ayahs" (or "learnedAyahs") and nothing else.
  assert.deepEqual(Object.keys(json).slice(0, 5), ['format', 'version', 'exportedAt', 'count', 'ayahs']);
  assert.deepEqual(parseLearned(text).ids, sample);
  const imported = parseImport(text);
  assert.equal(imported.kind, 'learned-ayahs');
  assert.deepEqual(imported.bookmarks, new Set([g(2, 255), g(18, 10)]));
});

test('an Android export imports as-is', () => {
  // Exactly what LearnedAyahsExport.export() writes on the phone.
  const android = `{
  "format": "learned-ayahs",
  "version": 1,
  "exportedAt": "2026-07-17T06:00:00Z",
  "count": 88,
  "ayahs": [
    "2:255",
    "36",
    "112"
  ]
}`;
  const r = parseImport(android);
  assert.equal(r.kind, 'learned-ayahs');
  assert.deepEqual(r.learned.ids, sample);
  assert.equal(r.bookmarks, null);
});

test('the original desktop player’s quran_library export is understood', () => {
  const library = {
    folders: [
      { id: 'a', title: '01-03 Every Learned Ayah', is_deleted: false },
      { id: 'b', title: 'To learn', is_deleted: false },
      { id: 'c', title: 'Old Every Learned Ayah', is_deleted: true },
    ],
    items: [
      { id: 1, folder_id: 'a', is_deleted: false },
      { id: 262, folder_id: 'a', is_deleted: false },
      { id: 300, folder_id: 'a', is_deleted: true },
      { id: 400, folder_id: 'b', is_deleted: false },
      { id: 500, folder_id: 'c', is_deleted: false },
      { id: 9999, folder_id: 'a', is_deleted: false },
    ],
    notes: [{ text: 'never imported' }],
  };
  const r = parseImport(JSON.stringify(library));
  assert.equal(r.kind, 'quran-library');
  assert.deepEqual(r.learned.ids, new Set([1, 262]));
});

test('free text falls back to the reference grammar', () => {
  const r = parseImport('Al-Mulk, 2:255');
  assert.equal(r.kind, 'text');
  assert.equal(r.learned.ids.size, 30 + 1);
});

test('the LLM prompt describes the export shape and the grammar', () => {
  const prompt = llmPrompt();
  assert.match(prompt, /"format": "learned-ayahs"/);
  assert.match(prompt, /36:1-83/);
  assert.match(prompt, /My description:\n$/);
});
