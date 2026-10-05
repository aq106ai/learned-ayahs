// The Recite & review coach — a framework-free port of player/RecitationViewModel.kt.
//
// You recite a learned ayah; the coach follows along and interrupts with the reciter's word clip
// only for a mistake that is certainly yours. Every gate below exists because its absence once
// produced false corrections on flawless recitation (see android-app-quran-player/CLAUDE.md):
//
//   - only a skip or a properly wrong word (beyond NEAR_MISS_TOLERANCE) is a mistake;
//   - the same wrong word must be named by MISTAKE_CONFIRMATIONS consecutive evaluations;
//   - at most MAX_CORRECTIONS_PER_WORD clips per word;
//   - an ayah is finished when every word has been correct at least once (cumulatively);
//   - nothing auto-advances on a timer; you leave an ayah by finishing it, by reciting the next
//     one, or with the Next button.
//
// The recap is per run (published at the end of the surah's learned ayahs, or on stop).
// acceptTranscript() is the single door transcripts come in by, which keeps all of this testable
// without a microphone.

import { globalToSurahAyah } from '../quran.js';
import { isIncompletePrefix, isWordMatch, levenshteinDistance, normalize, tokenize } from './normalizer.js';
import { endsWithPrefix } from './aligner.js';
import { openingTokensOf, stripLeadingIntrosFromTranscript } from './introFilter.js';
import { Status, evaluate, evaluateContinuing } from './evaluator.js';
import { matchingOtherAyah } from './wrongAyah.js';

export const Tuning = Object.freeze({
  MIN_STABLE_REPEATS: 2,
  MISTAKE_CONFIRMATIONS: 2,
  NEAR_MISS_TOLERANCE: 2,
  MISTAKE_COOLDOWN_MS: 1200,
  POST_CLIP_DELAY_MS: 400,
  END_OF_AYAH_PAUSE_MS: 300,
  MAX_CORRECTIONS_PER_WORD: 2,
  NEXT_AYAH_OPENING_TOKENS: 2,
});

const noop = () => {};
const contentOf = (words) => words.filter((w) => !w.isEnd);

function makeReport(outcomes, reachedEndOfSurah) {
  const ayahs = [...outcomes];
  const totalContentWords = ayahs.reduce((n, a) => n + a.totalContentWords, 0);
  const correctCount = ayahs.reduce((n, a) => n + a.correctCount, 0);
  const surahs = new Set(ayahs.map((a) => a.surah));
  return {
    ayahs,
    reachedEndOfSurah,
    totalContentWords,
    correctCount,
    mistakeCount: ayahs.reduce((n, a) => n + a.mistakenWordIndices.length, 0),
    perfectAyahs: ayahs.filter((a) => a.isPerfect).length,
    surah: surahs.size === 1 ? [...surahs][0] : null,
    accuracyPercent: totalContentWords > 0 ? Math.floor((correctCount * 100) / totalContentWords) : 0,
  };
}

export class RecitationCoach {
  /**
   * @param {object} env
   * @param {(surah: number, ayah: number) => Promise<Array<{text: string, isEnd: boolean}>>} env.loadWords
   * @param {() => {autoPlayMistakeAudio: boolean, autoAdvanceSuccess: boolean}} env.settings
   * @param {(surah: number, ayah: number, wordIndex: number) => Promise<void>} [env.playWordClip]
   * @param {object} [env.speech] startListening / stopListening / pauseForCorrectionClip /
   *   resumeAfterCorrectionClip / updateRecognizedText, all optional
   * @param {() => number} [env.now]
   * @param {(ms: number) => Promise<void>} [env.sleep]
   * @param {(state: object) => void} [env.onChange]
   */
  constructor(env) {
    this.loadWords = env.loadWords;
    this.settings = env.settings;
    this.playWordClip = env.playWordClip ?? (() => Promise.resolve());
    this.speech = {
      startListening: noop,
      stopListening: noop,
      pauseForCorrectionClip: noop,
      resumeAfterCorrectionClip: noop,
      updateRecognizedText: noop,
      ...(env.speech ?? {}),
    };
    this.now = env.now ?? (() => Date.now());
    this.sleep = env.sleep ?? ((ms) => new Promise((r) => setTimeout(r, ms)));
    this.onChange = env.onChange ?? noop;
    this.pending = new Set();

    this.learnedAyahs = [];
    this.learnedOpenings = [];
    this.currentGlobalId = 1;
    this.surah = 1;
    this.ayah = 1;
    this.currentWords = [];
    this.evaluationResult = null;
    this.confirmedMistake = null;
    this.believedMistakeIndices = new Set();
    this.wrongAyahFeedback = null;
    this.detectedWrongAyahId = null;
    this.isAutoPlayingMistake = false;
    this.sessionReport = null;
    this.recognizedText = '';
    this.started = false;
    this.runOutcomes = new Map();
    this.loadToken = 0;
    this.resetAyahSessionState();
  }

