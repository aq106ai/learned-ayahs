// core/recite2/wordFlow.js — one word at a time, reading one utterance at a time.
//
// The cases that matter here are the ones that killed the Android coach. It scored a cumulative
// transcript against the whole ayah, so a misheard opening stayed at the head of the transcript
// for the rest of the ayah and could align somewhere it did not belong. This flow never holds a
// transcript, so those cases have to stay fixed by construction, not by a later patch.
import test from 'node:test';
import assert from 'node:assert/strict';
import { createWordFlow, WordStatus } from '../../static/js/core/recite2/wordFlow.js';

/** Al-Ikhlas 112:3 — the ayah in the report. */
const ikhlas3 = () => createWordFlow([
  { text: 'لَمْ' }, { text: 'يَلِدْ' }, { text: 'وَلَمْ' }, { text: 'يُولَدْ' }, { text: '٣', isEnd: true },
]);

/** Al-Ikhlas 112:1 — the ayah whose first word the engine would not return. */
const ikhlas1 = () => createWordFlow([
  { text: 'قُلْ' }, { text: 'هُوَ' }, { text: 'ٱللَّهُ' }, { text: 'أَحَدٌ' }, { text: '١', isEnd: true },
]);

test('the end-of-ayah glyph is not a word to recite', () => {
  const flow = ikhlas3();
  assert.equal(flow.words.length, 4);
  assert.equal(flow.current.text, 'لَمْ');
});

test('a word said on its own moves the card on', () => {
  const flow = ikhlas3();
  for (const [i, said] of ['لم', 'يلد', 'ولم', 'يولد'].entries()) {
    assert.equal(flow.index, i);
    flow.offer(said);
  }
  assert.equal(flow.done, true);
  assert.equal(flow.correctCount, 4);
});

test('the whole ayah in one breath settles every word', () => {
  const flow = ikhlas3();
  flow.offer('لم يلد ولم يولد');
  assert.equal(flow.done, true);
  assert.equal(flow.correctCount, 4);
});

test('a misheard utterance cannot wedge the card, and is kept as evidence', () => {
  const flow = ikhlas3();
  // The utterance that broke the Android coach: it matches the ayah's LAST two words, and the
  // old reading scored it 2/4 and then never looked past it again.
  flow.offer('ولم تولد');
  assert.equal(flow.index, 0, 'still waiting for لَمْ');
  assert.equal(flow.correctCount, 0, 'nothing credited from a reading of the wrong words');
  assert.deepEqual(flow.outcomes[0].misheard, ['ولم', 'تولد']);

  flow.offer('لم يلد ولم يولد');
  assert.equal(flow.done, true, 'the recitation after it is read normally');
  assert.equal(flow.correctCount, 4);
});

test('what was offered for a word the engine never returns is recorded against that word', () => {
  const flow = ikhlas1();
  // Google's Arabic model answers والله for قُلْ هُوَ ٱللَّهُ and never returns قُلْ.
  flow.offer('والله');
  flow.offer('والله');
  assert.equal(flow.index, 0);
  assert.deepEqual(flow.outcomes[0].misheard, ['والله', 'والله']);
  assert.equal(flow.outcomes[0].status, WordStatus.PENDING);
});

test('the student moves past such a word themselves; the flow never does it for them', () => {
  const flow = ikhlas1();
  flow.offer('والله');
  flow.offer('والله');
  flow.offer('والله');
  assert.equal(flow.index, 0, 'no amount of mishearing advances the card on its own');

  flow.skipCurrent();
  assert.equal(flow.index, 1);
  assert.equal(flow.outcomes[0].status, WordStatus.SKIPPED);
  assert.notEqual(flow.outcomes[0].status, WordStatus.CORRECT, 'a skip is never a pass');

  flow.offer('هو');
  flow.offer('الله');
  flow.offer('أحد');
  assert.equal(flow.done, true);
  assert.equal(flow.correctCount, 3, 'the skipped word is not counted as recognised');
});

test('a partial credits a word but never blames one', () => {
  const flow = ikhlas3();
  flow.offer('لم', { final: false, id: 1 });
  assert.equal(flow.index, 1, 'the card moves while the word is still being said');

  flow.offer('كذا', { final: false, id: 2 });
  assert.deepEqual(flow.outcomes[1].misheard, [], 'a partial cannot record a miss');

  flow.offer('كذا', { final: true, id: 2 });
  assert.deepEqual(flow.outcomes[1].misheard, ['كذا']);
});

test('two utterances are not read as one, however they arrive', () => {
  // Without the utterance id the second partial looks like a longer reading of the first, so
  // لَمْ is offered again — this time against يَلِدْ — and scored as a miss on a correct
  // recitation. The id is what keeps them apart.
  const flow = ikhlas3();
  flow.offer('لم', { final: false, id: 1 });
  flow.offer('يلد', { final: false, id: 2 });
  assert.equal(flow.index, 2);
  assert.deepEqual(flow.outcomes[1].misheard, []);
});

test('a partial and the final it becomes credit the word once', () => {
  const flow = ikhlas3();
  flow.offer('لم', { final: false, id: 7 });
  flow.offer('لم يلد', { final: false, id: 7 });
  flow.offer('لم يلد', { final: true, id: 7 });
  assert.equal(flow.index, 2);
  assert.equal(flow.correctCount, 2);
});

test('a recogniser that revises its partial downward is re-read from the top', () => {
  const flow = ikhlas3();
  flow.offer('لم يلد', { final: false, id: 3 });
  assert.equal(flow.index, 2);
  flow.offer('لم', { final: true, id: 3 });
  assert.equal(flow.index, 2, 'a shorter revision does not take back what was already heard');
});

test('a word still being said is not a mistake', () => {
  const flow = createWordFlow([{ text: 'ٱلْعَـٰلَمِينَ' }]);
  flow.offer('العا', { final: false, id: 1 });
  assert.equal(flow.index, 0);
  assert.deepEqual(flow.outcomes[0].misheard, []);
  flow.offer('العالمين', { final: true, id: 1 });
  assert.equal(flow.done, true);
});

test('one written word said as two tokens still counts', () => {
  // The Uthmani text attaches the vocative; every engine says two words.
  const flow = createWordFlow([{ text: 'يَـٰٓأَيُّهَا' }, { text: 'ٱلنَّاسُ' }]);
  flow.offer('يا ايها');
  assert.equal(flow.index, 1);
  flow.offer('الناس');
  assert.equal(flow.done, true);
});

test('going back a word clears it so it can be said again', () => {
  const flow = ikhlas3();
  flow.offer('لم');
  flow.offer('يلد');
  assert.equal(flow.index, 2);

  flow.stepBack();
  assert.equal(flow.index, 1);
  assert.equal(flow.outcomes[1].status, WordStatus.PENDING);
  assert.equal(flow.outcomes[0].status, WordStatus.CORRECT, 'only the word stepped back to is cleared');

  flow.offer('يلد');
  assert.equal(flow.index, 2);
});

test('starting over forgets everything', () => {
  const flow = ikhlas3();
  flow.offer('لم يلد');
  flow.reset();
  assert.equal(flow.index, 0);
  assert.equal(flow.correctCount, 0);
  assert.deepEqual(flow.outcomes.map((o) => o.status), Array(4).fill(WordStatus.PENDING));
});

test('speech after the last word is ignored rather than scored', () => {
  const flow = ikhlas3();
  flow.offer('لم يلد ولم يولد');
  assert.equal(flow.done, true);
  flow.offer('قل هو الله احد');
  assert.equal(flow.correctCount, 4);
  assert.equal(flow.done, true);
});
