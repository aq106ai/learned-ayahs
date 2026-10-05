// First-run introduction (port of ui/home/IntroDialog.kt): the hadith on keeping the Qur'an,
// then how to mark and how to revise.

import { h } from '../lib/dom.js';

const PAGES = [
  () => h('div', { class: 'stack' },
    h('p', { class: 'arabic', lang: 'ar', style: { fontSize: '1.5rem', textAlign: 'center', margin: 0 } },
      'تَعَاهَدُوا هَذَا الْقُرْآنَ، فَوَالَّذِي نَفْسُ مُحَمَّدٍ بِيَدِهِ لَهُوَ أَشَدُّ تَفَلُّتًا مِنَ الْإِبِلِ فِي عُقُلِهَا'),
    h('p', { style: { margin: 0 } }, '"Keep refreshing your knowledge of the Qur\'an, for by Him in Whose Hand my soul is, it is more liable to escape than camels which are tied."'),
    h('p', { class: 'small muted', style: { margin: 0 } }, 'Sahih al-Bukhari 5033 · Sahih Muslim 791'),
    h('p', { class: 'small muted' }, 'Mark the ayahs you have memorised, and this app will help you revise them.')),
  () => tips([
    'Open any surah and tap the circle beside an ayah you have memorised.',
    'Adding a lot at once? “Add by description” takes refs like 2:255, 36:1-83, or whole surahs like 112.',
    'Already use the Android app? Export there, then Import here — the files are the same.',
  ]),
  () => tips([
    'Press play and your learned ayahs recite in order — turn on “Repeat: Ayah” to have each one repeat so it settles in.',
    'The player reads along with you: each word lights up as it is recited. Swipe or tap to move between ayahs.',
    'Try “Word by word”: one word at a time with its meaning. Next and Previous — even on headphones — step word by word.',
  ]),
];

function tips(items) {
  return h('ul', { class: 'stack', style: { paddingLeft: '18px', margin: 0 } }, items.map((t) => h('li', {}, t)));
}

export function showIntro(onDone) {
  let page = 0;
  const body = h('div', {});
  const counter = h('span', { class: 'small muted' });
  const next = h('button', { class: 'btn primary', type: 'button', 'data-testid': 'intro-next' });
  const skip = h('button', { class: 'btn ghost', type: 'button', 'data-testid': 'intro-skip' }, 'Skip');
  const wrap = h('div', { class: 'dialog-wrap' },
    h('div', { class: 'dialog', role: 'dialog', 'aria-modal': 'true', 'aria-label': 'Welcome', 'data-testid': 'intro' },
      h('h2', {}, 'Welcome to Learned Ayahs'),
      body,
      h('div', { class: 'actions', style: { alignItems: 'center' } }, counter, h('span', { class: 'spacer' }), skip, next)));
  const finish = () => {
    wrap.remove();
    onDone();
  };
  const draw = () => {
    body.replaceChildren(PAGES[page]());
    counter.textContent = `${page + 1} / ${PAGES.length}`;
    next.textContent = page === PAGES.length - 1 ? 'Begin' : 'Next';
    skip.hidden = page === PAGES.length - 1;
  };
  next.addEventListener('click', () => {
    if (page === PAGES.length - 1) finish();
    else {
      page++;
      draw();
    }
  });
  skip.addEventListener('click', finish);
  draw();
  document.body.append(wrap);
  next.focus();
}