  /** Snapshot for the UI. */
  get state() {
    return {
      learnedAyahs: this.learnedAyahs,
      currentGlobalId: this.currentGlobalId,
      surah: this.surah,
      ayah: this.ayah,
      words: this.currentWords,
      evaluationResult: this.evaluationResult,
      confirmedMistake: this.confirmedMistake,
      believedMistakeIndices: this.believedMistakeIndices,
      wrongAyahFeedback: this.wrongAyahFeedback,
      detectedWrongAyahId: this.detectedWrongAyahId,
      isAutoPlayingMistake: this.isAutoPlayingMistake,
      sessionReport: this.sessionReport,
      recognizedText: this.recognizedText,
    };
  }

  emit() {
    this.onChange(this.state);
  }

  track(promise) {
    this.pending.add(promise);
    promise.finally(() => this.pending.delete(promise));
    return promise;
  }

  /** Resolves once every timer, clip and load the coach started has finished (for tests). */
  async settled() {
    while (this.pending.size) await Promise.allSettled([...this.pending]);
  }

  async setLearned(ids) {
    this.learnedAyahs = [...new Set(ids)].sort((a, b) => a - b);
    const openings = [];
    for (const id of this.learnedAyahs) {
      const [surah, ayah] = globalToSurahAyah(id);
      const words = await this.loadWords(surah, ayah);
      const tokens = contentOf(words).slice(0, 3).flatMap((w) => tokenize(w.text));
      openings.push({ globalId: id, tokens });
    }
    this.learnedOpenings = openings;
    this.emit();
  }

  resetAyahSessionState() {
    this.lastHandledMistakeWordIndex = -1;
    this.isAdvancingAyah = false;
    this.isAutoPlayingMistake = false;
    this.wrongAyahFeedback = null;
    this.detectedWrongAyahId = null;
    this.lastPartialLastToken = null;
    this.lastPartialRepeat = 0;
    this.lockedCorrectCount = 0;
    this.mistakeCooldownUntil = 0;
    this.pendingMistakeKey = null;
    this.pendingMistakeRepeats = 0;
    this.correctionsPerWord = new Map();
    this.wordsEverCorrect = [];
    this.lastEvaluationTranscript = '';
    this.sessionMistakeIndices = new Set();
    this.confirmedMistake = null;
    this.believedMistakeIndices = new Set();
  }

  /** Loads an ayah. Moving on inside a run keeps listening; only a deliberate stop closes the mic. */
  selectAyah(globalId, startListening = false) {
    const id = Math.min(Math.max(globalId, 1), 6236);
    if (this.currentWords.length) this.recordCurrentAyahOutcome();
    this.currentGlobalId = id;
    [this.surah, this.ayah] = globalToSurahAyah(id);
    this.resetAyahSessionState();
    if (!startListening) this.speech.stopListening();
    this.speech.updateRecognizedText('');
    this.recognizedText = '';
    this.currentWords = [];
    const token = ++this.loadToken;
    this.emit();
    return this.track(
      (async () => {
        const words = await this.loadWords(this.surah, this.ayah);
        if (token !== this.loadToken) return;
        this.currentWords = words;
        this.evaluationResult = evaluate(words, '', true);
        this.emit();
        if (startListening) this.startRecording();
      })(),
    );
  }

  startRecording() {
    this.started = true;
    if (this.isAutoPlayingMistake) return;
    this.isAdvancingAyah = false;
    this.wrongAyahFeedback = null;
    this.detectedWrongAyahId = null;
    if (this.sessionReport) {
      // Starting again after a recap begins a new run.
      this.sessionReport = null;
      this.runOutcomes.clear();
    }
    this.speech.startListening();
    this.emit();
  }

  stopRecording() {
    this.speech.stopListening();
    // Stopping half way through should still show what was recited up to that point.
    this.recordCurrentAyahOutcome();
    this.publishSessionReport(false);
    this.emit();
  }

  dismissSessionReport() {
    this.sessionReport = null;
    this.runOutcomes.clear();
    this.emit();
  }

  playWord(wordIndex) {
    return this.playWordClip(this.surah, this.ayah, wordIndex);
  }

  playWordOf(surah, ayah, wordIndex) {
    return this.playWordClip(surah, ayah, wordIndex);
  }

