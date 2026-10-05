// The word-by-word reader: one word at a time with its meaning (port of ui/WordByWordView.kt).
//
// Reading direction is right-to-left, like a mushaf: swiping right (or tapping) moves to the
// next word. Running past either end of the ayah moves to the neighbouring ayah.
//
// Two ways it is used:
//   - PlaybackMode.WORD_BY_WORD: the engine plays words; the reader follows engine.wordIndex.
//   - The overlay in the other modes: the reader keeps its own position, follows playback
//     while audio plays, and claims Next/Previous from every controller (stepWordHook).

import { h } from '../lib/dom.js';
import { PlaybackMode } from '../core/quran.js';

export function wordReader({ engine, store }) {
  const wordFileMode = () => engine.mode === PlaybackMode.WORD_BY_WORD;
  let local = 0; // overlay position
  let followUntil = 0;

  const big = h('div', { class: 'big', lang: 'ar', 'data-testid': 'word-current' });
  const meaning = h('div', { class: 'meaning', 'data-testid': 'word-meaning' });
  const counter = h('div', { class: 'counter', 'data-testid': 'word-counter' });
  const dots = h('div', { class: 'word-dots', 'aria-hidden': 'true' });
  const el = h('div', { class: 'word-reader', 'data-testid': 'word-reader' }, big, meaning, counter, dots);

  const words = () => engine.contentWords;
  const position = () => (wordFileMode() ? engine.wordIndex : local);

  function render() {
    const ws = words();
    if (!ws.length) {
      big.textContent = '';
      meaning.textContent = '';
      counter.textContent = engine.track ? 'Loading ayah text…' : '';
      dots.replaceChildren();
      return;
    }
    const i = Math.min(position(), ws.length - 1);
    big.textContent = ws[i].text;
    meaning.textContent = store.settings.showWordTranslations ? ws[i].translation ?? '' : '';
    counter.textContent = `${i + 1} / ${ws.length}   ·   swipe or tap for the next word`;
    if (dots.children.length !== ws.length) {
      dots.replaceChildren(...ws.map(() => h('span')));
    }
    [...dots.children].forEach((d, k) => d.classList.toggle('on', k === i));
  }

  /** Steps one word; returns true when the step was taken inside this ayah. */
  function step(delta) {
    if (wordFileMode()) {
      engine.stepWord(delta);
      return true;
    }
    const ws = words();
    const target = local + delta;
    followUntil = performance.now() + 1500;
    if (ws.length && target >= 0 && target < ws.length) {
      local = target;
      engine.seekToWord(local);
      render();
      return true;
    }
    // Past the edge: the neighbouring ayah, landing on its first or last word.
    pendingEdge = delta > 0 ? 'next' : 'prev';
    engine.stepAyah(delta > 0 ? 1 : -1);
    return true;
  }

  let pendingEdge = null;

  // Follow playback (overlay only, and not straight after the user moved the reader).
  const offTick = engine.on('tick', () => {
    if (wordFileMode() || !engine.playing || performance.now() < followUntil) return;
    const active = engine.activeWord;
    if (active >= 0 && active !== local) {
      local = active;
      render();
    }
  });
  const offTrack = engine.on('track', () => {
    if (!wordFileMode()) local = pendingEdge === 'prev' ? Math.max(words().length - 1, 0) : 0;
    pendingEdge = null;
    render();
  });
  const offWord = engine.on('word', render);
  const offState = engine.on('state', render);

  // Every controller's Next/Previous steps words while the overlay is open.
  if (!wordFileMode()) engine.stepWordHook = (delta) => step(delta);

  // Swipe (RTL) and tap.
  let startX = null;
  el.addEventListener('pointerdown', (e) => {
    startX = e.clientX;
  });
  el.addEventListener('pointerup', (e) => {
    if (startX == null) return;
    const dx = e.clientX - startX;
    startX = null;
    if (Math.abs(dx) > 50) {
      if (!store.settings.swipeToNavigate) return;
      step(dx > 0 ? 1 : -1); // a rightward swipe advances, the way an Arabic book turns
    } else if (store.settings.tapToAdvance) {
      step(1);
    } else if (wordFileMode()) {
      engine.loadWord(engine.wordIndex, { autoplay: true });
    }
  });

  render();
  return {
    el,
    step,
    destroy() {
      offTick();
      offTrack();
      offWord();
      offState();
      if (engine.stepWordHook) engine.stepWordHook = null;
    },
  };
}
