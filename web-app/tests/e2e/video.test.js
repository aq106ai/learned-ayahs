// "Make a video", the player's backgrounds and its Share button, in a real browser. The audio is
// the harness's generated clips, so this needs no network; the video is encoded by the browser's
// own WebCodecs (H.264/AAC where it has them, VP9/Opus otherwise, as in this Chromium).

import { after, before, test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { ids, launchBrowser, openApp, startServer } from './harness.js';

let server;
let browser;

before(async () => {
  server = await startServer();
  browser = await launchBrowser();
});

after(async () => {
  await browser?.close();
  await server?.stop();
});

test('the video maker previews the ayah and makes a video with sound and pictures', async () => {
  const { page, context, errors } = await openApp(browser, server, { path: 'video/112/1' });
  await page.waitForSelector('[data-testid=video-preview] canvas', { timeout: 20000 });
  // The whole of Al-Ikhlas, with A'udhu and Bismillah, small and quick.
  await page.fill('[data-testid=video-to]', '4');
  await page.dispatchEvent('[data-testid=video-to]', 'change');
  const made = await page.evaluate(async () => {
    const { VideoExporter } = await import('./js/video/exporter.js');
    const { reciterFromKey } = await import('./js/core/reciters.js');
    const exporter = new VideoExporter({ proxy: true });
    const request = {
      surah: 112, fromAyah: 1, toAyah: 4, reciter: reciterFromKey('MAHER_AL_MUAIQLY'), aspect: 'PORTRAIT',
      wordByWord: true, fullTranslation: true, opening: true, themeKey: null, photos: false, credits: false,
    };
    const stages = new Set();
    const { blob, format, durationMs } = await exporter.export(request, { scale: 0.25, fps: 10, onProgress: (p) => stages.add(p.stage) });
    const head = new Uint8Array(await blob.slice(0, 64).arrayBuffer());
    return { size: blob.size, container: format.container, durationMs, stages: [...stages], head: Array.from(head) };
  });
  // A'udhu (0.6 s) + Bismillah (0.6 s) + four 1.2 s ayahs, back to back as in the player, with the
  // lead-in, tail and end card.
  assert.equal(made.durationMs, 500 + 600 + 600 + 4 * 1200 + 1800 + 4000);
  assert.deepEqual(made.stages, ['DOWNLOADING', 'AUDIO', 'VIDEO']);
  assert.ok(made.size > 20_000, `video is ${made.size} bytes`);
  if (made.container === 'webm') assert.deepEqual(made.head.slice(0, 4), [0x1a, 0x45, 0xdf, 0xa3], 'a WebM (EBML) file');
  else assert.equal(String.fromCharCode(...made.head.slice(4, 8)), 'ftyp', 'an MP4 file');

  // And through the screen: Create, then Download.
  await page.click('[data-testid=video-aspect-SQUARE]');
  await page.click('[data-testid=video-create]');
  await page.waitForSelector('[data-testid=video-download]', { timeout: 120000 });
  const download = await Promise.all([page.waitForEvent('download'), page.click('[data-testid=video-download]')]).then(([d]) => d);
  assert.match(download.suggestedFilename(), /^LearnedAyahs_112_001-004_1x1\.(mp4|webm)$/);
  const file = readFileSync(await download.path());
  assert.ok(file.length > 20_000);
  assert.equal(await page.$('[data-testid=video-caption]'), null);
  await page.waitForSelector('[data-testid=video-share-text]');
  assert.equal(await page.isChecked('[data-testid=video-bismillah]'), true, 'Bismillah is on for the start of a surah');
  assert.equal(await page.isChecked('[data-testid=video-endCard]'), true, 'the end card is on');
  assert.deepEqual(errors, []);
  await context.close();
});

test('a background turns the player into the video look, with the translation', async () => {
  const { page, context, errors } = await openApp(browser, server, { path: 'player', query: '?test' });
  await page.evaluate((l) => window.__app.store.setLearnedIds(l), await ids(page, [[112, 1], [112, 2]]));
  await page.evaluate(() => window.__app.engine.restore());
  await page.waitForSelector('[data-testid=ayah-text]');
  assert.equal(await page.isHidden('[data-testid=player-background]'), true, 'plain by default');
  assert.equal(await page.$('[data-testid=ayah-translation]'), null);

  await page.click('[data-testid=open-actions-menu]');
  await page.click('[data-testid=open-background]');
  await page.click('[data-testid=background-matched]');
  await page.click('.dialog .btn.primary');
  assert.equal(await page.evaluate(() => window.__app.store.settings.playerBackground), 'matched');
  assert.equal(await page.evaluate(() => window.__app.store.settings.showAyahTranslation), true, 'a background brings the translation');
  await page.waitForSelector('.player.with-bg');
  await page.waitForFunction(() => /He is Allah/.test(document.querySelector('[data-testid=ayah-translation]')?.textContent ?? ''));
  // The scene has actually been painted.
  await page.waitForFunction(() => document.documentElement.style.getPropertyValue('--bg'), null, { timeout: 15000 });
  await page.waitForFunction(() => {
    const c = document.querySelector('[data-testid=player-background]');
    if (!c || c.hidden || !c.width) return false;
    const px = c.getContext('2d').getImageData(Math.floor(c.width / 2), Math.floor(c.height / 2), 1, 1).data;
    return px[3] > 0;
  }, null, { timeout: 15000 });
  assert.deepEqual(errors, []);
  await context.close();
});

test('Share → Image saves a picture of the ayah; Share → Video opens the maker set up like the player', async () => {
  const { page, context, errors } = await openApp(browser, server, { path: 'player', query: '?test', settings: { playerBackground: 'night', showAyahTranslation: true } });
  await page.evaluate((l) => window.__app.store.setLearnedIds(l), await ids(page, [[112, 1]]));
  await page.evaluate(() => window.__app.engine.restore());
  await page.waitForSelector('[data-testid=ayah-text]');

  await page.click('[data-testid=open-actions-menu]');
  await page.click('[data-testid=share]');
  await page.click('[data-testid=share-aspect-TALL]');
  // Exercise the download fallback explicitly; desktop Chrome can expose native file sharing.
  await page.evaluate(() => { navigator.canShare = () => false; });
  const download = await Promise.all([page.waitForEvent('download', { timeout: 30000 }), page.click('.dialog .btn.primary')]).then(([d]) => d);
  assert.equal(download.suggestedFilename(), 'LearnedAyahs_112_001-001_4x5.jpg');
  const jpg = readFileSync(await download.path());
  assert.deepEqual([...jpg.subarray(0, 2)], [0xff, 0xd8], 'a JPEG');
  assert.ok(jpg.length > 20_000);

  await page.click('[data-testid=open-actions-menu]');
  await page.click('[data-testid=share]');
  await page.click('.dialog .actions .btn:not(.primary)');
  await page.waitForSelector('[data-testid=video-screen]');
  assert.match(page.url(), /#\/video\/112\/1\/player$/);
  // Started out like the player: its chosen background is selected.
  await page.waitForSelector('[data-testid=video-theme-night][aria-pressed=true]');
  assert.deepEqual(errors, []);
  await context.close();
});


test('a range can be copied as Arabic or English without creating a video', async () => {
  const { page, context, errors } = await openApp(browser, server, { path: 'video/112/1' });
  await page.evaluate(() => {
    window.__copies = [];
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText: async (text) => window.__copies.push(text) } });
  });
  await page.fill('[data-testid=video-to]', '2');
  await page.dispatchEvent('[data-testid=video-to]', 'change');
  await page.click('[data-testid=video-text-english]');
  await page.click('[data-testid=video-copy-text]');
  await page.waitForFunction(() => window.__copies.length === 1);
  let text = await page.evaluate(() => window.__copies[0]);
  assert.match(text, /Al-Ikhlas 112:1-2/);
  assert.match(text, /[\u0600-\u06ff]/);
  assert.doesNotMatch(text, /Say|Allah, the Eternal/);
  await page.click('[data-testid=video-text-english]');
  await page.click('[data-testid=video-text-arabic]');
  await page.click('[data-testid=video-copy-text]');
  await page.waitForFunction(() => window.__copies.length === 2);
  text = await page.evaluate(() => window.__copies[1]);
  assert.match(text, /Say/);
  assert.doesNotMatch(text, /[\u0600-\u06ff]/);
  assert.equal(await page.locator('[data-testid=video-download]').count(), 0);
  assert.deepEqual(errors, []);
  await context.close();
});

// The test server's 114:2 is a 9 s file whose timings end near 2.3 s, like EveryAyah's Maher Al
// Muaiqly 2:258, which holds 2:259 too. The video ends the ayah where its words do.
test('a video of an ayah whose file runs on carries only that ayah', async () => {
  const { page, context, errors } = await openApp(browser, server, { path: 'video/114/2' });
  const made = await page.evaluate(async () => {
    const { VideoExporter } = await import('./js/video/exporter.js');
    const { reciterFromKey } = await import('./js/core/reciters.js');
    const request = {
      surah: 114, fromAyah: 2, toAyah: 2, reciter: reciterFromKey('MAHER_AL_MUAIQLY'), aspect: 'SQUARE',
      wordByWord: false, fullTranslation: false, opening: false, endCard: false, themeKey: null, photos: false, credits: false,
    };
    const { durationMs } = await new VideoExporter({ proxy: true }).export(request, { scale: 0.2, fps: 5 });
    return durationMs;
  });
  // Lead-in, the ayah to its last word and a breath (2270 + 400 ms), and the tail; not 9 s.
  assert.equal(made, 500 + 2270 + 400 + 1800);
  assert.deepEqual(errors, []);
  await context.close();
});
