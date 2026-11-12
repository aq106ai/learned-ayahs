// The video and background logic ported from the Android app (video/*.kt): the timeline, the
// background plan, the live background, the text layout and the theme data.

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { CROSSFADE_MS, END_CARD_MS, LiveBackground, MIN_HOLD_MS, PlayerBackgrounds, backgroundAt, buildTimeline, clipAt, planBackgrounds, showsEndCard } from '../../static/js/video/plan.js';
import { activeWordIndex, layoutAyah, pageAt, splitTranslation } from '../../static/js/video/layout.js';
import { FALLBACK, THEMES } from '../../static/js/video/themes.js';
import { variant } from '../../static/js/video/scenes.js';
import { contrastRatio, paletteFor } from '../../static/js/video/palette.js';
import { bismillahAllowed, clipsOf } from '../../static/js/video/exporter.js';
import { formatShareText } from '../../static/js/video/shareText.js';

const ASSETS = new URL('../../../android-app-quran-player/app/src/main/assets/', import.meta.url);

test('the timeline lays clips end to end with a lead-in, gaps and a tail', () => {
  const t = buildTimeline([{ kind: 'AUDHU', durationMs: 600 }, { kind: 'AYAH', surah: 112, ayah: 1, durationMs: 1200 }]);
  assert.deepEqual(t.clips.map((c) => [c.startMs, c.endMs]), [[500, 1100], [1500, 2700]]);
  assert.equal(t.totalMs, 2700 + 1800);
  assert.equal(t.endCardMs, 0);
  assert.equal(showsEndCard(t, t.totalMs - 1), false);
  const withCard = buildTimeline([{ kind: 'AYAH', surah: 112, ayah: 1, durationMs: 1200 }], END_CARD_MS);
  assert.equal(withCard.totalMs, 500 + 1200 + 1800 + END_CARD_MS);
  assert.equal(showsEndCard(withCard, withCard.totalMs - END_CARD_MS), true);
  assert.equal(showsEndCard(withCard, withCard.totalMs - END_CARD_MS - 1), false);
  assert.equal(clipAt(t, 0).kind, 'AUDHU');
  assert.equal(clipAt(t, 1300).kind, 'AUDHU', 'between clips the last one stays on screen');
  assert.equal(clipAt(t, 1500).kind, 'AYAH');
});

test('matched backgrounds hold for 12 s before changing, then crossfade', () => {
  const clips = [0, 5000, 13000, 14000].map((startMs, i) => ({ kind: 'AYAH', surah: 1, ayah: i + 1, startMs }));
  const themes = ['night', 'rain', 'sea', 'night'];
  const slots = planBackgrounds(clips, null, (s, a) => themes[a - 1], (s, a) => a, FALLBACK);
  assert.deepEqual(slots.map((s) => [s.startMs, s.themeKey]), [[0, 'night'], [13000, 'sea']]);
  assert.deepEqual(planBackgrounds(clips, 'mercy', () => 'x', () => 7, FALLBACK), [{ startMs: 0, themeKey: 'mercy', variant: 7 }]);
  const mid = backgroundAt(slots, 13000 + CROSSFADE_MS / 2);
  assert.equal(mid.current.themeKey, 'sea');
  assert.equal(mid.previous.themeKey, 'night');
  assert.ok(Math.abs(mid.fade - 0.5) < 1e-9);
  assert.equal(backgroundAt(slots, 20000).previous, null);
});

test('the live background follows the same rules on a running clock', () => {
  const live = new LiveBackground();
  assert.equal(live.at(0), null);
  live.want({ themeKey: 'night', variant: 1 }, 0);
  assert.equal(live.at(0).fade, 1, 'the first background is simply there');
  live.want({ themeKey: 'rain', variant: 2 }, 5000);
  assert.equal(live.at(5000).current.themeKey, 'night', 'held');
  live.want({ themeKey: 'night', variant: 9 }, MIN_HOLD_MS + 1);
  assert.equal(live.at(MIN_HOLD_MS + 1).current.variant, 1, 'another variant of the same theme is not a change');
  live.want({ themeKey: 'rain', variant: 2 }, MIN_HOLD_MS + 1);
  assert.equal(live.at(MIN_HOLD_MS + 1).current.themeKey, 'rain');
  live.want({ themeKey: 'sea', variant: 3 }, MIN_HOLD_MS + 2, true);
  assert.equal(live.at(MIN_HOLD_MS + 2).current.themeKey, 'sea', 'picking one skips the hold');
});

test('each player background choice picks its theme', () => {
  const photoFor = (k) => (k === 'night' ? 'photo:night_sky' : null);
  assert.equal(PlayerBackgrounds.themeFor('plain', 'night', photoFor), null);
  assert.equal(PlayerBackgrounds.themeFor('matched', 'night', photoFor), 'night');
  assert.equal(PlayerBackgrounds.themeFor('photos', 'night', photoFor), 'photo:night_sky');
  assert.equal(PlayerBackgrounds.themeFor('photos', 'rain', photoFor), 'rain');
  assert.equal(PlayerBackgrounds.themeFor('photo:forest', 'night', photoFor), 'photo:forest');
});

const measure = { arabicWidth: (t, px) => t.length * px * 0.5, glossWidth: (t, px) => t.length * px * 0.5 };
const words = (n) => Array.from({ length: n }, (_, i) => ({ arabic: 'كلمة', gloss: 'word', contentIndex: i }));

