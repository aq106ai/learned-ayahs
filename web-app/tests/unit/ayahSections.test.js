// Ported from AyahSectionsTest.kt.
import test from 'node:test';
import assert from 'node:assert/strict';
import { VERSE_COUNTS, surahAyahToGlobal as g } from '../../static/js/core/quran.js';
import { learnedSections, sectionAt, sectionKey, sectionsBySurah } from '../../static/js/core/ayahSections.js';
import { formatRefs } from '../../static/js/core/ayahRef.js';

const range = (s, from, to) => Array.from({ length: to - from + 1 }, (_, i) => g(s, from + i));
const shape = (sections) => sections.map((x) => [x.surah, x.index, x.total, x.from, x.to, x.count]);

test('a section is one unbroken run of learned ayahs, counted per surah', () => {
  // Three stretches marked in Al-Baqarah, one in Taha: three sections and one, not 10 and 2.
  const ids = new Set([
    ...range(2, 1, 3), g(2, 10), ...range(2, 255, 257),
    ...range(20, 1, 2),
  ]);
  assert.deepEqual(shape(learnedSections(ids)), [
    [2, 1, 3, 1, 3, 3],
    [2, 2, 3, 10, 10, 1],
    [2, 3, 3, 255, 257, 3],
    [20, 1, 1, 1, 2, 2],
  ]);
  assert.deepEqual([...sectionsBySurah(ids)].map(([s, gs]) => [s, gs.length]), [[2, 3], [20, 1]]);
});

test('a section carries the global ids its ends sit on', () => {
  const sections = learnedSections(new Set(range(2, 255, 257)));
  assert.equal(sections[0].firstGlobalId, g(2, 255));
  assert.equal(sections[0].lastGlobalId, g(2, 257));
  assert.equal(sectionKey(sections[0]), '2:255-257');
  assert.equal(sectionKey(null), null);
});

test('18 stretches in Al-Baqarah are 18 sections, numbered 1 to 18', () => {
  // Every third ayah from 1: 18 single-ayah sections with a gap between each.
  const starts = Array.from({ length: 18 }, (_, i) => 1 + i * 3);
  const sections = learnedSections(new Set(starts.map((a) => g(2, a))));
  assert.equal(sections.length, 18);
  assert.deepEqual(sections.map((x) => x.index), starts.map((_, i) => i + 1));
  assert.ok(sections.every((x) => x.total === 18 && x.count === 1 && x.from === x.to));
});

test('a surah learned in full is one section, though its ref is the bare surah', () => {
  const ids = new Set(range(112, 1, 4));
  assert.deepEqual(formatRefs(ids), ['112']);
  assert.deepEqual(shape(learnedSections(ids)), [[112, 1, 1, 1, 4, 4]]);
  // Al-Fatihah too, so the rule isn't an artefact of a four-ayah surah.
  const fatihah = new Set(range(1, 1, VERSE_COUNTS[0]));
  assert.deepEqual(shape(learnedSections(fatihah)), [[1, 1, 1, 1, VERSE_COUNTS[0], VERSE_COUNTS[0]]]);
});

test('nothing learned is no sections, and junk ids are ignored', () => {
  assert.deepEqual(learnedSections(new Set()), []);
  assert.deepEqual([...sectionsBySurah(new Set())], []);
  // Out of range, zero, negative, fractional and duplicated.
  const ids = new Set([0, -4, 6237, 99999, 1.5, g(2, 255), g(2, 255)]);
  assert.deepEqual(shape(learnedSections(ids)), [[2, 1, 1, 255, 255, 1]]);
});

test('sections come surah by surah, and ayah by ayah inside a surah', () => {
  // Fed in deliberately out of order.
  const ids = new Set([g(36, 1), g(2, 20), g(112, 1), g(2, 1)]);
  assert.deepEqual(learnedSections(ids).map((x) => [x.surah, x.from]), [[2, 1], [2, 20], [36, 1], [112, 1]]);
});

test('sectionAt finds the section an ayah sits in, and nothing for an unlearned one', () => {
  const ids = new Set([...range(2, 255, 257), ...range(20, 1, 2)]);
  const baqarah = learnedSections(ids)[0];
  assert.deepEqual(sectionAt(ids, g(2, 255)), baqarah);
  assert.deepEqual(sectionAt(ids, g(2, 256)), baqarah);
  assert.deepEqual(sectionAt(ids, g(2, 257)), baqarah);
  assert.equal(sectionAt(ids, g(2, 258)), null);
  assert.equal(sectionAt(ids, g(36, 1)), null);
  // Anything that isn't an ayah id at all.
  assert.equal(sectionAt(ids, 0), null);
  assert.equal(sectionAt(ids, 6237), null);
  assert.equal(sectionAt(ids, undefined), null);
});
