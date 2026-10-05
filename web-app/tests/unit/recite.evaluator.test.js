// Ported from RecitationEvaluatorTest.kt.
import test from 'node:test';
import assert from 'node:assert/strict';
import {
  Status,
  evaluate,
  evaluateContinuing,
  evaluateStreaming,
} from '../../static/js/core/recite/evaluator.js';
import { w } from './helpers.js';

const verse2 = [w('ٱلْحَمْدُ'), w('لِلَّهِ'), w('رَبِّ'), w('ٱلْعَـٰلَمِينَ'), w('٢', true)];
const statuses = (r) => r.evaluations.map((e) => e.status);
const { CORRECT, PENDING, MISTAKE } = Status;

test('a perfect recitation scores 100%', () => {
  const r = evaluate(verse2, 'الحمد لله رب العالمين');
  assert.equal(r.totalContentWords, 4);
  assert.equal(r.correctCount, 4);
  assert.equal(r.mistakeCount, 0);
  assert.equal(r.accuracyPercentage, 100);
  assert.ok(r.isComplete);
});

test('a wrong word is detected with feedback', () => {
  const r = evaluate(verse2, 'الحمد لله مالك العالمين');
  assert.equal(r.correctCount, 3);
  assert.equal(r.mistakeCount, 1);
  assert.equal(r.accuracyPercentage, 75);
  assert.equal(r.firstMistake.status, MISTAKE);
});

test('a partial recitation leaves the rest pending', () => {
  const r = evaluate(verse2, 'الحمد لله');
  assert.equal(r.correctCount, 2);
  assert.equal(r.mistakeCount, 0);
  assert.deepEqual(statuses(r), [CORRECT, CORRECT, PENDING, PENDING]);
});

test('a skipped word is flagged where it was missed', () => {
  const r = evaluate(verse2, 'الحمد رب العالمين');
  assert.equal(r.correctCount, 3);
  assert.equal(r.mistakeCount, 1);
  assert.equal(r.evaluations[1].status, MISTAKE);
});

test('partials never mark mistakes', () => {
  const r = evaluate(verse2, 'الحمد لله مالك', false);
  assert.equal(r.mistakeCount, 0);
  assert.equal(r.isComplete, false);
  assert.deepEqual(statuses(r).slice(0, 3), [CORRECT, CORRECT, PENDING]);
});

test('isComplete requires every word correct; trailing extra tokens are fine', () => {
  assert.equal(evaluate(verse2, 'الحمد لله رب').isComplete, false);
  assert.equal(evaluate(verse2, 'الحمد لله مالك العالمين').isComplete, false);
  assert.equal(evaluate(verse2, 'الحمد لله رب العالمين').isComplete, true);
  const extra = evaluate(verse2, 'الحمد لله رب العالمين extra');
  assert.ok(extra.isComplete);
  assert.equal(extra.mistakeCount, 0);
});

test('hamza on ya and waw is folded; an empty reference is not complete', () => {
  const r = evaluate([w('مُسْتَهْزِئُونَ'), w('مُؤْمِنُونَ')], 'مستهزيون مومنون');
  assert.ok(r.isComplete);
  assert.equal(evaluate([], '').isComplete, false);
});

test('streaming: an in-progress prefix stays pending', () => {
  const r = evaluateStreaming(verse2, 'الح', true);
  assert.equal(r.mistakeCount, 0);
  assert.deepEqual(statuses(r).slice(0, 2), [PENDING, PENDING]);
});

test('streaming: an unstable wrong token stays pending, a stable one is a mistake', () => {
  const unstable = evaluateStreaming(verse2, 'الحمد لله مالك', false);
  assert.equal(unstable.mistakeCount, 0);
  assert.deepEqual(statuses(unstable).slice(0, 3), [CORRECT, CORRECT, PENDING]);
  const stable = evaluateStreaming(verse2, 'الحمد لله مالك', true);
  assert.equal(stable.mistakeCount, 1);
  assert.equal(stable.evaluations[2].status, MISTAKE);
  assert.equal(stable.evaluations[2].wordIndex, 2);
  assert.ok(stable.firstMistake);
});

