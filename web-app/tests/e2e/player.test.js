// The player, against the same rules as the Android PlaybackService: intros, Next/Previous,
// repeat modes, Full surah crossing, word by word, and the mini player.

import { after, before, test } from 'node:test';
import assert from 'node:assert/strict';
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

const state = (page) =>
  page.evaluate(() => {
    const e = window.__app.engine;
    return {
      ref: e.track ? `${e.track.surah}:${e.track.ayah}` : null,
      intro: e.intro,
      playing: e.playing,
      gap: e.gapPending,
      word: e.wordIndex,
      mode: e.mode,
      queue: e.queue.length,
      error: e.error,
    };
  });

/** Records every intro / ayah the engine starts, in order. */
const record = (page) =>
  page.evaluate(() => {
    const e = window.__app.engine;
    window.__heard = [];
    const note = () => {
      const label = e.intro ?? (e.track ? `${e.track.surah}:${e.track.ayah}` : null);
      if (e.playing && label && window.__heard.at(-1) !== label) window.__heard.push(label);
    };
    e.on('state', note);
    e.on('track', note);
  });

const heard = (page) => page.evaluate(() => window.__heard);

async function playerWith(refs, settings = {}) {
  const { page, context, errors } = await openApp(browser, server, { path: 'player', query: '?test', settings });
  await page.evaluate((l) => window.__app.store.setLearnedIds(l), await ids(page, refs));
  await page.evaluate(() => window.__app.engine.restore());
  return { page, context, errors };
}

test("revision plays A'udhu, Bismillah, then each learned ayah in order, and stops at the end", async () => {
  const { page, context, errors } = await playerWith([[112, 1], [112, 2], [112, 3], [113, 1]]);
  await record(page);
  await page.click('[data-testid=play]');
  await page.waitForFunction(() => window.__heard.length >= 8 || (!window.__app.engine.playing && window.__heard.length > 2), null, { timeout: 20000 });
  await page.waitForFunction(() => !window.__app.engine.playing, null, { timeout: 10000 });
  assert.deepEqual(await heard(page), ['AUDHU', 'BISMILLAH', '112:1', '112:2', '112:3', 'AUDHU', 'BISMILLAH', '113:1']);
  assert.equal((await state(page)).ref, '113:1');
  assert.deepEqual(errors, []);
  await context.close();
});

test('Al-Fatihah gets no separate Bismillah; Next during an intro starts the ayah', async () => {
  const { page, context, errors } = await playerWith([[1, 1], [1, 2]]);
  await record(page);
  await page.click('[data-testid=play]');
  await page.waitForFunction(() => window.__app.engine.intro === 'AUDHU' && window.__app.engine.playing);
  await page.click('[data-testid=next]');
  await page.waitForFunction(() => !window.__app.engine.intro && window.__app.engine.playing);
  assert.equal((await state(page)).ref, '1:1');
  await page.waitForFunction(() => window.__heard.includes('1:2'), null, { timeout: 10000 });
  assert.deepEqual((await heard(page)).slice(0, 3), ['AUDHU', '1:1', '1:2']);
  assert.deepEqual(errors, []);
  await context.close();
});

test('Next and Previous step ayahs; the queue wraps only under Repeat: Surah', async () => {
  const { page, context, errors } = await playerWith([[112, 2], [112, 3], [112, 4]]);
  assert.equal((await state(page)).ref, '112:2');
  await page.click('[data-testid=prev]');
  assert.equal((await state(page)).ref, '112:2', 'no wrap with repeat off');
  await page.click('[data-testid=next]');
  await page.click('[data-testid=next]');
  assert.equal((await state(page)).ref, '112:4');
  await page.click('[data-testid=next]');
  assert.equal((await state(page)).ref, '112:4');
  await page.evaluate(() => window.__app.engine.setRepeat('SURAH'));
  await page.click('[data-testid=next]');
  assert.equal((await state(page)).ref, '112:2', 'wraps with repeat surah');
  await page.click('[data-testid=prev]');
  assert.equal((await state(page)).ref, '112:4');
  assert.deepEqual(errors, []);
  await context.close();
});

test('Full surah plays every ayah and Next/Previous cross into the neighbouring surah', async () => {
  const { page, context, errors } = await playerWith([[113, 2]]);
  await page.click('[data-testid=expand-controls]');
  await page.click('[data-testid=mode-FULL_SURAH]');
  let s = await state(page);
  assert.equal(s.mode, 'FULL_SURAH');
  assert.equal(s.queue, 5);
  assert.equal(s.ref, '113:2');
  await page.evaluate(() => window.__app.engine.loadIndex(4));
  await page.click('[data-testid=next]');
  s = await state(page);
  assert.equal(s.ref, '114:1');
  assert.equal(s.queue, 6);
  await page.click('[data-testid=prev]');
  assert.equal((await state(page)).ref, '113:5');
  assert.match(await page.textContent('[data-testid=player-meta]'), /Al-Falaq · Ayah 5 · 5 of 5/);
  assert.deepEqual(errors, []);
  await context.close();
});