  /** Feeds a transcript in as though the recogniser had produced it — same door, same guards. */
  acceptTranscript(text, committed = true, alternatives = []) {
    if (!text || !text.trim() || this.isAutoPlayingMistake || this.isAdvancingAyah || !this.started) {
      return;
    }
    if (committed) {
      this.lastPartialLastToken = null;
      this.lastPartialRepeat = 0;
    }
    this.recognizedText = text;
    this.applyEvaluation(text, committed, alternatives);
    this.emit();
  }

  applyEvaluation(transcript, committed, alternatives) {
    const words = this.currentWords;
    if (!words.length) return;
    this.lastEvaluationTranscript = transcript;

    const tokens = tokenize(stripLeadingIntrosFromTranscript(transcript, openingTokensOf(words)));

    const last = tokens[tokens.length - 1] ?? null;
    let lastTokenStable;
    if (committed) {
      lastTokenStable = true;
    } else {
      if (last != null && last === this.lastPartialLastToken) {
        this.lastPartialRepeat++;
      } else {
        this.lastPartialLastToken = last;
        this.lastPartialRepeat = 0;
      }
      lastTokenStable = this.lastPartialRepeat >= Tuning.MIN_STABLE_REPEATS;
    }

    if (lastTokenStable && this.hasMovedOnToNextAyah(tokens)) {
      this.recordCurrentAyahOutcome();
      this.nextAyah(true);
      return;
    }

    if (lastTokenStable && this.lockedCorrectCount === 0) {
      const wrongAyahId = matchingOtherAyah(
        tokens,
        this.currentGlobalId,
        contentOf(words).slice(0, 3).flatMap((w) => tokenize(w.text)),
        this.learnedOpenings,
      );
      if (wrongAyahId != null) {
        const list = this.learnedAyahs;
        const index = list.indexOf(this.currentGlobalId);
        const nextId = index >= 0 && index < list.length - 1 ? list[index + 1] : null;
        if (wrongAyahId === nextId) {
          this.recordCurrentAyahOutcome();
          this.selectAyah(wrongAyahId, true);
          return;
        }
        const [s, a] = globalToSurahAyah(wrongAyahId);
        this.detectedWrongAyahId = wrongAyahId;
        this.wrongAyahFeedback = `That sounds like a different ayah (${s}:${a}). Stay on this one, or change ayah.`;
        return;
      }
    }

    const result = this.bestReading(words, [transcript, ...alternatives], lastTokenStable, committed);
    this.evaluationResult = result;
    if (result.correctCount > 0) {
      this.wrongAyahFeedback = null;
      this.detectedWrongAyahId = null;
    }

    let newLocked = 0;
    while (newLocked < result.evaluations.length && result.evaluations[newLocked].status === Status.CORRECT) {
      newLocked++;
    }
    if (newLocked > this.lockedCorrectCount) {
      this.lockedCorrectCount = newLocked;
      if (this.lastHandledMistakeWordIndex >= 0 && this.lockedCorrectCount > this.lastHandledMistakeWordIndex) {
        this.lastHandledMistakeWordIndex = -1;
      }
    }

    const contentCount = contentOf(words).length;
    if (this.wordsEverCorrect.length !== contentCount) this.wordsEverCorrect = new Array(contentCount).fill(false);
    for (const e of result.evaluations) {
      if (e.status === Status.CORRECT && e.wordIndex < contentCount) this.wordsEverCorrect[e.wordIndex] = true;
    }

    if (this.confirmedMistake) {
      const now = result.evaluations[this.confirmedMistake.wordIndex];
      if (now && now.status === Status.CORRECT) this.confirmedMistake = null;
    }

    if (contentCount > 0 && this.wordsEverCorrect.every(Boolean)) {
      // Bank the ayah and keep going; the recap comes at the end of the surah.
      this.recordCurrentAyahOutcome();
      if (this.settings().autoAdvanceSuccess && !this.isAdvancingAyah) {
        this.isAdvancingAyah = true;
        this.track(
          (async () => {
            await this.sleep(Tuning.END_OF_AYAH_PAUSE_MS);
            if (this.isAtEndOfSurahRun()) {
              this.speech.stopListening();
              this.publishSessionReport(true);
              this.isAdvancingAyah = false;
              this.emit();
            } else {
              await this.nextAyah(true);
            }
          })(),
        );
      }
      return;
    }

    const firstMistake = result.firstMistake ?? this.jumpedOverWord(result, committed);
    if (!firstMistake) {
      this.pendingMistakeKey = null;
      this.pendingMistakeRepeats = 0;
      return;
    }
    if (firstMistake.wordIndex < this.lockedCorrectCount) return;
    if (tokens.length <= this.lockedCorrectCount) return;
    if (!committed && !lastTokenStable) return;
    if (!committed && firstMistake.spokenWord == null) return;
    if (this.isStillMidWord(firstMistake.spokenWord, words, firstMistake.wordIndex)) return;
    if (!this.isConfidentMistake(firstMistake, words)) return;

    // Corroborate before believing: an artifact moves between passes, a real mistake does not.
    const key = `${firstMistake.wordIndex}:${firstMistake.spokenWord ?? ''}`;
    if (key === this.pendingMistakeKey) {
      this.pendingMistakeRepeats++;
    } else {
      this.pendingMistakeKey = key;
      this.pendingMistakeRepeats = 1;
    }
    if (this.pendingMistakeRepeats < Tuning.MISTAKE_CONFIRMATIONS) return;
    this.pendingMistakeKey = null;
    this.pendingMistakeRepeats = 0;
    this.sessionMistakeIndices.add(firstMistake.wordIndex);
    this.confirmedMistake = firstMistake;
    this.believedMistakeIndices = new Set(this.sessionMistakeIndices);

    if (this.now() < this.mistakeCooldownUntil) return;
    if (!this.settings().autoPlayMistakeAudio) return;
    if (this.isAutoPlayingMistake || this.isAdvancingAyah) return;
    const already = this.correctionsPerWord.get(firstMistake.wordIndex) ?? 0;
    if (already >= Tuning.MAX_CORRECTIONS_PER_WORD) return;
    this.correctionsPerWord.set(firstMistake.wordIndex, already + 1);

    this.lastHandledMistakeWordIndex = firstMistake.wordIndex;
    this.isAutoPlayingMistake = true;
    this.speech.pauseForCorrectionClip();
    const { surah, ayah } = this;
    this.track(
      (async () => {
        try {
          await this.playWordClip(surah, ayah, firstMistake.wordIndex);
        } catch {
          // A clip that cannot play must fail fast, never hold the microphone.
        }
        this.trimToLockedPrefix(words);
        this.mistakeCooldownUntil = this.now() + Tuning.MISTAKE_COOLDOWN_MS;
        await this.sleep(Tuning.POST_CLIP_DELAY_MS);
        this.speech.resumeAfterCorrectionClip();
        this.isAutoPlayingMistake = false;
        this.emit();
      })(),
    );
  }

