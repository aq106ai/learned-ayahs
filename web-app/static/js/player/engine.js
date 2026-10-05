// The playback engine — the web counterpart of the Android app's PlaybackService.
//
// Two independent axes, exactly as on Android:
//   PlaybackMode  REVISE (your learned ayahs) · FULL_SURAH (every ayah of the surah) ·
//                 WORD_BY_WORD (your learned ayahs, one word at a time)
//   RepeatMode    OFF · SURAH (the queue loops) · AYAH (the ayah — or, word by word, the word —
//                 repeats, with an optional pause before each repeat)
//
// Rules carried over from PlaybackService:
//   - A'udhu billah + Bismillah play before ayah 1 of a surah (no Bismillah for 1 and 9), when
//     the ayah starts fresh — not when resuming mid-ayah, not on a repeat, never word by word.
//   - Next always steps one ayah (one word in WORD_BY_WORD). During an intro, Next/Previous skip
//     to the ayah. In FULL_SURAH, running off either end moves into the neighbouring surah.
//     Elsewhere the queue wraps only under repeat SURAH.
//   - Previous goes to the previous ayah; it never "restarts the current one".
//   - Word highlighting uses the reciter's validated segments or nothing — never an estimate.
//   - When a word reader is open, every controller's Next/Previous steps words (stepWordHook).

import {
  NO_BISMILLAH_INTRO_SURAHS,
  PlaybackMode,
  QueueBuilder,
  RepeatMode,
  buildMaster,
  globalToSurahAyah,
  isValidGlobalId,
  playbackModeLabel,
  surahAyahToGlobal,
  trackTitle,
} from '../core/quran.js';
import { REMOTE, audioUrls, reciterFromKey } from '../core/reciters.js';
import { activeWordIndex } from '../core/wordSync.js';
import * as quran from '../services/quranData.js';

const INTRO_REPLAY_THRESHOLD_MS = 500;

export class PlayerEngine extends EventTarget {
  /**
   * @param {import('../state/store.js').Store} store
   * @param {{proxy: boolean}} options proxy: audio comes from the server's same-origin cache
   */
  constructor(store, { proxy }) {
    super();
    this.store = store;
    this.serverProxy = proxy;
    this.elements = [new Audio(), new Audio()];
    for (const el of this.elements) {
      el.preload = 'auto';
      el.addEventListener('ended', (e) => e.target === this.audio && this.onEnded());
      el.addEventListener('error', (e) => e.target === this.audio && this.onError());
      el.addEventListener('playing', (e) => e.target === this.audio && this.setPlaying(true));
      // Only a pause that sticks counts: not the one fired at the natural end of a clip, nor one
      // the engine immediately follows with play() (word boundaries, intro → ayah).
      el.addEventListener('pause', (e) => {
        const a = e.target;
        if (a === this.audio && !this.swapping && a.paused && !a.ended) this.setPlaying(false);
      });
      el.addEventListener('loadedmetadata', (e) => e.target === this.audio && this.emit('tick'));
      el.addEventListener('waiting', (e) => e.target === this.audio && this.emit('state'));
    }
    this.audio = this.elements[0];
    this.spare = this.elements[1];
    this.master = [];
    this.queue = [];
    this.index = 0;
    this.surah = 1;
    this.mode = PlaybackMode.REVISE;
    this.repeat = RepeatMode.OFF;
    this.playing = false;
    this.intro = null; // 'AUDHU' | 'BISMILLAH'
    this.gapTimer = null;
    this.gapPending = false;
    this.words = [];
    this.segments = null;
    this.wordIndex = 0;
    this.wordBoundaryMs = null;
    this.wordFileMode = false;
    this.stepWordHook = null;
    this.triedRemote = false;
    this.error = null;
    this.rafId = 0;
    this.positionMs = 0;
    this.setupMediaSession();

    store.on((what) => {
      if (what === 'learned' || what === 'all') this.onLearnedChanged();
      if (what === 'settings' || what === 'all') this.onSettingsChanged();
    });
  }

  // -- state -------------------------------------------------------------------------------

