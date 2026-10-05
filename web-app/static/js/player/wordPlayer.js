// One-shot word-clip player (port of player/WordAudioPlayer.kt). Recite & review and tapping a
// word use this, so it never disturbs the main queue, mode or position.
//
// playAndAwait() resolves when the clip finishes *or fails*: a clip that cannot play must fail
// fast, never hold the microphone paused for the whole timeout.

export class WordPlayer {
  constructor(urlFor) {
    this.urlFor = urlFor; // (surah, ayah, wordIndex) => url
    this.audio = new Audio();
    this.audio.preload = 'auto';
  }

  play(surah, ayah, wordIndex) {
    this.audio.src = this.urlFor(surah, ayah, wordIndex);
    this.audio.currentTime = 0;
    return this.audio.play().catch(() => {});
  }

  playAndAwait(surah, ayah, wordIndex, timeoutMs = 8000) {
    return new Promise((resolve) => {
      const done = () => {
        clearTimeout(timer);
        this.audio.removeEventListener('ended', done);
        this.audio.removeEventListener('error', done);
        resolve();
      };
      const timer = setTimeout(done, timeoutMs);
      this.audio.addEventListener('ended', done);
      this.audio.addEventListener('error', done);
      this.audio.src = this.urlFor(surah, ayah, wordIndex);
      this.audio.currentTime = 0;
      this.audio.play().catch(done);
    });
  }

  stop() {
    this.audio.pause();
  }
}
