// Accounts and user management: the first account administers the server, data follows the
// account between devices, and administrators manage everyone else.

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

const learnedIds = (page) => page.evaluate(() => [...window.__app.store.learned].sort((a, b) => a - b));
/** Saves pending changes now and waits until the server has them. */
const synced = (page) =>
  page.evaluate(() => window.__app.store.flush()).then(() =>
    page.waitForFunction(() => window.__app.store.syncState === 'synced' && !window.__app.store.dirty.size, null, { timeout: 10000 }));

async function signIn(page, username, password, { register = false } = {}) {
  await page.click('[data-testid=nav-account]');
  await page.waitForSelector('[data-testid=auth-submit]');
  if (register && (await page.textContent('[data-testid=auth-submit]')) !== 'Create account') await page.click('[data-testid=auth-switch]');
  await page.fill('[data-testid=username]', username);
  await page.fill('[data-testid=password]', password);
  await page.click('[data-testid=auth-submit]');
}

test('the first account becomes the administrator and keeps what was marked as a guest', async () => {
  const { page, context, errors } = await openApp(browser, server, { path: 'surahs', query: '?test' });
  const guest = await ids(page, [[112, 1], [112, 2], [112, 3], [112, 4]]);
  await page.evaluate((l) => window.__app.store.setLearnedIds(l), guest);
  await signIn(page, 'admin', 'correct horse', { register: true });
  await page.waitForFunction(() => window.__app.store.user?.username === 'admin');
  assert.equal(await page.evaluate(() => window.__app.store.user.role), 'admin');
  await page.waitForSelector('[data-testid=nav-admin]');
  assert.deepEqual(await learnedIds(page), guest);
  await synced(page);
  assert.deepEqual(errors, []);
  await context.close();
});

test('an account brings its ayahs and settings to another device, and changes follow it', async () => {
  const a = await openApp(browser, server, { path: 'surahs', query: '?test' });
  await signIn(a.page, 'admin', 'correct horse');
  await a.page.waitForFunction(() => window.__app.store.user?.username === 'admin');
  assert.equal((await learnedIds(a.page)).length, 4);

  const b = await openApp(browser, server, { path: 'surahs', query: '?test' });
  await signIn(b.page, 'admin', 'correct horse');
  await b.page.waitForFunction(() => window.__app.store.learned.size === 4);

  // Change on device A…
  await a.page.goto(`${server.url}?test#/surah/114`);
  await a.page.click('[data-testid=mark-all]');
  await a.page.evaluate(() => window.__app.store.setSetting('reciter', 'ABDUL_BASIT'));
  await synced(a.page);
  // …device B picks it up when it comes back to the foreground.
  await b.page.evaluate(() => window.__app.store.pull());
  assert.equal((await learnedIds(b.page)).length, 10);
  assert.equal(await b.page.evaluate(() => window.__app.store.settings.reciter), 'ABDUL_BASIT');

  // A device that was used as a guest is offered to add its own ayahs on sign-in.
  const c = await openApp(browser, server, { path: 'surahs', query: '?test' });
  const fatihah = await ids(c.page, [[1, 1], [1, 2]]);
  await c.page.evaluate((l) => window.__app.store.setLearnedIds(l), fatihah);
  await signIn(c.page, 'admin', 'correct horse');
  const merge = c.page.locator('.dialog', { hasText: 'Add this browser' });
  await merge.waitFor();
  await merge.locator('button', { hasText: 'Add 2 ayahs' }).click();
  await c.page.waitForFunction(() => window.__app.store.learned.size === 12);
  await synced(c.page);

  for (const x of [a, b, c]) {
    assert.deepEqual(x.errors, []);
    await x.context.close();
  }
});

