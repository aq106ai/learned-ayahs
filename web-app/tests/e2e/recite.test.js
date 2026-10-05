// Recite & review (beta). The browser's speech recogniser is replaced by a scripted one, so the
// whole path — recogniser events → SpeechSession → coach → screen — runs without a microphone.

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

/** A stand-in for webkitSpeechRecognition that the test speaks through with window.__say(). */
function installFakeRecognizer() {
  const live = [];
  class FakeRecognition {
    constructor() {
      this.results = [];
    }
    start() {
      live.push(this);
    }
    stop() {
      this.abort();
      this.onend?.();
    }
    abort() {
      const i = live.indexOf(this);
      if (i >= 0) live.splice(i, 1);
    }
  }
  window.SpeechRecognition = FakeRecognition;
  window.__say = (text, isFinal = true) => {
    const rec = live.at(-1);
    if (!rec) throw new Error('not listening');
    const result = Object.assign([{ transcript: text, confidence: 0.9 }], { isFinal });
    rec.results.push(result);
    rec.onresult?.({ results: rec.results, resultIndex: rec.results.length - 1 });
  };
}

test('turning on the beta asks first; reciting walks the learned ayahs, corrects a real mistake, and ends with a recap', async () => {
  const { page, context, errors } = await openApp(browser, server, { path: 'settings', query: '?test' });
  await context.addInitScript(installFakeRecognizer);
  await page.reload();
  await page.waitForSelector('[data-testid=setting-reciteBetaEnabled]');
  await page.evaluate(async (l) => window.__app.store.setLearnedIds(l), await ids(page, [[112, 1], [112, 2], [112, 3], [112, 4]]));

  // The beta needs consent; cancelling leaves it off.
  await page.click('[data-testid=setting-reciteBetaEnabled]');
  await page.click('.dialog button:has-text("Cancel")');
  assert.equal(await page.isChecked('[data-testid=setting-reciteBetaEnabled]'), false);
  assert.equal(await page.locator('[data-testid=nav-recite]').count(), 0);
  await page.click('[data-testid=setting-reciteBetaEnabled]');
  await page.click('.dialog button:has-text("Turn on")');
  await page.click('[data-testid=nav-recite]');
  await page.waitForSelector('[data-testid=recite-screen]');
  await page.waitForFunction(() => window.__reciteTest?.coach.state.words.length > 0);
  assert.match(await page.textContent('[data-testid=recite-title]'), /Al-Ikhlas · Ayah 1/);

  // Recite 112:1 correctly through the (fake) recogniser: every word turns green, then on to 112:2.
  await page.click('[data-testid=recite-mic]');
  assert.equal(await page.getAttribute('[data-testid=recite-mic]', 'aria-label'), 'Stop reciting');
  await page.evaluate(() => window.__say('قل هو الله احد'));
  await page.waitForFunction(() => /Ayah 2/.test(document.querySelector('[data-testid=recite-title]').textContent));

  // A real mistake — the same wrong word, heard twice — is corrected with the reciter's word.
  await page.evaluate(() => window.__reciteTest.feed('الله الكبير'));
  assert.equal(await page.locator('[data-testid=mistake-card]').count(), 0, 'one hearing is not enough');
  await page.evaluate(() => window.__reciteTest.feed('الله الكبير'));
  await page.waitForSelector('[data-testid=mistake-card]');
  assert.match(await page.textContent('[data-testid=mistake-card]'), /Mistake detected/);
  await page.waitForFunction(() => !window.__reciteTest.coach.state.isAutoPlayingMistake, null, { timeout: 8000 });

  // Recite it properly, then the rest of the surah.
  await page.evaluate(() => window.__say('الله الصمد'));
  await page.waitForFunction(() => /Ayah 3/.test(document.querySelector('[data-testid=recite-title]').textContent));
  await page.evaluate(() => window.__say('لم يلد ولم يولد'));
  await page.waitForFunction(() => /Ayah 4/.test(document.querySelector('[data-testid=recite-title]').textContent));
  await page.evaluate(() => window.__say('ولم يكن له كفوا احد'));

  // End of the learned ayahs: the recap, with the one word to work on.
  await page.waitForSelector('[data-testid=recite-report]');
  const report = await page.textContent('.dialog');
  assert.match(report, /Surah complete/);
  assert.match(report, /3 of 4 ayahs perfect · 1 word to work on/);
  await page.click('.dialog button:has-text("Done")');
  assert.equal(await page.getAttribute('[data-testid=recite-mic]', 'aria-label'), 'Start reciting');
  assert.deepEqual(errors, []);
  await context.close();
});

test('Recite & review is off until turned on in Settings', async () => {
  const { page, context } = await openApp(browser, server, { path: 'recite', learned: [6222] });
  assert.match(await page.textContent('#view'), /Recite & review is a beta/);
  await context.close();
});
