// Revise by section: a full screen of every run of consecutive learned ayahs, numbered within
// its surah, each peeking at its first word and able to send the player there. Offered only
// while revising learned ayahs.

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

const ref = (page) => page.evaluate(() => {
  const t = window.__app.engine.track;
  return t ? `${t.surah}:${t.ayah}` : null;
});

const count = (page, selector) => page.evaluate((s) => document.querySelectorAll(s).length, selector);

async function playerWith(refs, settings = {}) {
  const { page, context, errors } = await openApp(browser, server, { path: 'player', query: '?test', settings });
  await page.evaluate((l) => window.__app.store.setLearnedIds(l), await ids(page, refs));
  await page.evaluate(() => window.__app.engine.restore());
  return { page, context, errors };
}

// It opens from the surahs panel, beside Manage learned ayahs.
const openSections = async (page) => {
  await page.click('[data-testid=open-surah-panel]');
  await page.click('[data-testid=open-sections]');
  await page.waitForSelector('[data-testid=sections-screen]');
};

// 112:1-2 and 112:4 are two sections in Al-Ikhlas; 113:1 is one on its own.
const LEARNED = [[112, 1], [112, 2], [112, 4], [113, 1]];

test('the screen lists every section, numbered within its surah', async () => {
  const { page, context, errors } = await playerWith(LEARNED);
  await openSections(page);

  assert.equal(await page.textContent('[data-testid=sections-screen] .muted.small'), '2 surahs · 3 sections');
  const rows = await page.evaluate(() => [...document.querySelectorAll('.section-row')].map((el) => ({
    key: el.dataset.section,
    num: el.querySelector('.section-num').textContent,
    text: el.querySelector('.section-range').textContent,
    sub: el.querySelector('.sub')?.textContent ?? null,
  })));
  assert.deepEqual(rows, [
    { key: '112:1-2', num: '1', text: 'Ayahs 1–2', sub: '2 ayahs' },
    // A section of one ayah is named as that ayah, with no "1 ayahs" beside it.
    { key: '112:4-4', num: '2', text: 'Ayah 4', sub: null },
    // Numbering restarts in each surah.
    { key: '113:1-1', num: '1', text: 'Ayah 1', sub: null },
  ]);
  assert.equal(await page.textContent('details[data-surah="112"] summary .sub'), '2 sections');

  // The surah being revised is open; the rest are listed collapsed, counted in the singular.
  assert.equal(await page.textContent('details[data-surah="113"] summary .sub'), '1 section');
  assert.equal(await page.evaluate(() => document.querySelector('details[data-surah="112"]').open), true);
  assert.equal(await page.evaluate(() => document.querySelector('details[data-surah="113"]').open), false);
  assert.equal(await page.isVisible('[data-section="113:1-1"]'), false);

  assert.deepEqual(errors, []);
  await context.close();
});

test('it fills the window rather than sitting at one edge', async () => {
  const { page, context, errors } = await playerWith(LEARNED);
  await openSections(page);

  const fits = await page.evaluate(() => {
    const panel = document.querySelector('.panel');
    const box = panel.getBoundingClientRect();
    return { full: panel.classList.contains('full'), width: Math.round(box.width), window: window.innerWidth };
  });
  assert.equal(fits.full, true);
  assert.equal(fits.width, fits.window);

  assert.deepEqual(errors, []);
  await context.close();
});

test('a section peeks at its first word, and hides it again', async () => {
  const { page, context, errors } = await playerWith(LEARNED);
  await openSections(page);

  const word = '[data-testid="word-112:1-2"]';
  assert.equal(await page.isVisible(word), false);

  await page.click('[data-testid="peek-112:1-2"]');
  await page.waitForSelector(`${word}:not([hidden])`);
  // The first word of 112:1, and only that word.
  const shown = (await page.textContent(word)).trim();
  assert.ok(shown.length > 0, 'the first word should be shown');
  assert.ok(!shown.includes(' '), `expected one word, got ${shown}`);
  assert.equal(await page.getAttribute('[data-testid="peek-112:1-2"]', 'aria-pressed'), 'true');
  // Only the section that was asked about.
  assert.equal(await page.isVisible('[data-testid="word-112:4-4"]'), false);

  await page.click('[data-testid="peek-112:1-2"]');
  assert.equal(await page.isVisible(word), false);

  assert.deepEqual(errors, []);
  await context.close();
});

test('Go to sends the player to the section and closes the screen', async () => {
  const { page, context, errors } = await playerWith(LEARNED);
  await openSections(page);

  await page.click('[data-testid="goto-112:4-4"]');
  await page.waitForFunction(() => window.__app.engine.track?.ayah === 4);
  assert.equal(await ref(page), '112:4');
  assert.equal(await count(page, '[data-testid=sections-screen]'), 0);

  // The reader is the ordinary one: the screen never hid the ayah.
  await page.waitForSelector('[data-testid=ayah-text]');

  assert.deepEqual(errors, []);
  await context.close();
});

test('it is offered only while revising learned ayahs', async () => {
  const { page, context, errors } = await playerWith(LEARNED);
  await page.click('[data-testid=open-surah-panel]');
  await page.waitForSelector('[data-testid=open-sections]');
  await page.keyboard.press('Escape');

  await page.click('[data-testid=open-actions-menu]');
  await page.click('[data-testid=mode-FULL_SURAH]');
  await page.waitForFunction(() => window.__app.engine.mode === 'FULL_SURAH');

  // Full surah streams every ayah of the surah, marked or not: nothing to divide into sections.
  await page.click('[data-testid=open-surah-panel]');
  await page.waitForSelector('[data-testid=manage-link], .panel');
  assert.equal(await count(page, '[data-testid=open-sections]'), 0);

  assert.deepEqual(errors, []);
  await context.close();
});

test('one section is nothing to pick between, so it is not offered', async () => {
  // A single unbroken stretch is the whole learned list: the screen would say nothing new.
  const { page, context, errors } = await playerWith([[112, 1], [112, 2], [112, 3]]);
  await page.click('[data-testid=open-surah-panel]');
  await page.waitForSelector('.panel');
  assert.equal(await count(page, '[data-testid=open-sections]'), 0);

  assert.deepEqual(errors, []);
  await context.close();
});

test('the surahs panel says how much of each surah is marked', async () => {
  const { page, context, errors } = await playerWith(LEARNED);
  await page.click('[data-testid=open-surah-panel]');
  await page.waitForSelector('.panel-body .list-row');
  const subtitles = await page.evaluate(() => [...document.querySelectorAll('.panel-body .list-row')]
    .slice(-3).map((el) => el.querySelector('.sub')?.textContent));
  // Al-Ikhlas has 4 ayahs and 3 marked; An-Nas has nothing marked, so it shows its length.
  assert.deepEqual(subtitles, ['3 of 4 learned', '1 of 5 learned', '6 ayahs']);

  assert.deepEqual(errors, []);
  await context.close();
});
