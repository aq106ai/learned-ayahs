// Shared test helpers: the same bundled Qur'an text the app serves (the Android app's asset).

import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const here = path.dirname(fileURLToPath(import.meta.url));
export const ASSETS = path.resolve(here, '../../../android-app-quran-player/app/src/main/assets');

let text = null;

export function quranText() {
  text ??= JSON.parse(readFileSync(path.join(ASSETS, 'quran_text.json'), 'utf8'));
  return text;
}

/** Words for an ayah in the shape the app uses: {text, isEnd}. */
export function wordsFor(surah, ayah) {
  const row = quranText()[`${surah}:${ayah}`];
  return row ? row.words.map((w) => ({ text: w.text, isEnd: w.char_type === 'end' })) : [];
}

export const w = (text, isEnd = false) => ({ text, isEnd });
