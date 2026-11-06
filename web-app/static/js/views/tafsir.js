// Tafsir on the current ayah, from the player's Tafsir button (port of ui/player/TafsirSheet.kt).
//
// The chosen edition (Settings, or picked here) is fetched once per ayah from Quran.com, turned
// into plain blocks by core/tafsirText.js and kept for offline use. It is shown with its name,
// author and source, and which ayahs the entry covers: a tafsir often explains a group together.
// Commentary is kept apart from the translation — it never appears in the reader itself.

import { dialog, h } from '../lib/dom.js';
import { isRtl, languageName, tafsirById, tafsirsFor, translationById } from '../core/catalog.js';
import { coveredRange } from '../core/tafsirText.js';
import { surahName } from '../core/quran.js';
import { ayahTafsir, loadCatalog } from '../services/content.js';

const TAG = { heading: 'h3', paragraph: 'p', item: 'li', quote: 'blockquote' };

/** The blocks as elements, built here: only textContent ever carries the provider's text. */
export function renderBlocks(blocks) {
  return blocks.map((b) => h(TAG[b.kind] ?? 'p', { dir: 'auto' }, b.spans.map((s) => {
    if (s.bold && s.italic) return h('strong', {}, h('em', {}, s.text));
    if (s.bold) return h('strong', {}, s.text);
    if (s.italic) return h('em', {}, s.text);
    return s.text;
  })));
}

export async function showTafsir(store, surah, ayah) {
  const body = h('div', { class: 'tafsir', 'data-testid': 'tafsir-body' }, h('p', { class: 'small muted' }, 'Loading…'));
  const catalog = await loadCatalog().catch(() => null);
  let token = 0;

  function picker(selected) {
    const lang = translationById(catalog, store.settings.translationEdition).language;
    return h('label', { class: 'field' },
      h('span', { class: 'small muted' }, 'Tafsir'),
      h('select', {
        class: 'select', 'data-testid': 'tafsir-edition', 'aria-label': 'Tafsir',
        onChange: (e) => {
          store.setSetting('tafsirEdition', e.target.value);
          draw();
        },
      }, [
        selected ? null : h('option', { value: '', selected: true }, 'Choose a tafsir…'),
        ...tafsirsFor(catalog, lang).map((t) => h('option', { value: t.id, selected: t.id === selected }, `${languageName(catalog, t.language)} · ${t.name}`)),
      ]));
  }

  async function draw() {
    const mine = ++token;
    if (!catalog) {
      body.replaceChildren(h('p', { class: 'small muted' }, 'The list of tafsirs could not be loaded. Check your connection and try again.'));
      return;
    }
    const edition = tafsirById(catalog, store.settings.tafsirEdition);
    if (!edition) {
      body.replaceChildren(
        h('p', { class: 'small', 'data-testid': 'tafsir-none' }, 'Choose a tafsir to read commentary on this ayah. It downloads from Quran.com and is kept for offline use. You can change it later in Settings.'),
        picker(''));
      return;
    }
    body.replaceChildren(picker(edition.id), h('p', { class: 'small muted' }, `Loading ${edition.name}…`));
    try {
      const t = await ayahTafsir(surah, ayah, edition.id);
      if (mine !== token) return;
      const range = coveredRange(t.verses);
      const text = h('div', {
        class: 'tafsir-text', lang: edition.language, dir: isRtl(catalog, edition.language) ? 'rtl' : 'ltr', 'data-testid': 'tafsir-text',
      }, t.blocks.length ? renderBlocks(t.blocks) : h('p', { class: 'muted' }, 'This tafsir has no commentary on this ayah.'));
      body.replaceChildren(
        picker(edition.id),
        h('p', { class: 'small muted', 'data-testid': 'tafsir-source' }, `${edition.name} by ${edition.author}, from ${edition.source}.`),
        range && range !== `${surah}:${ayah}` ? h('p', { class: 'small', 'data-testid': 'tafsir-range' }, `This commentary covers ${range} together.`) : null,
        text,
      );
    } catch (err) {
      if (mine !== token) return;
      body.replaceChildren(
        picker(edition.id),
        h('p', { class: 'small', 'data-testid': 'tafsir-error' }, `${edition.name} could not be loaded. ${err.message ?? ''}`.trim()),
        h('button', { class: 'btn small', type: 'button', 'data-testid': 'tafsir-retry', onClick: draw }, 'Try again'));
    }
  }

  draw();
  await dialog({ title: `Tafsir · ${surahName(surah)} ${surah}:${ayah}`, content: body, actions: [{ label: 'Close', value: true, primary: true }] });
  token++;
}
