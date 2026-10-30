// Sources and disclaimer (port of ui/common/SourcesAndDisclaimer.kt; keep the two in step).
// Always in Settings, and a tap away from the welcome's last page; nobody is asked to agree to it
// before using the app (the licences need the sources credited, not accepted).

import { h } from '../lib/dom.js';
import { RECITERS } from '../core/reciters.js';

const SOURCES = [
  ['Qur\'an text (Uthmani script) and English word-by-word meanings', 'Quran.com', 'https://quran.com'],
  [`Ayah recitations by ${RECITERS.map((r) => r.displayName).join(', ')}`, 'EveryAyah', 'https://everyayah.com'],
  ['Single-word recitations', 'Quran.com', 'https://quran.com'],
  ['English translation of whole ayahs (player and shared videos)', 'Saheeh International, via Tanzil.net', 'https://tanzil.net'],
  ['Nature photos for backgrounds (CC0)', 'Wikimedia Commons', 'https://commons.wikimedia.org'],
  ['Word timings used to highlight each word', 'Quranic Universal Library (QUL) by Tarteel', 'https://qul.tarteel.ai'],
  ['Arabic font Scheherazade New', 'SIL International', 'https://software.sil.org/scheherazade/'],
];

const INTRO = 'Learned Ayahs is a free tool that makes it easier to revise what you have memorised. ' +
  'It does not author any of the Qur\'an text, meanings or recitations it shows and plays: ' +
  'it gives you access to these public online sources.';

const POINTS = [
  'If you notice a mistake in the text, a meaning or a recitation, please report it to the source it comes from, listed above. Problems with the app itself can be reported at github.com/aq106ai/learned-ayahs/issues.',
  'Word meanings are a study aid, not a translation of the Qur\'an. Word highlighting and Recite & review are approximate.',
  'Always confirm your memorisation and recitation with a qualified teacher.',
  'The app is provided free and "as is", without warranty of any kind. To the extent the law allows, its developers are not responsible for the content of these sources or for any loss arising from using the app.',
];

/** The sources and the disclaimer, for the dialog and for Settings. */
export function sourcesAndDisclaimer() {
  return h('div', { class: 'stack', 'data-testid': 'sources' },
    h('p', { style: { margin: 0 } }, INTRO),
    h('ul', { class: 'stack', style: { paddingLeft: '18px', margin: 0 } },
      SOURCES.map(([what, who, url]) => h('li', {}, `${what}: `,
        h('a', { href: url, target: '_blank', rel: 'noopener' }, who)))),
    h('ul', { class: 'stack small muted', style: { paddingLeft: '18px', margin: 0 } },
      POINTS.map((p) => h('li', {}, p))));
}

/** The sources and the disclaimer in a dialog, from the welcome. */
export function showSources() {
  const close = h('button', { class: 'btn primary', type: 'button', 'data-testid': 'sources-close' }, 'Close');
  const wrap = h('div', { class: 'dialog-wrap' },
    h('div', { class: 'dialog', role: 'dialog', 'aria-modal': 'true', 'aria-label': 'Sources and disclaimer', 'data-testid': 'sources-dialog' },
      h('h2', {}, 'Sources and disclaimer'),
      h('div', { style: { maxHeight: '60vh', overflowY: 'auto' } }, sourcesAndDisclaimer()),
      h('div', { class: 'actions' }, h('span', { class: 'spacer' }), close)));
  close.addEventListener('click', () => wrap.remove());
  document.body.append(wrap);
  close.focus();
}