  /** True when the student has audibly started the *next* learned ayah (reads the transcript end). */
  hasMovedOnToNextAyah(spokenTokens) {
    const list = this.learnedAyahs;
    const index = list.indexOf(this.currentGlobalId);
    if (index < 0 || index >= list.length - 1) return false;
    const nextId = list[index + 1];
    const nextOpening = this.learnedOpenings.find((o) => o.globalId === nextId)?.tokens ?? [];
    if (nextOpening.length < Tuning.NEXT_AYAH_OPENING_TOKENS) return false;
    const opening = nextOpening.slice(0, Tuning.NEXT_AYAH_OPENING_TOKENS);
    const currentTokens = contentOf(this.currentWords).flatMap((w) => tokenize(w.text));
    if (opening.every((t) => currentTokens.some((c) => isWordMatch(c, t)))) return false;
    return endsWithPrefix(nextOpening, Tuning.NEXT_AYAH_OPENING_TOKENS, spokenTokens);
  }

  /** Scores every reading the recogniser offered and keeps the one that settles the most words. */
  bestReading(words, transcripts, lastTokenStable, allowSkipMistakes) {
    let best = null;
    for (const candidate of transcripts) {
      if (!candidate || !candidate.trim()) continue;
      const r = evaluateContinuing(words, candidate, lastTokenStable, this.lockedCorrectCount, allowSkipMistakes);
      if (
        !best ||
        r.correctCount > best.correctCount ||
        (r.correctCount === best.correctCount && r.mistakeCount < best.mistakeCount)
      ) {
        best = r;
      }
    }
    return (
      best ??
      evaluateContinuing(words, transcripts[0] ?? '', lastTokenStable, this.lockedCorrectCount, allowSkipMistakes)
    );
  }

  /** A PENDING word with a CORRECT word after it is a skip (committed results only). */
  jumpedOverWord(result, committed) {
    if (!committed) return null;
    let lastCorrect = -1;
    result.evaluations.forEach((e, i) => {
      if (e.status === Status.CORRECT) lastCorrect = i;
    });
    if (lastCorrect <= 0) return null;
    const skipped = result.evaluations.slice(0, lastCorrect).find((e) => e.status === Status.PENDING);
    if (!skipped) return null;
    return {
      ...skipped,
      status: Status.MISTAKE,
      spokenWord: null,
      feedback: `Skipped word '${skipped.originalWord.text}'`,
    };
  }

