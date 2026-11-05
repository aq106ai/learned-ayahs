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
  let drag = null;

  const big = h('div', { class: 'big', lang: 'ar', 'data-testid': 'word-current' });
  const meaning = h('div', { class: 'meaning', 'data-testid': 'word-meaning' });
  const counter = h('div', { class: 'counter', 'data-testid': 'word-counter' });
  const dots = h('div', { class: 'word-dots', 'aria-hidden': 'true' });
  const stage = h('div', { class: 'word-stage' }, big, meaning, counter, dots);
  const el = h('div', { class: 'word-reader', 'data-testid': 'word-reader' }, stage);

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
    if (drag || wordFileMode() || !engine.playing || performance.now() < followUntil) return;
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

  // The word follows the finger. Past half the width it settles on the next word (a rightward
  // swipe advances, the way an Arabic book turns); short of that it snaps back. A drag is not a tap.
  let settleToken = 0;
  function snapHome() {
    const token = ++settleToken;
    stage.classList.add('settle');
    stage.style.transform = 'translateX(0px)';
    const finish = () => {
      stage.removeEventListener('transitionend', finish);
      if (token !== settleToken) return;
      settleToken++;
      stage.classList.remove('settle');
      stage.style.transform = '';
    };
    stage.addEventListener('transitionend', finish);
    setTimeout(finish, 280);
  }
  function endDrag(e) {
    if (!drag || e.pointerId !== drag.id) return;
    const dx = e.clientX - drag.x;
    const moved = drag.moved || Math.abs(dx) > 10 || Math.abs(e.clientY - drag.y) > 10;
    drag = null;
    followUntil = performance.now() + 1500;
    if (el.hasPointerCapture?.(e.pointerId)) el.releasePointerCapture(e.pointerId);
    const width = el.clientWidth || 1;
    const swipe = store.settings.swipeToNavigate;
    if (swipe && Math.abs(dx) > width * 0.5) {
      stage.classList.remove('settle');
      stage.style.transform = '';
      step(dx > 0 ? 1 : -1);
      return;
    }
    if (moved) {
      if (stage.style.transform) snapHome();
      return;
    }
    stage.style.transform = '';
    if (store.settings.tapToAdvance) step(1);
    else if (wordFileMode()) engine.loadWord(engine.wordIndex, { autoplay: true });
  }
  el.addEventListener('pointerdown', (e) => {
    if (e.button !== 0 || drag) return;
    settleToken++;
    drag = { x: e.clientX, y: e.clientY, id: e.pointerId, moved: false };
    stage.style.transform = '';
    stage.classList.remove('settle');
    if (store.settings.swipeToNavigate) el.setPointerCapture?.(e.pointerId);
  });
  el.addEventListener('pointermove', (e) => {
    if (!drag || e.pointerId !== drag.id) return;
    const dx = e.clientX - drag.x;
    drag.moved ||= Math.abs(dx) > 10 || Math.abs(e.clientY - drag.y) > 10;
    if (store.settings.swipeToNavigate && drag.moved) stage.style.transform = `translateX(${dx}px)`;
  });
  el.addEventListener('pointerup', endDrag);
  function cancelDrag(e) {
    if (!drag || e.pointerId !== drag.id) return;
    drag = null;
    followUntil = performance.now() + 1500;
    if (stage.style.transform) snapHome();
  }
  el.addEventListener('pointercancel', cancelDrag);
  el.addEventListener('lostpointercapture', cancelDrag);
  el.addEventListener('pointerleave', (e) => {
    if (!el.hasPointerCapture?.(e.pointerId)) cancelDrag(e);
  });

  render();
  return {
    el,
    step,
    destroy() {
      settleToken++;
      drag = null;
      offTick();
      offTrack();
      offWord();
      offState();
      if (engine.stepWordHook) engine.stepWordHook = null;
    },
  };
}
