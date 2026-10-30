// The data video backgrounds and translations need, shared with the Android app (served from
// data/): translation_en_saheeh.json (Saheeh International, one string per ayah, by global id),
// video_themes.json (each ayah's background theme) and nature_photos.json (the CC0 photos).
// The photos themselves are served from backgrounds/nature/ alongside the app.

import { surahAyahToGlobal } from '../core/quran.js';
import { FALLBACK } from './themes.js';

const cache = new Map();

function load(name) {
  if (!cache.has(name)) {
    const p = fetch(`data/${name}`).then((r) => {
      if (!r.ok) throw new Error(`Could not load ${name} (${r.status})`);
      return r.json();
    });
    cache.set(name, p);
    p.catch(() => cache.delete(name));
  }
  return cache.get(name);
}

export async function translation(surah, ayah) {
  const all = await load('translation_en_saheeh.json');
  return all[surahAyahToGlobal(surah, ayah) - 1] ?? null;
}

export async function loadThemeMap() {
  return load('video_themes.json');
}

/** The drawn theme matched to an ayah, from the map; needs loadThemeMap() first. */
export function themeKeySync(map, surah, ayah) {
  const index = map?.ayahs?.[surahAyahToGlobal(surah, ayah) - 1];
  return map?.themes?.[index] ?? FALLBACK;
}

export async function themeKey(surah, ayah) {
  return themeKeySync(await loadThemeMap(), surah, ayah);
}

// -- nature photos ----------------------------------------------------------------------------

export const PHOTO_PREFIX = 'photo:';
export const isPhoto = (key) => typeof key === 'string' && key.startsWith(PHOTO_PREFIX);
/** Subjects that suit an ayah whose theme no photo subject names. */
const GENERAL = ['sunrise', 'mountain_lake', 'green_hills', 'ocean', 'forest'];

let subjects = null;

/** Photo subjects: [{key, title, themeKey, photos: [{id, author, ...}]}]. Empty if none listed. */
export async function photoSubjects() {
  if (subjects) return subjects;
  try {
    const json = await load('nature_photos.json');
    const groups = new Map();
    for (const p of json.photos ?? []) {
      if (!groups.has(p.subject)) groups.set(p.subject, { key: p.subject, title: p.title, themeKey: PHOTO_PREFIX + p.subject, photos: [] });
      groups.get(p.subject).photos.push(p);
    }
    subjects = [...groups.values()];
  } catch {
    // Not kept: offline now doesn't mean no photos for the rest of the visit.
    return [];
  }
  return subjects;
}

function hash(s) {
  let h = 0;
  for (const ch of s) h = (Math.imul(h, 31) + ch.charCodeAt(0)) | 0;
  return h;
}

/** The subject for an ayah matched to drawn theme [key]: the first that lists it, else a calm one. */
export function subjectFor(all, key) {
  if (!all.length) return null;
  const listed = all.find((s) => s.photos[0].themes?.includes(key));
  if (listed) return listed;
  const general = GENERAL.map((k) => all.find((s) => s.key === k)).filter(Boolean);
  const pool = general.length ? general : all;
  return pool[((hash(key) % pool.length) + pool.length) % pool.length];
}

export function photoFor(all, themeKey, variant) {
  const subject = all.find((s) => s.themeKey === themeKey);
  if (!subject) return null;
  return subject.photos[((variant % subject.photos.length) + subject.photos.length) % subject.photos.length];
}

export const photoUrl = (photo) => `backgrounds/nature/${photo.id}.jpg`;
export const thumbUrl = (subject) => `backgrounds/nature/${subject.photos[0].id}_thumb.jpg`;

const images = new Map();

/** The photo, decoded and ready to draw; cached. */
export function loadPhoto(photo) {
  if (!images.has(photo.id)) {
    const p = fetch(photoUrl(photo))
      .then((r) => {
        if (!r.ok) throw new Error("Couldn't load the background photo.");
        return r.blob();
      })
      .then((b) => createImageBitmap(b));
    images.set(photo.id, p);
    p.catch(() => images.delete(photo.id));
  }
  return images.get(photo.id);
}

export function photoCredit(photo) {
  return photo.author === 'Unknown' ? 'Photo: Wikimedia Commons (CC0)' : `Photo: ${photo.author} · Wikimedia Commons (CC0)`;
}
