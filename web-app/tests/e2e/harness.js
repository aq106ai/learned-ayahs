// End-to-end test harness: runs server.py on a free port with a throwaway database and an audio
// cache pre-filled with short generated WAV clips (served under the .mp3 names the app asks for;
// the server sniffs the real type), so tests need no network and audio "plays" in headless Chromium.

import { spawn } from 'node:child_process';
import { mkdtempSync, mkdirSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';

const HERE = dirname(fileURLToPath(import.meta.url));
const WEB_APP = join(HERE, '..', '..');

/** A mono 16-bit PCM WAV of `seconds` of a quiet tone. */
export function wav(seconds, freq = 440) {
  const rate = 8000;
  const n = Math.round(seconds * rate);
  const buf = Buffer.alloc(44 + n * 2);
  buf.write('RIFF', 0);
  buf.writeUInt32LE(36 + n * 2, 4);
  buf.write('WAVE', 8);
  buf.write('fmt ', 12);
  buf.writeUInt32LE(16, 16);
  buf.writeUInt16LE(1, 20);
  buf.writeUInt16LE(1, 22);
  buf.writeUInt32LE(rate, 24);
  buf.writeUInt32LE(rate * 2, 28);
  buf.writeUInt16LE(2, 32);
  buf.writeUInt16LE(16, 34);
  buf.write('data', 36);
  buf.writeUInt32LE(n * 2, 40);
  for (let i = 0; i < n; i++) buf.writeInt16LE(Math.round(Math.sin((2 * Math.PI * freq * i) / rate) * 1200), 44 + i * 2);
  return buf;
}

const pad3 = (n) => String(n).padStart(3, '0');

/**
 * Fills an audio cache: whole ayahs for `folder`, the two intro clips, and word clips.
 * @param {string} root cache directory
 * @param {{folder?: string, ayahs?: Array<[number, number]>, words?: Array<[number, number, number]>, seconds?: number}} spec
 */
export function seedAudio(root, { folder = 'MaherAlMuaiqly128kbps', ayahs = [], words = [], seconds = 1.2 } = {}) {
  const ayahDir = join(root, 'everyayah', folder);
  mkdirSync(ayahDir, { recursive: true });
  for (const [s, a] of ayahs) writeFileSync(join(ayahDir, `${pad3(s)}${pad3(a)}.mp3`), wav(seconds));
  writeFileSync(join(root, 'everyayah', 'audhubillah.mp3'), wav(0.6, 330));
  writeFileSync(join(root, 'everyayah', 'bismillah.mp3'), wav(0.6, 550));
  const wbw = join(root, 'wbw');
  mkdirSync(wbw, { recursive: true });
  for (const [s, a, count] of words) {
    for (let w = 1; w <= count; w++) writeFileSync(join(wbw, `${pad3(s)}_${pad3(a)}_${pad3(w)}.mp3`), wav(0.3, 660));
  }
}

const range = (s, from, to) => Array.from({ length: to - from + 1 }, (_, i) => [s, from + i]);

/** The audio every e2e test can rely on: Al-Fatihah, the last three surahs, and Ayat al-Kursi. */
export const DEFAULT_AUDIO = {
  ayahs: [...range(1, 1, 7), ...range(112, 1, 4), ...range(113, 1, 5), ...range(114, 1, 6), [2, 255], [111, 5]],
  words: [[112, 1, 4], [112, 2, 2], [112, 3, 4], [112, 4, 5], [1, 1, 4], [1, 2, 4]],
};

/** Starts server.py; resolves once it is listening. */
export async function startServer({ registration = 'open', audio = DEFAULT_AUDIO } = {}) {
  const dir = mkdtempSync(join(tmpdir(), 'la-e2e-'));
  const cache = join(dir, 'audio-cache');
  seedAudio(cache, audio);
  const proc = spawn(
    'python3',
    ['server.py', '--port', '0', '--db', join(dir, 'test.db'), '--audio-cache', cache, '--no-fetch-audio', '--registration', registration],
    { cwd: WEB_APP, stdio: ['ignore', 'pipe', 'pipe'], env: { ...process.env, PYTHONUNBUFFERED: '1' } },
  );
  let stderr = '';
  proc.stderr.on('data', (d) => (stderr += d));
  const url = await new Promise((resolve, reject) => {
    let out = '';
    const timer = setTimeout(() => reject(new Error(`server did not start:\n${out}\n${stderr}`)), 15000);
    proc.stdout.on('data', (d) => {
      out += d;
      const m = out.match(/http:\/\/localhost:(\d+)\//);
      if (m) {
        clearTimeout(timer);
        resolve(`http://127.0.0.1:${m[1]}/`);
      }
    });
    proc.on('exit', (code) => reject(new Error(`server exited (${code}):\n${stderr}`)));
  });
  return {
    url,
    dir,
    get stderr() {
      return stderr;
    },
    async stop() {
      proc.kill();
      await new Promise((r) => (proc.exitCode !== null ? r() : proc.on('exit', r)));
      rmSync(dir, { recursive: true, force: true });
    },
  };
}

/** Launches Chromium. Set PW_CHROMIUM to use an already-installed browser binary. */
export function launchBrowser() {
  return chromium.launch({
    executablePath: process.env.PW_CHROMIUM || undefined,
    args: ['--autoplay-policy=no-user-gesture-required'],
  });
}

/**
 * A fresh page that fails the test on uncaught page errors / console errors.
 * `seenIntro` skips the welcome dialog; `settings` and `learned` pre-seed a guest profile.
 */
export async function openApp(browser, server, { path = '', seenIntro = true, learned, settings, query = '' } = {}) {
  const context = await browser.newContext({ serviceWorkers: 'block', viewport: { width: 1280, height: 860 } });
  const page = await context.newPage();
  const errors = [];
  page.on('pageerror', (err) => errors.push(`pageerror: ${err.message}`));
  page.on('console', (msg) => {
    if (msg.type() === 'error' && !/Failed to load resource/.test(msg.text())) errors.push(`console: ${msg.text()}`);
  });
  if (seenIntro || learned || settings) {
    const profile = {
      learned: learned ?? [],
      bookmarks: [],
      settings: { seenIntro, ...(settings ?? {}) },
      progress: {},
    };
    await context.addInitScript((p) => {
      if (!localStorage.getItem('la:v1:guest')) localStorage.setItem('la:v1:guest', JSON.stringify(p));
    }, profile);
  }
  await page.goto(`${server.url}${query}#/${path}`);
  await page.waitForSelector('#view > :not(.boot)');
  return { page, context, errors };
}

/** Global ayah ids for surah:ayah pairs (uses the app's own mapping). */
export async function ids(page, refs) {
  return page.evaluate(async (r) => {
    const q = await import('./js/core/quran.js');
    return r.map(([s, a]) => q.surahAyahToGlobal(s, a));
  }, refs);
}
