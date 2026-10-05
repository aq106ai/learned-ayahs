// Guest (no account) flows: first run, marking ayahs, add by description, Android import/export,
// settings. Everything stays in the browser.

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

const learnedIds = (page) => page.evaluate(() => [...window.__app.store.learned].sort((a, b) => a - b));

test('first run shows the welcome, then lands on the surah list; it is not shown again', async () => {
  const { page, context, errors } = await openApp(browser, server, { seenIntro: false, query: '?test' });
  await page.waitForSelector('[data-testid=intro]');
  assert.match(await page.textContent('[data-testid=intro]'), /Keep refreshing your knowledge/);
  await page.click('[data-testid=intro-next]');
  await page.click('[data-testid=intro-next]');
  await page.click('[data-testid=intro-next]'); // "Begin"
  await page.waitForSelector('[data-testid=intro]', { state: 'detached' });
  assert.equal(new URL(page.url()).hash, '#/surahs');
  await page.reload();
  await page.waitForSelector('#view > :not(.boot)');
  assert.equal(await page.locator('[data-testid=intro]').count(), 0);
  assert.deepEqual(errors, []);
  await context.close();
});

test('marking ayahs in a surah persists and shows in the surah list', async () => {
  const { page, context, errors } = await openApp(browser, server, { path: 'surah/112', query: '?test' });
  await page.click('[data-testid=learn-1]');
  await page.click('[data-testid=learn-3]');
  assert.deepEqual(await learnedIds(page), await ids(page, [[112, 1], [112, 3]]));
  await page.goto(`${server.url}?test#/surahs`);
  await page.waitForSelector('[data-testid=surah-112]');
  assert.match(await page.textContent('[data-testid=surah-112]'), /2 learned/);
  // Survives a reload (saved in this browser).
  await page.reload();
  await page.waitForSelector('[data-testid=surah-112]');
  assert.deepEqual(await learnedIds(page), await ids(page, [[112, 1], [112, 3]]));
  // The surah list's check marks a whole surah.
  await page.click('[data-testid=surah-113] .check-btn');
  assert.equal((await learnedIds(page)).length, 2 + 5);
  assert.deepEqual(errors, []);
  await context.close();
});

test('add by description marks references and surah names', async () => {
  const { page, context, errors } = await openApp(browser, server, { path: 'add', query: '?test' });
  await page.fill('[data-testid=description-field]', '2:255, Al-Ikhlas, 114:1-3, nonsense');
  assert.match(await page.textContent('[data-testid=description-preview]'), /Will mark 8 ayahs · 8 new/);
  await page.click('[data-testid=description-apply]');
  assert.deepEqual(await learnedIds(page), await ids(page, [[2, 255], [112, 1], [112, 2], [112, 3], [112, 4], [114, 1], [114, 2], [114, 3]]));
  assert.match(await page.textContent('[data-testid=current-selection]'), /8 ayahs in 3 surahs/);
  assert.deepEqual(errors, []);
  await context.close();
});

test('imports a file exported by the Android app, and exports one the Android app can read', async () => {
  const { page, context, errors } = await openApp(browser, server, { path: 'surahs', query: '?test' });
  // Exactly what LearnedAyahsExport.kt writes.
  const android = JSON.stringify({ format: 'learned-ayahs', version: 1, exportedAt: '2026-07-17T06:00:00Z', count: 12, ayahs: ['1', '2:255', '112:1-4'] });
  const chooser = page.waitForEvent('filechooser');
  await page.click('[data-testid=import]');
  await (await chooser).setFiles({ name: 'learned-ayahs-2026-07-17.json', mimeType: 'application/json', buffer: Buffer.from(android) });
  const dialog = page.locator('.dialog');
  await dialog.waitFor();
  assert.match(await dialog.textContent(), /a Learned Ayahs export/);
  assert.match(await dialog.textContent(), /12 ayahs, 12 of them not marked yet/);
  await dialog.locator('button', { hasText: 'Add 12 ayahs' }).click();
  const expected = await ids(page, [[1, 1], [1, 2], [1, 3], [1, 4], [1, 5], [1, 6], [1, 7], [2, 255], [112, 1], [112, 2], [112, 3], [112, 4]]);
  assert.deepEqual(await learnedIds(page), expected);

  const download = page.waitForEvent('download');
  await page.click('[data-testid=export]');
  const file = await download;
  assert.match(file.suggestedFilename(), /^LearnedAyahs-\d{4}-\d{2}-\d{2}\.json$/);
  const exported = JSON.parse(readFileSync(await file.path(), 'utf8'));
  assert.equal(exported.format, 'learned-ayahs');
  assert.equal(exported.version, 1);
  assert.equal(exported.count, 12);
  assert.deepEqual(exported.ayahs, ['1', '2:255', '112']);
  assert.deepEqual(errors, []);
  await context.close();
});

test('settings: theme and reciter apply immediately and persist', async () => {
  const { page, context, errors } = await openApp(browser, server, { path: 'settings', query: '?test' });
  await page.click('[data-testid=theme-LIGHT]');
  assert.equal(await page.getAttribute('html', 'data-theme'), 'light');
  await page.click('[data-testid=reciter-AL_HUSARY]');
  await page.reload();
  await page.waitForSelector('[data-testid=theme-LIGHT]');
  assert.equal(await page.getAttribute('html', 'data-theme'), 'light');
  assert.equal(await page.getAttribute('[data-testid=reciter-AL_HUSARY]', 'aria-checked'), 'true');
  assert.equal(await page.evaluate(() => window.__app.store.settings.reciter), 'AL_HUSARY');
  assert.deepEqual(errors, []);
  await context.close();
});

test('unknown routes fall back to the surah list when nothing is marked', async () => {
  const { page, context } = await openApp(browser, server, { path: 'nowhere' });
  assert.equal(new URL(page.url()).hash, '#/surahs');
  await context.close();
});
