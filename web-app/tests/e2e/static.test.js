// The static build (server.py --export-static) on a plain web server: no accounts, everything
// kept in the browser, audio straight from its sources.

import { after, before, test } from 'node:test';
import assert from 'node:assert/strict';
import { spawn, spawnSync } from 'node:child_process';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { launchBrowser, openApp } from './harness.js';

const WEB_APP = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
let dir;
let proc;
let site;
let browser;

before(async () => {
  dir = mkdtempSync(join(tmpdir(), 'la-static-'));
  const out = join(dir, 'site');
  const res = spawnSync('python3', ['server.py', '--export-static', out], { cwd: WEB_APP, encoding: 'utf8' });
  assert.equal(res.status, 0, res.stderr);
  proc = spawn('python3', ['-m', 'http.server', '0', '--bind', '127.0.0.1', '--directory', out], { stdio: ['ignore', 'pipe', 'pipe'] });
  const port = await new Promise((resolve, reject) => {
    let text = '';
    const onData = (d) => {
      text += d;
      const m = text.match(/port (\d+)/);
      if (m) resolve(m[1]);
    };
    proc.stdout.on('data', onData);
    proc.stderr.on('data', onData);
    proc.on('exit', (code) => reject(new Error(`http.server exited (${code}): ${text}`)));
  });
  site = { url: `http://127.0.0.1:${port}/` };
  browser = await launchBrowser();
});

after(async () => {
  await browser?.close();
  proc?.kill();
  if (dir) rmSync(dir, { recursive: true, force: true });
});

test('the static build runs as a browser-only app', async () => {
  const { page, context, errors } = await openApp(browser, site, { path: 'surah/112', query: '?test' });
  assert.equal(await page.evaluate(() => window.__app.server.available), false);
  assert.equal(await page.locator('[data-testid=nav-account]').count(), 0, 'no accounts without the server');
  await page.click('[data-testid=learn-1]');
  assert.equal(await page.evaluate(() => window.__app.store.learned.size), 1);
  // Audio comes straight from everyayah.com, not a server cache.
  assert.match(await page.evaluate(() => window.__app.engine.urls.audhu()), /^https:\/\/everyayah\.com\//);
  await page.goto(`${site.url}?test#/account`);
  await page.waitForSelector('#view > :not(.boot)');
  assert.equal(new URL(page.url()).hash, '#/account');
  assert.match(await page.textContent('#view'), /This browser only/);
  // The static server answers /api/* with 404s; those are how the app knows it is alone.
  assert.deepEqual(errors.filter((e) => !/404/.test(e)), []);
  await context.close();
});
