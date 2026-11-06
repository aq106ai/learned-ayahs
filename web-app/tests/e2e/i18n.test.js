// The interface language: chosen in Settings, applied on reload, right to left for Urdu and Arabic.
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

const layout = (page) => page.evaluate(() => ({
  lang: document.documentElement.lang,
  dir: document.documentElement.dir,
  overflow: document.documentElement.scrollWidth - document.documentElement.clientWidth,
  nav: [...document.querySelectorAll('#nav a span')].map((s) => s.textContent),
}));

test('choosing Urdu in Settings reloads the app in Urdu, right to left, without sideways scrolling', async () => {
  const { page, context, errors } = await openApp(browser, server, { path: 'settings', query: '?test' });
  assert.equal((await layout(page)).dir, 'ltr');
  await Promise.all([page.waitForEvent('load'), page.click('[data-testid=ui-language-ur]')]);
  await page.waitForSelector('#view > :not(.boot)');
  const l = await layout(page);
  assert.deepEqual([l.lang, l.dir, l.overflow], ['ur', 'rtl', 0]);
  assert.ok(l.nav.includes('سیٹنگز'), l.nav.join(','));
  assert.equal(await page.getAttribute('[data-testid=ui-language-ur]', 'aria-checked'), 'true');
  // Surah names in Arabic script; the reference parser still reads Latin names.
  await page.evaluate(async (l2) => window.__app.store.setLearnedIds(l2), await ids(page, [[112, 1]]));
  await page.click('[data-testid=nav-surahs]');
  await page.waitForFunction(() => document.body.textContent.includes('الإخلاص'));
  // Back to English, and the choice sticks across reloads.
  await page.click('[data-testid=nav-settings]');
  await Promise.all([page.waitForEvent('load'), page.click('[data-testid=ui-language-en]')]);
  await page.waitForSelector('#view > :not(.boot)');
  assert.deepEqual((await layout(page)).dir, 'ltr');
  assert.deepEqual(errors, []);
  await context.close();
});

test('the browser language is followed until one is chosen, at phone width too', async () => {
  const context = await browser.newContext({ serviceWorkers: 'block', locale: 'ar-EG', viewport: { width: 390, height: 844 } });
  const { page, errors } = await openApp(browser, server, { path: 'player', query: '?test', context: await (async () => {
    await context.addInitScript(() => {
      if (!localStorage.getItem('la:v1:guest')) localStorage.setItem('la:v1:guest', JSON.stringify({ learned: [], bookmarks: [], settings: { seenIntro: true }, progress: {} }));
    });
    return context;
  })() });
  const l = await layout(page);
  assert.deepEqual([l.lang, l.dir, l.overflow], ['ar', 'rtl', 0]);
  assert.equal(await page.locator('[data-testid=ui-language-browser]').count(), 0, 'no picker on the player');
  assert.deepEqual(errors, []);
  await context.close();
});
