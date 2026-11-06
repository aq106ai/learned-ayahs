// Shared view helpers: import/export flows, surah search, learned counts.

import { dialog, downloadText, h, pickFile, toast } from '../lib/dom.js';
import { exportLearned, parseImport, suggestedFileName } from '../core/learnedExport.js';
import { formatRefs } from '../core/ayahRef.js';
import { SURAH_COUNT, globalToSurahAyah, surahName, surahNameLatin } from '../core/quran.js';

import { tx } from '../core/i18n.js';
export function learnedCountsBySurah(ids) {
  const counts = new Map();
  for (const id of ids) {
    const [s] = globalToSurahAyah(id);
    counts.set(s, (counts.get(s) ?? 0) + 1);
  }
  return counts;
}

const nameKey = (s) => s.toLowerCase().replace(/[^a-z0-9]/g, '');

export function matchSurahs(query) {
  const q = nameKey(query.trim());
  const all = Array.from({ length: SURAH_COUNT }, (_, i) => i + 1);
  if (!q) return all;
  return all.filter((s) => String(s) === q || String(s).startsWith(q) || nameKey(surahName(s)).includes(q) || nameKey(surahNameLatin(s)).includes(q));
}

export function exportFlow(store) {
  downloadText(suggestedFileName(), exportLearned(store.learned, { bookmarks: store.bookmarks }));
  toast(tx('Exported {n} ayahs.', { n: store.learned.size }));
}

/** Import an Android/web export, a quran_library export, or a plain list. Adds; never removes. */
export async function importFlow(store) {
  const file = await pickFile();
  if (!file) return;
  const parsed = parseImport(file.text);
  const ids = parsed.learned.ids;
  if (!ids.size && !parsed.bookmarks?.size) {
    toast(tx("No ayahs found in that file. Is it a Learned Ayahs export?"), { error: true });
    return;
  }
  const fresh = [...ids].filter((id) => !store.learned.has(id)).length;
  const kind = {
    'learned-ayahs': 'a Learned Ayahs export',
    'quran-library': 'a Qur’an app library export (its "Every Learned Ayah" folders)',
    json: 'a JSON list',
    list: 'a list of ayahs',
    text: 'a list of references',
  }[parsed.kind];
  const content = h(
    'div',
    { class: 'stack' },
    h('p', {}, `${file.name} is ${kind}.`),
    h('p', { class: 'muted' }, tx('{n} ayahs, {fresh} of them not marked yet.', { n: ids.size, fresh })),
    parsed.bookmarks?.size ? h('p', { class: 'muted' }, `Plus ${parsed.bookmarks.size} bookmarks.`) : null,
    parsed.learned.unparsed.length
      ? h('p', { class: 'notice' }, `Ignored: ${parsed.learned.unparsed.slice(0, 12).join(', ')}${parsed.learned.unparsed.length > 12 ? '…' : ''}`)
      : null,
    h('p', { class: 'tiny muted' }, tx('Importing adds to what you have already marked; nothing is removed.')),
  );
  const ok = await dialog({
    title: tx('Import learned ayahs'),
    content,
    actions: [
      { label: tx('Cancel'), value: false },
      { label: tx('Add {n} ayahs', { n: fresh }), value: true, primary: true },
    ],
  });
  if (!ok) return;
  const added = store.addLearned(ids);
  if (parsed.bookmarks?.size) store.addBookmarks(parsed.bookmarks);
  toast(tx('Added {n} ayahs.', { n: added }));
}

export function refsSummary(ids, max = 40) {
  const refs = formatRefs(ids);
  return refs.length > max ? `${refs.slice(0, max).join(', ')} … (+${refs.length - max} more)` : refs.join(', ');
}