  emit(type) {
    this.dispatchEvent(new Event(type));
  }

  on(type, fn) {
    this.addEventListener(type, fn);
    return () => this.removeEventListener(type, fn);
  }

  get reciter() {
    return reciterFromKey(this.store.settings.reciter);
  }

  get urls() {
    return audioUrls({ proxy: this.serverProxy && !this.store.settings.streamDirect });
  }

  get track() {
    return this.queue[this.index] ?? null;
  }

  get contentWords() {
    return this.words.filter((w) => !w.isEnd);
  }

  get durationMs() {
    if (this.intro || (this.mode === PlaybackMode.WORD_BY_WORD)) return 0;
    const d = this.audio.duration;
    return Number.isFinite(d) ? d * 1000 : 0;
  }

  get introLabel() {
    if (this.intro === 'AUDHU') return "A'udhu Billah";
    if (this.intro === 'BISMILLAH') return 'Bismillah';
    return null;
  }

  get modeLabel() {
    return playbackModeLabel(this.mode, this.repeat);
  }

  /** The word to highlight: the playing word, by validated timings, or -1. */
  get activeWord() {
    if (this.mode === PlaybackMode.WORD_BY_WORD) return this.wordIndex;
    if (this.intro || !this.segments) return -1;
    if (!this.playing && this.positionMs <= 0) return -1;
    return activeWordIndex(this.positionMs, this.segments, this.contentWords.length);
  }

  setPlaying(playing) {
    if (this.playing === playing) return;
    this.playing = playing;
    if (playing) this.startTicker();
    this.updateMediaSession();
    this.emit('state');
  }

  // -- loading -----------------------------------------------------------------------------

  /** Restores mode, repeat and position from the store (the last ayah played). */
  restore() {
    const s = this.store.settings;
    this.mode = PlaybackMode[s.playbackMode] ?? PlaybackMode.REVISE;
    this.repeat = RepeatMode[s.repeatMode] ?? RepeatMode.OFF;
    this.master = buildMaster(this.store.learned);
    const last = this.store.progress.lastGlobalId;
    const start = isValidGlobalId(last) ? last : this.master[0]?.globalId ?? 1;
    this.surah = globalToSurahAyah(start)[0];
    this.rebuildQueue(start);
    this.loadIndex(this.index, { autoplay: false });
  }

  rebuildQueue(keepGlobalId) {
    this.queue = QueueBuilder.buildQueue(this.master, this.mode, this.repeat, this.surah);
    if (!this.queue.length && this.mode !== PlaybackMode.FULL_SURAH) {
      this.index = 0;
      return;
    }
    let i = this.queue.findIndex((t) => t.globalId === keepGlobalId);
    if (i < 0) {
      // Nearest ayah at or after the one we were on, else the first.
      i = this.queue.findIndex((t) => t.globalId > keepGlobalId);
      if (i < 0) i = 0;
    }
    this.index = i;
  }

  onLearnedChanged() {
    const current = this.track?.globalId;
    this.master = buildMaster(this.store.learned);
    if (this.mode === PlaybackMode.FULL_SURAH) {
      this.emit('state');
      return;
    }
    const before = this.track;
    this.rebuildQueue(current ?? this.master[0]?.globalId ?? 1);
    if (this.track?.globalId !== before?.globalId) {
      const wasPlaying = this.playing;
      if (this.track) this.loadIndex(this.index, { autoplay: wasPlaying });
      else this.stop();
    }
    this.emit('state');
  }

  onSettingsChanged() {
    const reciterKey = this.store.settings.reciter;
    const clips = this.store.settings.useWordClips;
    const direct = this.store.settings.streamDirect;
    if (this.lastReciterKey === undefined) {
      Object.assign(this, { lastReciterKey: reciterKey, lastClips: clips, lastDirect: direct });
      return;
    }
    const changed = reciterKey !== this.lastReciterKey || clips !== this.lastClips || direct !== this.lastDirect;
    Object.assign(this, { lastReciterKey: reciterKey, lastClips: clips, lastDirect: direct });
    if (changed && this.track) {
      // A new reciter means new audio and new timings; keep the ayah, restart it.
      this.loadIndex(this.index, { autoplay: this.playing, suppressIntro: true });
    }
  }

