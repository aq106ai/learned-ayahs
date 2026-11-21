// The microphone, reporting one utterance at a time.
//
// `services/speech.js` (the old Recite) carries a running prefix and hands the coach the whole
// transcript every time. This does the opposite on purpose: each result the engine produces is
// reported on its own, with the index the engine gave it, and nothing is remembered between
// them. `wordFlow` has no transcript to go stale, and what reaches it is exactly what the
// recogniser said for one stretch of speech — which is also what makes the log worth reading.

const Recognition = globalThis.SpeechRecognition ?? globalThis.webkitSpeechRecognition;

export const listenerSupported = () => Boolean(Recognition);

export class Listener {
  /**
   * @param {object} handlers
   * @param {(utterance: {id: number, text: string, alternatives: string[], final: boolean}) => void} handlers.onUtterance
   * @param {(listening: boolean) => void} [handlers.onListening]
   * @param {(message: string, code?: string) => void} [handlers.onError]
   * @param {string} [handlers.lang]
   */
  constructor({ onUtterance, onListening = () => {}, onError = () => {}, lang = 'ar-SA' }) {
    this.onUtterance = onUtterance;
    this.onListening = onListening;
    this.onError = onError;
    this.lang = lang;
    this.want = false;
    this.rec = null;
    /** Rises for every utterance of the run, so the log can group partials with their final. */
    this.utterances = 0;
    /** Result indices already reported as final, so a restart cannot replay them. */
    this.sessionBase = 0;
  }

  get listening() {
    return this.want;
  }

  start() {
    if (!Recognition) {
      this.onError('This browser has no speech recognition. Chrome or Edge on desktop works.', 'unsupported');
      return;
    }
    this.want = true;
    this.open();
    this.onListening(true);
  }

  stop() {
    this.want = false;
    this.close();
    this.onListening(false);
  }

  setLanguage(lang) {
    if (lang === this.lang) return;
    this.lang = lang;
    if (this.want) {
      this.close();
      this.open();
    }
  }

  open() {
    this.close();
    const rec = new Recognition();
    rec.lang = this.lang;
    rec.continuous = true;
    rec.interimResults = true;
    // Several readings of the same audio: the first is not always the right one, and for a lab
    // the ones it did not pick are the interesting part.
    rec.maxAlternatives = 5;

    rec.onresult = (event) => {
      rec.seen = event.results.length;
      for (let i = event.resultIndex; i < event.results.length; i++) {
        const result = event.results[i];
        const readings = [];
        for (let k = 0; k < result.length; k++) readings.push(result[k].transcript.trim());
        const text = readings[0] ?? '';
        if (!text) continue;
        this.onUtterance({
          id: this.sessionBase + i,
          text,
          alternatives: readings.slice(1).filter(Boolean),
          final: Boolean(result.isFinal),
        });
      }
    };

    rec.onerror = (event) => {
      // Ordinary silence between phrases; the engine ends the session and we open another.
      if (event.error === 'no-speech' || event.error === 'aborted') return;
      this.onError(event.error === 'not-allowed'
        ? 'The browser blocked the microphone. Allow it for this page and start again.'
        : `Speech recognition error: ${event.error}`, event.error);
    };

    rec.onend = () => {
      if (this.rec !== rec) return;
      // Result indices restart with each session, so keep the ids rising across them.
      this.sessionBase += rec.seen ?? 0;
      this.rec = null;
      if (this.want) this.open();
    };

    this.rec = rec;
    try {
      rec.start();
    } catch (err) {
      this.onError(`Could not start listening: ${err?.message ?? err}`);
    }
  }

  close() {
    const rec = this.rec;
    this.rec = null;
    if (!rec) return;
    rec.onresult = null;
    rec.onerror = null;
    rec.onend = null;
    try { rec.abort(); } catch { /* already gone */ }
  }
}
