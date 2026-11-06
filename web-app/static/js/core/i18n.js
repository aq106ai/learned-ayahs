// The app's interface language (port of the Android app's res/values-*/strings.xml).
//
// tx('Settings') returns the text in the chosen language, or the English it was given when that
// language has no translation for it, so a missing entry can never blank or break the screen.
// The English text is the key. {name} placeholders are filled from tx(key, {name}).
//
// The language is read once, synchronously, when this module loads — before any view module
// evaluates its labels — from this browser's saved choice, else the browser's own languages.
// Changing it reloads the page (setUiLanguage), so nothing is ever half translated.
// It is a per-device preference, like the sidebar's fold: not synced with an account.
//
// Translations live in static/js/i18n/<code>.js and are shared word for word with the Android
// app (android-app-quran-player/.setup/ui_strings.json generates both); tests/unit/i18n.test.js
// checks every tx('…') in the code is translated in every shipped language.

import ur from '../i18n/ur.js';
import ar from '../i18n/ar.js';

export const UI_LANGUAGES = Object.freeze([
  { code: 'en', name: 'English', nativeName: 'English', rtl: false },
  { code: 'ur', name: 'Urdu', nativeName: 'اردو', rtl: true },
  { code: 'ar', name: 'Arabic', nativeName: 'العربية', rtl: true },
]);

const TABLES = { ur, ar };
export const UI_LANGUAGE_KEY = 'la.uiLanguage';

function saved() {
  try {
    return localStorage.getItem(UI_LANGUAGE_KEY) || '';
  } catch {
    return '';
  }
}

/** '' (follow the browser), or a code from UI_LANGUAGES. */
export const uiLanguageChoice = () => saved();

/** The first of [preferred] (BCP 47 tags) that we ship, else English. */
export function resolveLanguage(choice, preferred = []) {
  const codes = UI_LANGUAGES.map((l) => l.code);
  if (codes.includes(choice)) return choice;
  for (const tag of preferred) {
    const base = String(tag).toLowerCase().split('-')[0];
    if (codes.includes(base)) return base;
  }
  return 'en';
}

let current = resolveLanguage(saved(), typeof navigator !== 'undefined' ? navigator.languages ?? [navigator.language] : []);
let table = TABLES[current] ?? null;

export const uiLanguage = () => current;
export const isRtlUi = () => Boolean(UI_LANGUAGES.find((l) => l.code === current)?.rtl);

/** Tests and the language picker's preview: use [code] in this page without saving it. */
export function useLanguage(code) {
  current = resolveLanguage(code);
  table = TABLES[current] ?? null;
}

/** Saves [code] ('' = follow the browser) and reloads into it. */
export function setUiLanguage(code) {
  try {
    if (code) localStorage.setItem(UI_LANGUAGE_KEY, code);
    else localStorage.removeItem(UI_LANGUAGE_KEY);
  } catch {
    // Storage refused: the choice lasts for this visit only.
    useLanguage(code);
  }
  location.reload();
}

export function tx(key, params) {
  let s = (table && table[key]) || key;
  if (params) s = s.replace(/\{(\w+)\}/g, (m, name) => (name in params ? String(params[name]) : m));
  return s;
}

/** Sets <html lang dir> for the interface (the Qur'an text sets its own). */
export function applyDocumentLanguage(doc = document) {
  doc.documentElement.lang = current;
  doc.documentElement.dir = isRtlUi() ? 'rtl' : 'ltr';
}
