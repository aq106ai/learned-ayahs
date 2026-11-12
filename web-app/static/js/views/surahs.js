// Browse and mark: all 114 surahs with search, learned badges, mark-all, and the learned-ayahs
// banner (add by description, export, import). Port of ui/home/SurahListScreen.kt.

import { h, icon } from '../lib/dom.js';
import { VERSE_COUNTS, surahAyahToGlobal, surahName } from '../core/quran.js';
import { exportFlow, importFlow, learnedCountsBySurah, matchSurahs } from './common.js';

import { tx } from '../core/i18n.js';
export function surahsView({ store, go }) {
  let query = '';
  const count = h('span', { class: 'count', 'data-testid': 'learned-count' });
  const countLabel = h('div', { class: 'muted small' });
  const list = h('div', { class: 'surah-list', 'data-testid': 'surah-list' });
  const proceed = h('a', { class: 'btn primary', href: '#/player' }, icon('book'), tx('Revise'));

  const banner = h(
    'section',
    { class: 'card banner' },
    h('div', {}, count, countLabel),
    h('div', { class: 'spacer' }),
    h(
      'div',
      { class: 'row wrap' },
      h('a', { class: 'btn', href: '#/add', 'data-testid': 'add-by-description' }, icon('plus'), tx('Add by description')),
      h('button', { class: 'btn', type: 'button', onClick: () => exportFlow(store), 'data-testid': 'export' }, icon('download'), tx('Export')),
      h('button', { class: 'btn', type: 'button', onClick: () => importFlow(store), 'data-testid': 'import' }, icon('upload'), tx('Import')),
    ),
  );

  const search = h('input', {
    class: 'input',
    type: 'search',
    placeholder: tx('Search surah by name or number'),
    'aria-label': tx('Search surah'),
    onInput: (e) => {
      query = e.target.value;
      renderList();
    },
  });

  function renderCounts() {
    const n = store.learned.size;
    count.textContent = String(n);
    const surahs = learnedCountsBySurah(store.learned).size;
    countLabel.textContent = n
      ? (surahs === 1 ? tx('ayahs marked learned, in 1 surah') : tx('ayahs marked learned, in {n} surahs', { n: surahs }))
      : tx('ayahs marked — open a surah and tick the ayahs you know');
    proceed.hidden = n === 0;
  }

  function renderList() {
    const counts = learnedCountsBySurah(store.learned);
    list.replaceChildren(
      ...matchSurahs(query).map((s) => {
        const learned = counts.get(s) ?? 0;
        const total = VERSE_COUNTS[s - 1];
        const all = learned === total;
        const check = h(
          'button',
          {
            class: `check-btn${learned && !all ? ' partial' : ''}`,
            type: 'button',
            'aria-pressed': String(all),
            'aria-label': all ? tx('Unmark all of {surah}', { surah: surahName(s) }) : tx('Mark all of {surah} learned', { surah: surahName(s) }),
            title: all ? tx('Unmark all') : tx('Mark all learned'),
            onClick: (e) => {
              e.preventDefault();
              e.stopPropagation();
              const ids = Array.from({ length: total }, (_, i) => surahAyahToGlobal(s, i + 1));
              if (all) store.removeLearned(ids);
              else store.addLearned(ids);
            },
          },
          icon('check'),
        );
        return h(
          'a',
          { class: `surah-item${learned ? ' has-learned' : ''}`, href: `#/surah/${s}`, 'data-testid': `surah-${s}` },
          h('span', { class: 'surah-num' }, String(s)),
          h(
            'span',
            { class: 'meta' },
            h('div', { class: 'name' }, surahName(s)),
            h('div', { class: 'sub' }, tx('{n} ayahs', { n: total }), learned ? ` · ${tx('{n} learned', { n: learned })}` : ''),
          ),
          check,
        );
      }),
    );
    if (!list.children.length) list.append(h('p', { class: 'muted' }, tx('No surah matches that search.')));
  }

  const el = h(
    'div',
    { class: 'page' },
    h(
      'header',
      { class: 'page-head' },
      h('div', {}, h('h1', {}, tx('Manage learned ayahs')), h('p', { class: 'muted' }, tx('Mark the ayahs you have memorised. Your revision plays exactly these.'))),
      proceed,
    ),
    banner,
    h('div', { class: 'search', style: { marginTop: '16px' } }, icon('search'), search),
    list,
  );

  renderCounts();
  renderList();
  const off = store.on((what) => {
    if (what === 'learned' || what === 'all') {
      renderCounts();
      renderList();
    }
  });
  void go;
  return { el, destroy: off, title: tx('Manage') };
}
