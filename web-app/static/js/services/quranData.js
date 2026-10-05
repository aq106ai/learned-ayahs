// The bundled Qur'an data (shared with the Android app, served from /data/):
//   quran_text.json        all 6236 ayahs, Uthmani word text; "end" marks the ayah-number glyph
//   word_translations.json English word-by-word, aligned to content words
//   word_timings_<r>.json  validated per-word [start, end) segments for one reciter
// Loaded lazily and cached for the session (and by the service worker for offline use).

const cache = new Map();

function load(name) {
  if (!cache.has(name)) {
    cache.set(
      name,
      fetch(`data/${name}`).then((r) => {
        if (!r.ok) throw new Error(`Could not load ${name} (${r.status})`);
        return r.json();
      }),
    );
    // Allow a retry after a failure (e.g. offline before the data was cached).
    cache.get(name).catch(() => cache.delete(name));
  }
  return cache.get(name);
}

let text = null;
let translations = null;
const timings = new Map();

export async function loadText() {
  text ??= await load('quran_text.json');
  return text;
}

export async function loadTranslations() {
  translations ??= await load('word_translations.json');
  return translations;
}

export async function loadTimings(reciter) {
  if (!timings.has(reciter.key)) timings.set(reciter.key, await load(reciter.timingsAsset));
  return timings.get(reciter.key);
}

/** Words of an ayah: {text, isEnd, translation}. Requires loadText() to have resolved. */
export function wordsSync(surah, ayah) {
  const row = text?.[`${surah}:${ayah}`];
  if (!row) return [];
  const meanings = translations?.[`${surah}:${ayah}`] ?? [];
  let content = 0;
  return row.words.map((w) => {
    const isEnd = w.char_type === 'end';
    return { text: w.text, isEnd, translation: isEnd ? null : meanings[content++] ?? null };
  });
}

export async function words(surah, ayah) {
  await loadText();
  return wordsSync(surah, ayah);
}

export const contentWords = (ws) => ws.filter((w) => !w.isEnd);

/** Validated segments for this reciter's recording, or null: never an estimate. */
export function segmentsSync(reciter, surah, ayah) {
  return timings.get(reciter.key)?.[`${surah}:${ayah}`]?.segments ?? null;
}

export const isTextLoaded = () => text !== null;
export const areTranslationsLoaded = () => translations !== null;
