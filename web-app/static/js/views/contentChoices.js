// What the app shows beside the Arabic, chosen in the intro and changeable in Settings: the
// translation (language, then edition), the style (word meanings, the whole ayah, or both), the
// word-meaning language, an optional tafsir, and (in the intro) the reciter.
// Port of ui/settings/ContentChoices.kt. Every choice is saved as a catalog id.

import { h } from '../lib/dom.js';
import {
  TRANSLATION_STYLES, isRtl, languageName, styleOf, switchesFor, tafsirById, tafsirsFor,
  translationById, translationLanguages, translationsIn, wordMeaningsById,
} from '../core/catalog.js';
import { RECITERS } from '../core/reciters.js';
import { loadCatalog, prepareEdition } from '../services/content.js';

const STYLES = [
  [TRANSLATION_STYLES.WORDS, 'Word meanings', 'Under each Arabic word'],
  [TRANSLATION_STYLES.AYAH, 'Full translation', 'The whole ayah, under the Arabic'],
  [TRANSLATION_STYLES.BOTH, 'Both', 'Word meanings and the whole ayah'],
];

const editionLabel = (t) => (t.author && t.author !== t.name && !t.name.includes(t.author) ? `${t.name} — ${t.author}` : t.name);

function select(testid, label, options, value, onChange, { dir } = {}) {
  const el = h('select', { class: 'select', 'data-testid': testid, 'aria-label': label, dir, onChange: (e) => onChange(e.target.value) },
    options.map(([v, text, attrs]) => h('option', { value: v, selected: v === value, ...(attrs ?? {}) }, text)));
  return h('label', { class: 'field' }, h('span', { class: 'small muted' }, label), el);
}

/**
 * The choices as one block. [reciter] adds the reciter picker (the intro has no other place for
 * it; Settings keeps its own card). Re-renders itself as choices change.
 */
export function contentChoices(store, { reciter = false } = {}) {
  const el = h('div', { class: 'stack content-choices', 'data-testid': 'content-choices' }, h('p', { class: 'small muted' }, 'Loading…'));
  const status = h('p', { class: 'tiny muted', 'data-testid': 'content-status', 'aria-live': 'polite' });
  let catalog = null;
  let lastPrepared = null;

  function prepare(edition) {
    if (edition.bundled) {
      status.textContent = 'Included with the app — works offline.';
      return;
    }
    if (lastPrepared === edition.id) return;
    lastPrepared = edition.id;
    status.textContent = `Downloading ${edition.name}…`;
    prepareEdition(edition.id)
      .then(() => { if (lastPrepared === edition.id) status.textContent = `${edition.name} is saved for offline use.`; })
      .catch((err) => {
        if (lastPrepared !== edition.id) return;
        lastPrepared = null;
        status.textContent = `${err.message} Saheeh International is shown until it downloads.`;
      });
  }

  function set(key, value) {
    store.setSetting(key, value);
    draw();
  }

  function draw() {
    const s = store.settings;
    const edition = translationById(catalog, s.translationEdition);
    const lang = edition.language;
    const style = styleOf(s);
    const words = wordMeaningsById(catalog, s.wordMeaningLanguage);
    const tafsir = tafsirById(catalog, s.tafsirEdition);

    const languageOptions = translationLanguages(catalog).map((code) => [code, languageName(catalog, code, { native: true })]);
    const editionOptions = translationsIn(catalog, lang).map((t) => [t.id, `${editionLabel(t)}${t.bundled ? ' (offline)' : ''}`]);
    const wordOptions = catalog.wordMeanings.map((w) => [w.id, languageName(catalog, w.language, { native: true })]);
    const tafsirOptions = [['', 'None'], ...tafsirsFor(catalog, lang).map((t) => [t.id, `${languageName(catalog, t.language)} · ${editionLabel(t)}`])];

    el.replaceChildren(
      select('content-translation-language', 'Translation language', languageOptions, lang, (code) => {
        const first = translationsIn(catalog, code)[0];
        if (!first) return;
        // Word meanings follow the language where Quran.com has them in it.
        if (catalog.wordMeanings.some((w) => w.id === code)) store.setSetting('wordMeaningLanguage', code);
        set('translationEdition', first.id);
      }),
      select('content-translation', 'Translation', editionOptions, edition.id, (id) => set('translationEdition', id), { dir: isRtl(catalog, lang) ? 'rtl' : 'auto' }),
      status,
      h('div', { class: 'small muted' }, 'Show'),
      h('div', { class: 'chips', role: 'radiogroup', 'aria-label': 'Translation style' }, STYLES.map(([value, label, sub]) => h('button', {
        class: 'chip', type: 'button', role: 'radio', title: sub,
        'aria-checked': String(style === value), 'aria-pressed': String(style === value),
        'data-testid': `content-style-${value}`,
        onClick: () => {
          const sw = switchesFor(value);
          store.setSetting('showWordTranslations', sw.showWordTranslations);
          set('showAyahTranslation', sw.showAyahTranslation);
        },
      }, label))),
      style !== TRANSLATION_STYLES.AYAH
        ? select('content-word-language', 'Word meanings in', wordOptions, words.id, (id) => set('wordMeaningLanguage', id))
        : null,
      select('content-tafsir', 'Tafsir (optional)', tafsirOptions, tafsir?.id ?? '', (id) => set('tafsirEdition', id)),
      h('p', { class: 'tiny muted', style: { margin: 0 } },
        tafsir ? `${tafsir.name} by ${tafsir.author}, from ${tafsir.source}. Opens from the Tafsir button in the player.` : 'Commentary on the current ayah, from the Tafsir button in the player.'),
      reciter
        ? h('div', { class: 'stack' },
          h('div', { class: 'small muted' }, 'Reciter'),
          h('div', { class: 'chips', role: 'radiogroup', 'aria-label': 'Reciter' }, RECITERS.map((r) => h('button', {
            class: 'chip', type: 'button', role: 'radio',
            'aria-checked': String(s.reciter === r.key), 'aria-pressed': String(s.reciter === r.key),
            'data-testid': `content-reciter-${r.key}`,
            onClick: () => set('reciter', r.key),
          }, r.displayName))),
          h('p', { class: 'tiny muted', style: { margin: 0 } }, 'Every reciter here has checked word timings, so each word lights up as it is recited.'))
        : null,
    );
    prepare(edition);
  }

  loadCatalog().then((c) => { catalog = c; draw(); }).catch(() => {
    el.replaceChildren(h('p', { class: 'small muted' }, 'The list of translations could not be loaded. Saheeh International is used for now.'));
  });
  return el;
}