  ayahUrl(track) {
    return this.urls.ayah(this.reciter, track.surah, track.ayah);
  }

  setSource(el, url) {
    if (el.dataset.src !== url) {
      el.dataset.src = url;
      el.src = url;
    }
  }

  async loadAyahData(track) {
    await quran.loadText();
    if (this.store.settings.showWordTranslations || this.mode === PlaybackMode.WORD_BY_WORD) {
      quran.loadTranslations().then(() => {
        if (this.track === track) {
          this.words = quran.wordsSync(track.surah, track.ayah);
          this.emit('track');
        }
      }, () => {});
    }
    const reciter = this.reciter;
    this.words = quran.wordsSync(track.surah, track.ayah);
    this.segments = quran.segmentsSync(reciter, track.surah, track.ayah);
    if (!this.segments) {
      quran.loadTimings(reciter).then(() => {
        if (this.track === track && this.reciter === reciter) {
          this.segments = quran.segmentsSync(reciter, track.surah, track.ayah);
          this.emit('track');
        }
      }, () => {});
    }
  }

  /**
   * Makes queue[index] current. `startWord` (word mode) is 0 or Infinity for "last word".
   */
  loadIndex(index, { autoplay = false, startMs = 0, startWord = 0, suppressIntro = false } = {}) {
    this.cancelGap();
    this.intro = null;
    this.error = null;
    this.triedRemote = false;
    if (!this.queue.length) {
      this.words = [];
      this.segments = null;
      this.emit('track');
      this.emit('state');
      return;
    }
    this.index = Math.min(Math.max(index, 0), this.queue.length - 1);
    const track = this.track;
    this.surah = track.surah;
    this.store.setLastGlobalId(track.globalId);
    this.words = quran.wordsSync(track.surah, track.ayah);
    this.segments = quran.segmentsSync(this.reciter, track.surah, track.ayah);
    this.positionMs = startMs;
    const ready = this.loadAyahData(track);

    if (this.mode === PlaybackMode.WORD_BY_WORD) {
      ready.then(() => {
        if (this.track !== track) return;
        const count = this.contentWords.length;
        const word = startWord === Infinity ? Math.max(count - 1, 0) : Math.min(startWord, Math.max(count - 1, 0));
        this.loadWord(word, { autoplay });
      });
    } else {
      const url = this.ayahUrl(track);
      if (this.spare.dataset.src === url && this.audio.dataset.src !== url) {
        // Gapless: the next ayah is already buffered in the spare element.
        this.swapping = true;
        this.audio.pause();
        [this.audio, this.spare] = [this.spare, this.audio];
        this.swapping = false;
      } else {
        this.setSource(this.audio, url);
      }
      try {
        this.audio.currentTime = startMs / 1000;
      } catch {
        // Not seekable yet; it starts from 0, which is what startMs usually is.
      }
      if (autoplay) {
        if (!suppressIntro && this.needsIntro(track) && startMs < INTRO_REPLAY_THRESHOLD_MS) this.startIntro();
        else this.startAudio();
      } else {
        this.audio.pause();
      }
      this.preloadNext();
    }
    this.updateMediaSession();
    this.emit('track');
    this.emit('state');
  }

  preloadNext() {
    if (this.mode === PlaybackMode.WORD_BY_WORD || this.repeat === RepeatMode.AYAH) return;
    let next = this.queue[this.index + 1];
    if (!next && this.repeat === RepeatMode.SURAH) next = this.queue[0];
    if (!next || next.ayah === 1) return; // ayah 1 starts with an intro anyway
    this.setSource(this.spare, this.ayahUrl(next));
    this.spare.load();
  }

  needsIntro(track) {
    return this.mode !== PlaybackMode.WORD_BY_WORD && track?.ayah === 1;
  }

