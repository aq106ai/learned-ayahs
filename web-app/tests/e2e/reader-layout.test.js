import { after, before, test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdirSync } from 'node:fs';
import { ids, launchBrowser, openApp, startServer } from './harness.js';
let browser, server;
before(async () => { server = await startServer(); browser = await launchBrowser(); });
after(async () => { await browser?.close(); await server?.stop(); });
const settle = page => page.evaluate(async () => {
  await document.fonts.ready;
  await new Promise(r => requestAnimationFrame(() => requestAnimationFrame(r)));
});
for (const mode of ['AUTO_SCROLL', 'FIT_TO_SCREEN']) {
  test(`${mode}: first and last words stay reachable across window sizes`, async () => {
    const { page, context, errors } = await openApp(browser, server, { path: 'player', query: '?test', settings: { ayahOverflowMode: mode, showWordTranslations: true, showAyahTranslation: true, tapToAdvance: false } });
    try {
      await page.evaluate(l => window.__app.store.setLearnedIds(l), await ids(page, [[2,282], [112,2]]));
      await page.evaluate(() => window.__app.engine.restore());
      await page.waitForSelector('[data-testid=ayah-text] .word');
      for (const [width,height] of [[320,640],[360,740],[411,891],[600,700],[840,600],[1024,768],[740,360]]) {
        await page.setViewportSize({width,height});
        await page.evaluate(({width}) => {
          document.documentElement.style.fontSize = width === 600 ? '200%' : '';
          window.__app.store.setSetting('playerBackground', width === 840 ? 'night' : 'plain');
        }, {width});
        await settle(page);
        const edges = await page.evaluate(() => {
          const reader = document.querySelector('.reader');
          reader.style.scrollBehavior = 'auto';
          reader.scrollTop = 0;
          const bounds = reader.getBoundingClientRect();
          const first = reader.querySelector('.word .ar').getBoundingClientRect();
          const pageTop = window.scrollY;
          reader.scrollTop = reader.scrollHeight;
          const last = [...reader.querySelectorAll('.word .ar')].at(-1).getBoundingClientRect();
          return { top: bounds.top, bottom: bounds.bottom, height:bounds.height, first:first.top, last:last.bottom, pageTop, pageAfter:window.scrollY,
            headBottom:document.querySelector('.player-head').getBoundingClientRect().bottom,
            footerTop:document.querySelector('.controls').getBoundingClientRect().top };
        });
        assert.ok(edges.height > 60, JSON.stringify({width,height,...edges}));
        assert.ok(edges.first >= edges.top - 1, `first word clipped: ${JSON.stringify({width,height,...edges})}`);
        assert.ok(edges.last <= edges.bottom + 1, `last word clipped: ${JSON.stringify({width,height,...edges})}`);
        assert.ok(edges.top >= edges.headBottom - 1 && edges.bottom <= edges.footerTop + 1);
        assert.equal(edges.pageTop, edges.pageAfter);
      }
      await page.setViewportSize({width:360,height:740});
      await page.click('[data-testid=expand-controls]');
      await settle(page);
      await page.evaluate(() => { document.querySelector('.reader').scrollTop = 0; });
      mkdirSync('test-results', {recursive:true});
      await page.screenshot({path:`test-results/reader-${mode}.png`});
      await page.evaluate(() => window.__app.engine.next());
      await settle(page);
      assert.equal(await page.locator('.reader').evaluate(el => el.scrollTop), 0, 'new ayah resets scrolling');
      assert.deepEqual(errors, []);
    } finally { await context.close(); }
  });
}
test('auto-follow scrolls only the reader, not the page or header', async () => {
  const {page,context,errors} = await openApp(browser,server,{path:'player',query:'?test'});
  try {
    await page.setViewportSize({width:360,height:640});
    await page.evaluate(l => window.__app.store.setLearnedIds(l), await ids(page,[[2,282]]));
    await page.evaluate(() => window.__app.engine.restore());
    await page.waitForSelector('.ayah-flow .word');
    await settle(page);
    const top = await page.locator('.player-head').evaluate(el => el.getBoundingClientRect().top);
    await page.evaluate(() => {
      const e=window.__app.engine;
      Object.defineProperty(e,'activeWord',{configurable:true,get:()=>e.contentWords.length-1});
      e.playing=true; e.emit('tick');
    });
    await page.waitForFunction(() => document.querySelector('.reader').scrollTop > 0);
    assert.equal(await page.locator('.player-head').evaluate(el => el.getBoundingClientRect().top),top);
    assert.deepEqual(errors,[]);
  } finally { await context.close(); }
});


test('Mode and Actions stay reachable; View changes presentation without starting playback', async () => {
  const {page,context,errors} = await openApp(browser,server,{path:'player',query:'?test'});
  try {
    await page.evaluate(l => window.__app.store.setLearnedIds(l), await ids(page,[[112,2]]));
    await page.evaluate(() => window.__app.engine.restore());
    for (const [width,height] of [[320,640],[740,360],[600,800]]) {
      await page.setViewportSize({width,height});
      await page.evaluate(() => window.__app.engine.setMode('REVISE'));
      await page.click('[data-testid=open-actions-menu]');
      // One mode item, named for the mode it switches to.
      assert.equal(await page.locator('.player-menu-items [data-testid^=mode-]').count(),1);
      await page.click('[data-testid=mode-FULL_SURAH]');
      await page.waitForFunction(() => window.__app.engine.queue.length === 4);
      await page.click('[data-testid=open-actions-menu]');
      assert.equal(await page.locator('[data-testid=mode-REVISE]').count(),1);
      await page.click('[data-testid=toggle-word-by-word]');
      await page.waitForSelector('[data-testid=word-reader]');
      assert.equal(await page.evaluate(() => window.__app.engine.mode),'FULL_SURAH');
      assert.equal(await page.evaluate(() => window.__app.engine.isActive),false);
      await page.click('[data-testid=open-actions-menu]');
      await page.click('[data-testid=view-ayah]');
      await page.waitForSelector('[data-testid=ayah-text]');
      await page.click('[data-testid=open-actions-menu]');
      for (const id of ['tafsir','share','open-background','player-settings']) {
        await page.locator(`[data-testid=${id}]`).scrollIntoViewIfNeeded();
        const box = await page.locator(`[data-testid=${id}]`).boundingBox();
        assert.ok(box && box.x >= 0 && box.y >= 0 && box.x + box.width <= width + 1 && box.y + box.height <= height + 1, `${id}: ${JSON.stringify(box)}`);
      }
      await page.keyboard.press('Escape');
      assert.equal(await page.locator('.player-menu[open]').count(),0);
    }
    await page.setViewportSize({width:360,height:740});
    await page.screenshot({path:'test-results/player-menu-review.png'});
    assert.deepEqual(errors,[]);
  } finally { await context.close(); }
});
