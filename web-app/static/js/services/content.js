// Loads the translations and word meanings the user has chosen (see core/catalog.js).
//
// Arabic, the English word meanings and Saheeh International are bundled (data/), so they work
// offline from the first visit. Any other edition is fetched from Quran.com the first time it is
// needed, cleaned to plain text, checked for completeness and kept in Cache Storage, so it works
// offline from then on. A failed or partial download is never kept.

import * as catalogRules from '../core/catalog.js';
import { tafsirBlocks } from '../core/tafsirText.js';
import { surahAyahToGlobal, verseCount } from '../core/quran.js';
import { loadData, loadText, rawAyah } from './quranData.js';

export const CONTENT_CACHE = 'la-content-v1';
const TOTAL_AYAHS = 6236;

let getSettings = () => ({});
/** Where the chosen ids come from (the store's settings); called once at start-up. */
export function bindSettings(fn) {
  getSettings = fn;
}

let catalogLoaded = null;
export const loadCatalog = () => loadData('content_catalog.json').then((c) => (catalogLoaded = c));

/** The chosen edition if the catalog has loaded (it loads at start-up), else null. */
export function currentEditionSync() {
  return catalogLoaded ? catalogRules.translationById(catalogLoaded, getSettings().translationEdition) : null;
}
/** The chosen mushaf style's CSS font family (the default face until the catalog loads). */
export function mushafFamilySync() {
  const id = getSettings().mushafStyle;
  const style = catalogLoaded?.mushafStyles?.find((m) => m.id === id) ?? catalogLoaded?.mushafStyles?.[0];
  return `"${style?.family ?? 'Scheherazade New'}"`;
}

/** For labels: the chosen edition's name, before and after the catalog loads. */
export const editionNameSync = () => currentEditionSync()?.name ?? 'Saheeh International';
/** For labels like the Arabic / English chips: the chosen translation's language. */
export function translationLanguageSync() {
  const e = currentEditionSync();
  return e ? catalogRules.languageName(catalogLoaded, e.language) : 'English';
}

const listeners = new Set();
/** Called whenever newly loaded content could change what is on screen. Returns an unsubscribe. */
export function onContentLoaded(fn) {
  listeners.add(fn);
  return () => listeners.delete(fn);
}
const notify = () => listeners.forEach((fn) => { try { fn(); } catch { /* a view's own problem */ } });

async function stored(key, build) {
  const url = new URL(`content-cache/${key}`, location.href).href;
  const cache = 'caches' in globalThis ? await caches.open(CONTENT_CACHE).catch(() => null) : null;
  const hit = await cache?.match(url).catch(() => null);
  if (hit) {
    try {
      return await hit.json();
    } catch {
      // A damaged entry: fetch again below.
    }
  }
  const value = await build();
  await cache?.put(url, new Response(JSON.stringify(value), { headers: { 'Content-Type': 'application/json' } })).catch(() => {});
  return value;
}

async function getJson(url) {
  const res = await fetch(url);
  if (!res.ok) throw new Error(`The download failed (${res.status}). Check your connection and try again.`);
  return res.json();
}

// -- whole-ayah translations -------------------------------------------------------------------

const editions = new Map();

/** Every ayah of an edition, in order (index = global id - 1). */
export function loadEdition(edition) {
  if (!editions.has(edition.id)) {
    const p = edition.bundled
      ? loadData(edition.id === 'en-saheeh' ? 'translation_en_saheeh.json' : `translation_${edition.id}.json`)
      : loadCatalog().then((catalog) => stored(`translation/${edition.id}.json`, async () => {
        const data = await getJson(`${catalog.api}/quran/translations/${edition.resourceId}?fields=verse_key`);
        const out = new Array(TOTAL_AYAHS).fill(null);
        for (const row of data.translations ?? []) {
          const [s, a] = String(row.verse_key ?? '').split(':').map(Number);
          if (!s || !a || a > verseCount(s)) continue;
          out[surahAyahToGlobal(s, a) - 1] = catalogRules.plainTranslation(row.text);
        }
        const missing = out.filter((t) => t == null).length;
        // An edition may legitimately leave a handful of ayahs empty; a short download may not.
        if (missing > 60) throw new Error(`${edition.name} came back incomplete. Try again later.`);
        return out;
      }));
    editions.set(edition.id, p);
    p.then(notify, () => editions.delete(edition.id));
  }
  return editions.get(edition.id);
}

/** The chosen (or given) edition. */
export async function currentEdition(id = getSettings().translationEdition) {
  return catalogRules.translationById(await loadCatalog(), id);
}

