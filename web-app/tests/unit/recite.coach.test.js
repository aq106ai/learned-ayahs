// Ported from player/ReciteReviewEndToEndTest.kt: drives whole runs through the coach with
// transcripts (the same door the recogniser uses), against the real bundled Qur'an text.
import test from 'node:test';
import assert from 'node:assert/strict';
import { surahAyahToGlobal as g } from '../../static/js/core/quran.js';
import { RecitationCoach } from '../../static/js/core/recite/coach.js';
import { wordsFor } from './helpers.js';

function makeCoach({ autoPlayMistakeAudio = false, autoAdvanceSuccess = true } = {}) {
  const clips = [];
  const coach = new RecitationCoach({
    loadWords: async (surah, ayah) => wordsFor(surah, ayah),
    settings: () => ({ autoPlayMistakeAudio, autoAdvanceSuccess }),
    playWordClip: async (surah, ayah, index) => {
      clips.push(`${surah}:${ayah}#${index}`);
    },
    sleep: () => Promise.resolve(),
  });
  return { coach, clips };
}

async function start(learned, options) {
  const { coach, clips } = makeCoach(options);
  await coach.setLearned(learned);
  await coach.selectAyah(Math.min(...learned));
  coach.startRecording();
  return { coach, clips };
}

const alIkhlas = ['قل هو الله أحد', 'الله الصمد', 'لم يلد ولم يولد', 'ولم يكن له كفوا أحد'];

test('Al-Ikhlas is recited without a single interruption, and the run ends with a recap', async () => {
  const ids = [1, 2, 3, 4].map((a) => g(112, a));
  const { coach, clips } = await start(ids);
  for (const [i, transcript] of alIkhlas.entries()) {
    assert.equal(coach.currentGlobalId, ids[i], `on the wrong ayah before 112:${i + 1}`);
    coach.acceptTranscript(transcript);
    await coach.settled();
  }
  const report = coach.sessionReport;
  assert.ok(report, 'the run never produced a recap');
  assert.equal(report.ayahs.length, 4);
  assert.equal(report.mistakeCount, 0);
  assert.equal(report.accuracyPercent, 100);
  assert.ok(report.reachedEndOfSurah);
  assert.deepEqual(clips, []);
});

test('names one wrong word and nothing else', async () => {
  const { coach } = await start([g(1, 2)]);
  coach.acceptTranscript('الحمد لله مالك العالمين');
  coach.acceptTranscript('الحمد لله مالك العالمين');
  coach.stopRecording();
  const outcome = coach.sessionReport.ayahs[0];
  assert.deepEqual(outcome.mistakenWordIndices, [2], 'only رَبِّ (word 2) is wrong');
  assert.equal(outcome.correctCount, 2, 'the words before the mistake still count');
  assert.equal(outcome.totalContentWords, 4);
});

test('a skipped word is named at the word that was missed', async () => {
  const { coach } = await start([g(112, 4)]);
  coach.acceptTranscript('ولم يكن كفوا أحد');
  coach.acceptTranscript('ولم يكن كفوا أحد');
  coach.stopRecording();
  assert.deepEqual(coach.sessionReport.ayahs[0].mistakenWordIndices, [2]);
});

test('an ayah opening with the attached vocative is recited cleanly', async () => {
  const { coach } = await start([g(2, 21)]);
  coach.acceptTranscript('يا أيها الناس اعبدوا ربكم الذي خلقكم والذين من قبلكم لعلكم تتقون');
  const r = coach.evaluationResult;
  assert.equal(r.totalContentWords, 11);
  assert.equal(r.correctCount, 11);
  assert.equal(r.mistakeCount, 0);
});

test('a mishearing is rescued by the recogniser’s other readings', async () => {
  const { coach } = await start([g(102, 1)]);
  coach.acceptTranscript('الحاكم التكاثر', true, ['ألهاكم التكاثر', 'الحاكم التكاثر']);
  assert.equal(coach.evaluationResult.correctCount, 2);
  assert.equal(coach.evaluationResult.mistakeCount, 0);
});

test('alternatives do not rescue a word that was actually wrong', async () => {
  const { coach } = await start([g(1, 2)]);
  const alternatives = ['الحمد لله ملك العالمين', 'الحمد لله مالكي العالمين', 'الحمد لله مالك العالمين'];
  coach.acceptTranscript('الحمد لله مالك العالمين', true, alternatives);
  coach.acceptTranscript('الحمد لله مالك العالمين', true, alternatives);
  coach.stopRecording();
  assert.deepEqual(coach.sessionReport.ayahs[0].mistakenWordIndices, [2]);
});

test('a near miss is never shown as a mistake', async () => {
  const { coach } = await start([g(102, 1)]);
  for (let i = 0; i < 3; i++) coach.acceptTranscript('الحاكم التكاثر');
  assert.ok(coach.evaluationResult.firstMistake, 'the raw evaluation does flag it');
  assert.equal(coach.confirmedMistake, null);
  assert.deepEqual(coach.believedMistakeIndices, new Set());
});

test('a believed mistake is shown, and corrected at most twice', async () => {
  const { coach, clips } = await start([g(1, 2)], { autoPlayMistakeAudio: true });
  coach.acceptTranscript('الحمد لله مالك العالمين');
  assert.equal(coach.confirmedMistake, null, 'a single evaluation is never believed');
  coach.acceptTranscript('الحمد لله مالك العالمين');
  assert.equal(coach.confirmedMistake.wordIndex, 2);
  assert.deepEqual(coach.believedMistakeIndices, new Set([2]));
  await coach.settled();
  assert.deepEqual(clips, ['1:2#2'], 'the reciter’s clip for رَبِّ was played');
  assert.equal(coach.recognizedText, 'الحمد لله', 'the transcript restarts from the locked words');
});

test('without alternatives the first reading is used unchanged', async () => {
  const { coach } = await start([g(102, 1)]);
  coach.acceptTranscript('الحاكم التكاثر');
  assert.equal(coach.evaluationResult.correctCount, 0);
});

test('reciting the next ayah moves on without a button', async () => {
  const ids = [g(112, 1), g(112, 2)];
  const { coach } = await start(ids);
  coach.acceptTranscript('قل هو الله الله الصمد');
  await coach.settled();
  assert.equal(coach.currentGlobalId, ids[1]);
});

test('a different learned ayah is reported, not scored', async () => {
  const { coach } = await start([g(1, 2), g(112, 1)]);
  await coach.selectAyah(g(1, 2), true);
  coach.acceptTranscript('قل هو');
  assert.equal(coach.detectedWrongAyahId, null, 'the next learned ayah is a move, not an advisory');
  await coach.settled();
  assert.equal(coach.currentGlobalId, g(112, 1));

  const second = await start([g(1, 2), g(112, 1), g(113, 1)]);
  await second.coach.selectAyah(g(1, 2), true);
  second.coach.acceptTranscript('قل أعوذ برب');
  assert.equal(second.coach.detectedWrongAyahId, g(113, 1));
  assert.match(second.coach.wrongAyahFeedback, /113:1/);
});
