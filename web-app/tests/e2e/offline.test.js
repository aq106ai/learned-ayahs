// Offline: with the service worker on, the app starts without a connection, plays the ayahs
// saved for offline, and a signed-in user keeps their account — changes reach the server once
// the connection is back.

import { after, before, test } from 'node:test';
import assert from 'node:assert/strict';
import { ids, launchBrowser, startServer } from './harness.js';

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

async function freshContext() {
  const context = await browser.newContext({ serviceWorkers: 'allow' });
  await context.addInitScript(() => {
    if (!localStorage.getItem('la:v1:guest')) localStorage.setItem('la:v1:guest', JSON.stringify({ settings: { seenIntro: true } }));
  });
  const page = await context.newPage();
  const errors = [];
  page.on('pageerror', (err) => errors.push(err.message));
  await page.goto(`${server.url}?test#/surahs`);
  await page.waitForSelector('[data-testid=surah-list]');
  await page.evaluate(() => navigator.serviceWorker.ready);
  await page.reload(); // now under the service worker
  await page.waitForSelector('[data-testid=surah-list]');
  return { context, page, errors };
}

async function saveOffline(page) {
  await page.click('[data-testid=nav-settings]');
  await page.click('[data-testid=save-offline]');
  await page.waitForSelector('.toast:has-text("Saved for offline use")');
}

async function goOfflineAndReload(context, page) {
  await context.setOffline(true);
  await page.reload();
  await page.waitForSelector('#view > :not(.boot)');
}

async function playsFromCache(page) {
  await page.click('[data-testid=nav-player]');
  await page.waitForSelector('[data-testid=reader]');
  await page.click('[data-testid=play]');
  await page.waitForFunction(() => window.__app.engine.playing && !window.__app.engine.intro, null, { timeout: 15000 });
  const e = await page.evaluate(() => ({ src: window.__app.engine.audio.dataset.src, error: window.__app.engine.error }));
  assert.match(e.src, /^audio\/everyayah\//, 'played from the saved copy, not the internet');
  assert.equal(e.error, null);
  await page.click('[data-testid=play]');
}

test('a guest starts offline and plays the ayahs saved for offline', async () => {
  const { context, page, errors } = await freshContext();
  await page.evaluate(async (l) => window.__app.store.setLearnedIds(l), await ids(page, [[112, 1], [112, 2]]));
  await saveOffline(page);
  assert.match(await page.textContent('[data-testid=offline-status]'), /Saved offline: 2 of 2/);
  await goOfflineAndReload(context, page);
  assert.equal(await page.evaluate(() => window.__app.store.learned.size), 2);
  await playsFromCache(page);
  assert.deepEqual(errors, []);
  await context.close();
});

test('a signed-in user stays signed in offline, and offline changes sync when back online', async () => {
  const { context, page, errors } = await freshContext();
  await page.click('[data-testid=nav-account]');
  await page.click('[data-testid=auth-switch]');
  await page.fill('[data-testid=username]', 'traveller');
  await page.fill('[data-testid=password]', 'offline-pass');
  await page.click('[data-testid=auth-submit]');
  await page.waitForURL(/#\/player$/);
  await page.evaluate(async (l) => window.__app.store.setLearnedIds(l), await ids(page, [[112, 1]]));
  await page.evaluate(() => window.__app.store.flush());
  await saveOffline(page);

  await goOfflineAndReload(context, page);
  const s = await page.evaluate(() => ({ user: window.__app.store.user?.username, sync: window.__app.store.syncState, n: window.__app.store.learned.size }));
  assert.deepEqual(s, { user: 'traveller', sync: 'offline', n: 1 });
  await playsFromCache(page);

  // Mark more while offline…
  await page.evaluate(async (l) => window.__app.store.addLearned(l), await ids(page, [[2, 255]]));
  await page.waitForFunction(() => window.__app.store.syncState === 'offline' && window.__app.store.dirty.size > 0);
  // …and the server gets it once the connection returns.
  await context.setOffline(false);
  await page.waitForFunction(() => window.__app.store.syncState === 'synced' && !window.__app.store.dirty.size, null, { timeout: 20000 });
  const onServer = await page.evaluate(() => fetch('api/data').then((r) => r.json()).then((d) => d.learned.length));
  assert.equal(onServer, 2);
  assert.deepEqual(errors, []);
  await context.close();
});