test('an ayah is laid out right to left and shrinks before it pages', () => {
  const one = layoutAyah(words(4), measure, 1000, 600, 80, true);
  assert.equal(one.pages.length, 1);
  const line = one.pages[0].words;
  assert.ok(line[0].x > line[1].x, 'the first word is on the right');
  const many = layoutAyah(words(120), measure, 600, 300, 80, true);
  assert.ok(many.pages.length > 1);
  assert.ok(Math.abs(many.arabicSize - 80 * 0.66) < 1e-9, 'shrunk as far as it goes before paging');
  const segments = Array.from({ length: 120 }, (_, i) => [i * 100, i * 100 + 90]);
  const second = many.pages[1].firstContentIndex;
  assert.equal(pageAt(many, segments[second][0] - 1, segments, 12000), 0);
  assert.equal(pageAt(many, segments[second][0], segments, 12000), 1);
});

test('the word being recited, and translations split for pages', () => {
  const seg = [[0, 400], [500, 900], [1000, 1400]];
  assert.equal(activeWordIndex(-1, seg, 3), -1);
  assert.equal(activeWordIndex(600, seg, 3), 1);
  assert.equal(activeWordIndex(950, seg, 3), 1, 'between words the last one stays lit');
  assert.equal(activeWordIndex(5000, seg, 3), 2);
  assert.deepEqual(splitTranslation('a b c d', 2), ['a b', 'c d']);
  assert.deepEqual(splitTranslation('a', 3), ['a', '', '']);
});

test('a scene palette keeps text readable, and Bismillah follows the same rules as the app', () => {
  const photo = { colors: [[16, 20, 24, 255], [16, 20, 24, 255], [16, 20, 24, 255], [255, 255, 255, 255]] };
  for (const theme of THEMES) {
    for (let i = 0; i < theme.variants.length; i++) {
      const palette = paletteFor(variant(theme.key, i));
      assert.ok(contrastRatio(palette.text, palette.panel) >= 4.5, theme.key);
      assert.ok(contrastRatio(palette.text, palette.bg) >= 4.5, theme.key);
      assert.ok(contrastRatio(palette.accent, palette.panel) >= 4.5, theme.key);
      assert.ok(contrastRatio(palette.onAccent, palette.accent) >= 4.5, theme.key);
      assert.equal(palette.isLight, false);
      // The Light theme's version of the same scene: dark text on pale surfaces.
      const light = paletteFor(variant(theme.key, i), true);
      assert.ok(contrastRatio(light.text, light.panel) >= 4.5, theme.key);
      assert.ok(contrastRatio(light.text, light.bg) >= 4.5, theme.key);
      assert.ok(contrastRatio(light.muted, light.bg) >= 4.5, theme.key);
      assert.ok(contrastRatio(light.accent, light.border) >= 4.5, theme.key);
      assert.ok(contrastRatio(light.onAccent, light.accent) >= 4.5, theme.key);
      assert.equal(light.isLight, true);
    }
  }
  const photos = paletteFor(photo);
  assert.ok(contrastRatio(photos.text, photos.panel) >= 4.5);
  // A photo's near-white highlight becomes the accent: the play icon and chip labels on it go dark.
  assert.ok(contrastRatio(photos.onAccent, photos.accent) >= 4.5);
  assert.equal(photos.isLight, false);
  assert.equal(bismillahAllowed(9, 1), false);
  assert.equal(bismillahAllowed(1, 1), false);
  assert.equal(bismillahAllowed(112, 2), true);
  assert.deepEqual(clipsOf({ surah: 112, fromAyah: 2, toAyah: 2, opening: false, bismillah: true }).map((c) => c.kind), ['BISMILLAH', 'AYAH']);
  assert.deepEqual(clipsOf({ surah: 9, fromAyah: 1, toAyah: 1, opening: true, bismillah: true }).map((c) => c.kind), ['AUDHU', 'AYAH']);
  assert.equal(
    formatShareText('Al-Ikhlas', 112, [{ number: 1, arabic: 'قُلْ هُوَ ٱللَّهُ أَحَدٌ', english: 'Say, He is Allah, One.' }], true, true),
    'قُلْ هُوَ ٱللَّهُ أَحَدٌ\nSay, He is Allah, One.\n\nAl-Ikhlas 112:1',
  );
  assert.equal(
    formatShareText('Al-Ikhlas', 112, [{ number: 1, arabic: 'قُلْ هُوَ ٱللَّهُ أَحَدٌ', english: 'Say, He is Allah, One.' }], true, false),
    'قُلْ هُوَ ٱللَّهُ أَحَدٌ\n\nAl-Ikhlas 112:1',
  );
});

test("the web app's themes match the ayah map the Android app ships", () => {
  const map = JSON.parse(readFileSync(new URL('video_themes.json', ASSETS), 'utf8'));
  const keys = new Set(THEMES.map((t) => t.key));
  assert.equal(THEMES.length, 53);
  for (const k of map.themes) assert.ok(keys.has(k), `theme ${k} from video_themes.json`);
  assert.ok(keys.has(FALLBACK));
  for (const t of THEMES) for (const [, colors] of t.variants) assert.equal(colors.length, 4);
});


test('text sharing rejects an empty range or no selected language', () => {
  assert.throws(() => formatShareText('Al-Ikhlas', 112, [], true, true), /at least one ayah/);
  assert.throws(() => formatShareText('Al-Ikhlas', 112, [{ number: 1, arabic: 'text' }], false, false), /at least the Arabic/);
});
