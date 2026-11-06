// Translations, word meanings and tafsir choices: picked in the intro and Settings, downloaded
// from (a stub of) Quran.com, kept for offline use, and shown in the player. No network: every
// request to api.quran.com is answered here.

import { after, before, test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { ids, launchBrowser, openApp, startServer } from './harness.js';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const ASSETS = path.resolve(HERE, '../../../android-app-quran-player/app/src/main/assets');
const TEXT = JSON.parse(readFileSync(path.join(ASSETS, 'quran_text.json'), 'utf8'));
const VERSE_KEYS = Object.keys(TEXT);

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

/**
 * Answers api.quran.com like the real one, in the shapes the app reads. `requests` counts what
 * was asked for; `fail` makes translation downloads fail.
 */
function quranComStub({ fail = false, failTafsir = false } = {}) {
  const requests = [];
  const setup = (context) => context.route('https://api.quran.com/**', (route) => {
    const url = new URL(route.request().url());
    requests.push(url.pathname);
    const json = (body) => route.fulfill({ status: 200, contentType: 'application/json', headers: { 'access-control-allow-origin': '*' }, body: JSON.stringify(body) });
    let m = url.pathname.match(/\/quran\/translations\/(\d+)$/);
    if (m) {
      if (fail) return route.fulfill({ status: 503, headers: { 'access-control-allow-origin': '*' }, body: 'down' });
      return json({
        translations: VERSE_KEYS.map((k) => ({ resource_id: Number(m[1]), verse_key: k, text: `UR(${m[1]}) ${k}<sup foot_note=9>1</sup>` })),
      });
    }
    m = url.pathname.match(/\/tafsirs\/(\d+)\/by_ayah\/(\d+):(\d+)$/);
    if (m) {
      if (failTafsir) return route.fulfill({ status: 503, headers: { 'access-control-allow-origin': '*' }, body: 'down' });
      const [, id, s, a] = m;
      // Ibn Kathir explains Al-Ikhlas as one group; everything else stands alone.
      const verses = s === '112' ? ['112:1', '112:2', '112:3', '112:4'] : [`${s}:${a}`];
      return json({
        tafsir: {
          resource_id: Number(id),
          verses: Object.fromEntries(verses.map((k, i) => [k, { id: i }])),
          text: `<h2 style="color: red">Tafsir ${id} on ${s}:${a}</h2><p onclick="x()">First <strong>point</strong>.<script>window.__pwned = true</script></p><img src=x onerror="window.__pwned = true"><p>Second.</p>`,
        },
      });
    }
    m = url.pathname.match(/\/verses\/by_chapter\/(\d+)$/);
    if (m) {
      const surah = Number(m[1]);
      const lang = url.searchParams.get('language');
      const keys = VERSE_KEYS.filter((k) => k.startsWith(`${surah}:`));
      const page = Number(url.searchParams.get('page') ?? 1);
      const per = Number(url.searchParams.get('per_page') ?? 10);
      const slice = keys.slice((page - 1) * per, page * per);
      return json({
        verses: slice.map((k) => ({
          verse_key: k,
          words: TEXT[k].words.map((w) => ({
            position: w.position,
            char_type_name: w.char_type,
            translation: { text: `${lang}-${k}-${w.position}`, language_name: lang === 'ur' ? 'urdu' : lang },
          })),
        })),
        pagination: { next_page: page * per < keys.length ? page + 1 : null },
      });
    }
    return route.fulfill({ status: 404, headers: { 'access-control-allow-origin': '*' }, body: '{}' });
  });
  return { requests, setup };
}

async function learn(page, refs) {
  await page.evaluate((l) => window.__app.store.setLearnedIds(l), await ids(page, refs));
  await page.evaluate(() => window.__app.engine.restore());
}

test('the intro picks the translation, style, word-meaning language, tafsir and reciter; the player shows them', async () => {
  const stub = quranComStub();
  const { page, context, errors } = await openApp(browser, server, { seenIntro: false, query: '?test', setup: stub.setup });
  await page.waitForSelector('[data-testid=intro]');
  await page.click('[data-testid=intro-next]');
  await page.click('[data-testid=intro-next]');
  await page.waitForSelector('[data-testid=intro-content] [data-testid=content-translation]');
  // Saheeh International comes with the app.
  assert.equal(await page.inputValue('[data-testid=content-translation]'), 'en-saheeh');
  assert.match(await page.textContent('[data-testid=content-status]'), /works offline/);

  await page.selectOption('[data-testid=content-translation-language]', 'ur');
  // The first Urdu edition is chosen, and the word meanings follow the language.
  const edition = await page.inputValue('[data-testid=content-translation]');
  assert.match(edition, /^qc-\d+$/);
  assert.equal(await page.inputValue('[data-testid=content-word-language]'), 'ur');
  await page.selectOption('[data-testid=content-translation]', 'qc-54');
  await page.waitForFunction(() => /saved for offline/.test(document.querySelector('[data-testid=content-status]').textContent));
  await page.click('[data-testid=content-style-both]');
  await page.selectOption('[data-testid=content-tafsir]', 'qc-tafsir-160');
  await page.click('[data-testid=content-reciter-AS_SUDAIS]');
  for (let i = 0; i < 3; i++) await page.click('[data-testid=intro-next]');
  await page.waitForSelector('[data-testid=intro]', { state: 'detached' });

  const s = await page.evaluate(() => window.__app.store.settings);
  assert.deepEqual(
    [s.translationEdition, s.wordMeaningLanguage, s.tafsirEdition, s.reciter, s.showWordTranslations, s.showAyahTranslation],
    ['qc-54', 'ur', 'qc-tafsir-160', 'AS_SUDAIS', true, true],
  );

  await learn(page, [[112, 1]]);
  await page.goto(`${server.url}?test#/player`);
  await page.waitForSelector('[data-testid=ayah-translation]');
  await page.waitForFunction(() => document.querySelector('[data-testid=ayah-translation]').textContent === 'UR(54) 112:1');
  await page.waitForFunction(() => [...document.querySelectorAll('.tr')].map((e) => e.textContent).join('|') === 'ur-112:1-1|ur-112:1-2|ur-112:1-3|ur-112:1-4');
  assert.deepEqual(errors, []);
  await context.close();
});

test('a downloaded translation is kept: it shows again with the provider unreachable', async () => {
  const stub = quranComStub();
  const settings = { translationEdition: 'qc-54', showAyahTranslation: true, wordMeaningLanguage: 'ur' };
  const first = await openApp(browser, server, { path: 'player', query: '?test', settings, setup: stub.setup });
  await learn(first.page, [[112, 2]]);
  await first.page.waitForFunction(() => document.querySelector('[data-testid=ayah-translation]')?.textContent === 'UR(54) 112:2');
  await first.page.waitForFunction(() => document.querySelector('.tr')?.textContent === 'ur-112:2-1');
  await first.page.close();

  // Same browser profile, provider gone.
  await first.context.unroute('https://api.quran.com/**');
  const before = stub.requests.length;
  const offline = await openApp(browser, server, {
    path: 'player', query: '?test', context: first.context,
    setup: (c) => c.route('https://api.quran.com/**', (r) => r.abort()),
  });
  await offline.page.waitForFunction(() => document.querySelector('[data-testid=ayah-translation]')?.textContent === 'UR(54) 112:2');
  await offline.page.waitForFunction(() => document.querySelector('.tr')?.textContent === 'ur-112:2-1');
  assert.equal(stub.requests.length, before);
  assert.deepEqual([...first.errors, ...offline.errors], []);
  await first.context.close();
});

test('an edition that cannot download says so and shows Saheeh International meanwhile', async () => {
  const stub = quranComStub({ fail: true });
  const { page, context, errors } = await openApp(browser, server, { path: 'settings', query: '?test', setup: stub.setup, settings: { showAyahTranslation: true } });
  await page.waitForSelector('[data-testid=content-translation]');
  await page.selectOption('[data-testid=content-translation-language]', 'ur');
  await page.selectOption('[data-testid=content-translation]', 'qc-54');
  await page.waitForFunction(() => /Saheeh International is shown/.test(document.querySelector('[data-testid=content-status]').textContent));
  await learn(page, [[112, 1]]);
  await page.click('[data-testid=nav-player]');
  await page.waitForFunction(() => /^Say, "He is Allah/.test(document.querySelector('[data-testid=ayah-translation]')?.textContent ?? ''));
  assert.deepEqual(errors, []);
  await context.close();
});

test('Settings changes the style and the edition; the choice is saved as an id; old saved ids fall back', async () => {
  const stub = quranComStub();
  const { page, context, errors } = await openApp(browser, server, { path: 'settings', query: '?test', setup: stub.setup, settings: { translationEdition: 'qc-0' } });
  await page.waitForSelector('[data-testid=content-translation]');
  // An id the catalog no longer has shows as the default.
  assert.equal(await page.inputValue('[data-testid=content-translation]'), 'en-saheeh');
  await page.click('[data-testid=content-style-ayah]');
  assert.equal(await page.locator('[data-testid=content-word-language]').count(), 0, 'no word-meaning language without word meanings');
  let s = await page.evaluate(() => window.__app.store.settings);
  assert.deepEqual([s.showWordTranslations, s.showAyahTranslation], [false, true]);
  await page.click('[data-testid=content-style-words]');
  s = await page.evaluate(() => window.__app.store.settings);
  assert.deepEqual([s.showWordTranslations, s.showAyahTranslation], [true, false]);
  await page.selectOption('[data-testid=content-translation-language]', 'en');
  await page.selectOption('[data-testid=content-translation]', 'qc-85');
  assert.equal(await page.evaluate(() => window.__app.store.settings.translationEdition), 'qc-85');
  // The player's labels name the chosen edition.
  await page.click('[data-testid=content-style-both]');
  await learn(page, [[112, 1]]);
  await page.click('[data-testid=nav-player]');
  await page.waitForFunction(() => document.querySelector('[data-testid=ayah-translation]')?.textContent === 'UR(85) 112:1');
  assert.deepEqual(errors, []);
  await context.close();
});

test('Tafsir on the current ayah: the chosen edition, its source, the ayahs it covers, and no provider markup', async () => {
  const stub = quranComStub();
  const first = await openApp(browser, server, { path: 'player', query: '?test', settings: { tafsirEdition: 'qc-tafsir-169' }, setup: stub.setup });
  const { page } = first;
  await learn(page, [[112, 2]]);
  await page.click('[data-testid=tafsir]');
  await page.waitForSelector('[data-testid=tafsir-text]');
  assert.match(await page.textContent('[data-testid=tafsir-source]'), /Ibn Kathir \(Abridged\) by Hafiz Ibn Kathir, from Quran\.com/);
  assert.match(await page.textContent('[data-testid=tafsir-range]'), /112:1–4/);
  assert.equal(await page.textContent('[data-testid=tafsir-text] h3'), 'Tafsir 169 on 112:2');
  assert.equal(await page.textContent('[data-testid=tafsir-text] strong'), 'point');
  assert.deepEqual(await page.evaluate(() => {
    const el = document.querySelector('[data-testid=tafsir-text]');
    return {
      scripts: el.querySelectorAll('script, img, iframe').length,
      attrs: [...el.querySelectorAll('*')].flatMap((n) => [...n.attributes].map((a) => a.name)).filter((n) => n !== 'dir'),
      pwned: Boolean(window.__pwned),
    };
  }), { scripts: 0, attrs: [], pwned: false });
  await page.keyboard.press('Escape');
  await page.close();

  // Read once, kept: it opens again with the provider gone.
  await first.context.unroute('https://api.quran.com/**');
  const offline = await openApp(browser, server, { path: 'player', query: '?test', context: first.context, setup: (c) => c.route('https://api.quran.com/**', (r) => r.abort()) });
  await offline.page.click('[data-testid=tafsir]');
  await offline.page.waitForSelector('[data-testid=tafsir-text]');
  assert.equal(await offline.page.textContent('[data-testid=tafsir-text] h3'), 'Tafsir 169 on 112:2');
  assert.deepEqual([...first.errors, ...offline.errors], []);
  await first.context.close();
});

test('Tafsir with none chosen offers the list; a failed download can be retried', async () => {
  const stub = quranComStub({ failTafsir: true });
  const { page, context, errors } = await openApp(browser, server, { path: 'player', query: '?test', settings: { translationEdition: 'qc-54' }, setup: stub.setup });
  await learn(page, [[1, 2]]);
  await page.click('[data-testid=tafsir]');
  await page.waitForSelector('[data-testid=tafsir-none]');
  // Urdu reader: Urdu tafsirs first.
  const firstOption = await page.locator('[data-testid=tafsir-edition] option').nth(1).textContent();
  assert.match(firstOption, /^Urdu · /);
  await page.selectOption('[data-testid=tafsir-edition]', 'qc-tafsir-160');
  await page.waitForSelector('[data-testid=tafsir-error]');
  assert.equal(await page.evaluate(() => window.__app.store.settings.tafsirEdition), 'qc-tafsir-160');
  await context.unroute('https://api.quran.com/**');
  await quranComStub().setup(context);
  await page.click('[data-testid=tafsir-retry]');
  await page.waitForSelector('[data-testid=tafsir-text]');
  assert.equal(await page.getAttribute('[data-testid=tafsir-text]', 'dir'), 'rtl');
  assert.equal(await page.locator('[data-testid=tafsir-range]').count(), 0, 'a single ayah needs no range');
  assert.deepEqual(errors, []);
  await context.close();
});
