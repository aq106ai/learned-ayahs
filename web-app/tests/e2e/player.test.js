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
  await page.click('[data-testid=open-actions-menu]');
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
  await page.click('[data-testid=open-actions-menu]');
  await page.click('[data-testid=toggle-word-by-word]');
  await page.waitForSelector('[data-testid=word-reader]');
  assert.match(await page.textContent('[data-testid=word-counter]'), /^1 \/ 4/);
  assert.equal(await page.textContent('[data-testid=word-meaning]'), 'Say');
  const word = await page.textContent('[data-testid=word-current]');
  const box = await page.locator('[data-testid=word-reader]').boundingBox();
  const y = box.y + box.height / 2;
  await page.mouse.move(box.x + box.width * 0.25, y);
  await page.mouse.down();
  await page.mouse.move(box.x + box.width * 0.45, y, { steps: 6 });
  await page.mouse.up();
  assert.equal(await page.textContent('[data-testid=word-current]'), word, 'a short swipe snaps back');
  await page.mouse.move(box.x + box.width * 0.1, y);
  await page.mouse.down();
  await page.mouse.move(box.x + box.width * 0.85, y, { steps: 8 });
  await page.mouse.up();
  assert.match(await page.textContent('[data-testid=word-counter]'), /^2 \/ 4/, 'past halfway the next word settles on screen');
  await page.click('[data-testid=prev]');
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


test('word drags hold playback focus and cancel cleanly without turning into taps', async () => {
  const { page, context, errors } = await playerWith([[112, 1]]);
  const result = await page.evaluate(async () => {
    const { wordReader } = await import('./js/views/wordReader.js');
    const handlers = new Map();
    let seeks = 0;
    const engine = {
      mode: 'REVISE', playing: true, activeWord: 0, track: { surah: 112, ayah: 1 },
      contentWords: [{ text: 'first' }, { text: 'second' }],
      on: (event, fn) => { handlers.set(event, fn); return () => handlers.delete(event); },
      seekToWord: () => seeks++, stepAyah: () => {},
    };
    const settings = { swipeToNavigate: true, tapToAdvance: true };
    const reader = wordReader({ engine, store: { settings } });
    document.body.append(reader.el);
    reader.el.style.width = '400px';
    const stage = reader.el.querySelector('.word-stage');
    const fire = (type, x, y = 20) => reader.el.dispatchEvent(new PointerEvent(type, { pointerId: 99, button: 0, clientX: x, clientY: y }));
    // Synthetic pointers cannot take browser capture; lost capture is tested explicitly below.
    reader.el.setPointerCapture = () => {};
    // The snap-back ends on transitionend or a 280 ms fallback; a slow CI runner can take longer
    // than a fixed sleep, so wait for the stage to come home (or give up after 3 s).
    const settled = async () => {
      const until = performance.now() + 3000;
      while (stage.style.transform && performance.now() < until) await new Promise((r) => setTimeout(r, 25));
    };
    fire('pointerdown', 10);
    fire('pointermove', 150);
    engine.activeWord = 1;
    handlers.get('tick')();
    const held = reader.el.querySelector('.big').textContent;
    fire('pointermove', 10); // returning to the start is still a drag, not a tap
    fire('pointerup', 10);
    await settled();
    const afterReturn = [seeks, stage.style.transform];
    fire('pointerdown', 10);
    fire('pointermove', 190);
    fire('lostpointercapture', 190);
    await settled();
    const afterCancel = [seeks, stage.style.transform];
    settings.swipeToNavigate = false;
    fire('pointerdown', 10);
    fire('pointermove', 40);
    fire('pointerup', 40);
    const afterDisabled = seeks;
    fire('pointerdown', 10);
    fire('pointermove', 10, 100);
    fire('pointerup', 10, 100);
    const afterVertical = seeks;
    reader.destroy();
    reader.el.remove();
    return { held, afterReturn, afterCancel, afterDisabled, afterVertical };
  });
  assert.deepEqual(result, { held: 'first', afterReturn: [0, ''], afterCancel: [0, ''], afterDisabled: 0, afterVertical: 0 });
  assert.deepEqual(errors, []);
  await context.close();
});

test("a player background's colours stay on every page, and Plain clears them", async () => {
  const { page, context, errors } = await playerWith([[112, 1]], { playerBackground: 'matched' });
  const panel = () => page.evaluate(() => document.documentElement.style.getPropertyValue('--panel'));
  await page.waitForFunction(() => document.documentElement.style.getPropertyValue('--panel'));
  const inPlayer = await panel();
  await page.click('[data-testid=nav-surahs]');
  // A hash link only queues `hashchange`; reading straight after the click raced the router on
  // slow CI runners. Wait until the Manage view has replaced the player.
  await page.waitForFunction(() => document.title.startsWith('Manage'));
  assert.equal(await panel(), inPlayer, 'Manage keeps the scene colours');
  assert.equal(await page.locator('#app').evaluate((el) => el.classList.contains('scene')), false, 'the picture itself is only in the player');
  await page.evaluate(() => window.__app.store.setSetting('theme', 'LIGHT'));
  assert.notEqual(await panel(), inPlayer, 'Light recolours the scene palette on other pages too');
  await page.evaluate(() => window.__app.store.setSetting('playerBackground', 'plain'));
  assert.equal(await panel(), '', 'Plain goes back to the plain theme');
  assert.deepEqual(errors, []);
  await context.close();
});

