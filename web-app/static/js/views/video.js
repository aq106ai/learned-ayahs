// "Make a video": pick the ayahs, the shape, the reciter, the text and the background, see a
// still of the first ayah, then make the video in the browser and download or share it.
// Port of the Android app's ui/video/VideoMakerScreen.kt.
//
// Route: #/video/<surah>/<ayah>[/player]. ayah 0 starts at the beginning of the surah; "player"
// starts the options out like the player (its background, translation and word meanings).

import { h, icon, toast } from '../lib/dom.js';
import { SURAH_COUNT, surahName, verseCount } from '../core/quran.js';
import { RECITERS, reciterFromKey } from '../core/reciters.js';
import { ASPECTS, VideoExporter, aspectFromKey, caption, fileName, supportedFormat } from '../video/exporter.js';
import { isPhoto, photoSubjects, thumbUrl, themeKey } from '../video/data.js';
import { PlayerBackgrounds } from '../video/plan.js';
import { BackgroundScenes, THEMES, theme, variant } from '../video/scenes.js';

const DEFAULT_SURAH_AYAHS = 10;
const PREVIEW_W = 540;
const STAGES = { DOWNLOADING: 'Downloading the recitation…', AUDIO: 'Preparing the audio…', VIDEO: 'Drawing the video…' };

/** Options like the player's: its background, translation and word meanings. */
export function likeThePlayer(options, settings) {
  const bg = settings.playerBackground;
  const out = { ...options, fullTranslation: settings.showAyahTranslation, wordByWord: settings.showWordTranslations };
  if (bg === PlayerBackgrounds.PLAIN || bg === PlayerBackgrounds.MATCHED) return { ...out, themeKey: null, photos: false };
  if (bg === PlayerBackgrounds.PHOTOS) return { ...out, themeKey: null, photos: true };
  return { ...out, themeKey: bg, photos: isPhoto(bg) };
}

