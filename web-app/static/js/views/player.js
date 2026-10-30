// The player: an immersive ayah reader with playback controls, the surah navigator and the
// playlist as side panels. Port of ui/PlayerScreen.kt, ui/PlaylistScreen.kt and ui/AyahTextSection.kt.

import { dialog, formatTime, h, icon, toast } from '../lib/dom.js';
import {
  PLAYBACK_MODE_LABELS,
  PlaybackMode,
  RepeatMode,
  VERSE_COUNTS,
  globalToSurahAyah,
  repeatLabel,
  surahName,
  trackTitle,
} from '../core/quran.js';
import { RECITERS, reciterFromKey } from '../core/reciters.js';
import { REVISION_DELAY_CHOICES } from '../state/store.js';
import { learnedCountsBySurah, matchSurahs } from './common.js';
import { wordReader } from './wordReader.js';
import { playerBackground } from './playerBackground.js';
import { download } from './video.js';
import { photoSubjects, thumbUrl, translation } from '../video/data.js';
import { ASPECTS, VideoExporter, caption, fileName } from '../video/exporter.js';
import { PlayerBackgrounds } from '../video/plan.js';
import { BackgroundScenes, THEMES, variant } from '../video/scenes.js';

export function playerView({ store, engine, server, go }) {
  let overlay = false; // word-by-word overlay in the whole-ayah modes
  let reader = null;
  let expanded = false;
  let wakeLock = null;

  // -- header ------------------------------------------------------------------------------

  const meta = h('div', { class: 'meta', 'data-testid': 'player-meta' });
  const wordToggle = h('button', {
    class: 'btn small',
    type: 'button',
    'data-testid': 'toggle-word-by-word',
    onClick: () => {
      overlay = !overlay;
      renderReader();
    },
  });
  const head = h(
    'div',
    { class: 'player-head' },
    h('button', { class: 'btn ghost icon-only', type: 'button', 'aria-label': 'Browse surahs', 'data-testid': 'open-surah-panel', onClick: () => openSurahPanel() }, icon('list')),
    meta,
    h('button', { class: 'btn ghost icon-only', type: 'button', 'aria-label': 'Playlist', 'data-testid': 'open-playlist', onClick: () => openPlaylistPanel() }, icon('layers')),
    wordToggle,
    h('button', { class: 'btn ghost icon-only', type: 'button', 'aria-label': 'Background', 'data-testid': 'open-background', onClick: () => openBackgroundDialog() }, icon('image')),
    h('button', { class: 'btn ghost icon-only', type: 'button', 'aria-label': 'Share this ayah', 'data-testid': 'share', onClick: () => openShareDialog() }, icon('share')),
  );
  const background = playerBackground({ engine, store });

  // -- reader ------------------------------------------------------------------------------

  const readerHost = h('div', { class: 'reader-inner' });
  const readerEl = h('div', { class: 'reader', 'data-testid': 'reader' }, readerHost);
  let wordEls = [];

  function emptyState() {
    return h(
      'div',
      { class: 'empty', 'data-testid': 'player-empty' },
      h('h2', {}, 'No ayahs marked yet.'),
      h('p', {}, 'Open a surah and tick the ayahs you have memorised to add them to your revision. You can also add several at once by description.'),
      h('div', { class: 'row', style: { justifyContent: 'center' } },
        h('a', { class: 'btn primary', href: '#/surahs' }, icon('book'), 'Browse surahs'),
        h('a', { class: 'btn', href: '#/add' }, icon('plus'), 'Add by description'),
      ),
    );
  }

  function wholeAyah() {
    const words = engine.words;
    const showTr = store.settings.showWordTranslations;
    let content = 0;
    wordEls = [];
    const flow = h(
      'div',
      { class: 'ayah-flow', lang: 'ar', 'data-testid': 'ayah-text' },
      words.map((w) => {
        const el = h(
          'span',
          { class: `word${w.isEnd ? ' end' : ''}` },
          h('span', { class: 'ar' }, w.text),
          showTr && !w.isEnd ? h('span', { class: 'tr' }, w.translation ?? '') : null,
        );
        if (!w.isEnd) {
          const index = content++;
          el.dataset.index = String(index);
          wordEls.push(el);
        }
        return el;
      }),
    );
    const t = engine.track;
    // The whole ayah's meaning (Saheeh International), when it's on.
    const meaning = t && store.settings.showAyahTranslation ? h('p', { class: 'ayah-translation', 'data-testid': 'ayah-translation' }) : null;
    if (meaning) translation(t.surah, t.ayah).then((text) => { meaning.textContent = text ?? ''; fitAyah(); }).catch(() => {});
    return h(
      'div',
      {},
      engine.introLabel ? h('div', { class: 'intro-label', 'data-testid': 'intro-label' }, engine.introLabel) : null,
      words.length ? flow : h('p', { class: 'muted', style: { textAlign: 'center' } }, 'Loading ayah text…'),
      meaning,
      t ? h('div', { class: 'ayah-caption' }, `${surahName(t.surah)} · Ayah ${t.ayah}`) : null,
    );
  }

  const readerKind = () => {
    if (!engine.track) return 'empty';
    return engine.mode === PlaybackMode.WORD_BY_WORD || overlay ? 'word' : 'whole';
  };
  let renderedKind = null;
  let renderedIntro = null;

  function renderReader() {
    reader?.destroy();
    reader = null;
    renderedKind = readerKind();
    renderedIntro = engine.introLabel;
    const wordMode = engine.mode === PlaybackMode.WORD_BY_WORD;
    wordToggle.hidden = wordMode || !engine.track;
    wordToggle.textContent = overlay ? 'Whole ayah' : 'Word by word';
    if (!engine.track) {
      readerEl.classList.remove('tap');
      readerHost.replaceChildren(emptyState());
      return;
    }
    if (wordMode || overlay) {
      reader = wordReader({ engine, store });
      readerEl.classList.remove('tap');
      readerHost.replaceChildren(reader.el);
    } else {
      readerEl.classList.toggle('tap', store.settings.tapToAdvance);
      readerHost.replaceChildren(wholeAyah());
      fitAyah();
      updateHighlight();
    }
  }

  let lastActive = -2;
  function updateHighlight() {
    const active = engine.activeWord;
    if (active === lastActive) return;
    lastActive = active;
    wordEls.forEach((el, i) => el.classList.toggle('active', i === active));
    if (active >= 0 && store.settings.ayahOverflowMode === 'AUTO_SCROLL' && readerEl.scrollHeight > readerEl.clientHeight) {
      wordEls[active]?.scrollIntoView({ block: 'center', behavior: 'smooth' });
    }
  }

  /** FIT_TO_SCREEN: shrink the ayah's font just enough that it fits without scrolling. */
  function fitAyah() {
    readerHost.style.removeProperty('--ayah-size');
    if (store.settings.ayahOverflowMode !== 'FIT_TO_SCREEN') return;
    let size = 3.2;
    requestAnimationFrame(() => {
      while (readerEl.scrollHeight > readerEl.clientHeight + 2 && size > 1.1) {
        size -= 0.1;
        readerHost.style.setProperty('--ayah-size', `${size.toFixed(2)}rem`);
      }
    });
  }

  // Tap and swipe on the whole-ayah reader.
  let startX = null;
  readerEl.addEventListener('pointerdown', (e) => {
    startX = e.clientX;
  });
  readerEl.addEventListener('pointerup', (e) => {
    if (reader || startX == null || !engine.track) return;
    const dx = e.clientX - startX;
    startX = null;
    if (e.target.closest('a, button')) return;
    if (Math.abs(dx) > 60) {
      if (store.settings.swipeToNavigate) (dx > 0 ? engine.next() : engine.prev());
    } else if (store.settings.tapToAdvance) {
      engine.next();
    }
  });

  // -- controls ----------------------------------------------------------------------------

  const nowTitle = h('div', { class: 'title', 'data-testid': 'now-playing' });
  const nowMode = h('span', { class: 'badge', 'data-testid': 'mode-label' });
  const playBtn = h('button', { class: 'round play', type: 'button', 'data-testid': 'play', onClick: () => engine.toggle() });
  const prevBtn = h('button', { class: 'round', type: 'button', 'aria-label': 'Previous', 'data-testid': 'prev', onClick: () => engine.prev() }, icon('prev'));
  const nextBtn = h('button', { class: 'round', type: 'button', 'aria-label': 'Next', 'data-testid': 'next', onClick: () => engine.next() }, icon('next'));
  const moreBtn = h('button', {
    class: 'btn ghost icon-only',
    type: 'button',
    'aria-label': 'More controls',
    'data-testid': 'expand-controls',
    onClick: () => {
      expanded = !expanded;
      renderMore();
    },
  });
  const seekInput = h('input', { type: 'range', min: '0', max: '1000', value: '0', 'aria-label': 'Seek', 'data-testid': 'seek' });
  const posLabel = h('span', {}, '0:00');
  const durLabel = h('span', {}, '0:00');
  const seekRow = h('div', { class: 'seek' }, posLabel, seekInput, durLabel);
  let seeking = false;
  seekInput.addEventListener('input', () => {
    seeking = true;
    posLabel.textContent = formatTime((seekInput.value / 1000) * engine.durationMs);
  });
  seekInput.addEventListener('change', () => {
    seeking = false;
    engine.seek((seekInput.value / 1000) * engine.durationMs);
  });
  const errorText = h('span', { class: 'spacer' });
  const errorRow = h('div', { class: 'notice error row', hidden: true, role: 'alert', 'data-testid': 'player-error' },
    errorText,
    h('button', { class: 'btn small', type: 'button', 'data-testid': 'retry', onClick: () => engine.retry() }, icon('refresh'), 'Retry'));
  const more = h('div', { class: 'more', 'data-testid': 'more-controls' });
  const reciteCta = h('div', { class: 'recite-cta' });

  const controls = h(
    'div',
    { class: 'controls' },
    reciteCta,
    errorRow,
    h('div', { class: 'now' }, nowTitle, nowMode, moreBtn),
    h('div', { class: 'transport' }, prevBtn, playBtn, nextBtn),
    seekRow,
    more,
  );

  const chip = (label, pressed, onClick, testid) =>
    h('button', { class: 'chip', type: 'button', 'aria-pressed': String(pressed), onClick, 'data-testid': testid }, label);

  function renderMore() {
    moreBtn.replaceChildren(icon(expanded ? 'chevronDown' : 'chevronUp'));
    more.hidden = !expanded;
    if (!expanded) return;
    const mode = engine.mode;
    const repeat = engine.repeat;
    const groups = [
      h('div', {}, h('div', { class: 'label' }, 'Play'), h('div', { class: 'chips' },
        Object.values(PlaybackMode).map((m) => chip(PLAYBACK_MODE_LABELS[m], m === mode, () => engine.setMode(m), `mode-${m}`)),
      )),
      h('div', {}, h('div', { class: 'label' }, 'Repeat'), h('div', { class: 'chips' },
        Object.values(RepeatMode).map((r) => chip(repeatLabel(r, mode), r === repeat, () => engine.setRepeat(r), `repeat-${r}`)),
      )),
    ];
    if (repeat === RepeatMode.AYAH) {
      groups.push(h('div', {},
        h('div', { class: 'label' }, 'Pause before repeat — time to revise or press Next'),
        h('div', { class: 'chips' }, REVISION_DELAY_CHOICES.map((s) =>
          chip(s === 0 ? 'Off' : `${s}s`, s === store.settings.revisionDelaySeconds, () => engine.setRevisionDelay(s), `delay-${s}`))),
      ));
    }
    if (mode === PlaybackMode.WORD_BY_WORD) {
      const clips = store.settings.useWordClips;
      groups.push(h('label', { class: 'toggle-row' },
        h('span', { class: 'text' },
          h('strong', {}, 'Word clips (Quran.com)'),
          h('span', {}, clips ? 'Isolated pronunciation clips. Recite & review always uses these.' : 'Off: each word is played in your ayah reciter, cut from the ayah audio.'),
        ),
        h('input', { class: 'switch', type: 'checkbox', checked: clips, onChange: (e) => store.setSetting('useWordClips', e.target.checked) }),
      ));
    }
    groups.push(h('div', {}, h('div', { class: 'label' }, 'Reciter'),
      h('select', { class: 'select', 'aria-label': 'Reciter', onChange: (e) => store.setSetting('reciter', e.target.value) },
        RECITERS.map((r) => h('option', { value: r.key, selected: r.key === store.settings.reciter }, r.displayName))),
    ));
    more.replaceChildren(...groups);
  }

  function renderState() {
    const t = engine.track;
    const active = engine.isActive;
    playBtn.replaceChildren(icon(active ? 'pause' : 'play', { filled: true }));
    playBtn.setAttribute('aria-label', active ? 'Pause' : 'Play');
    playBtn.disabled = !t;
    prevBtn.disabled = !t;
    nextBtn.disabled = !t;
    nowTitle.textContent = engine.introLabel ?? (t ? `${t.index}. ${trackTitle(t.surah, t.ayah)}` : 'Nothing to play yet');
    if (engine.gapPending) nowTitle.textContent += ' · pausing before repeat';
    nowMode.textContent = engine.modeLabel;
    errorRow.hidden = !engine.error;
    errorText.textContent = engine.error ? `${engine.error} Check your connection, or save ayahs for offline in Settings.` : '';
    seekRow.hidden = engine.mode === PlaybackMode.WORD_BY_WORD || !t;
    if (t) {
      const pos = engine.mode === PlaybackMode.FULL_SURAH ? `${t.ayah} of ${VERSE_COUNTS[t.surah - 1]}` : `${engine.index + 1} of ${engine.queue.length}`;
      meta.textContent = `${surahName(t.surah)} · Ayah ${t.ayah} · ${pos}`;
    } else {
      meta.textContent = 'Player';
    }
    reciteCta.replaceChildren(
      store.settings.reciteBetaEnabled && t
        ? h('a', { class: 'btn small primary', href: `#/recite/${t.globalId}`, 'data-testid': 'open-recite', onClick: () => engine.pause() }, icon('mic'), 'Recite & review')
        : '',
    );
    if (expanded) renderMore();
  }

  function renderTick() {
    updateHighlight();
    if (!seeking) {
      const d = engine.durationMs;
      const p = Math.min(engine.positionMs, d || Infinity);
      posLabel.textContent = formatTime(p);
      durLabel.textContent = formatTime(d);
      seekInput.value = d ? String(Math.round((p / d) * 1000)) : '0';
    }
  }

  // -- side panels -------------------------------------------------------------------------

  let panel = null;
  function closePanel() {
    panel?.remove();
    panel = null;
  }
  function openPanel(side, title, body, extra = null) {
    closePanel();
    panel = h('div', {},
      h('div', { class: 'scrim', onClick: closePanel }),
      h('aside', { class: `panel ${side}`, role: 'dialog', 'aria-label': title },
        h('div', { class: 'panel-head' }, h('h2', {}, title), extra,
          h('button', { class: 'btn ghost icon-only', type: 'button', 'aria-label': 'Close', onClick: closePanel }, icon('x'))),
        h('div', { class: 'panel-body' }, body),
      ),
    );
    document.body.append(panel);
    panel.querySelector('input, button')?.focus();
  }

  function openSurahPanel() {
    const counts = learnedCountsBySurah(store.learned);
    const list = h('div', {});
    const render = (q) => list.replaceChildren(...matchSurahs(q).map((s) => {
      const learned = counts.get(s) ?? 0;
      return h('button', {
        class: `list-row${engine.track?.surah === s ? ' current' : ''}`,
        type: 'button',
        onClick: () => {
          closePanel();
          if (engine.mode !== PlaybackMode.FULL_SURAH && !learned) engine.playSurahFrom(s, 1);
          else engine.jumpToSurah(s);
        },
      }, h('span', {}, `${s} · ${surahName(s)}`), h('span', { class: 'sub' }, learned ? `${learned} learned` : `${VERSE_COUNTS[s - 1]} ayahs`));
    }));
    const search = h('input', { class: 'input', type: 'search', placeholder: 'Search surah', onInput: (e) => render(e.target.value) });
    render('');
    openPanel('start', 'Surahs', h('div', { class: 'stack' },
      h('a', { class: 'btn', href: '#/surahs', onClick: closePanel }, icon('book'), 'Manage learned ayahs'),
      search,
      list,
    ));
  }

  function openPlaylistPanel() {
    const body = h('div', { class: 'stack' });
    const bookmarks = [...store.bookmarks].sort((a, b) => a - b);
    if (bookmarks.length) {
      body.append(h('div', { class: 'section-title', style: { margin: '4px 0 0' } }, 'Bookmarks'));
      body.append(...bookmarks.map((id) => {
        const [s, a] = globalToSurahAyah(id);
        return h('div', { class: 'row' },
          h('button', { class: 'list-row', type: 'button', onClick: () => { closePanel(); engine.playGlobal(id); } }, trackTitle(s, a)),
          h('button', { class: 'star-btn', type: 'button', 'aria-pressed': 'true', 'aria-label': 'Remove bookmark', onClick: () => { store.toggleBookmark(id); openPlaylistPanel(); } }, icon('star')),
        );
      }));
    }
    const queue = engine.queue;
    const surahs = [...new Set(queue.map((t) => t.surah))];
    body.append(h('div', { class: 'muted small' }, `${surahs.length} surahs · ${queue.length} ayahs · ${engine.modeLabel}`));
    for (const s of surahs) {
      const tracks = queue.filter((t) => t.surah === s);
      const group = h('details', { class: 'group', open: engine.track?.surah === s },
        h('summary', {}, `${surahName(s)}`, h('span', { class: 'sub muted small', style: { marginLeft: 'auto' } }, `${tracks.length} ayahs`)),
      );
      group.addEventListener('toggle', () => {
        if (!group.open || group.dataset.filled) return;
        group.dataset.filled = '1';
        group.append(...tracks.map((t) => h('div', { class: 'row' },
          h('button', { class: `list-row${t === engine.track ? ' current' : ''}`, type: 'button', onClick: () => { closePanel(); engine.playGlobal(t.globalId); } }, `Ayah ${t.ayah}`),
          h('button', {
            class: 'star-btn', type: 'button', 'aria-pressed': String(store.bookmarks.has(t.globalId)),
            'aria-label': store.bookmarks.has(t.globalId) ? 'Remove bookmark' : 'Bookmark this ayah',
            onClick: (e) => { store.toggleBookmark(t.globalId); e.currentTarget.setAttribute('aria-pressed', String(store.bookmarks.has(t.globalId))); },
          }, icon('star')),
        )));
      });
      body.append(group);
      if (group.open) group.dispatchEvent(new Event('toggle'));
    }
    if (!queue.length) body.append(h('p', { class: 'muted' }, 'Nothing in the playlist yet.'));
    openPanel('end', 'Playlist', body);
  }

  // -- background and share -----------------------------------------------------------------

  async function openBackgroundDialog() {
    const subjects = await photoSubjects();
    const current = () => store.settings.playerBackground;
    const pick = (value) => {
      // A background is the look of a shared video, translation included; turning one on brings
      // the translation with it. It can be switched off again just below.
      if (current() === PlayerBackgrounds.PLAIN && value !== PlayerBackgrounds.PLAIN) store.setSetting('showAyahTranslation', true);
      store.setSetting('playerBackground', value);
      draw();
    };
    const thumbs = new BackgroundScenes(72, 128);
    // Drawn once each: every pick redraws the dialog, and repainting dozens of scenes each time
    // stalled it on a slow phone. The same canvas is simply moved into the new row.
    const drawnThumbs = new Map();
    const drawnThumb = (key) => {
      if (!drawnThumbs.has(key)) {
        const c = h('canvas', { width: 72, height: 128 });
        thumbs.draw(c.getContext('2d'), variant(key, 0), 2000);
        drawnThumbs.set(key, c);
      }
      return drawnThumbs.get(key);
    };
    const body = h('div', { class: 'stack', 'data-testid': 'background-dialog' });
    function draw() {
      const tile = (title, value, image) => h('button', {
        class: `theme-tile small${current() === value ? ' selected' : ''}`, type: 'button', 'aria-pressed': String(current() === value),
        'data-testid': `background-${value}`, onClick: () => pick(value),
      }, h('span', { class: 'theme-pic' }, image), h('span', { class: 'theme-name' }, title));
      body.replaceChildren(
        h('div', { class: 'chips' },
          chip('Plain', current() === PlayerBackgrounds.PLAIN, () => pick(PlayerBackgrounds.PLAIN), 'background-plain'),
          chip('Matched scenes', current() === PlayerBackgrounds.MATCHED, () => pick(PlayerBackgrounds.MATCHED), 'background-matched'),
          subjects.length ? chip('Matched photos', current() === PlayerBackgrounds.PHOTOS, () => pick(PlayerBackgrounds.PHOTOS), 'background-photos') : null),
        h('p', { class: 'small muted' }, 'Matched follows what each ayah is about, as in a shared video. Or choose one for every ayah:'),
        h('div', { class: 'theme-row' }, THEMES.map((t) => tile(t.title, t.key, drawnThumb(t.key)))),
        subjects.length ? h('div', { class: 'theme-row' }, subjects.map((s) => tile(s.title, s.themeKey, h('img', { src: thumbUrl(s), alt: '', loading: 'lazy' })))) : null,
        h('label', { class: 'toggle-row' },
          h('span', { class: 'text' }, h('strong', {}, 'Translation'), h('span', {}, 'Saheeh International, under the whole ayah')),
          h('input', { class: 'switch', type: 'checkbox', checked: store.settings.showAyahTranslation, 'data-testid': 'background-translation', onChange: (e) => store.setSetting('showAyahTranslation', e.target.checked) })),
        h('p', { class: 'small muted' }, 'Scenes of sky, land, water, light and pattern, and CC0 nature photos from Wikimedia Commons, never people or animals. A moving background uses a little more battery.'),
      );
    }
    draw();
    await dialog({ title: 'Background', content: body, actions: [{ label: 'Done', value: true, primary: true }] });
    thumbs.release();
  }

  async function openShareDialog() {
    const t = engine.track;
    if (!t) return;
    let aspect = 'PORTRAIT';
    const shapes = h('div', { class: 'chips' });
    const drawShapes = () => shapes.replaceChildren(...ASPECTS.map((a) => chip(a.ratio, a.key === aspect, () => { aspect = a.key; drawShapes(); }, `share-aspect-${a.key}`)));
    drawShapes();
    const status = h('p', { class: 'small muted', 'data-testid': 'share-status' });
    const content = h('div', { class: 'stack', 'data-testid': 'share-dialog' },
      h('p', { class: 'small muted' }, 'A video of it recited, or a picture of it as it looks now.'),
      h('div', { class: 'small muted' }, 'Image shape'), shapes, status);
    // The word being recited, unless an intro is playing or the ayah hasn't started. With word
    // clips the position is into the word's own file, not the ayah: use where the word starts.
    let atMs = null;
    if (!engine.introLabel) {
      if (engine.mode === PlaybackMode.WORD_BY_WORD && engine.usesWordClips()) {
        const start = engine.segments?.[engine.wordIndex]?.[0];
        if (start != null) atMs = start + 1;
      } else if (engine.positionMs > 0) {
        atMs = engine.positionMs;
      }
    }
    let drawing = false;
    const choice = await dialog({
      title: 'Share this ayah',
      content,
      actions: [
        { label: 'Video…', value: 'video' },
        {
          label: 'Share image', value: 'image', primary: true,
          onClick: async () => {
            if (drawing) return false; // a second tap while the first is still drawing
            drawing = true;
            status.textContent = 'Drawing the image…';
            try {
              await shareImage(t, aspect, atMs);
              return true;
            } catch (e) {
              status.textContent = e.message || "The image couldn't be made.";
              return false;
            } finally {
              drawing = false;
            }
          },
        },
      ],
    });
    if (choice === 'video') go(`/video/${t.surah}/${t.ayah}/player`);
  }

  async function shareImage(t, aspect, atMs) {
    const s = store.settings;
    const bg = s.playerBackground;
    const request = {
      surah: t.surah, fromAyah: t.ayah, toAyah: t.ayah, reciter: reciterFromKey(s.reciter), aspect,
      wordByWord: s.showWordTranslations, fullTranslation: s.showAyahTranslation, opening: false, credits: false,
      themeKey: bg === PlayerBackgrounds.PLAIN || bg === PlayerBackgrounds.MATCHED || bg === PlayerBackgrounds.PHOTOS ? null : bg,
      photos: bg === PlayerBackgrounds.PHOTOS || bg.startsWith('photo:'),
    };
    const exporter = new VideoExporter({ proxy: Boolean(server?.available) && !s.streamDirect });
    const shape = ASPECTS.find((a) => a.key === aspect);
    const canvas = await exporter.still(request, shape.width, atMs);
    const blob = await (canvas.convertToBlob ? canvas.convertToBlob({ type: 'image/jpeg', quality: 0.92 }) : new Promise((r) => canvas.toBlob(r, 'image/jpeg', 0.92)));
    const name = fileName(request, 'jpg');
    const file = new File([blob], name, { type: 'image/jpeg' });
    if (navigator.canShare?.({ files: [file] })) {
      try {
        await navigator.share({ files: [file], text: caption({ ...request, reciter: request.reciter.key }, request.reciter.displayName) });
        return;
      } catch (e) {
        if (e?.name === 'AbortError') return; // closed the share sheet
        // NotAllowedError: drawing took long enough that the browser no longer counts the share
        // as following the tap. Save it instead of doing nothing.
      }
    }
    download(blob, name);
    toast('Image saved to your downloads.');
  }

  // -- wake lock & keys --------------------------------------------------------------------

  async function keepAwake() {
    if (!('wakeLock' in navigator) || !engine.track || document.visibilityState !== 'visible') return;
    try {
      wakeLock = await navigator.wakeLock.request('screen');
    } catch {
      wakeLock = null;
    }
  }
  const onVisible = () => {
    if (document.visibilityState === 'visible') keepAwake();
  };
  const onKey = (e) => {
    if (e.key === 'Escape' && panel) {
      closePanel();
      return;
    }
    if (panel || e.target.closest('input, textarea, select, .dialog-wrap') || e.metaKey || e.ctrlKey || e.altKey) return;
    if (e.key === ' ') {
      if (e.target.closest('button, a')) return; // the focused control handles its own Space
      e.preventDefault();
      engine.toggle();
    } else if (e.key === 'k') {
      e.preventDefault();
      engine.toggle();
    } else if (e.key === 'ArrowLeft') {
      engine.next(); // right-to-left: the next ayah is to the left
    } else if (e.key === 'ArrowRight') {
      engine.prev();
    }
  };
  document.addEventListener('visibilitychange', onVisible);
  document.addEventListener('keydown', onKey);

  // -- wiring ------------------------------------------------------------------------------

  const el = h('div', { class: 'player' }, background.el, head, readerEl, controls);
  function applyBackground() {
    const on = store.settings.playerBackground !== PlayerBackgrounds.PLAIN;
    el.classList.toggle('with-bg', on);
    // The scene fills the window, and the sidebar and tab bar go translucent over it.
    document.getElementById('app')?.classList.toggle('scene', on);
    background.refresh();
  }
  const offs = [
    engine.on('track', () => {
      lastActive = -2;
      // The word reader follows the engine itself; the whole-ayah view shows the new words.
      if (renderedKind !== 'word' || readerKind() !== 'word') renderReader();
      renderState();
    }),
    engine.on('state', () => {
      if (readerKind() !== renderedKind || (renderedKind === 'whole' && engine.introLabel !== renderedIntro)) renderReader();
      renderState();
    }),
    engine.on('tick', renderTick),
    store.on((what) => { if (what === 'settings' || what === 'all') { applyBackground(); renderReader(); renderState(); } }),
  ];
  applyBackground();
  renderReader();
  renderState();
  renderMore();
  renderTick();
  keepAwake();
  void go;

  return {
    el,
    title: 'Player',
    destroy() {
      offs.forEach((off) => off());
      reader?.destroy();
      background.destroy();
      document.getElementById('app')?.classList.remove('scene');
      closePanel();
      document.removeEventListener('visibilitychange', onVisible);
      document.removeEventListener('keydown', onKey);
      wakeLock?.release?.().catch(() => {});
    },
  };
}

