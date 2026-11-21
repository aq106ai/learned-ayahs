// One word at a time, rebuilt.
//
// The Android coach this replaces scored a *cumulative* transcript against the whole ayah on
// every evaluation, and that is what killed it. The transcript grew for the length of a run and
// was only ever trimmed back to a prefix the coach had already accepted, so an opening the
// recogniser got wrong stayed at its head for ever — and on Al-Ikhlas 112:3 that stale head
// ("ولم تولد") was itself a clean match for the ayah's *last two* words. It won every reading,
// the correct recitation behind it was never looked at, and the card could not move.
//
// So this reads **one utterance at a time** and keeps no transcript at all. There is no head to
// go stale, and no alignment of the whole ayah to get wrong: the only question ever asked is
// "is this the word we are waiting for?". Everything the student says while waiting is kept
// against that word, which is the evidence we never had — see `misheard`.
//
// What it deliberately does NOT do: decide on its own that a word was skipped. The archived
// coach did, and a word the recogniser simply cannot return looks exactly like a word the
// student skipped. Here the student is always one button away from moving on, and the flow
// never moves on for them.

import { tokenize, isIncompletePrefix } from '../recite/normalizer.js';
import { matchAt } from '../recite/aligner.js';

export const WordStatus = {
  PENDING: 'pending',
  CORRECT: 'correct',
  SKIPPED: 'skipped',
};

/**
 * @param {{text: string, isEnd?: boolean}[]} words one ayah, end glyph included or not
 */
export function createWordFlow(words = []) {
  const content = words.filter((w) => !w.isEnd);
  const texts = content.map((w) => w.text);

  let outcomes = [];
  let index = 0;
  /** Tokens of the utterance being spoken that have already been read. */
  let spoken = 0;
  /** Which utterance those tokens belong to, so a new one is never read as a continuation. */
  let utterance = null;

  function reset() {
    outcomes = content.map((w) => ({
      word: w.text,
      status: WordStatus.PENDING,
      heard: null,
      /** Everything offered while this word was the one being waited for. */
      misheard: [],
    }));
    index = 0;
    spoken = 0;
    utterance = null;
  }
  reset();

  /** A new utterance is starting: nothing of it has been read yet. */
  function endUtterance() {
    spoken = 0;
    utterance = null;
  }

  /**
   * Reads what the recogniser just heard.
   *
   * @param {string} text the latest utterance on its own — never a running transcript
   * @param {{final?: boolean, id?: number|string|null}} options `final` false is a partial: it
   *   may credit a word but never blames one, because the student may still be in the middle of
   *   saying it. `id` is the engine's utterance id — partials of one utterance repeat what they
   *   already said, so without it two partials of *different* utterances read as one and the
   *   first one's words are scored again against the words after them.
   * @returns {{type: 'correct'|'unmatched', index: number, heard: string, expected?: string}[]}
   */
  function offer(text, { final = true, id = null } = {}) {
    if (id !== null && id !== utterance) {
      utterance = id;
      spoken = 0;
    }
    const tokens = tokenize(text);
    // A recogniser revising its own partial can get shorter; re-read the utterance from the top.
    if (tokens.length < spoken) spoken = 0;
    const fresh = tokens.slice(spoken);
    const events = [];
    let t = 0;

    while (t < fresh.length && index < content.length) {
      const step = matchAt(texts, index, fresh, t);
      if (step) {
        const heard = fresh.slice(t, t + step.tokens).join(' ');
        for (let k = 0; k < step.words; k++) {
          outcomes[index + k] = { ...outcomes[index + k], status: WordStatus.CORRECT, heard };
        }
        events.push({ type: 'correct', index, words: step.words, heard });
        index += step.words;
        t += step.tokens;
        continue;
      }
      const last = t === fresh.length - 1;
      // Still saying it, or might be: not a miss, and not read yet either.
      if (last && (!final || isIncompletePrefix(fresh[t], texts[index]))) break;
      outcomes[index].misheard.push(fresh[t]);
      events.push({ type: 'unmatched', index, heard: fresh[t], expected: texts[index] });
      t++;
    }

    spoken += t;
    if (final) endUtterance();
    return events;
  }

  /** The student moves past a word the recogniser will not return. Never automatic. */
  function skipCurrent() {
    if (index >= content.length) return;
    outcomes[index] = { ...outcomes[index], status: WordStatus.SKIPPED };
    index += 1;
    endUtterance();
  }

  /** Back to the word before this one, to try it again. */
  function stepBack() {
    if (index === 0) return;
    index -= 1;
    outcomes[index] = { ...outcomes[index], status: WordStatus.PENDING, heard: null };
    endUtterance();
  }

  return {
    get words() { return content; },
    get index() { return index; },
    get outcomes() { return outcomes.map((o) => ({ ...o, misheard: [...o.misheard] })); },
    get current() { return content[index] ?? null; },
    get done() { return index >= content.length; },
    get correctCount() { return outcomes.filter((o) => o.status === WordStatus.CORRECT).length; },
    offer,
    endUtterance,
    skipCurrent,
    stepBack,
    reset,
  };
}
