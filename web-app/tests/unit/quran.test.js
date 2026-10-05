// Ported from AyahMappingTest.kt and QueueBuilderTest.kt.
import test from 'node:test';
import assert from 'node:assert/strict';
import {
  PlaybackMode,
  QueueBuilder,
  RepeatMode,
  VERSE_COUNTS,
  ayahFilename,
  buildMaster,
  globalToSurahAyah,
  makeTrack,
  surahAyahToGlobal,
} from '../../static/js/core/quran.js';

test('first and last ayahs map correctly', () => {
  assert.deepEqual(globalToSurahAyah(1), [1, 1]);
  assert.equal(surahAyahToGlobal(1, 1), 1);
  assert.deepEqual(globalToSurahAyah(6236), [114, 6]);
  assert.equal(surahAyahToGlobal(114, 6), 6236);
});

test('surah boundaries map correctly', () => {
  assert.deepEqual(globalToSurahAyah(7), [1, 7]);
  assert.deepEqual(globalToSurahAyah(8), [2, 1]);
});

test('round trip holds for every ayah', () => {
  let global = 0;
  for (let surah = 1; surah <= 114; surah++) {
    for (let ayah = 1; ayah <= VERSE_COUNTS[surah - 1]; ayah++) {
      global++;
      assert.equal(surahAyahToGlobal(surah, ayah), global);
      assert.deepEqual(globalToSurahAyah(global), [surah, ayah]);
    }
  }
  assert.equal(global, 6236, "the Qur'an has 6236 ayahs");
});

test('out-of-range ids throw instead of mapping to a nonexistent ayah', () => {
  assert.throws(() => globalToSurahAyah(0));
  assert.throws(() => globalToSurahAyah(6237));
});

test('filenames are zero-padded everyayah names', () => {
  assert.equal(ayahFilename(1, 1), '001001.mp3');
  assert.equal(ayahFilename(114, 6), '114006.mp3');
  assert.equal(ayahFilename(2, 255), '002255.mp3');
});

test('the master list is sorted, de-duplicated, range-checked and indexed from 1', () => {
  const master = buildMaster([262, 1, 262, 0, 7000, 8]);
  assert.deepEqual(master.map((t) => t.globalId), [1, 8, 262]);
  assert.deepEqual(master.map((t) => t.index), [1, 2, 3]);
});

const g = surahAyahToGlobal;
const master = [g(2, 1), g(2, 5), g(2, 255), g(36, 1), g(36, 2), g(112, 1)].map((id) => makeTrack(id));
const ids = (q) => q.map((t) => t.globalId);

test('word by word uses the same queue as revise', () => {
  for (const repeat of [RepeatMode.OFF, RepeatMode.SURAH]) {
    assert.deepEqual(
      ids(QueueBuilder.buildQueue(master, PlaybackMode.REVISE, repeat, 2)),
      ids(QueueBuilder.buildQueue(master, PlaybackMode.WORD_BY_WORD, repeat, 2)),
    );
  }
});

test('revise plays the master list regardless of repeat ayah', () => {
  assert.equal(QueueBuilder.buildQueue(master, PlaybackMode.REVISE, RepeatMode.OFF, 2), master);
  assert.equal(QueueBuilder.buildQueue(master, PlaybackMode.REVISE, RepeatMode.AYAH, 2), master);
});

test('full surah streams every ayah of the surah, not just learned', () => {
  const queue = QueueBuilder.buildQueue(master, PlaybackMode.FULL_SURAH, RepeatMode.OFF, 112);
  assert.deepEqual(queue.map((t) => t.ayah), [1, 2, 3, 4]);
  assert.ok(queue.every((t) => t.surah === 112));
});

test('revise with repeat surah keeps only that surah’s learned ayahs', () => {
  const queue = QueueBuilder.buildQueue(master, PlaybackMode.REVISE, RepeatMode.SURAH, 2);
  assert.deepEqual(queue.map((t) => t.ayah), [1, 5, 255]);
});

test('repeat mode is independent of playback mode for full surah', () => {
  const off = ids(QueueBuilder.buildQueue(master, PlaybackMode.FULL_SURAH, RepeatMode.OFF, 112));
  assert.deepEqual(ids(QueueBuilder.buildQueue(master, PlaybackMode.FULL_SURAH, RepeatMode.AYAH, 112)), off);
  assert.deepEqual(ids(QueueBuilder.buildQueue(master, PlaybackMode.FULL_SURAH, RepeatMode.SURAH, 112)), off);
});

test('learned surahs are distinct and sorted', () => {
  assert.deepEqual(QueueBuilder.learnedSurahs(master), [2, 36, 112]);
});

test('surah navigation wraps around learned surahs', () => {
  assert.equal(QueueBuilder.nextSurah(master, 2), 36);
  assert.equal(QueueBuilder.nextSurah(master, 36), 112);
  assert.equal(QueueBuilder.nextSurah(master, 112), 2);
  assert.equal(QueueBuilder.prevSurah(master, 2), 112);
  assert.equal(QueueBuilder.prevSurah(master, 36), 2);
});

test('surah navigation wraps around all 114 surahs', () => {
  assert.equal(QueueBuilder.nextSurahAll(1), 2);
  assert.equal(QueueBuilder.nextSurahAll(113), 114);
  assert.equal(QueueBuilder.nextSurahAll(114), 1);
  assert.equal(QueueBuilder.prevSurahAll(1), 114);
  assert.equal(QueueBuilder.prevSurahAll(2), 1);
});

test('full surah for an unknown surah is empty', () => {
  assert.deepEqual(QueueBuilder.buildQueue(master, PlaybackMode.FULL_SURAH, RepeatMode.OFF, 0), []);
  assert.deepEqual(QueueBuilder.buildQueue(master, PlaybackMode.FULL_SURAH, RepeatMode.OFF, 115), []);
});