export function videoView({ store, engine, server, go, params }) {
  const surah = Math.min(Math.max(Number(params[0]) || 1, 1), SURAH_COUNT);
  const ayah = Number(params[1]) || 0;
  const count = verseCount(surah);
  let options = {
    surah,
    fromAyah: ayah >= 1 && ayah <= count ? ayah : 1,
    toAyah: ayah >= 1 && ayah <= count ? ayah : Math.min(count, DEFAULT_SURAH_AYAHS),
    reciter: store.settings.reciter,
    aspect: 'PORTRAIT',
    wordByWord: true,
    fullTranslation: true,
    opening: !(ayah > 1),
    themeKey: null,
    photos: false,
    credits: false,
  };
  if (params[2] === 'player') options = likeThePlayer(options, store.settings);

  const exporter = new VideoExporter({ proxy: Boolean(server.available) && !store.settings.streamDirect });
  const request = () => ({ ...options, reciter: reciterFromKey(options.reciter) });
  let job = null; // AbortController while making
  let result = null; // {blob, format}
  let previewTimer = 0;
  let previewToken = 0;
  let subjects = [];

  // -- preview -------------------------------------------------------------------------------

  const previewBox = h('div', { class: 'video-preview', 'data-testid': 'video-preview' }, h('p', { class: 'muted small' }, 'Preparing preview…'));
  function refreshPreview() {
    clearTimeout(previewTimer);
    previewTimer = setTimeout(async () => {
      const token = ++previewToken;
      const aspect = aspectFromKey(options.aspect);
      previewBox.style.aspectRatio = `${aspect.width} / ${aspect.height}`;
      try {
        const canvas = await exporter.still(request(), PREVIEW_W);
        if (token !== previewToken) return;
        const img = h('canvas', { width: canvas.width, height: canvas.height, 'aria-label': 'Preview of the first ayah' });
        img.getContext('2d').drawImage(canvas, 0, 0);
        previewBox.replaceChildren(img);
      } catch (e) {
        if (token === previewToken) previewBox.replaceChildren(h('p', { class: 'muted small' }, e.message || "The preview couldn't be drawn."));
      }
    }, 150);
  }

  // Changes are applied after the event that made them: re-drawing the form removes the focused
  // field, and its blur would otherwise fire another change in the middle of the re-draw.
  function update(change) {
    setTimeout(() => apply(change));
  }

  function apply(change) {
    options = { ...options, ...change };
    const max = verseCount(options.surah);
    options.fromAyah = Math.min(Math.max(options.fromAyah, 1), max);
    options.toAyah = Math.min(Math.max(options.toAyah, options.fromAyah), max);
    result = null;
    render();
    refreshPreview();
  }

  // -- building blocks -----------------------------------------------------------------------

  const section = (title, ...children) => h('section', { class: 'card' }, h('h2', {}, title), ...children);
  const chip = (label, selected, onClick, testid) =>
    h('button', { class: 'chip', type: 'button', 'aria-pressed': String(selected), disabled: Boolean(job), 'data-testid': testid, onClick }, label);
  const toggle = (key, title, sub) =>
    h('label', { class: 'toggle-row' },
      h('span', { class: 'text' }, h('strong', {}, title), h('span', {}, sub)),
      h('input', { class: 'switch', type: 'checkbox', checked: options[key], disabled: Boolean(job), 'data-testid': `video-${key}`, onChange: (e) => update({ [key]: e.target.checked }) }));
  const stepper = (label, value, min, max, onChange, testid) =>
    h('label', { class: 'field inline' }, h('span', {}, label),
      h('input', { class: 'input', type: 'number', min, max, value, disabled: Boolean(job), 'data-testid': testid, onChange: (e) => onChange(Number(e.target.value) || min) }));

  const thumbs = new Map();
  function drawnThumb(key) {
    if (!thumbs.has(key)) {
      const scenes = new BackgroundScenes(108, 192);
      const c = h('canvas', { width: 108, height: 192 });
      scenes.draw(c.getContext('2d'), variant(key, 0), 2000);
      thumbs.set(key, c.toDataURL('image/jpeg', 0.8));
    }
    return thumbs.get(key);
  }
  const tile = (title, sub, selected, image, onClick, testid) =>
    h('button', { class: `theme-tile${selected ? ' selected' : ''}`, type: 'button', disabled: Boolean(job), 'aria-pressed': String(selected), 'data-testid': testid, onClick },
      h('span', { class: 'theme-pic' }, image ? h('img', { src: image, alt: '', loading: 'lazy' }) : h('span', {}, 'Auto')),
      h('span', { class: 'theme-name' }, title),
      sub ? h('span', { class: 'theme-sub' }, sub) : null);

  // -- the page ------------------------------------------------------------------------------

  const body = h('div', { class: 'stack' });
  const el = h('div', { class: 'page video-page', 'data-testid': 'video-screen' },
    h('div', { class: 'page-head' },
      h('button', { class: 'btn ghost icon-only', type: 'button', 'aria-label': 'Back', onClick: () => history.length > 1 ? history.back() : go('/player') }, icon('chevronLeft')),
      h('h1', {}, 'Make a video')),
    previewBox,
    body);

  let matchedTitle = '';
  async function refreshMatched() {
    const drawn = await themeKey(options.surah, options.fromAyah);
    matchedTitle = options.photos ? (subjects.find((s) => s.photos[0].themes?.includes(drawn))?.title ?? '') : theme(drawn).title;
    render();
  }

  function render() {
    const working = Boolean(job);
    const max = verseCount(options.surah);
    const surahSelect = h('select', { class: 'select', disabled: working, 'data-testid': 'video-surah', onChange: (e) => {
      const s = Number(e.target.value);
      update({ surah: s, fromAyah: 1, toAyah: Math.min(verseCount(s), DEFAULT_SURAH_AYAHS), opening: true });
      refreshMatched();
    } }, Array.from({ length: SURAH_COUNT }, (_, i) => h('option', { value: i + 1, selected: i + 1 === options.surah }, `${i + 1} · ${surahName(i + 1)}`)));

    const ayahs = section('Ayahs',
      surahSelect,
      h('div', { class: 'row' },
        stepper('From ayah', options.fromAyah, 1, max, (v) => { update({ fromAyah: v, toAyah: Math.max(v, options.toAyah) }); refreshMatched(); }, 'video-from'),
        stepper('To ayah', options.toAyah, options.fromAyah, max, (v) => update({ toAyah: v }), 'video-to'),
        h('button', { class: 'btn small', type: 'button', disabled: working, onClick: () => update({ fromAyah: 1, toAyah: max, opening: true }) }, 'Whole surah')),
      h('p', { class: 'small muted' }, `${max} ayahs in ${surahName(options.surah)}`));

    const shape = section('Shape', h('div', { class: 'chips' }, ASPECTS.map((a) => chip(`${a.ratio} · ${a.label}`, options.aspect === a.key, () => update({ aspect: a.key }), `video-aspect-${a.key}`))));
    const reciter = section('Reciter', h('div', { class: 'chips' }, RECITERS.map((r) => chip(r.displayName, options.reciter === r.key, () => update({ reciter: r.key }), `video-reciter-${r.key}`))));
    const bismillah = options.fromAyah === 1 && options.surah !== 1 && options.surah !== 9;
    const text = section('Text',
      toggle('wordByWord', 'Word-by-word meanings', 'Under each Arabic word'),
      toggle('fullTranslation', 'Translation', 'Saheeh International'),
      toggle('opening', bismillah ? "Begin with A'udhu and Bismillah" : "Begin with A'udhu", 'Recited before the first ayah'),
      toggle('credits', 'Credits on video', 'Sources in small print under the text. Off, they go in the share message'));

    const tiles = h('div', { class: 'theme-row' },
      tile('Matched', matchedTitle, options.themeKey === null, null, () => update({ themeKey: null }), 'video-theme-matched'),
      ...(options.photos
        ? subjects.map((s) => tile(s.title, null, options.themeKey === s.themeKey, thumbUrl(s), () => update({ themeKey: s.themeKey }), `video-theme-${s.themeKey}`))
        : THEMES.map((t) => tile(t.title, null, options.themeKey === t.key, drawnThumb(t.key), () => update({ themeKey: t.key }), `video-theme-${t.key}`))));
    const background = section('Background',
      subjects.length ? h('div', { class: 'chips' },
        chip('Drawn scenes', !options.photos, () => { update({ photos: false, themeKey: null }); refreshMatched(); }, 'video-drawn'),
        chip('Nature photos', options.photos, () => { update({ photos: true, themeKey: null }); refreshMatched(); }, 'video-photos')) : null,
      h('p', { class: 'small muted' }, 'Matched picks a background for each ayah from what it is about. Or choose one for the whole video.'),
      tiles,
      h('p', { class: 'small muted' }, options.photos
        ? 'Photos of nature only, from Wikimedia Commons (CC0), each checked by hand: never people or animals. Videos carry no music, only the recitation.'
        : 'Backgrounds are drawn scenes of sky, land, water, light and pattern, never people or animals. Videos carry no music, only the recitation.'));

    body.replaceChildren(ayahs, shape, reciter, text, background, videoSection());
  }

  // -- making it -----------------------------------------------------------------------------

  const progressBar = h('div', { class: 'progress' }, h('span'));
  const progressText = h('p', { class: 'small' });
  function videoSection() {
    const n = options.toAyah - options.fromAyah + 1;
    const r = reciterFromKey(options.reciter);
    if (job) {
      return section('Video', progressText, progressBar,
        h('button', { class: 'btn', type: 'button', onClick: () => job?.abort() }, 'Cancel'));
    }
    if (result) {
      const mb = (result.blob.size / (1024 * 1024)).toFixed(1);
      const name = fileName(options, result.format.ext);
      const file = new File([result.blob], name, { type: result.format.mime });
      const canShare = navigator.canShare?.({ files: [file] });
      return section('Video',
        h('p', {}, `Your video is ready (${mb} MB, ${result.format.ext.toUpperCase()}).`),
        h('div', { class: 'row' },
          h('button', { class: 'btn primary', type: 'button', 'data-testid': 'video-download', onClick: () => download(result.blob, name) }, icon('download'), 'Download'),
          canShare ? h('button', { class: 'btn', type: 'button', 'data-testid': 'video-share', onClick: () => navigator.share({ files: [file], text: caption(options, r.displayName) }).catch(() => {}) }, icon('share'), 'Share') : null,
          h('button', { class: 'btn ghost', type: 'button', onClick: () => { result = null; render(); } }, 'Make another')),
        h('p', { class: 'small muted' }, 'When you post it, the sources go in the text:'),
        h('pre', { class: 'caption-text', 'data-testid': 'video-caption' }, caption(options, r.displayName)));
    }
    return section('Video',
      h('p', { class: 'small muted' }, `${n} ayah${n === 1 ? '' : 's'} · ${r.displayName}. It's made in this browser; keep this page open until it's done.`),
      formatNote,
      h('button', { class: 'btn primary block', type: 'button', 'data-testid': 'video-create', onClick: create }, icon('film'), 'Create video'));
  }

  const formatNote = h('p', { class: 'small muted', 'data-testid': 'video-format' });
  supportedFormat(aspectFromKey('PORTRAIT')).then((f) => {
    formatNote.textContent = f
      ? (f.container === 'mp4' ? 'Saved as MP4.' : 'Saved as WebM (this browser can’t write MP4; YouTube and most players take WebM).')
      : 'This browser can’t make videos. Use a recent Chrome, Edge or Safari.';
  });

  async function create() {
    job = new AbortController();
    progressText.textContent = STAGES.DOWNLOADING;
    progressBar.firstChild.style.width = '0%';
    render();
    try {
      result = await exporter.export(request(), {
        signal: job.signal,
        onProgress: ({ stage, fraction }) => {
          progressText.textContent = `${STAGES[stage]} ${Math.round(fraction * 100)}%`;
          progressBar.firstChild.style.width = `${Math.round(fraction * 100)}%`;
        },
      });
    } catch (e) {
      if (e.name !== 'AbortError') toast(e.message || "The video couldn't be made in this browser.", { error: true, timeout: 7000 });
    } finally {
      job = null;
      render();
    }
  }

  // Leaving the page cancels a video in progress.
  const destroy = () => {
    job?.abort();
    clearTimeout(previewTimer);
  };

  photoSubjects().then((s) => {
    subjects = s;
    render();
  });
  render();
  refreshMatched();
  refreshPreview();
  return { el, title: 'Make a video', destroy };
}

export function download(blob, name) {
  const url = URL.createObjectURL(blob);
  const a = h('a', { href: url, download: name });
  document.body.append(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 60_000);
}
