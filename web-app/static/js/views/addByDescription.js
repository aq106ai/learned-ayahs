// Bulk-add ayahs by typing references, or by pasting what an AI assistant wrote.
// Port of ui/settings/AddByDescriptionScreen.kt. The app itself never calls any AI service.

import { append, clear, h, icon, toast } from '../lib/dom.js';
import { formatRefs } from '../core/ayahRef.js';
import { llmPrompt, parseLearned } from '../core/learnedExport.js';
import { learnedCountsBySurah } from './common.js';

import { tx } from '../core/i18n.js';
export function addByDescriptionView({ store }) {
  const current = h('div', { class: 'notice small', 'data-testid': 'current-selection' });
  const preview = h('p', { class: 'small', 'data-testid': 'description-preview' });
  const ignored = h('p', { class: 'small notice error', hidden: true });
  const input = h('textarea', {
    class: 'input',
    id: 'refs',
    placeholder: tx('36:1-83, Al-Mulk, 78-114'),
    'data-testid': 'description-field',
  });
  const add = h('button', { class: 'btn primary', type: 'button', disabled: true, 'data-testid': 'description-apply' }, icon('plus'), tx('Add'));
  let applied = null;

  function renderCurrent() {
    const ids = store.learned;
    const surahs = learnedCountsBySurah(ids).size;
    append(clear(current), [
      h('strong', {}, surahs === 1 ? tx('Already marked · {n} ayahs in 1 surah', { n: ids.size }) : tx('Already marked · {n} ayahs in {surahs} surahs', { n: ids.size, surahs })),
      ids.size ? h('div', { class: 'muted', style: { marginTop: '6px', wordBreak: 'break-word' } }, formatRefs(ids).join(', ')) : null,
    ]);
  }

  function renderPreview() {
    const text = input.value;
    const parsed = parseLearned(text);
    const fresh = [...parsed.ids].filter((id) => !store.learned.has(id)).length;
    if (applied != null) preview.textContent = tx('Added {n} ayahs.', { n: applied });
    else if (!text.trim()) preview.textContent = tx('Nothing entered yet.');
    else if (!parsed.ids.size) preview.textContent = tx("Couldn't read any ayahs from that.");
    else preview.textContent = tx('Will mark {n} ayahs · {fresh} new.', { n: parsed.ids.size, fresh });
    ignored.hidden = !parsed.unparsed.length;
    ignored.textContent = parsed.unparsed.length ? `Ignored: ${parsed.unparsed.join(', ')}` : '';
    add.disabled = !parsed.ids.size;
  }

  input.addEventListener('input', () => {
    applied = null;
    renderPreview();
  });
  add.addEventListener('click', () => {
    const parsed = parseLearned(input.value);
    applied = store.addLearned(parsed.ids);
    input.value = '';
    renderPreview();
    toast(tx('Added {n} ayahs.', { n: applied }));
  });

  const copy = h('button', {
    class: 'btn',
    type: 'button',
    'data-testid': 'copy-llm-prompt',
    onClick: async () => {
      try {
        await navigator.clipboard.writeText(llmPrompt());
        toast(tx('Prompt copied. Paste it into any AI assistant, then add your description.'));
      } catch {
        input.value = llmPrompt();
        renderPreview();
        toast(tx('Could not reach the clipboard; the prompt is in the box above to copy.'));
      }
    },
  }, icon('copy'), tx('Copy AI prompt'));

  const el = h(
    'div',
    { class: 'page page-narrow' },
    h('a', { class: 'btn ghost small', href: '#/surahs' }, icon('chevronLeft'), tx('Surahs')),
    h('header', { class: 'page-head', style: { marginTop: '12px' } },
      h('div', {}, h('h1', {}, tx('Add by description')), h('p', { class: 'muted' }, tx('Mark many ayahs at once.')))),
    h('section', { class: 'card stack' },
      current,
      h('div', { class: 'field' },
        h('label', { for: 'refs' }, tx('Ayah references, or JSON from an AI assistant')),
        h('p', { class: 'muted small', style: { margin: 0 } }, tx("Type what you've learned — for example: 36:1-83,  2:255,  Al-Baqarah 1-5,  112,  78-114")),
        input,
      ),
      preview,
      ignored,
      h('div', { class: 'row' }, add),
    ),
    h('section', { class: 'card stack' },
      h('h2', {}, tx('Describe it in your own words')),
      h('p', { class: 'muted small', style: { margin: 0 } },
        tx('Copy the prompt into any AI assistant, describe your memorisation in your own words, then paste its reply into the box above. Nothing is sent anywhere by this app.')),
      h('div', {}, copy),
    ),
  );
  renderCurrent();
  renderPreview();
  const off = store.on((what) => {
    if (what === 'learned' || what === 'all') {
      renderCurrent();
      renderPreview();
    }
  });
  return { el, destroy: off, title: tx('Add by description') };
}
