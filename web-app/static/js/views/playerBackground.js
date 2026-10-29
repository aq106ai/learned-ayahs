// The scene behind the player when a background is chosen (settings.playerBackground): the same
// drawn scenes and nature photos as a video, following the ayah being recited by the same rules
// (LiveBackground). It moves only while the recitation plays; paused, it holds still and is only
// redrawn when something changes. Port of the Android app's ui/PlayerBackground.kt.
//
// Drawn at half the screen's resolution (at least 540 px on the short side) and scaled up by the
// browser: the scenes are soft gradients and shapes that lose nothing by it, and each cached still
// stays small.

import { surahAyahToGlobal } from '../core/quran.js';
import { h } from '../lib/dom.js';
import { isPhoto, loadPhoto, photoFor, photoSubjects, subjectFor, themeKey } from '../video/data.js';
import { LiveBackground, PlayerBackgrounds } from '../video/plan.js';
import { BackgroundScenes, photoSpec, variant } from '../video/scenes.js';

const FRAME_MS = 33;

export function playerBackground({ engine, store }) {
  const canvas = h('canvas', { class: 'player-bg', 'aria-hidden': 'true', 'data-testid': 'player-background' });
  const live = new LiveBackground();
  const photoSpecs = new Map();
  let scenes = null;
  let wanted = null; // {choice, key: {themeKey, variant}}
  let shownChoice = null;
  let drawn = null;
  let sceneMs = 0;
  let lastFrame = 0;
  let raf = 0;
  let idle = 0;
  let token = 0;

  const choice = () => store.settings.playerBackground;
  const active = () => choice() !== PlayerBackgrounds.PLAIN;

  function resize() {
    // Half the screen's pixels, but never under 540 on the short side: the scenes' lines are
    // sized to the canvas, and on a small window they would thin out to nothing.
    const cw = Math.max(1, canvas.clientWidth);
    const ch = Math.max(1, canvas.clientHeight);
    const dpr = window.devicePixelRatio || 1;
    const scale = Math.min(dpr, Math.max(dpr / 2, 540 / Math.min(cw, ch)));
    const w = Math.max(2, Math.round(cw * scale));
    const hgt = Math.max(2, Math.round(ch * scale));
    if (scenes && scenes.width === w && scenes.height === hgt) return;
    canvas.width = w;
    canvas.height = hgt;
    scenes?.release();
    scenes = new BackgroundScenes(w, hgt);
    drawn = null;
    draw();
  }

  /** What the current ayah asks for; a photo is only asked for once it has loaded. */
  async function refreshWanted() {
    const t = engine.track;
    const c = choice();
    if (!t || c === PlayerBackgrounds.PLAIN) return;
    const mine = ++token;
    const seed = surahAyahToGlobal(t.surah, t.ayah);
    const drawnKey = await themeKey(t.surah, t.ayah);
    const subjects = await photoSubjects();
    const key = PlayerBackgrounds.themeFor(c, drawnKey, (k) => subjectFor(subjects, k)?.themeKey ?? null);
    let ready = !isPhoto(key);
    if (!ready) {
      const photo = photoFor(subjects, key, seed);
      if (photo) {
        try {
          photoSpecs.set(`${key}#${seed}`, photoSpec(photo.id, await loadPhoto(photo)));
          ready = true;
        } catch {
          ready = false;
        }
      }
    }
    if (mine !== token) return;
    wanted = { choice: c, key: { themeKey: ready ? key : drawnKey, variant: seed } };
    tick();
  }

  function sceneFor(key) {
    if (isPhoto(key.themeKey)) return photoSpecs.get(`${key.themeKey}#${key.variant}`) ?? variant(null, key.variant);
    return variant(key.themeKey, key.variant);
  }

  function draw() {
    if (!scenes) return;
    const ctx = canvas.getContext('2d');
    const shown = live.at(performance.now());
    drawn = shown;
    if (!shown) {
      ctx.clearRect(0, 0, canvas.width, canvas.height);
      return;
    }
    if (shown.previous) scenes.draw(ctx, sceneFor(shown.previous), sceneMs);
    scenes.draw(ctx, sceneFor(shown.current), sceneMs, shown.fade);
  }

  /** One step of the loop: apply what's wanted, then draw if anything moves or changed. */
  function tick() {
    cancelAnimationFrame(raf);
    clearTimeout(idle);
    if (!active()) return;
    const now = performance.now();
    if (wanted) {
      // A background the user has just picked shows at once; the ayahs' own changes wait.
      live.want(wanted.key, now, shownChoice !== null && wanted.choice !== shownChoice);
      shownChoice = wanted.choice;
    }
    const shown = live.at(now);
    const fading = shown && shown.fade < 1;
    if (engine.playing || fading) {
      raf = requestAnimationFrame((t) => {
        if (t - lastFrame >= FRAME_MS) {
          if (engine.playing && lastFrame) sceneMs += Math.min(t - lastFrame, 100);
          lastFrame = t;
          draw();
        }
        tick();
      });
    } else {
      lastFrame = 0;
      if (!drawn || shown?.current !== drawn.current || shown?.fade !== drawn.fade) draw();
      idle = setTimeout(tick, 500);
    }
  }

  const observer = typeof ResizeObserver !== 'undefined' ? new ResizeObserver(() => resize()) : null;
  observer?.observe(canvas);
  const offs = [
    engine.on('track', refreshWanted),
    engine.on('state', tick),
  ];

  return {
    el: canvas,
    /** Call when the setting changes (or on first show). */
    refresh() {
      canvas.hidden = !active();
      if (!active()) {
        cancelAnimationFrame(raf);
        clearTimeout(idle);
        live.clear();
        shownChoice = null;
        wanted = null;
        return;
      }
      requestAnimationFrame(resize);
      refreshWanted();
    },
    destroy() {
      offs.forEach((off) => off());
      cancelAnimationFrame(raf);
      clearTimeout(idle);
      observer?.disconnect();
      scenes?.release();
    },
  };
}