/** One ayah in the chosen edition; Saheeh International if that edition can't be had. */
export async function ayahTranslation(surah, ayah, id) {
  const edition = await currentEdition(id);
  const index = surahAyahToGlobal(surah, ayah) - 1;
  try {
    const all = await loadEdition(edition);
    return all[index] ?? null;
  } catch (err) {
    if (edition.bundled) throw err;
    const fallback = await loadEdition(await currentEdition('en-saheeh'));
    return fallback[index] ?? null;
  }
}

/** Downloads an edition now (Settings and the intro call this as soon as one is chosen). */
export async function prepareEdition(id) {
  return loadEdition(await currentEdition(id));
}

// -- tafsir ------------------------------------------------------------------------------------

const tafsirMemory = new Map(); // `${id}:${s}:${a}` -> Promise<{verses, blocks}>

/**
 * The chosen tafsir (or [id]) on one ayah: {edition, verses: ["112:1", …], blocks} — the
 * entry may cover a group of ayahs. Read from the cache, else fetched and kept. Throws when
 * there is no tafsir chosen, or it can't be had.
 */
export async function ayahTafsir(surah, ayah, id = getSettings().tafsirEdition) {
  const catalog = await loadCatalog();
  const edition = catalogRules.tafsirById(catalog, id);
  if (!edition) throw new Error('Choose a tafsir first.');
  const key = `${edition.id}:${surah}:${ayah}`;
  if (!tafsirMemory.has(key)) {
    const p = stored(`tafsir/${edition.id}/${surah}_${ayah}.json`, async () => {
      const data = await getJson(`${catalog.api}/tafsirs/${edition.resourceId}/by_ayah/${surah}:${ayah}`);
      const t = data.tafsir ?? {};
      const blocks = tafsirBlocks(t.text ?? '');
      const verses = Object.keys(t.verses ?? {});
      return { verses: verses.length ? verses : [`${surah}:${ayah}`], blocks };
    });
    tafsirMemory.set(key, p);
    p.catch(() => tafsirMemory.delete(key));
  }
  return { edition, ...(await tafsirMemory.get(key)) };
}

// -- word meanings ------------------------------------------------------------------------------

const meanings = new Map(); // `${lang}:${surah}` -> {"s:a": [meaning|null, ...]}
const pending = new Map();

const meaningLanguage = () => getSettings().wordMeaningLanguage || 'en';

/** Loaded meanings for one ayah in [lang], or null (not loaded yet, or English: the bundle has it). */
export function meaningsSync(surah, ayah, lang = meaningLanguage()) {
  if (lang === 'en') return null;
  return meanings.get(`${lang}:${surah}`)?.[`${surah}:${ayah}`] ?? null;
}

/** Fetches (or reads from the cache) one surah's meanings in [lang]. */
export function ensureMeanings(surah, lang = meaningLanguage()) {
  const key = `${lang}:${surah}`;
  if (lang === 'en' || meanings.has(key)) return Promise.resolve(meanings.get(key) ?? null);
  if (!pending.has(key)) {
    const p = (async () => {
      const catalog = await loadCatalog();
      if (!catalog.wordMeanings.some((w) => w.id === lang)) return null;
      await loadText();
      const languageName = catalog.languages[lang]?.name?.toLowerCase();
      const table = await stored(`words/${lang}/${surah}.json`, async () => {
        const out = {};
        let page = 1;
        while (page) {
          const data = await getJson(`${catalog.api}/verses/by_chapter/${surah}?words=true&language=${encodeURIComponent(lang)}&per_page=50&page=${page}&word_fields=position`);
          for (const verse of data.verses ?? []) {
            const [s, a] = verse.verse_key.split(':').map(Number);
            const ours = rawAyah(s, a);
            if (ours) out[verse.verse_key] = catalogRules.alignWordMeanings(ours.words, verse, languageName);
          }
          page = data.pagination?.next_page ?? null;
        }
        if (Object.keys(out).length !== verseCount(surah)) throw new Error('The word meanings came back incomplete.');
        return out;
      });
      meanings.set(key, table);
      notify();
      return table;
    })();
    pending.set(key, p);
    p.finally(() => pending.delete(key)).catch(() => {});
  }
  return pending.get(key);
}

/** Forgets everything downloaded (Settings → Remove downloaded translations). */
export async function clearDownloaded() {
  for (const [id] of editions) if (id !== 'en-saheeh') editions.delete(id);
  meanings.clear();
  tafsirMemory.clear();
  if ('caches' in globalThis) await caches.delete(CONTENT_CACHE);
}

/** How many non-bundled pieces are stored, for Settings. */
export async function downloadedCount() {
  if (!('caches' in globalThis)) return 0;
  const cache = await caches.open(CONTENT_CACHE);
  return (await cache.keys()).length;
}
