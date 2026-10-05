// Ported from WordSyncTest.kt. Segments are [start, end) like `start until end` in Kotlin.
import test from 'node:test';
import assert from 'node:assert/strict';
import { activeWordIndex } from '../../static/js/core/wordSync.js';

const segments = [
  [0, 640],
  [840, 1280],
  [1960, 2320],
];

test('a position inside a segment selects that word', () => {
  assert.equal(activeWordIndex(100, segments, 3), 0);
  assert.equal(activeWordIndex(1000, segments, 3), 1);
  assert.equal(activeWordIndex(2000, segments, 3), 2);
});

test('the gap between words keeps the last finished word active', () => {
  assert.equal(activeWordIndex(700, segments, 3), 0);
  assert.equal(activeWordIndex(640, segments, 3), 0, 'the end is exclusive');
});

test('a position before the first word selects nothing', () => {
  assert.equal(activeWordIndex(100, [[500, 900]], 1), -1);
});

test('empty inputs select nothing', () => {
  assert.equal(activeWordIndex(100, [], 3), -1);
  assert.equal(activeWordIndex(100, segments, 0), -1);
});

test('extra segments are clamped to the word count', () => {
  assert.equal(activeWordIndex(5000, segments, 2), 1);
});