  startAudio() {
    this.wantPlay = true;
    // A clip that failed to load (e.g. while offline) is fetched again on the next attempt.
    if (this.audio.error) this.audio.load();
    const p = this.audio.play();
    if (p) p.catch((err) => this.onPlayRejected(err));
  }

  onPlayRejected(err) {
    if (err?.name === 'NotAllowedError') {
      this.setPlaying(false);
      this.emit('state');
    }
  }

  // -- intro -------------------------------------------------------------------------------

  startIntro() {
    this.intro = 'AUDHU';
    this.setSource(this.audio, this.urls.audhu());
    this.audio.currentTime = 0;
    this.startAudio();
    this.updateMediaSession();
    this.emit('state');
  }

  advanceIntro() {
    if (this.intro === 'AUDHU' && !NO_BISMILLAH_INTRO_SURAHS.has(this.track?.surah)) {
      this.intro = 'BISMILLAH';
      this.setSource(this.audio, this.urls.bismillah());
      this.audio.currentTime = 0;
      this.startAudio();
      this.emit('state');
      return;
    }
    this.finishIntro();
  }

  finishIntro() {
    this.intro = null;
    this.setSource(this.audio, this.ayahUrl(this.track));
    this.audio.currentTime = 0;
    this.startAudio();
    this.preloadNext();
    this.updateMediaSession();
    this.emit('state');
  }

  // -- transport ---------------------------------------------------------------------------

  play() {
    if (!this.track) return;
    this.error = null;
    this.triedRemote = false;
    const resumingFromGap = this.gapPending;
    this.cancelGap();
    if (this.intro) {
      this.startAudio();
      return;
    }
    if (this.mode === PlaybackMode.WORD_BY_WORD) {
      if (this.audio.ended || this.wordBoundaryReached) this.loadWord(this.wordIndex, { autoplay: true });
      else this.startAudio();
      return;
    }
    if (this.audio.ended) this.audio.currentTime = 0;
    const atStart = this.audio.currentTime * 1000 < INTRO_REPLAY_THRESHOLD_MS;
    if (this.needsIntro(this.track) && atStart && !resumingFromGap) {
      this.startIntro();
      return;
    }
    this.startAudio();
  }

  pause() {
    this.wantPlay = false;
    this.cancelGap();
    this.audio.pause();
    this.setPlaying(false);
    this.emit('state');
  }

  toggle() {
    if (this.playing || this.gapPending) this.pause();
    else this.play();
  }

  stop() {
    this.wantPlay = false;
    this.cancelGap();
    this.audio.pause();
    this.intro = null;
    this.setPlaying(false);
    this.emit('state');
  }

  get isActive() {
    return this.playing || this.gapPending;
  }

  next() {
    if (this.intro) return this.finishIntro();
    if (this.mode === PlaybackMode.WORD_BY_WORD) return this.stepWord(1);
    if (this.stepWordHook?.(1)) return undefined;
    return this.stepAyah(1);
  }

  prev() {
    if (this.intro) return this.finishIntro();
    if (this.mode === PlaybackMode.WORD_BY_WORD) return this.stepWord(-1);
    if (this.stepWordHook?.(-1)) return undefined;
    return this.stepAyah(-1);
  }

  /** One ayah forward/back, whatever the mode (word mode lands on the first/last word). */
  stepAyah(delta) {
    if (!this.queue.length) return;
    const resume = this.isActive;
    const last = this.queue.length - 1;
    const target = this.index + delta;
    if (this.mode === PlaybackMode.FULL_SURAH && this.repeat !== RepeatMode.SURAH && (target > last || target < 0)) {
      this.surah = delta > 0 ? QueueBuilder.nextSurahAll(this.surah) : QueueBuilder.prevSurahAll(this.surah);
      this.queue = QueueBuilder.surahAllAyahs(this.surah);
      this.loadIndex(delta > 0 ? 0 : this.queue.length - 1, { autoplay: resume });
      return;
    }
    if (target >= 0 && target <= last) {
      this.loadIndex(target, { autoplay: resume, startWord: delta > 0 ? 0 : Infinity });
    } else if (this.repeat === RepeatMode.SURAH) {
      this.loadIndex((target + this.queue.length) % this.queue.length, { autoplay: resume });
    } else if (resume && !this.playing) {
      this.play();
    }
  }