test('Repeat: Ayah replays the ayah after the chosen pause, without the intro', async () => {
  const { page, context, errors } = await playerWith([[112, 2]], { repeatMode: 'AYAH', revisionDelaySeconds: 3 });
  await record(page);
  await page.click('[data-testid=play]');
  await page.waitForFunction(() => window.__app.engine.gapPending, null, { timeout: 10000 });
  assert.match(await page.textContent('[data-testid=now-playing]'), /pausing before repeat/);
  // After the pause the same ayah plays again — and no intro (it is a repeat).
  await page.waitForFunction(() => !window.__app.engine.gapPending && window.__app.engine.playing, null, { timeout: 6000 });
  assert.deepEqual(await heard(page), ['112:2']);
  await page.click('[data-testid=play]'); // pause
  assert.equal((await state(page)).playing, false);
  assert.deepEqual(errors, []);
  await context.close();
});

test('word by word steps one word at a time, rolls into the next ayah, and never plays an intro', async () => {
  const { page, context, errors } = await playerWith([[112, 1], [112, 2]], { useWordClips: true });
  await page.click('[data-testid=expand-controls]');
  await page.click('[data-testid=mode-WORD_BY_WORD]');
  await page.waitForSelector('[data-testid=word-reader]');
  assert.match(await page.textContent('[data-testid=word-counter]'), /^1 \/ 4/);
  assert.equal(await page.textContent('[data-testid=word-meaning]'), 'Say');
  await page.click('[data-testid=next]');
  assert.match(await page.textContent('[data-testid=word-counter]'), /^2 \/ 4/);
  await page.click('[data-testid=prev]');
  await record(page);
  await page.click('[data-testid=play]');
  await page.waitForFunction(() => window.__app.engine.track?.ayah === 2, null, { timeout: 10000 });
  const s = await state(page);
  assert.equal(s.ref, '112:2');
  assert.ok(!(await heard(page)).includes('AUDHU'));
  await page.click('[data-testid=play]');
  assert.deepEqual(errors, []);
  await context.close();
});

test('the mini player keeps playback in reach on other pages', async () => {
  const { page, context, errors } = await playerWith([[112, 1], [112, 2], [112, 3], [112, 4]], { repeatMode: 'SURAH' });
  await page.click('[data-testid=play]');
  await page.click('[data-testid=nav-surahs]');
  await page.waitForSelector('#mini-player:not([hidden])');
  await page.click('[data-testid=mini-toggle]');
  assert.equal((await state(page)).playing, false);
  await page.click('#mini-player .title');
  await page.waitForSelector('[data-testid=reader]');
  assert.equal(new URL(page.url()).hash, '#/player');
  assert.deepEqual(errors, []);
  await context.close();
});

test('the last ayah played is remembered across reloads', async () => {
  const { page, context } = await playerWith([[112, 1], [112, 2], [112, 3]]);
  await page.click('[data-testid=next]');
  await page.click('[data-testid=next]');
  await page.reload();
  await page.waitForSelector('[data-testid=reader]');
  assert.equal((await state(page)).ref, '112:3');
  await context.close();
});

test('audio that cannot load says so, with a Retry, instead of stalling', async () => {
  const { page, context, errors } = await playerWith([[2, 1]]); // not in the test audio cache
  // The server cannot fetch it (--no-fetch-audio) and the direct fallback is blocked too.
  await context.route(/everyayah\.com|quranicaudio\.com/, (route) => route.abort());
  await page.click('[data-testid=play]');
  await page.click('[data-testid=next]'); // past the intro, to the ayah itself
  await page.waitForSelector('[data-testid=player-error]:not([hidden])', { timeout: 15000 });
  assert.match(await page.textContent('[data-testid=player-error]'), /Couldn't load audio for 2:1/);
  assert.equal((await state(page)).playing, false);
  await page.click('[data-testid=retry]');
  await page.waitForSelector('[data-testid=player-error]:not([hidden])', { timeout: 15000 });
  // The page itself reported nothing unexpected (failed media loads are expected here).
  assert.deepEqual(errors.filter((e) => !/audio|media|NotSupportedError/i.test(e)), []);
  await context.close();
});
