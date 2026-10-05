// Speech recognition for Recite & review, via the browser's Web Speech API (Chrome, Edge,
// Safari). The web counterpart of RecitationSpeechManager.kt.
//
// Recognition services end their session at pauses, so this keeps listening by starting a new
// one, carrying the words already heard as a prefix. Every hypothesis the engine offers for the
// latest phrase is passed on, because the right reading is sometimes not the first one.

const Recognition = globalThis.SpeechRecognition ?? globalThis.webkitSpeechRecognition;

export const speechSupported = () => Boolean(Recognition);

export class SpeechSession {
  /**
   * @param {object} handlers
   * @param {(text: string, committed: boolean, alternatives: string[]) => void} handlers.onTranscript
   * @param {(listening: boolean) => void} [handlers.onListening]
   * @param {(message: string) => void} [handlers.onError]
   */
  constructor({ onTranscript, onListening = () => {}, onError = () => {}, lang = 'ar-SA' }) {
    this.onTranscript = onTranscript;
    this.onListening = onListening;
    this.onError = onError;
    this.lang = lang;
    this.want = false;
    this.paused = false;
    this.prefix = '';
    this.rec = null;
    this.restartTimer = null;
    this.failures = 0;
  }

  get listening() {
    return this.want && !this.paused;
  }

  start() {
    if (!Recognition) {
      this.onError('Speech recognition is not available in this browser. Try Chrome, Edge or Safari.');
      return;
    }
    this.want = true;
    this.paused = false;
    this.failures = 0;
    this.open();
    this.onListening(true);
  }

  stop() {
    this.want = false;
    this.paused = false;
    this.close();
    this.onListening(false);
  }

  /** While a correction clip plays the student is listening, not reciting. */
  pauseForCorrectionClip() {
    this.paused = true;
    this.close();
  }

  resumeAfterCorrectionClip() {
    this.paused = false;
    if (this.want) this.open();
  }

  /** Replaces what has been heard so far (after a correction, or a new ayah). */
  updateRecognizedText(text) {
    this.prefix = text ? `${text} ` : '';
    if (this.rec && this.want && !this.paused) {
      // Start a fresh session so stale results don't come back appended to the new prefix.
      this.close();
      this.open();
    }
  }

  open() {
    if (this.rec || !this.want || this.paused) return;
    const rec = new Recognition();
    rec.lang = this.lang;
    rec.continuous = true;
    rec.interimResults = true;
    rec.maxAlternatives = 5;
    const sessionPrefix = this.prefix;
    rec.onresult = (event) => {
      this.failures = 0;
      let finals = '';
      let interim = '';
      let lastFinal = null;
      for (let i = 0; i < event.results.length; i++) {
        const result = event.results[i];
        if (result.isFinal) {
          finals += `${result[0].transcript} `;
          lastFinal = result;
        } else {
          interim += `${result[0].transcript} `;
        }
      }
      const committed = interim.trim().length === 0;
      const text = `${sessionPrefix}${finals}${interim}`.replace(/\s+/g, ' ').trim();
      const alternatives = [];
      if (committed && lastFinal && lastFinal.length > 1) {
        const before = `${sessionPrefix}${finals}`.slice(0, -(lastFinal[0].transcript.length + 1));
        for (let a = 1; a < lastFinal.length; a++) {
          alternatives.push(`${before}${lastFinal[a].transcript}`.replace(/\s+/g, ' ').trim());
        }
      }
      if (committed) this.prefix = `${text} `;
      this.onTranscript(text, committed, alternatives);
    };
    rec.onerror = (event) => {
      if (event.error === 'no-speech' || event.error === 'aborted') return;
      if (event.error === 'not-allowed' || event.error === 'service-not-allowed') {
        this.want = false;
        this.onListening(false);
        this.onError('Microphone access was blocked. Allow it in your browser to use Recite & review.');
        return;
      }
      this.failures++;
      if (this.failures >= 3) {
        this.want = false;
        this.onListening(false);
        this.onError(
          event.error === 'network'
            ? 'Speech recognition needs an internet connection in this browser.'
            : `Speech recognition stopped (${event.error}).`,
        );
      }
    };
    rec.onend = () => {
      if (this.rec === rec) this.rec = null;
      // Sessions end at every pause; keep listening by opening the next one.
      if (this.want && !this.paused) {
        clearTimeout(this.restartTimer);
        this.restartTimer = setTimeout(() => this.open(), 150);
      }
    };
    this.rec = rec;
    try {
      rec.start();
    } catch {
      this.rec = null;
    }
  }

  close() {
    clearTimeout(this.restartTimer);
    const rec = this.rec;
    this.rec = null;
    if (rec) {
      rec.onresult = null;
      rec.onend = null;
      try {
        rec.abort();
      } catch {
        // already stopped
      }
    }
  }
}
