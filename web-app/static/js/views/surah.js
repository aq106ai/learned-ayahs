// One surah: mark ayahs learned (the circle), bookmark them (the star), or listen from any ayah.
// Port of ui/surah/SurahDetailScreen.kt.

import { h, icon } from '../lib/dom.js';
import { VERSE_COUNTS, surahAyahToGlobal, surahName } from '../core/quran.js';
import * as quran from '../services/quranData.js';

export function surahView({ store, engine, go, params }) {
  const surah = Math.min(Math.max(Number(params[0]) || 1, 1), 114);
  const total = VERSE_COUNTS[surah - 1];
  const ids = Array.from({ length: total }, (_, i) => surahAyahToGlobal(surah, i + 1));
  const summary = h('p', { class: 'muted' });
  const markAll = h('button', { class: 'btn', type: 'button', 'data-testid': 'mark-all' });
  const rows = new Map();
  const showTranslation = store.settings.showWordTranslations;

  markAll.addEventListener('click', () => {
    const all = ids.every((id) => store.learned.has(id));
    if (all) store.removeLearned(ids);
    else store.addLearned(ids);
  });

  const list = h('div', { class: 'ayah-list', 'data-testid': 'ayah-list' });

  function ayahRow(ayah, words) {
    const id = surahAyahToGlobal(surah, ayah);
    const learnedBtn = h('button', {
      class: 'check-btn',
      type: 'button',
      'data-testid': `learn-${ayah}`,
      onClick: () => store.toggleLearned(id),
    }, icon('check'));
    const star = h('button', {
      class: 'star-btn',
      type: 'button',
      'data-testid': `bookmark-${ayah}`,
      onClick: () => store.toggleBookmark(id),
    }, icon('star'));
    const listen = h('button', {
      class: 'btn ghost small',
      type: 'button',
      title: 'Listen from this ayah',
      'aria-label': `Listen from ayah ${ayah}`,
      onClick: () => {
        engine.playSurahFrom(surah, ayah);
        go('/player');
      },
    }, icon('play'));
    const content = words.filter((w) => !w.isEnd);
    const meaning = showTranslation ? content.map((w) => w.translation).filter(Boolean).join(' ') : '';
    const row = h(
      'article',
      { class: 'ayah-row', style: { contentVisibility: 'auto', containIntrinsicSize: '0 140px' } },
      h('div', { class: 'ayah-no' }, String(ayah)),
      h(
        'div',
        {},
        h('div', { class: 'arabic', lang: 'ar' }, words.map((w) => w.text).join(' ')),
        meaning ? h('div', { class: 'small muted' }, meaning) : null,
      ),
      h('div', { class: 'actions' }, learnedBtn, star, listen),
    );
    rows.set(id, { row, learnedBtn, star });
    return row;
  }

  function refresh() {
    let learned = 0;
    for (const [id, r] of rows) {
      const isLearned = store.learned.has(id);
      if (isLearned) learned++;
      r.row.classList.toggle('learned', isLearned);
      r.learnedBtn.setAttribute('aria-pressed', String(isLearned));
      r.learnedBtn.setAttribute('aria-label', isLearned ? 'Marked learned' : 'Mark learned');
      const marked = store.bookmarks.has(id);
      r.star.setAttribute('aria-pressed', String(marked));
      r.star.setAttribute('aria-label', marked ? 'Remove bookmark' : 'Bookmark this ayah');
    }
    if (!rows.size) learned = ids.filter((id) => store.learned.has(id)).length;
    summary.textContent = `${total} ayahs · ${learned} learned`;
    const all = learned === total;
    markAll.replaceChildren(icon('check'), all ? 'Unmark all ayahs' : 'Mark all ayahs learned');
  }

  async function load() {
    list.append(h('div', { class: 'boot' }, h('div', { class: 'spinner' })));
    await quran.loadText();
    if (showTranslation) await quran.loadTranslations().catch(() => {});
    list.replaceChildren(...Array.from({ length: total }, (_, i) => ayahRow(i + 1, quran.wordsSync(surah, i + 1))));
    refresh();
  }

  const nav = h(
    'div',
    { class: 'row' },
    surah > 1 ? h('a', { class: 'btn ghost small', href: `#/surah/${surah - 1}` }, '‹ Previous surah') : null,
    h('span', { class: 'spacer' }),
    surah < 114 ? h('a', { class: 'btn ghost small', href: `#/surah/${surah + 1}` }, 'Next surah ›') : null,
  );

  const el = h(
    'div',
    { class: 'page' },
    h('a', { class: 'btn ghost small', href: '#/surahs' }, icon('chevronLeft'), 'All surahs'),
    h(
      'header',
      { class: 'page-head', style: { marginTop: '12px' } },
      h('div', {}, h('h1', {}, `${surah} · ${surahName(surah)}`), summary),
      h(
        'div',
        { class: 'row wrap' },
        markAll,
        h('button', {
          class: 'btn primary',
          type: 'button',
          onClick: () => {
            engine.playSurahFrom(surah, 1);
            go('/player');
          },
        }, icon('headphones'), 'Listen'),
      ),
    ),
    list,
    h('div', { style: { marginTop: '18px' } }, nav),
  );

  refresh();
  load();
  const off = store.on((what) => {
    if (['learned', 'bookmarks', 'all'].includes(what)) refresh();
  });
  return { el, destroy: off, title: surahName(surah) };
}