test('streaming: a stable skip is flagged', () => {
  const r = evaluateStreaming(verse2, 'الحمد رب', true);
  assert.equal(r.mistakeCount, 1);
  assert.equal(r.evaluations[1].status, MISTAKE);
  assert.equal(r.evaluations[0].status, CORRECT);
});

test('continuing from a mistake completes the remaining words', () => {
  const r = evaluateContinuing(verse2, 'رب العالمين', true, 2);
  assert.equal(r.mistakeCount, 0);
  assert.deepEqual(statuses(r), [CORRECT, CORRECT, CORRECT, CORRECT]);
  assert.ok(r.isComplete);
});

test('a one-word lookback extends without resetting the prefix', () => {
  const r = evaluateContinuing(verse2, 'لله رب', true, 2);
  assert.equal(r.mistakeCount, 0);
  assert.deepEqual(statuses(r), [CORRECT, CORRECT, CORRECT, PENDING]);
  assert.ok(!r.isComplete);
});

test('a stable wrong token keeps the lock', () => {
  const r = evaluateContinuing(verse2, 'مالك', true, 2);
  assert.equal(r.mistakeCount, 1);
  assert.deepEqual(statuses(r).slice(0, 3), [CORRECT, CORRECT, MISTAKE]);
  assert.equal(r.evaluations[2].wordIndex, 2);
  assert.ok(!r.isComplete);
});

test('the locked prefix alone has no new mistake', () => {
  const r = evaluateContinuing(verse2, 'الحمد لله', true, 2);
  assert.equal(r.mistakeCount, 0);
  assert.deepEqual(statuses(r).slice(0, 3), [CORRECT, CORRECT, PENDING]);
  assert.equal(r.firstMistake, null);
});

test('a live partial does not flag a skip as a mistake', () => {
  const r = evaluateContinuing(verse2, 'الحمد رب', true, 0, false);
  assert.equal(r.mistakeCount, 0);
  assert.deepEqual(statuses(r).slice(0, 2), [CORRECT, PENDING]);
  assert.equal(r.firstMistake, null);
});

test('resuming mid-ayah after a gap extends without a skip mistake', () => {
  const r = evaluateContinuing(verse2, 'رب العالمين', true, 2, false);
  assert.equal(r.mistakeCount, 0);
  assert.ok(r.isComplete);
});

test('unstable wrong token stays pending; a stable one is a mistake', () => {
  const unstable = evaluateContinuing(verse2, 'الحمد لله مالك', false, 0, false);
  assert.equal(unstable.mistakeCount, 0);
  assert.equal(unstable.evaluations[2].status, PENDING);
  const stable = evaluateContinuing(verse2, 'الحمد لله مالك', true, 0, false);
  assert.equal(stable.mistakeCount, 1);
  assert.equal(stable.evaluations[2].status, MISTAKE);
  assert.equal(stable.evaluations[2].spokenWord, 'مالك');
});

test('a committed skip is still flagged', () => {
  const r = evaluateContinuing(verse2, 'الحمد رب', true, 0, true);
  assert.equal(r.mistakeCount, 1);
  assert.equal(r.evaluations[1].status, MISTAKE);
  assert.equal(r.evaluations[1].spokenWord, null);
});

test('an intro prefix leaves the words pending without a mistake', () => {
  const r = evaluateContinuing(verse2, 'اعوذ', true, 0, false);
  assert.equal(r.mistakeCount, 0);
  assert.equal(r.correctCount, 0);
  assert.equal(r.evaluations[0].status, PENDING);
  assert.equal(r.firstMistake, null);
});

test('a mid-ayah gap preserves pending words and later matches', () => {
  const sixWords = [w('قُلْ'), w('أَعُوذُ'), w('بِرَبِّ'), w('ٱلْفَلَقِ'), w('مِن'), w('شَرِّ'), w('١', true)];
  const r = evaluateContinuing(sixWords, 'الفلق من', true, 2, false);
  assert.equal(r.mistakeCount, 0);
  assert.deepEqual(statuses(r), [CORRECT, CORRECT, PENDING, CORRECT, CORRECT, PENDING]);
  assert.equal(r.firstMistake, null);
});
