// Ported from AyahRefTest.kt.
import test from 'node:test';
import assert from 'node:assert/strict';
import { VERSE_COUNTS, surahAyahToGlobal as g } from '../../static/js/core/quran.js';
import { formatRefs, parseRefs } from '../../static/js/core/ayahRef.js';

const range = (s, from, to) => Array.from({ length: to - from + 1 }, (_, i) => g(s, from + i));
const set = (arr) => new Set(arr);

test('single ayah', () => {
  const r = parseRefs('2:255');
  assert.deepEqual(r.ids, set([g(2, 255)]));
  assert.deepEqual(r.unparsed, []);
});

test('ayah range within a surah', () => {
  assert.deepEqual(parseRefs('36:1-5').ids, set(range(36, 1, 5)));
});

test('whole surah by number', () => {
  assert.deepEqual(parseRefs('112').ids, set(range(112, 1, 4)));
});

test('range of whole surahs', () => {
  const r = parseRefs('113-114');
  assert.equal(r.ids.size, 5 + 6);
  assert.ok(r.ids.has(g(113, 1)) && r.ids.has(g(114, 6)));
});

test('by name, whole and range', () => {
  assert.deepEqual(parseRefs('Al-Ikhlas').ids, set(range(112, 1, 4)));
  assert.deepEqual(parseRefs('Al-Baqarah 255').ids, set([g(2, 255)]));
  assert.deepEqual(parseRefs('Ya-Sin 1-3').ids, set(range(36, 1, 3)));
});

test('names are matched loosely', () => {
  for (const name of ['al-ikhlas', 'AL IKHLAS', 'Ikhlas', 'ikhlas']) {
    assert.deepEqual(parseRefs(name).ids, set(range(112, 1, 4)), name);
  }
});

test('several tokens at once, comma or newline separated', () => {
  for (const input of ['2:255, 112, 36:1-3', '2:255\n112\n36:1-3']) {
    const r = parseRefs(input);
    assert.equal(r.ids.size, 1 + 4 + 3);
    assert.deepEqual(r.unparsed, []);
  }
});

test('ranges are clamped to the real surah length', () => {
  assert.deepEqual(parseRefs('112:1-999').ids, set(range(112, 1, 4)));
});

test('rubbish is reported rather than silently dropped', () => {
  const r = parseRefs('2:255, wibble, 900, 112');
  assert.ok(r.ids.has(g(2, 255)));
  assert.ok(r.ids.has(g(112, 1)));
  assert.ok(r.unparsed.length > 0, 'surah 900 does not exist');
});

test('format collapses runs and whole surahs', () => {
  const ids = set([...range(112, 1, 4), ...range(36, 1, 3), g(2, 255)]);
  assert.deepEqual(formatRefs(ids), ['2:255', '36:1-3', '112']);
});

test('format then parse round-trips', () => {
  const ids = set([
    ...range(2, 1, 286),
    g(3, 55),
    ...range(36, 1, 83),
    g(18, 1),
    g(18, 2),
    g(18, 10),
    ...range(114, 1, 6),
  ]);
  const back = parseRefs(formatRefs(ids).join(','));
  assert.deepEqual(back.ids, ids);
  assert.deepEqual(back.unparsed, []);
});

test('round-trips the whole Quran', () => {
  const all = set(Array.from({ length: 6236 }, (_, i) => i + 1));
  assert.deepEqual(parseRefs(formatRefs(all).join(',')).ids, all);
  assert.equal(VERSE_COUNTS.length, 114);
});

test('JSON from an LLM can be pasted whole', () => {
  const reply = `{
  "format": "learned-ayahs",
  "version": 1,
  "ayahs": ["2:255", "36:1-3", "112"]
}`;
  assert.equal(parseRefs(reply).ids.size, 1 + 3 + 4);
});
