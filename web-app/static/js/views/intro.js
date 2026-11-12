// First-run introduction (port of ui/home/IntroDialog.kt): the hadith on keeping the Qur'an,
// choosing how the app looks, what to read beside the Arabic (translation, word meanings, tafsir)
// and who recites, then how to mark and how to revise. The sources and disclaimer are a
// tap away on the last page, and always in Settings.

import { h } from '../lib/dom.js';
import { PlayerBackgrounds } from '../video/plan.js';
import { isPhoto, loadPhoto, photoFor, photoSubjects, subjectFor, themeKey, thumbUrl } from '../video/data.js';
import { BackgroundScenes, photoSpec, variant } from '../video/scenes.js';
import { setScene } from './sceneColours.js';
import { showSources } from './terms.js';
import { contentChoices } from './contentChoices.js';
import { uiLanguagePicker } from './uiLanguage.js';

import { tx } from '../core/i18n.js';
const THEMES = [['DARK', tx('Dark')], ['LIGHT', tx('Light')], ['SYSTEM', tx('Match system')]];
const BACKGROUNDS = [
  [PlayerBackgrounds.PLAIN, tx('Plain'), tx('The player as it is')],
  [PlayerBackgrounds.MATCHED, tx('Scenes'), tx('Drawn, matched to each ayah')],
  [PlayerBackgrounds.PHOTOS, tx('Nature photos'), tx('Matched to each ayah')],
];

/**
 * The chosen background, painted behind the welcome dialog the moment it is picked (the opening
 * ayah's scene or photo, as the player would show it), and the app recoloured to match. The
 * welcome opens over the surah list, where no player is on screen to show it.
 */
let backdropToken = 0;
async function paintBackdrop(canvas, value) {
  const token = ++backdropToken;
  if (value === PlayerBackgrounds.PLAIN) {
    canvas.hidden = true;
    setScene(null);
    return;
  }
  const drawnKey = await themeKey(1, 1).catch(() => null);
  let spec = variant(drawnKey, 1);
  try {
    const subjects = await photoSubjects();
    const key = PlayerBackgrounds.themeFor(value, drawnKey, (k) => subjectFor(subjects, k)?.themeKey ?? null);
    if (isPhoto(key)) {
      const photo = photoFor(subjects, key, 1);
      if (photo) spec = photoSpec(photo.id, await loadPhoto(photo));
    } else if (key) {
      spec = variant(key, 1);
    }
  } catch {
    // The photo could not be loaded: the drawn scene stands in, as it does in the player.
  }
  if (token !== backdropToken || !canvas.isConnected) return;
  const width = Math.max(2, Math.round(window.innerWidth / 2));
  const height = Math.max(2, Math.round(window.innerHeight / 2));
  const scenes = new BackgroundScenes(width, height);
  canvas.width = width;
  canvas.height = height;
  scenes.draw(canvas.getContext('2d'), spec, 2000);
  scenes.release();
  canvas.hidden = false;
  setScene(spec);
}

/** Theme and player background, applied as they are picked: the app changes behind the dialog. */
function lookPage(store, onBackground) {
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
    onBackground(value);
    draw();
  };
  function draw() {
    const s = store.settings;
    body.replaceChildren(
      h('p', { style: { margin: 0 } }, tx('Make it yours. You can change these any time: the language in Settings, the theme and background from the three dots in the player.')),
      h('div', { class: 'small muted' }, tx('App language')),
      uiLanguagePicker(),
      h('div', { class: 'small muted' }, tx('Theme')),
      h('div', { class: 'chips' }, THEMES.map(([value, label]) => h('button', {
        class: 'chip', type: 'button', 'aria-pressed': String(s.theme === value), 'data-testid': `intro-theme-${value}`,
        onClick: () => { store.setSetting('theme', value); draw(); },
      }, label))),
      h('div', { class: 'small muted' }, tx('Behind the ayah while it plays')),
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
    h('p', { style: { margin: 0 } }, tx('"Keep refreshing your knowledge of the Qur\'an, for by Him in Whose Hand my soul is, it is more liable to escape than camels which are tied."')),
    h('p', { class: 'small muted', style: { margin: 0 } }, tx('Sahih al-Bukhari 5033 · Sahih Muslim 791')),
    h('p', { class: 'small muted' }, tx('Mark the ayahs you have memorised, and this app will help you revise them.'))),
  (store, onBackground) => lookPage(store, onBackground),
  (store) => h('div', { class: 'stack', 'data-testid': 'intro-content' },
    h('p', { style: { margin: 0 } }, tx('What to read beside the Arabic, and who recites. All of it can be changed later in Settings.')),
    contentChoices(store, { reciter: true })),
  () => tips([
    'Open any surah and tap the circle beside an ayah you have memorised.',
    tx('Adding a lot at once? “Add by description” takes refs like 2:255, 36:1-83, or whole surahs like 112.'),
    tx('Already use the Android app? Export there, then Import here — the files are the same.'),
  ]),
  () => h('div', { class: 'stack' },
    tips([
      'Press play and your learned ayahs recite in order — turn on “Repeat: Ayah” to have each one repeat so it settles in.',
      tx('The player reads along with you: each word lights up as it is recited. Swipe or tap to move between ayahs.'),
      tx('Try “Word by word”: one word at a time with its meaning. Next and Previous — even on headphones — step word by word.'),
    ]),
    h('p', { class: 'small muted', style: { margin: 0 } },
      tx('The Qur\'an text, meanings and recitations come from public sources. '),
      h('button', { class: 'link', type: 'button', 'data-testid': 'intro-sources', onClick: showSources }, tx('Sources and disclaimer')),
      tx(' — also in Settings.'))),
];

function tips(items) {
  return h('ul', { class: 'stack', style: { paddingInlineStart: '18px', margin: 0 } }, items.map((t) => h('li', {}, t)));
}

export function showIntro(store, onDone) {
  let page = 0;
  const body = h('div', {});
  const counter = h('span', { class: 'small muted' });
  const next = h('button', { class: 'btn primary', type: 'button', 'data-testid': 'intro-next' });
  const skip = h('button', { class: 'btn ghost', type: 'button', 'data-testid': 'intro-skip' }, tx('Skip'));
  const backdrop = h('canvas', { class: 'intro-backdrop', hidden: true, 'aria-hidden': 'true', 'data-testid': 'intro-backdrop' });
  const onBackground = (value) => paintBackdrop(backdrop, value);
  const wrap = h('div', { class: 'dialog-wrap' },
    backdrop,
    h('div', { class: 'dialog', role: 'dialog', 'aria-modal': 'true', 'aria-label': tx('Welcome'), 'data-testid': 'intro' },
      h('h2', {}, tx('Welcome to Learned Ayahs')),
      body,
      h('div', { class: 'actions', style: { alignItems: 'center' } }, counter, h('span', { class: 'spacer' }), skip, next)));
  const finish = () => {
    backdropToken++;
    wrap.remove();
    onDone();
  };
  const draw = () => {
    body.replaceChildren(PAGES[page](store, onBackground));
    counter.textContent = `${page + 1} / ${PAGES.length}`;
    next.textContent = page === PAGES.length - 1 ? tx('Begin') : tx('Next');
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
  if (store.settings.playerBackground !== PlayerBackgrounds.PLAIN) onBackground(store.settings.playerBackground);
  next.focus();
}