test('Repeat: Word repeats the word being recited with the whole ayah on screen, in either mode', async () => {
  const { page, context, errors } = await playerWith([[112, 1], [112, 2]]);
  await page.click('[data-testid=expand-controls]');
  await page.click('[data-testid=repeat-word]');
  assert.equal(await page.getAttribute('[data-testid=repeat-word]', 'aria-pressed'), 'true');
  assert.equal(await page.getAttribute('[data-testid=repeat-OFF]', 'aria-pressed'), 'false', 'one repeat choice at a time');
  assert.equal(await page.evaluate(() => window.__app.engine.wordPlayback), true);
  assert.equal(await page.locator('[data-testid=ayah-text]').count(), 1, 'the whole ayah stays on screen');
  await page.click('[data-testid=play]');
  await page.waitForFunction(() => window.__app.engine.playing);
  // Left alone, it stays on the first word of the first ayah.
  await page.waitForTimeout(2500);
  let s = await state(page);
  assert.deepEqual([s.ref, s.word], ['112:1', 0]);
  assert.equal(await page.isVisible('[data-testid=next-ayah]'), true);
  assert.equal(await page.locator('.word.active').count(), 1, 'the repeating word is lit');
  await page.click('[data-testid=next]');
  assert.equal((await state(page)).word, 1, 'Next steps to the next word');
  // A tap on the whole ayah steps a word too; the outer buttons move by ayah.
  const reader = await page.locator('[data-testid=reader]').boundingBox();
  await page.mouse.click(reader.x + reader.width / 2, reader.y + 20);
  assert.equal((await state(page)).word, 2, 'a tap steps a word');
  assert.equal(await page.getAttribute('[data-testid=next]', 'aria-label'), 'Next word');
  await page.click('[data-testid=next-ayah]');
  s = await state(page);
  assert.deepEqual([s.ref, s.word], ['112:2', 0], 'the outer button moves by ayah');
  await page.click('[data-testid=prev-ayah]');
  assert.equal((await state(page)).ref, '112:1');
  // Full surah repeats words too.
  await page.click('[data-testid=open-actions-menu]');
  await page.click('[data-testid=mode-FULL_SURAH]');
  s = await state(page);
  assert.equal(s.mode, 'FULL_SURAH');
  assert.equal(await page.evaluate(() => window.__app.engine.wordPlayback), true);
  // Choosing another repeat leaves Repeat: Word.
  await page.click('[data-testid=repeat-AYAH]');
  assert.equal(await page.isVisible('[data-testid=next-ayah]'), false);
  assert.equal(await page.evaluate(() => window.__app.store.settings.repeatWord), false);
  assert.equal(await page.evaluate(() => window.__app.engine.wordPlayback), false);
  assert.equal(await page.getAttribute('[data-testid=repeat-AYAH]', 'aria-pressed'), 'true');
  await page.evaluate(() => window.__app.engine.pause());
  assert.deepEqual(errors, []);
  await context.close();
});

test('Word clips replace the reciter choice; the repeat chips name what Surah repeats', async () => {
  const { page, context, errors } = await playerWith([[112, 1], [112, 2], [113, 1]]);
  await page.click('[data-testid=expand-controls]');
  assert.deepEqual(await page.locator('[data-testid=more-controls] .chips').first().locator('.chip').allTextContents(),
    ['Ayah', 'Surah learned ayahs', 'Word', 'Off']);
  assert.equal(await page.locator('[data-testid=player-reciter]').count(), 1);
  await page.check('[data-testid=word-clips]');
  assert.equal(await page.locator('[data-testid=player-reciter]').count(), 0, 'word clips have their own recording');
  assert.equal(await page.evaluate(() => window.__app.engine.wordPlayback), true);
  await page.uncheck('[data-testid=word-clips]');
  assert.equal(await page.locator('[data-testid=player-reciter]').count(), 1);
  // One learned ayah in the surah, or the full surah: plain "Surah".
  await page.evaluate(() => window.__app.engine.playGlobal(window.__app.engine.queue[2].globalId, { autoplay: false }));
  assert.equal(await page.textContent('[data-testid=repeat-SURAH]'), 'Surah');
  assert.deepEqual(errors, []);
  await context.close();
});

