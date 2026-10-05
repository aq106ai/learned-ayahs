// Ported from UthmaniTranscriptMatchTest.kt: plain-spelling (imlaei) transcripts — what a speech
// recogniser writes — must score as correct against the Uthmani text. The cases are extracted
// verbatim from the Kotlin test into uthmani-transcripts.json.
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { evaluateContinuing } from '../../static/js/core/recite/evaluator.js';
import { levenshteinDistance, normalize } from '../../static/js/core/recite/normalizer.js';
import { Tuning } from '../../static/js/core/recite/coach.js';
import { wordsFor } from './helpers.js';

const cases = JSON.parse(readFileSync(new URL('./uthmani-transcripts.json', import.meta.url), 'utf8'));

function assertAllComplete(list) {
  const failures = [];
  for (const [key, transcript] of list) {
    const [surah, ayah] = key.split(':').map(Number);
    const words = wordsFor(surah, ayah);
    assert.ok(words.length, `${key} must resolve from the bundled text`);
    const r = evaluateContinuing(words, transcript, true, 0);
    if (!r.isComplete) {
      const wrong = r.evaluations
        .filter((e) => e.status !== 'CORRECT')
        .map((e) => `[${e.wordIndex}] ${e.originalWord.text} <- ${e.spokenWord}`)
        .join(', ');
      failures.push(`${key} ${r.correctCount}/${r.totalContentWords}: ${wrong}`);
    }
  }
  assert.deepEqual(failures, [], 'ayahs recited correctly but not scored correct');
}

test('Uthmani orthography scores plain transcripts as correct', () => {
  assert.equal(cases.orthographyFixes.length, 11);
  assertAllComplete(cases.orthographyFixes);
});

test('ordinary ayahs still score as correct', () => {
  assert.equal(cases.unchanged.length, 10);
  assertAllComplete(cases.unchanged);
});

test('one wrong word is still flagged at the right index', () => {
  const r = evaluateContinuing(
    wordsFor(2, 21),
    'يا أيها الناس اعبدوا ربي الذي خلقكم والذين من قبلكم لعلكم تتقون',
    true,
    0,
  );
  assert.equal(r.mistakeCount, 1);
  assert.equal(r.firstMistake.wordIndex, 3);
  assert.equal(r.firstMistake.spokenWord, 'ربي');
});

test('residual spelling gaps stay inside the near-miss band', () => {
  const r = evaluateContinuing(
    wordsFor(12, 87),
    'يا بني اذهبوا فتحسسوا من يوسف وأخيه ولا تيأسوا من روح الله إنه لا ييأس من روح الله إلا القوم الكافرون',
    true,
    0,
  );
  assert.equal(r.firstMistake.wordIndex, 13);
  assert.ok(r.correctCount >= 13, 'the walk must not collapse to 0 correct');
  const expected = normalize(r.firstMistake.originalWord.text);
  const heard = normalize(r.firstMistake.spokenWord ?? '');
  assert.ok(levenshteinDistance(expected, heard) <= Tuning.NEAR_MISS_TOLERANCE);
});

test('a dropped word is flagged as a skip', () => {
  const r = evaluateContinuing(
    wordsFor(2, 21),
    'يا أيها اعبدوا ربكم الذي خلقكم والذين من قبلكم لعلكم تتقون',
    true,
    0,
  );
  assert.equal(r.firstMistake.wordIndex, 1);
  assert.equal(r.firstMistake.spokenWord, null);
  assert.ok(r.firstMistake.feedback.startsWith('Skipped word'));
});