  /** Word mode's "edge pages" and Android's skipAyah. */
  skipAyah(forward) {
    this.stepAyah(forward ? 1 : -1);
  }

  seek(ms) {
    if (this.intro || this.mode === PlaybackMode.WORD_BY_WORD) return;
    this.cancelGap();
    try {
      this.audio.currentTime = Math.max(ms, 0) / 1000;
    } catch {
      return;
    }
    this.positionMs = Math.max(ms, 0);
    this.emit('tick');
  }

  /** Jumps to an ayah of the current queue (or loads its surah in FULL_SURAH) and plays. */
  playGlobal(globalId, { autoplay = true } = {}) {
    let i = this.queue.findIndex((t) => t.globalId === globalId);
    if (i < 0) {
      const [surah] = globalToSurahAyah(globalId);
      if (this.mode === PlaybackMode.FULL_SURAH || !this.master.some((t) => t.globalId === globalId)) {
        this.setModeInternal(PlaybackMode.FULL_SURAH);
      }
      this.surah = surah;
      this.queue = QueueBuilder.buildQueue(this.master, this.mode, this.repeat, surah);
      i = Math.max(this.queue.findIndex((t) => t.globalId === globalId), 0);
    }
    this.loadIndex(i, { autoplay });
  }

  /** The surah screen's "listen from here": full-surah playback starting at that ayah. */
  playSurahFrom(surah, ayah = 1) {
    this.setModeInternal(PlaybackMode.FULL_SURAH);
    this.surah = surah;
    this.queue = QueueBuilder.surahAllAyahs(surah);
    this.loadIndex(ayah - 1, { autoplay: true });
  }

  jumpToSurah(surah) {
    const wasPlaying = this.isActive;
    this.surah = surah;
    if (this.mode === PlaybackMode.FULL_SURAH || this.repeat === RepeatMode.SURAH) {
      this.queue = QueueBuilder.buildQueue(this.master, this.mode, this.repeat, surah);
      this.loadIndex(0, { autoplay: wasPlaying });
    } else {
      const i = this.queue.findIndex((t) => t.surah === surah);
      if (i >= 0) this.loadIndex(i, { autoplay: wasPlaying });
    }
  }

  setModeInternal(mode) {
    this.mode = mode;
    this.store.setSetting('playbackMode', mode);
  }

  setMode(mode) {
    if (mode === this.mode) return;
    const current = this.track?.globalId ?? this.master[0]?.globalId ?? 1;
    const wasPlaying = this.isActive;
    this.setModeInternal(mode);
    this.surah = globalToSurahAyah(current)[0];
    this.rebuildQueue(current);
    this.loadIndex(this.index, { autoplay: wasPlaying, suppressIntro: true });
  }

  setRepeat(repeat) {
    if (repeat === this.repeat) return;
    const current = this.track?.globalId ?? this.master[0]?.globalId ?? 1;
    this.repeat = repeat;
    this.store.setSetting('repeatMode', repeat);
    const before = this.track;
    this.rebuildQueue(current);
    if (this.track?.globalId !== before?.globalId) this.loadIndex(this.index, { autoplay: this.isActive });
    this.preloadNext();
    this.updateMediaSession();
    this.emit('state');
  }

  setRevisionDelay(seconds) {
    this.store.setSetting('revisionDelaySeconds', seconds);
    this.emit('state');
  }

  // -- revision gap --------------------------------------------------------------------------

  parkThen(action) {
    const delay = this.store.settings.revisionDelaySeconds;
    if (delay <= 0) {
      action();
      return;
    }
    this.gapPending = true;
    this.emit('state');
    this.gapTimer = setTimeout(() => {
      this.gapPending = false;
      this.gapTimer = null;
      action();
    }, delay * 1000);
  }