test('the menu hides and shows word meanings, and leaves out Full Surah for a surah learned in full', async () => {
  const { page, context, errors } = await playerWith([[112, 1], [112, 2], [112, 3], [112, 4], [113, 1]]);
  await page.waitForSelector('.word .tr');
  await page.click('[data-testid=open-actions-menu]');
  assert.equal(await page.locator('[data-testid=mode-FULL_SURAH]').count(), 0, 'all of Al-Ikhlas is learned');
  await page.click('[data-testid=toggle-word-meanings]');
  assert.equal(await page.locator('.word .tr').count(), 0);
  assert.equal(await page.evaluate(() => window.__app.store.settings.showWordTranslations), false);
  await page.click('[data-testid=open-actions-menu]');
  await page.click('[data-testid=toggle-word-meanings]');
  await page.waitForSelector('.word .tr');
  // Al-Falaq is only partly learned: the full surah is a different list, so it is offered.
  await page.evaluate(() => window.__app.engine.playGlobal(window.__app.engine.queue[4].globalId, { autoplay: false }));
  await page.click('[data-testid=open-actions-menu]');
  assert.equal(await page.locator('[data-testid=mode-FULL_SURAH]').count(), 1);
  assert.deepEqual(errors, []);
  await context.close();
});


test('whole-ayah swipes keep the short threshold and vertical or cancelled drags do not advance', async () => {
  const { page, context, errors } = await playerWith([[112, 1], [112, 2]]);
  await page.evaluate(() => {
    const el = document.querySelector('[data-testid=reader]');
    const fire = (type, x, y) => el.dispatchEvent(new PointerEvent(type, { pointerId: 1, button: 0, clientX: x, clientY: y }));
    fire('pointerdown', 20, 20);
    fire('pointermove', 20, 100);
    fire('pointerup', 20, 100);
    fire('pointerdown', 20, 20);
    fire('pointercancel', 20, 20);
    fire('pointerup', 20, 20);
  });
  assert.equal((await state(page)).ref, '112:1');
  const box = await page.locator('[data-testid=reader]').boundingBox();
  await page.mouse.move(box.x + 20, box.y + 50);
  await page.mouse.down();
  await page.mouse.move(box.x + 100, box.y + 50, { steps: 8 });
  await page.mouse.up();
  await page.waitForFunction(() => window.__app.engine.track.ayah === 2);
  assert.deepEqual(errors, []);
  await context.close();
});

// EveryAyah's Maher Al Muaiqly 2:258 file holds 2:259 too: the words ran out at 36% of the slider
// and the app stayed on 2:258 while 2:259 played. The test server's 114:2 is such a file (9 s,
// with timings that end near 2 s).
test('an ayah whose file runs on past its timings ends with the ayah and moves on', async () => {
  const { page, context, errors } = await playerWith([[114, 2], [114, 3]]);
  await page.waitForFunction(() => window.__app.engine.audio.duration > 8 && window.__app.engine.segments);
  const shown = await page.evaluate(() => window.__app.engine.durationMs);
  assert.ok(shown > 1500 && shown < 4000, `the slider spans the ayah, not the file (${shown} ms)`);
  await record(page);
  await page.click('[data-testid=play]');
  await page.waitForFunction(() => window.__app.engine.track?.ayah === 3, null, { timeout: 6000 });
  assert.deepEqual(await heard(page), ['114:2', '114:3']);
  assert.deepEqual(errors, []);
  await context.close();
});

test('Repeat: Ayah and Repeat: Word stop where an overlong file\'s ayah ends', async () => {
  const { page, context, errors } = await playerWith([[114, 2], [114, 3]], { repeatMode: 'AYAH', revisionDelaySeconds: 3 });
  await page.waitForFunction(() => window.__app.engine.audio.duration > 8 && window.__app.engine.segments);
  await page.click('[data-testid=play]');
  await page.waitForFunction(() => window.__app.engine.gapPending, null, { timeout: 6000 });
  assert.equal((await state(page)).ref, '114:2', 'the ayah repeats');
  await page.evaluate(() => window.__app.engine.pause());
  // The ayah's last word, repeating: it must not run on into the rest of the file.
  await page.evaluate(() => {
    const { engine, store } = window.__app;
    store.setSetting('revisionDelaySeconds', 0);
    engine.setRepeatWord(true);
  });
  await page.waitForFunction(() => window.__app.engine.contentWords.length > 0 && window.__app.engine.wordPlayback);
  await page.evaluate(() => {
    const e = window.__app.engine;
    e.loadWord(e.contentWords.length - 1, { autoplay: false });
    window.__words = 0;
    e.on('word', () => window.__words++);
  });
  await page.click('[data-testid=play]');
  await page.waitForFunction(() => window.__words >= 2, null, { timeout: 6000 });
  const s = await state(page);
  assert.equal(s.ref, '114:2');
  await page.evaluate(() => window.__app.engine.pause());
  assert.deepEqual(errors, []);
  await context.close();
});