  isConfidentMistake(mistake, words) {
    if (mistake.spokenWord == null) return true;
    const expected = contentOf(words)[mistake.wordIndex]?.text;
    if (expected == null) return false;
    const e = normalize(expected);
    const s = normalize(mistake.spokenWord);
    if (!e || !s) return false;
    return levenshteinDistance(e, s) > Tuning.NEAR_MISS_TOLERANCE;
  }

  isStillMidWord(spoken, words, wordIndex) {
    if (!spoken || !spoken.trim()) return false;
    const content = contentOf(words);
    for (let i = wordIndex; i <= wordIndex + 1; i++) {
      const candidate = content[i];
      if (!candidate) break;
      if (isIncompletePrefix(spoken, candidate.text)) return true;
    }
    return false;
  }

  recordCurrentAyahOutcome() {
    const contentWords = contentOf(this.currentWords);
    const content = this.evaluationResult?.totalContentWords ?? contentWords.length;
    if (!content || this.currentGlobalId <= 0) return;
    const mistaken = [...this.sessionMistakeIndices].filter((i) => i < content).sort((a, b) => a - b);
    let correct;
    if (this.wordsEverCorrect.length === content) {
      correct = this.wordsEverCorrect.filter((v, i) => v && !mistaken.includes(i)).length;
    } else {
      correct = (this.evaluationResult?.evaluations ?? []).filter(
        (e) => e.status === Status.CORRECT && !mistaken.includes(e.wordIndex),
      ).length;
    }
    correct = Math.min(Math.max(correct, 0), Math.max(content - mistaken.length, 0));
    this.runOutcomes.set(this.currentGlobalId, {
      globalId: this.currentGlobalId,
      surah: this.surah,
      ayah: this.ayah,
      correctCount: correct,
      totalContentWords: content,
      mistakenWordIndices: mistaken,
      isPerfect: mistaken.length === 0 && correct === content,
    });
  }

  isAtEndOfSurahRun() {
    const list = this.learnedAyahs;
    if (!list.length) return true;
    const index = list.indexOf(this.currentGlobalId);
    if (index < 0 || index === list.length - 1) return true;
    return globalToSurahAyah(list[index + 1])[0] !== this.surah;
  }

  publishSessionReport(reachedEndOfSurah) {
    if (!this.runOutcomes.size) return;
    this.sessionReport = makeReport(this.runOutcomes.values(), reachedEndOfSurah);
  }

  /** After a correction clip, the transcript restarts from the words already locked in. */
  trimToLockedPrefix(words) {
    const lockedText = contentOf(words)
      .slice(0, this.lockedCorrectCount)
      .flatMap((w) => tokenize(w.text))
      .join(' ');
    this.speech.updateRecognizedText(lockedText);
    this.recognizedText = lockedText;
    this.lastPartialLastToken = null;
    this.lastPartialRepeat = 0;
    this.evaluationResult = evaluateContinuing(words, lockedText, false, this.lockedCorrectCount, false);
  }

  retryCurrentAyah() {
    this.resetAyahSessionState();
    this.runOutcomes.delete(this.currentGlobalId);
    this.speech.stopListening();
    this.sessionReport = null;
    this.speech.updateRecognizedText('');
    this.recognizedText = '';
    this.evaluationResult = evaluate(this.currentWords, '', true);
    this.startRecording();
  }

  nextAyah(startListening = false) {
    const list = this.learnedAyahs;
    if (!list.length) return this.selectAyah((this.currentGlobalId % 6236) + 1, startListening);
    const index = list.indexOf(this.currentGlobalId);
    const nextId = index >= 0 ? list[(index + 1) % list.length] : list.find((id) => id > this.currentGlobalId) ?? list[0];
    return this.selectAyah(nextId, startListening);
  }

  previousAyah(startListening = false) {
    const list = this.learnedAyahs;
    if (!list.length) {
      return this.selectAyah(this.currentGlobalId > 1 ? this.currentGlobalId - 1 : 6236, startListening);
    }
    const index = list.indexOf(this.currentGlobalId);
    const prevId =
      index >= 0
        ? list[(index - 1 + list.length) % list.length]
        : [...list].reverse().find((id) => id < this.currentGlobalId) ?? list[list.length - 1];
    return this.selectAyah(prevId, startListening);
  }
}