  cancelGap() {
    if (this.gapTimer) clearTimeout(this.gapTimer);
    this.gapTimer = null;
    if (this.gapPending) {
      this.gapPending = false;
      this.emit('state');
    }
  }

  // -- ending / errors -----------------------------------------------------------------------

  onEnded() {
    if (this.intro) {
      this.advanceIntro();
      return;
    }
    if (this.mode === PlaybackMode.WORD_BY_WORD) {
      this.onWordEnded();
      return;
    }
    if (this.repeat === RepeatMode.AYAH) {
      this.parkThen(() => {
        this.audio.currentTime = 0;
        this.startAudio();
      });
      return;
    }
    const last = this.queue.length - 1;
    if (this.index < last) this.loadIndex(this.index + 1, { autoplay: true });
    else if (this.repeat === RepeatMode.SURAH) this.loadIndex(0, { autoplay: true });
    else this.setPlaying(false); // end of the queue
  }

  onError() {
    if (this.intro) {
      // An intro clip that cannot load is skipped, never allowed to block the ayah.
      this.finishIntro();
      return;
    }
    // A preload that failed is retried when the ayah is actually played; nothing to report yet.
    if (!this.wantPlay) return;
    const src = this.audio.dataset.src ?? '';
    const remote = src.startsWith('audio/everyayah/')
      ? `${REMOTE.everyayah}/${src.slice('audio/everyayah/'.length)}`
      : src.startsWith('audio/wbw/')
        ? `${REMOTE.wordClips}/${src.slice('audio/wbw/'.length)}`
        : null;
    if (remote && !this.triedRemote) {
      // The server's cache could not fetch it; the browser may still reach the source directly.
      this.triedRemote = true;
      this.setSource(this.audio, remote);
      this.startAudio();
      return;
    }
    this.error = this.track ? `Couldn't load audio for ${this.track.surah}:${this.track.ayah}.` : "Couldn't load audio.";
    this.wantPlay = false;
    this.setPlaying(false);
    this.emit('error');
    this.emit('state');
  }

  /** After a load error: try the current ayah (or word) again. */
  retry() {
    if (!this.track) return;
    this.error = null;
    this.triedRemote = false;
    if (this.mode === PlaybackMode.WORD_BY_WORD) this.loadWord(this.wordIndex, { autoplay: true });
    else this.loadIndex(this.index, { autoplay: true, suppressIntro: true });
  }

  // -- word by word --------------------------------------------------------------------------

  usesWordClips() {
    return this.store.settings.useWordClips || !this.segments || !this.segments.length;
  }

  loadWord(i, { autoplay }) {
    const track = this.track;
    const count = this.contentWords.length;
    if (!track || !count) {
      this.emit('state');
      return;
    }
    this.cancelGap();
    this.wordIndex = Math.min(Math.max(i, 0), count - 1);
    this.wordBoundaryReached = false;
    this.wordFileMode = this.usesWordClips();
    if (this.wordFileMode) {
      this.wordBoundaryMs = null;
      this.setSource(this.audio, this.urls.word(track.surah, track.ayah, this.wordIndex));
      this.audio.currentTime = 0;
    } else {
      // Cut on the *next word's onset*: QUL's segment ends are often too early.
      const seg = this.segments;
      this.setSource(this.audio, this.ayahUrl(track));
      this.wordBoundaryMs = seg[this.wordIndex + 1]?.[0] ?? null;
      const startAt = seg[this.wordIndex][0] / 1000;
      const seekTo = () => {
        try {
          this.audio.currentTime = startAt;
        } catch {
          // ignored: retried on loadedmetadata
        }
      };
      if (this.audio.readyState >= 1) seekTo();
      else this.audio.addEventListener('loadedmetadata', seekTo, { once: true });
    }
    if (autoplay) this.startAudio();
    else this.audio.pause();
    this.updateMediaSession();
    this.emit('word');
    this.emit('state');
  }

  /** Word ended (clip finished, or the seek boundary was reached). */
  onWordEnded() {
    if (this.repeat === RepeatMode.AYAH) {
      this.parkThen(() => this.loadWord(this.wordIndex, { autoplay: true }));
      return;
    }
    this.stepWord(1, { force: true });
  }