test('administrators create users, close registration, disable, reset passwords and delete', async () => {
  const admin = await openApp(browser, server, { path: 'surahs', query: '?test' });
  await signIn(admin.page, 'admin', 'correct horse');
  await admin.page.waitForSelector('[data-testid=nav-admin]');
  await admin.page.click('[data-testid=nav-admin]');
  await admin.page.waitForSelector('[data-testid=user-admin]');
  await admin.page.fill('[data-testid=new-username]', 'student');
  await admin.page.fill('[data-testid=new-password]', 'student-pass-1');
  await admin.page.click('[data-testid=create-user]');
  await admin.page.waitForSelector('[data-testid=user-student]');
  // The admin cannot demote, disable or delete themself.
  assert.equal(await admin.page.locator('[data-testid=user-admin] button', { hasText: 'Disable' }).isDisabled(), true);

  // Close registration: new visitors can only sign in.
  await admin.page.click('[data-testid=registration-open]');
  await admin.page.waitForFunction(async () => !(await (await fetch('api/health')).json()).registrationOpen);
  const visitor = await openApp(browser, server, { path: 'account', query: '?test' });
  await visitor.page.waitForSelector('[data-testid=auth-submit]');
  assert.match(await visitor.page.textContent('#view'), /Registration is closed on this server/);

  // The student signs in with the account the admin made.
  await signIn(visitor.page, 'student', 'student-pass-1');
  await visitor.page.waitForFunction(() => window.__app.store.user?.username === 'student');
  assert.equal(await visitor.page.locator('[data-testid=nav-admin]').count(), 0);

  // Disabling signs the student out everywhere and blocks sign-in.
  await admin.page.click('[data-testid=user-student] >> button:has-text("Disable")');
  await admin.page.waitForSelector('[data-testid=user-student] >> text=Disabled');
  await visitor.page.evaluate(() => window.__app.store.pull());
  await visitor.page.waitForFunction(() => window.__app.store.user === null);
  await signIn(visitor.page, 'student', 'student-pass-1');
  await visitor.page.waitForSelector('[data-testid=auth-error]:not([hidden])');
  assert.match(await visitor.page.textContent('[data-testid=auth-error]'), /disabled by an administrator/);

  // Re-enable and reset the password.
  await admin.page.click('[data-testid=user-student] >> button:has-text("Enable")');
  await admin.page.waitForSelector('[data-testid=user-student] >> button:has-text("Disable")');
  await admin.page.click('[data-testid=user-student] >> button:has-text("Reset password")');
  await admin.page.fill('.dialog input[type=password]', 'brand-new-pass');
  await admin.page.click('.dialog button:has-text("Reset password")');
  await admin.page.waitForSelector('.toast:has-text("Password reset")');
  await visitor.page.fill('[data-testid=password]', 'brand-new-pass');
  await visitor.page.click('[data-testid=auth-submit]');
  await visitor.page.waitForFunction(() => window.__app.store.user?.username === 'student');

  // Make the student an admin, then back.
  await admin.page.click('[data-testid=user-student] >> button:has-text("Make admin")');
  await admin.page.waitForSelector('[data-testid=user-student] >> button:has-text("Make user")');
  await admin.page.click('[data-testid=user-student] >> button:has-text("Make user")');
  await admin.page.waitForSelector('[data-testid=user-student] >> button:has-text("Make admin")');

  // Delete.
  await admin.page.click('[data-testid=user-student] >> button[aria-label="Delete student"]');
  await admin.page.click('.dialog button:has-text("Delete")');
  await admin.page.waitForSelector('[data-testid=user-student]', { state: 'detached' });

  // Reopen registration for the tests that follow.
  await admin.page.click('[data-testid=registration-open]');
  await admin.page.waitForFunction(async () => (await (await fetch('api/health')).json()).registrationOpen);

  for (const x of [admin, visitor]) {
    assert.deepEqual(x.errors, []);
    await x.context.close();
  }
});

test('users change their password, sign out, and delete their own account', async () => {
  const { page, context, errors } = await openApp(browser, server, { path: 'surahs', query: '?test' });
  await signIn(page, 'reader', 'first-password', { register: true });
  await page.waitForURL(/#\/player$/); // signing in lands on the player
  assert.equal(await page.evaluate(() => window.__app.store.user.role), 'user');

  await page.click('[data-testid=nav-account]');
  await page.fill('input[autocomplete=current-password]', 'first-password');
  await page.fill('form input[autocomplete=new-password]', 'second-password');
  await page.click('button:has-text("Change password")');
  await page.waitForSelector('.toast:has-text("Password changed")');

  await page.click('[data-testid=sign-out]');
  await page.waitForFunction(() => window.__app.store.user === null);
  await page.fill('[data-testid=username]', 'reader');
  await page.fill('[data-testid=password]', 'first-password');
  await page.click('[data-testid=auth-submit]');
  await page.waitForSelector('[data-testid=auth-error]:not([hidden])');
  await page.fill('[data-testid=password]', 'second-password');
  await page.click('[data-testid=auth-submit]');
  await page.waitForURL(/#\/player$/);

  await page.click('[data-testid=nav-account]');
  await page.click('button:has-text("Delete account")');
  await page.fill('.dialog input[type=password]', 'second-password');
  await page.click('.dialog button:has-text("Delete account")');
  await page.waitForFunction(() => window.__app.store.user === null);
  const res = await page.evaluate(() => fetch('api/auth/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'X-Requested-With': 'learned-ayahs' },
    body: JSON.stringify({ username: 'reader', password: 'second-password' }),
  }).then((r) => r.status));
  assert.equal(res, 401);
  assert.deepEqual(errors, []);
  await context.close();
});
