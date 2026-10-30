// First-run introduction (port of ui/home/IntroDialog.kt): the hadith on keeping the Qur'an,
// choosing how the app looks, then how to mark and how to revise. The sources and disclaimer are a
// tap away on the last page, and always in Settings.

import { h } from '../lib/dom.js';
import { PlayerBackgrounds } from '../video/plan.js';
import { photoSubjects, thumbUrl } from '../video/data.js';
import { BackgroundScenes, variant } from '../video/scenes.js';
import { showSources } from './terms.js';

const THEMES = [['DARK', 'Dark'], ['LIGHT', 'Light'], ['SYSTEM', 'Match system']];
const BACKGROUNDS = [
  [PlayerBackgrounds.PLAIN, 'Plain', 'The player as it is'],
  [PlayerBackgrounds.MATCHED, 'Scenes', 'Drawn, matched to each ayah'],
  [PlayerBackgrounds.PHOTOS, 'Nature photos', 'Matched to each ayah'],
];

/** Theme and player background, applied as they are picked: the app changes behind the dialog. */
function lookPage(store) {
  const body = h('div', { class: 'stack', 'data-testid': 'intro-look' });
  const thumbs = new BackgroundScenes(72, 96);
  const sceneThumb = h('canvas', { width: 72, height: 96 });
  thumbs.draw(sceneThumb.getContext('2d'), variant('mercy', 0), 2000);
  thumbs.release();
  const photoThumb = h('img', { alt: '', loading: 'lazy' });
  photoSubjects().then((subjects) => { if (subjects[0]) photoThumb.src = thumbUrl(subjects[0]); }).catch(() => {});
  const pictures = {
    [PlayerBackgrounds.PLAIN]: h('span', { class: 'plain-swatch' }),
    [PlayerBackgrounds.MATCHED]: sceneThumb,
    [PlayerBackgrounds.PHOTOS]: photoThumb,
  };
  const pick = (value) => {
    // As in the player's background button: a background brings the ayah's translation with it.
    if (store.settings.playerBackground === PlayerBackgrounds.PLAIN && value !== PlayerBackgrounds.PLAIN) store.setSetting('showAyahTranslation', true);
    store.setSetting('playerBackground', value);
    draw();
  };
  function draw() {
    const s = store.settings;
    body.replaceChildren(
      h('p', { style: { margin: 0 } }, 'Make it yours. You can change these any time in Settings, or from the background button in the player.'),
      h('div', { class: 'small muted' }, 'Theme'),
      h('div', { class: 'chips' }, THEMES.map(([value, label]) => h('button', {
        class: 'chip', type: 'button', 'aria-pressed': String(s.theme === value), 'data-testid': `intro-theme-${value}`,
        onClick: () => { store.setSetting('theme', value); draw(); },
      }, label))),
      h('div', { class: 'small muted' }, 'Behind the ayah while it plays'),
      h('div', { class: 'theme-row' }, BACKGROUNDS.map(([value, title, sub]) => h('button', {
        class: `theme-tile${s.playerBackground === value ? ' selected' : ''}`, type: 'button', 'aria-pressed': String(s.playerBackground === value),
        'data-testid': `intro-background-${value}`, title: sub, onClick: () => pick(value),
      }, h('span', { class: 'theme-pic' }, pictures[value]), h('span', { class: 'theme-name' }, title)))),
    );
  }
  draw();
  return body;
}

const PAGES = [
  () => h('div', { class: 'stack' },
    h('p', { class: 'arabic', lang: 'ar', style: { fontSize: '1.5rem', textAlign: 'center', margin: 0 } },
      'تَعَاهَدُوا هَذَا الْقُرْآنَ، فَوَالَّذِي نَفْسُ مُحَمَّدٍ بِيَدِهِ لَهُوَ أَشَدُّ تَفَلُّتًا مِنَ الْإِبِلِ فِي عُقُلِهَا'),
    h('p', { style: { margin: 0 } }, '"Keep refreshing your knowledge of the Qur\'an, for by Him in Whose Hand my soul is, it is more liable to escape than camels which are tied."'),
    h('p', { class: 'small muted', style: { margin: 0 } }, 'Sahih al-Bukhari 5033 · Sahih Muslim 791'),
    h('p', { class: 'small muted' }, 'Mark the ayahs you have memorised, and this app will help you revise them.')),
  (store) => lookPage(store),
  () => tips([
    'Open any surah and tap the circle beside an ayah you have memorised.',
    'Adding a lot at once? “Add by description” takes refs like 2:255, 36:1-83, or whole surahs like 112.',
    'Already use the Android app? Export there, then Import here — the files are the same.',
  ]),
  () => h('div', { class: 'stack' },
    tips([
      'Press play and your learned ayahs recite in order — turn on “Repeat: Ayah” to have each one repeat so it settles in.',
      'The player reads along with you: each word lights up as it is recited. Swipe or tap to move between ayahs.',
      'Try “Word by word”: one word at a time with its meaning. Next and Previous — even on headphones — step word by word.',
    ]),
    h('p', { class: 'small muted', style: { margin: 0 } },
      'The Qur\'an text, meanings and recitations come from public sources. ',
      h('button', { class: 'link', type: 'button', 'data-testid': 'intro-sources', onClick: showSources }, 'Sources and disclaimer'),
      ' — also in Settings.')),
];

function tips(items) {
  return h('ul', { class: 'stack', style: { paddingLeft: '18px', margin: 0 } }, items.map((t) => h('li', {}, t)));
}

export function showIntro(store, onDone) {
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
    body.replaceChildren(PAGES[page](store));
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