  stepWord(delta, { force = false } = {}) {
    const resume = force || this.isActive;
    const count = this.contentWords.length;
    const target = this.wordIndex + delta;
    if (count && target >= 0 && target < count) {
      this.loadWord(target, { autoplay: resume });
      return;
    }
    // Running off either end of the ayah loads the neighbouring ayah's words.
    const last = this.queue.length - 1;
    let next = this.index + (delta > 0 ? 1 : -1);
    if (next < 0 || next > last) {
      if (this.repeat !== RepeatMode.SURAH) {
        if (force) this.setPlaying(false);
        return;
      }
      next = (next + this.queue.length) % this.queue.length;
    }
    this.loadIndex(next, { autoplay: resume, startWord: delta > 0 ? 0 : Infinity });
  }

  /** Word overlay in whole-ayah modes: seeks the ayah to a word when the setting allows it. */
  seekToWord(i) {
    if (!this.store.settings.seekInsideAyahAudio || !this.segments?.[i]) return false;
    this.seek(this.segments[i][0]);
    return true;
  }

  // -- ticker ------------------------------------------------------------------------------

  startTicker() {
    if (this.rafId) return;
    const tick = () => {
      this.rafId = 0;
      if (!this.playing) return;
      this.positionMs = this.audio.currentTime * 1000;
      if (
        this.mode === PlaybackMode.WORD_BY_WORD &&
        !this.wordFileMode &&
        this.wordBoundaryMs != null &&
        this.positionMs >= this.wordBoundaryMs
      ) {
        this.wordBoundaryReached = true;
        this.audio.pause();
        this.onWordEnded();
      }
      this.emit('tick');
      this.rafId = requestAnimationFrame(tick);
    };
    this.rafId = requestAnimationFrame(tick);
    // Background tabs throttle rAF; timeupdate keeps the seek boundary honest there.
    if (!this.timeupdateBound) {
      this.timeupdateBound = true;
      for (const el of this.elements) {
        el.addEventListener('timeupdate', () => {
          if (el !== this.audio || !this.playing || document.visibilityState === 'visible') return;
          this.positionMs = el.currentTime * 1000;
          if (this.mode === PlaybackMode.WORD_BY_WORD && !this.wordFileMode && this.wordBoundaryMs != null && this.positionMs >= this.wordBoundaryMs) {
            this.wordBoundaryReached = true;
            el.pause();
            this.onWordEnded();
          }
        });
      }
    }
  }

  // -- media session (lock screen, headset, car) ---------------------------------------------

  setupMediaSession() {
    if (!('mediaSession' in navigator)) return;
    const ms = navigator.mediaSession;
    const handlers = {
      play: () => this.play(),
      pause: () => this.pause(),
      stop: () => this.stop(),
      nexttrack: () => this.next(),
      previoustrack: () => this.prev(),
      seekto: (d) => this.seek(d.seekTime * 1000),
    };
    for (const [action, fn] of Object.entries(handlers)) {
      try {
        ms.setActionHandler(action, fn);
      } catch {
        // Unsupported action in this browser.
      }
    }
  }

  updateMediaSession() {
    if (!('mediaSession' in navigator) || typeof MediaMetadata === 'undefined') return;
    const t = this.track;
    if (!t) return;
    let title = trackTitle(t.surah, t.ayah);
    if (this.introLabel) title = `${this.introLabel} · ${title}`;
    if (this.mode === PlaybackMode.WORD_BY_WORD) title += ` · word ${this.wordIndex + 1}`;
    navigator.mediaSession.metadata = new MediaMetadata({
      title,
      artist: this.reciter.displayName,
      album: this.modeLabel,
      artwork: [{ src: new URL('icons/icon.svg', document.baseURI).href, sizes: 'any', type: 'image/svg+xml' }],
    });
    navigator.mediaSession.playbackState = this.playing ? 'playing' : 'paused';
  }
}

export { surahAyahToGlobal };
