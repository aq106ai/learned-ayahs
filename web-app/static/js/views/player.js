// The player: an immersive ayah reader with playback controls, the surah navigator and the
// playlist as side panels. Port of ui/PlayerScreen.kt, ui/PlaylistScreen.kt and ui/AyahTextSection.kt.

import { append, dialog, formatTime, h, icon, toast } from '../lib/dom.js';
import {
  PlaybackMode,
  QueueBuilder,
  RepeatMode,
  VERSE_COUNTS,
  globalToSurahAyah,
  surahLabel,
  surahName,
  trackTitle,
} from '../core/quran.js';
import { sectionKey, sectionsBySurah } from '../core/ayahSections.js';
import { contentWords, words as ayahWords, wordsSync } from '../services/quranData.js';
import { RECITERS, reciterFromKey } from '../core/reciters.js';
import { REVISION_DELAY_CHOICES } from '../state/store.js';
import { learnedCountsBySurah, matchSurahs } from './common.js';
import { wordReader } from './wordReader.js';
import { playerBackground } from './playerBackground.js';
import { download } from './video.js';
import { showTafsir } from './tafsir.js';
import { photoSubjects, thumbUrl, translation } from '../video/data.js';
import { ASPECTS, VideoExporter, fileName } from '../video/exporter.js';
import { currentPalette, setScene } from './sceneColours.js';
import { PlayerBackgrounds } from '../video/plan.js';
import { BackgroundScenes, THEMES, variant } from '../video/scenes.js';
import { copyPlain, sharePlain, shareTextOf } from '../video/shareText.js';
import { onContentLoaded, translationLanguageSync } from '../services/content.js';

import { tx } from '../core/i18n.js';
export function playerView({ store, engine, server, go }) {
  // One word at a time on screen, or the whole ayah. What plays is separate (engine.wordPlayback).
  const wordView = () => Boolean(store.settings.wordView);
  let reader = null;
  let expanded = false;
  let wakeLock = null;

  // -- header ------------------------------------------------------------------------------

  const meta = h('div', { class: 'meta', 'data-testid': 'player-meta' });
  // Everything that is not transport lives behind the header's three dots. Mode and view are one
  // item each, named for what choosing it does, so the menu is rebuilt whenever either changes.
  const menuItems = h('div', { class: 'player-menu-items', role: 'menu' });
  const menuSummary = h('summary', { class: 'btn ghost icon-only', 'aria-label': tx('More options'), title: tx('More options'), 'data-testid': 'open-actions-menu' }, icon('more'));
  const menuEl = h('details', { class: 'player-menu' }, menuSummary, menuItems);
  const menuItem = (label, iconName, testid, action, disabled = false) => h('button', {
    class: 'btn ghost', type: 'button', role: 'menuitem', 'data-testid': testid, disabled,
    onClick: () => { menuEl.open = false; menuSummary.focus(); action(); },
  }, icon(iconName), h('span', {}, label));
  // The settings listener below redraws the reader, the menu and the footer.
  const showWords = () => store.setSetting('wordView', true);
  const showAyah = () => store.setSetting('wordView', false);
  const learnedInSurah = (surah) => learnedCountsBySurah(store.learned).get(surah) ?? 0;
  function renderMenu() {
    const other = engine.mode === PlaybackMode.FULL_SURAH ? PlaybackMode.REVISE : PlaybackMode.FULL_SURAH;
    const translated = store.settings.showAyahTranslation;
    const meanings = store.settings.showWordTranslations;
    const t = engine.track;
    // A surah learned in full is the same list either way: no "Full Surah Mode".
    const sameList = other === PlaybackMode.FULL_SURAH && t && learnedInSurah(t.surah) >= VERSE_COUNTS[t.surah - 1];
    menuItems.replaceChildren(...[
      sameList ? null : menuItem(other === PlaybackMode.REVISE ? tx('Revise Learned Ayahs') : tx('Full Surah Mode'), 'repeat', `mode-${other}`, () => engine.setMode(other)),
      wordView()
        ? menuItem(tx('View Full Ayah'), 'type', 'view-ayah', showAyah)
        : menuItem(tx('Word by Word'), 'type', 'toggle-word-by-word', showWords),
      // Long ayahs is about what is on screen now, so it sits with the other view switches
      // rather than in Settings. The word reader pages a word at a time and never overflows.
      wordView() ? null : menuItem(
        store.settings.ayahOverflowMode === 'AUTO_SCROLL' ? tx('Fit to screen') : tx('Auto scroll'),
        'layers', 'toggle-overflow',
        () => store.setSetting('ayahOverflowMode', store.settings.ayahOverflowMode === 'AUTO_SCROLL' ? 'FIT_TO_SCREEN' : 'AUTO_SCROLL'),
      ),
      menuItem(translated ? tx('Hide Translation') : tx('Show Translation'), 'globe', 'toggle-translation', () => store.setSetting('showAyahTranslation', !translated)),
      menuItem(meanings ? tx('Hide Word Meanings') : tx('Show Word Meanings'), 'layers', 'toggle-word-meanings', () => store.setSetting('showWordTranslations', !meanings)),
      h('hr'),
      menuItem(tx('Tafsir'), 'book', 'tafsir', () => { if (engine.track) showTafsir(store, engine.track.surah, engine.track.ayah); }, !engine.track),
      menuItem(tx('Share'), 'share', 'share', () => openShareDialog(), !engine.track),
      menuItem(tx('Theme'), 'image', 'open-background', () => openBackgroundDialog()),
      menuItem(tx('Settings'), 'settings', 'player-settings', () => go('settings')),
    ].filter(Boolean));
  }
  const closeMenus = (event) => {
    if (!menuEl.open) return;
    if (event.key === 'Escape') {
      menuEl.open = false;
      menuSummary.focus();
    } else if (event.type === 'click' && !menuEl.contains(event.target)) menuEl.open = false;
  };
  document.addEventListener('click', closeMenus);
  document.addEventListener('keydown', closeMenus);
  const head = h('div', { class: 'player-head' },
    h('button', { class: 'btn ghost icon-only', type: 'button', 'aria-label': tx('Browse surahs'), title: tx('Browse surahs'), 'data-testid': 'open-surah-panel', onClick: () => openSurahPanel() }, icon('list')),
    meta,
    h('button', { class: 'btn ghost icon-only', type: 'button', 'aria-label': tx('Playlist'), title: tx('Playlist'), 'data-testid': 'open-playlist', onClick: () => openPlaylistPanel() }, icon('queue')),
    menuEl,
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
      h('h2', {}, tx('No ayahs marked yet.')),
      h('p', {}, tx('Open a surah and tick the ayahs you have memorised to add them to your revision. You can also add several at once by description.')),
      h('div', { class: 'row', style: { justifyContent: 'center' } },
        h('a', { class: 'btn primary', href: '#/surahs' }, icon('edit'), tx('Browse surahs')),
        h('a', { class: 'btn', href: '#/add' }, icon('plus'), tx('Add by description')),
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
      words.length ? flow : h('p', { class: 'muted', style: { textAlign: 'center' } }, tx('Loading ayah text…')),
      meaning,
      t ? h('div', { class: 'ayah-caption' }, `${surahName(t.surah)} · ${tx('Ayah {n}', { n: t.ayah })}`) : null,
    );
  }

  const readerKind = () => {
    if (!engine.track) return 'empty';
    return wordView() ? 'word' : 'whole';
  };
  let renderedKind = null;
  let renderedIntro = null;

  function renderReader() {
    lastActive = -2;
    readerEl.scrollTop = 0;
    reader?.destroy();
    reader = null;
    renderedKind = readerKind();
    renderedIntro = engine.introLabel;
    renderMenu();
    if (!engine.track) {
      readerEl.classList.remove('tap');
      readerHost.replaceChildren(emptyState());
      return;
    }
    if (wordView()) {
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
    if (engine.playing && active >= 0 && store.settings.ayahOverflowMode === 'AUTO_SCROLL' && readerEl.scrollHeight > readerEl.clientHeight) {
      const word = wordEls[active];
      if (word) {
        const viewport = readerEl.getBoundingClientRect();
        const bounds = word.getBoundingClientRect();
        const target = readerEl.scrollTop + bounds.top + bounds.height / 2 - viewport.top - readerEl.clientTop - readerEl.clientHeight / 2;
        readerEl.scrollTo({ top: Math.max(0, Math.min(target, readerEl.scrollHeight - readerEl.clientHeight)), behavior: 'smooth' });
      }
    }
  }

  /** FIT_TO_SCREEN: shrink the ayah's font just enough that it fits without scrolling. */
  let fitFrame = 0;
  let disposed = false;
  function fitAyah() {
    cancelAnimationFrame(fitFrame);
    fitFrame = requestAnimationFrame(() => {
      if (disposed || renderedKind !== 'whole' || !readerEl.isConnected) return;
      readerHost.style.removeProperty('--ayah-size');
      if (store.settings.ayahOverflowMode !== 'FIT_TO_SCREEN') return;
      const first = readerHost.querySelector('.ar');
      if (!first || readerEl.clientHeight <= 0) return;
      const base = parseFloat(getComputedStyle(first).fontSize);
      const minimum = Math.min(base, parseFloat(getComputedStyle(document.documentElement).fontSize) * 1.1);
      for (let size = base; size > minimum && (readerEl.scrollHeight > readerEl.clientHeight + 1 || readerEl.scrollWidth > readerEl.clientWidth + 1);) {
        size = Math.max(minimum, size - 1);
        readerHost.style.setProperty('--ayah-size', `${size}px`);
      }
      // If translations still exceed the viewport, retain a top-accessible scroll fallback.
      readerEl.scrollTop = 0;
    });
  }
  const readerResize = new ResizeObserver(fitAyah);
  readerResize.observe(readerEl);
  document.fonts?.addEventListener('loadingdone', fitAyah);


  // Whole-ayah navigation uses a short swipe, as on Android. Only the paged word reader
  // uses a halfway threshold. Scrolling vertically or returning a drag is never a tap.
  let ayahDrag = null;
  readerEl.addEventListener('pointerdown', (e) => {
    if (reader || e.button !== 0 || ayahDrag || e.target.closest('a, button')) return;
    ayahDrag = { id: e.pointerId, x: e.clientX, y: e.clientY, moved: false };
  });
  readerEl.addEventListener('pointermove', (e) => {
    if (!ayahDrag || e.pointerId !== ayahDrag.id) return;
    ayahDrag.moved ||= Math.hypot(e.clientX - ayahDrag.x, e.clientY - ayahDrag.y) > 10;
  });
  readerEl.addEventListener('pointerup', (e) => {
    if (!ayahDrag || e.pointerId !== ayahDrag.id) return;
    const { x, y, moved } = ayahDrag;
    ayahDrag = null;
    if (reader || !engine.track || e.target.closest('a, button')) return;
    const dx = e.clientX - x;
    const dy = e.clientY - y;
    // A swipe or tap is Next or Previous: an ayah, or a word while the words play one at a time
    // (the footer's outer buttons then move by ayah).
    const step = (delta) => (delta > 0 ? engine.next() : engine.prev());
    if (Math.abs(dx) >= 56 && Math.abs(dx) > Math.abs(dy)) {
      if (store.settings.swipeToNavigate) step(dx > 0 ? 1 : -1);
    } else if (!moved && Math.hypot(dx, dy) <= 10 && store.settings.tapToAdvance) {
      step(1);
    }
  });
  const cancelAyahDrag = () => { ayahDrag = null; };
  readerEl.addEventListener('pointercancel', cancelAyahDrag);
  readerEl.addEventListener('pointerleave', cancelAyahDrag);

  // -- controls ----------------------------------------------------------------------------

  const nowTitle = h('div', { class: 'title', 'data-testid': 'now-playing' });
  const nowMode = h('span', { class: 'badge', 'data-testid': 'mode-label' });
  const playBtn = h('button', { class: 'round play', type: 'button', 'data-testid': 'play', onClick: () => engine.toggle() });
  const prevBtn = h('button', { class: 'round', type: 'button', 'data-testid': 'prev', onClick: () => engine.prev() });
  const nextBtn = h('button', { class: 'round', type: 'button', 'data-testid': 'next', onClick: () => engine.next() });
  // While the words play one at a time, Previous and Next step words and these move by ayah.
  const prevAyahBtn = h('button', { class: 'round small', type: 'button', hidden: true, 'aria-label': tx('Previous ayah'), title: tx('Previous ayah'), 'data-testid': 'prev-ayah', onClick: () => engine.stepAyah(-1) }, icon('prev'));
  const nextAyahBtn = h('button', { class: 'round small', type: 'button', hidden: true, 'aria-label': tx('Next ayah'), title: tx('Next ayah'), 'data-testid': 'next-ayah', onClick: () => engine.stepAyah(1) }, icon('next'));
  const moreBtn = h('button', {
    class: 'btn ghost icon-only',
    type: 'button',
    'aria-label': tx('More controls'),
    'data-testid': 'expand-controls',
    onClick: () => {
      expanded = !expanded;
      renderMore();
    },
  });
  const seekInput = h('input', { type: 'range', min: '0', max: '1000', value: '0', 'aria-label': tx('Seek'), 'data-testid': 'seek' });
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
    h('button', { class: 'btn small', type: 'button', 'data-testid': 'retry', onClick: () => engine.retry() }, icon('refresh'), tx('Retry')));
  const more = h('div', { class: 'more', 'data-testid': 'more-controls' });
  const reciteCta = h('div', { class: 'recite-cta' });

  const controls = h(
    'div',
    { class: 'controls' },
    reciteCta,
    errorRow,
    h('div', { class: 'now' }, nowTitle, nowMode, moreBtn),
    h('div', { class: 'transport' }, prevAyahBtn, prevBtn, playBtn, nextBtn, nextAyahBtn),
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
    const repeatWord = engine.repeatWord;
    const clips = store.settings.useWordClips;
    const t = engine.track;
    // "Surah" repeats this surah's learned ayahs in the learned list; say so when there is more
    // than one of them to repeat.
    const surahChip = mode === PlaybackMode.REVISE && t && learnedInSurah(t.surah) > 1 ? tx('Surah learned ayahs') : tx('Surah');
    const pick = (r) => chip({ AYAH: tx('Ayah'), SURAH: surahChip, OFF: tx('Off') }[r], !repeatWord && r === repeat, () => engine.setRepeat(r), `repeat-${r}`);
    const groups = [
      // One choice of four. Word repeats the word being recited, in either view.
      h('div', {}, h('div', { class: 'label' }, tx('Repeat')), h('div', { class: 'chips' },
        pick(RepeatMode.AYAH),
        pick(RepeatMode.SURAH),
        chip(tx('Word'), repeatWord, () => engine.setRepeatWord(!repeatWord), 'repeat-word'),
        pick(RepeatMode.OFF),
      )),
    ];
    if (repeat === RepeatMode.AYAH || repeatWord) {
      groups.push(h('div', {},
        h('div', { class: 'label' }, tx('Pause before repeat — time to revise or press Next')),
        h('div', { class: 'chips' }, REVISION_DELAY_CHOICES.map((s) =>
          chip(s === 0 ? 'Off' : `${s}s`, s === store.settings.revisionDelaySeconds, () => engine.setRevisionDelay(s), `delay-${s}`))),
      ));
    }
    groups.push(h('label', { class: 'toggle-row' },
      h('span', { class: 'text' },
        h('strong', {}, tx('Word clips (Quran.com)')),
        h('span', {}, clips ? tx('Each word from Quran.com, one at a time, in place of your reciter.') : tx('Off: your ayah reciter. On: each word from Quran.com, one at a time.')),
      ),
      h('input', { class: 'switch', type: 'checkbox', 'data-testid': 'word-clips', checked: clips, onChange: (e) => store.setSetting('useWordClips', e.target.checked) }),
    ));
    // Word clips are Quran.com's own recording, so there is no reciter to choose.
    if (!clips) {
      groups.push(h('div', {}, h('div', { class: 'label' }, tx('Reciter')),
        h('select', { class: 'select', 'aria-label': tx('Reciter'), 'data-testid': 'player-reciter', onChange: (e) => store.setSetting('reciter', e.target.value) },
          RECITERS.map((r) => h('option', { value: r.key, selected: r.key === store.settings.reciter }, r.displayName))),
      ));
    }
    more.replaceChildren(...groups);
  }

  function renderState() {
    renderMenu();
    const t = engine.track;
    const active = engine.isActive;
    playBtn.replaceChildren(icon(active ? 'pause' : 'play', { filled: true }));
    playBtn.setAttribute('aria-label', active ? 'Pause' : 'Play');
    playBtn.disabled = !t;
    prevBtn.disabled = !t;
    nextBtn.disabled = !t;
    const words = engine.wordPlayback && Boolean(t);
    prevAyahBtn.hidden = !words;
    nextAyahBtn.hidden = !words;
    if (prevBtn.dataset.words !== String(words)) {
      prevBtn.dataset.words = String(words);
      prevBtn.replaceChildren(icon(words ? 'chevronLeft' : 'prev'));
      nextBtn.replaceChildren(icon(words ? 'chevronRight' : 'next'));
      prevBtn.setAttribute('aria-label', words ? tx('Previous word') : tx('Previous'));
      nextBtn.setAttribute('aria-label', words ? tx('Next word') : tx('Next'));
      prevBtn.title = prevBtn.getAttribute('aria-label');
      nextBtn.title = nextBtn.getAttribute('aria-label');
    }
    nowTitle.textContent = engine.introLabel ?? (t ? `${t.index}. ${trackTitle(t.surah, t.ayah)}` : 'Nothing to play yet');
    if (engine.gapPending) nowTitle.textContent += tx(' · pausing before repeat');
    nowMode.textContent = engine.modeLabel;
    errorRow.hidden = !engine.error;
    errorText.textContent = engine.error ? `${engine.error} ${tx('Check your connection, or save ayahs for offline in Settings.')}` : '';
    seekRow.hidden = engine.wordPlayback || !t;
    if (t) {
      const pos = engine.mode === PlaybackMode.FULL_SURAH ? tx('{n} of {total}', { n: t.ayah, total: VERSE_COUNTS[t.surah - 1] }) : tx('{n} of {total}', { n: engine.index + 1, total: engine.queue.length });
      meta.textContent = `${surahName(t.surah)} · ${tx('Ayah {n}', { n: t.ayah })} · ${pos}`;
    } else {
      meta.textContent = tx('Revise');
    }
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
  // The playlist keeps itself in step with the engine while it is open; dropped with the panel.
  let panelOff = null;
  function closePanel() {
    panelOff?.();
    panelOff = null;
    panel?.remove();
    panel = null;
  }
  function openPanel(side, title, body, extra = null) {
    closePanel();
    panel = h('div', {},
      h('div', { class: 'scrim', onClick: closePanel }),
      h('aside', { class: `panel ${side}`, role: 'dialog', 'aria-label': title },
        h('div', { class: 'panel-head' }, h('h2', {}, title), extra,
          h('button', { class: 'btn ghost icon-only', type: 'button', 'aria-label': tx('Close'), onClick: closePanel }, icon('x'))),
        h('div', { class: 'panel-body' }, body),
      ),
    );
    document.body.append(panel);
    panel.querySelector('input, button')?.focus();
  }

  /** The ayahs a section covers: "Ayahs 255–257", or just the ayah when it is one on its own. */
  const sectionRange = (section) => (section.from === section.to
    ? tx('Ayah {n}', { n: section.from })
    : tx('Ayahs {from}–{to}', { from: section.from, to: section.to }));
  const sectionsLabel = (n) => (n === 1 ? tx('1 section') : tx('{n} sections', { n }));

  /**
   * Revise by section: a full screen of every unbroken run of ayahs you have marked, surah by
   * surah and numbered 1, 2, 3… within each, named by its ayah numbers alone so it can be
   * recited from memory. A row peeks at its first word when the numbers are not prompt enough,
   * and Go to sends the player to the section's first ayah.
   *
   * It is a screen, not a reading mode: the reader behind it is untouched, and the menu only
   * offers it while revising learned ayahs, which is the queue these sections describe.
   */
  function openSectionsScreen() {
    const bySurah = sectionsBySurah(store.learned);
    const total = [...bySurah.values()].reduce((n, sections) => n + sections.length, 0);
    const body = h('div', { class: 'stack', 'data-testid': 'sections-screen' });
    if (!total) {
      body.append(h('p', { class: 'muted' }, tx('No ayahs marked yet.')));
      openPanel('full', tx('Sections'), body);
      return;
    }
    body.append(h('div', { class: 'muted small' }, tx('{surahs} surahs · {n} sections', { surahs: bySurah.size, n: total })));

    // The word is read when first asked for: the text is already loaded in the player, and the
    // async path is only the cold case (a screen opened before quran_text.json has landed).
    const setFirstWord = (section, el) => {
      const pick = (words) => { el.textContent = contentWords(words ?? [])[0]?.text ?? ''; };
      const loaded = wordsSync(section.surah, section.from);
      if (loaded?.length) pick(loaded);
      else ayahWords(section.surah, section.from).then(pick).catch(() => {});
    };

    const sectionRow = (section) => {
      const key = sectionKey(section);
      const word = h('span', { class: 'section-word', lang: 'ar', 'data-testid': `word-${key}`, hidden: true });
      const peek = h('button', {
        class: 'btn ghost icon-only', type: 'button', 'aria-pressed': 'false',
        'aria-label': tx('Show the first word'), title: tx('Show the first word'),
        'data-testid': `peek-${key}`,
        onClick: () => {
          const show = word.hidden;
          if (show && !word.textContent) setFirstWord(section, word);
          word.hidden = !show;
          peek.setAttribute('aria-pressed', String(show));
          const label = show ? tx('Hide the first word') : tx('Show the first word');
          peek.setAttribute('aria-label', label);
          peek.title = label;
        },
      }, icon('type'));
      const playing = engine.track
        && engine.track.globalId >= section.firstGlobalId
        && engine.track.globalId <= section.lastGlobalId;
      return h('div', { class: `section-row${playing ? ' current' : ''}`, 'data-section': key },
        // Its number within the surah, which is how a section is referred to out loud.
        h('span', { class: 'section-num' }, String(section.index)),
        h('div', { class: 'section-row-main' },
          h('span', { class: 'section-range' }, sectionRange(section)),
          section.count > 1 ? h('span', { class: 'sub muted small' }, tx('{n} ayahs', { n: section.count })) : null,
          word,
        ),
        peek,
        h('button', {
          class: 'btn small', type: 'button', 'data-testid': `goto-${key}`,
          onClick: () => { closePanel(); engine.playGlobal(section.firstGlobalId); },
        }, tx('Go to')),
      );
    };

    const list = h('div', { 'data-testid': 'sections-surahs' });
    for (const [surah, sections] of bySurah) {
      const block = h('details', { class: 'group', 'data-surah': String(surah) },
        h('summary', {},
          h('span', {}, surahLabel(surah)),
          h('span', { class: 'sub muted small', style: { marginInlineStart: 'auto' } }, sectionsLabel(sections.length))),
        ...sections.map(sectionRow));
      // Open on the surah being revised, so the screen lands where the user is.
      block.open = surah === engine.track?.surah;
      list.append(block);
    }
    body.append(list);
    openPanel('full', tx('Sections'), body);
    body.querySelector('details[open]')?.scrollIntoView({ block: 'start' });
  }

  function openSurahPanel() {
    const counts = learnedCountsBySurah(store.learned);
    // One section is the whole learned list in one stretch — nothing to pick between. And only
    // while revising learned ayahs: Full surah streams every ayah, marked or not.
    const sections = [...sectionsBySurah(store.learned).values()].reduce((n, g) => n + g.length, 0);
    const offerSections = engine.mode === PlaybackMode.REVISE && sections > 1;
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
        // How much of the surah is marked, not a bare count: "5 of 286 learned".
      }, h('span', {}, `${s} · ${surahName(s)}`), h('span', { class: 'sub' }, learned
        ? tx('{n} of {total} learned', { n: learned, total: VERSE_COUNTS[s - 1] })
        : tx('{n} ayahs', { n: VERSE_COUNTS[s - 1] })));
    }));
    const search = h('input', { class: 'input', type: 'search', placeholder: tx('Search surah'), onInput: (e) => render(e.target.value) });
    render('');
    openPanel('start', tx('Surahs'), h('div', { class: 'stack' },
      offerSections
        ? h('button', {
          class: 'btn', type: 'button', 'data-testid': 'open-sections',
          onClick: () => { closePanel(); openSectionsScreen(); },
        }, icon('layers'), tx('Revise by Section'))
        : null,
      h('a', { class: 'btn', href: '#/surahs', onClick: closePanel }, icon('edit'), tx('Manage learned ayahs')),
      search,
      list,
    ));
  }

  /**
   * The playlist. In Full surah mode the queue is one surah, but the panel lists all 114 so the
   * surah being recited can be changed from here; a surah's ayahs are built the first time its
   * group is opened. Other modes list the surahs the queue covers, as before.
   *
   * The ayah playing carries `.current` (its surah's group too) and an engine listener moves it,
   * so the panel can be left open while the queue runs on. It follows the playing ayah down the
   * list only while that ayah is on screen: someone who has scrolled off to another surah is
   * reading, not watching, and must not be yanked back.
   */
  function openPlaylistPanel() {
    const fullSurah = engine.mode === PlaybackMode.FULL_SURAH;
    const queue = engine.queue;
    const queueSurahs = [...new Set(queue.map((t) => t.surah))];
    const groups = new Map();
    const body = h('div', { class: 'stack', 'data-testid': 'playlist' });

    const starBtn = (globalId) => h('button', {
      class: 'star-btn', type: 'button', 'aria-pressed': String(store.bookmarks.has(globalId)),
      'aria-label': store.bookmarks.has(globalId) ? tx('Remove bookmark') : tx('Bookmark this ayah'),
      onClick: (e) => { store.toggleBookmark(globalId); e.currentTarget.setAttribute('aria-pressed', String(store.bookmarks.has(globalId))); },
    }, icon('star'));

    // -- bookmarks
    const bookmarks = [...store.bookmarks].sort((a, b) => a - b);
    if (bookmarks.length) {
      body.append(h('div', { class: 'section-title', style: { margin: '4px 0 0' } }, tx('Bookmarks')));
      body.append(...bookmarks.map((id) => {
        const [s, a] = globalToSurahAyah(id);
        return h('div', { class: 'row' },
          h('button', { class: 'list-row', type: 'button', 'data-ayah': String(id), onClick: () => { closePanel(); engine.playGlobal(id); } }, trackTitle(s, a)),
          h('button', { class: 'star-btn', type: 'button', 'aria-pressed': 'true', 'aria-label': tx('Remove bookmark'), onClick: () => { store.toggleBookmark(id); openPlaylistPanel(); } }, icon('star')),
        );
      }));
    }

    // -- what is playing
    body.append(h('div', { class: 'muted small' }, fullSurah
      ? [tx('{n} ayahs', { n: queue.length }), engine.modeLabel].join(' · ')
      : tx('{surahs} surahs · {n} ayahs · {mode}', { surahs: queueSurahs.length, n: queue.length, mode: engine.modeLabel })));

    // -- the surahs
    const ayahCount = (s) => (queueSurahs.includes(s) ? queue.filter((t) => t.surah === s).length : VERSE_COUNTS[s - 1]);
    // A surah the queue does not cover (Full surah lists all 114) is its own full list of ayahs.
    const tracksFor = (s) => (queueSurahs.includes(s) ? queue.filter((t) => t.surah === s) : QueueBuilder.surahAllAyahs(s));
    const groupList = h('div', { 'data-testid': 'playlist-surahs' });

    function fillGroup(group) {
      if (group.dataset.filled) return;
      group.dataset.filled = '1';
      group.append(...tracksFor(Number(group.dataset.surah)).map((t) => h('div', { class: 'row' },
        h('button', {
          class: 'list-row', type: 'button', 'data-ayah': String(t.globalId),
          onClick: () => { closePanel(); engine.playGlobal(t.globalId); },
        }, tx('Ayah {n}', { n: t.ayah })),
        starBtn(t.globalId),
      )));
    }

    function renderGroups(query = '') {
      groups.clear();
      groupList.replaceChildren(...(fullSurah ? matchSurahs(query) : queueSurahs).map((s) => {
        const group = h('details', { class: 'group', 'data-surah': String(s) },
          h('summary', {}, h('span', {}, surahLabel(s)),
            h('span', { class: 'sub muted small', style: { marginInlineStart: 'auto' } }, tx('{n} ayahs', { n: ayahCount(s) }))),
        );
        group.addEventListener('toggle', () => { if (group.open) fillGroup(group); });
        groups.set(s, group);
        return group;
      }));
    }

    if (fullSurah) {
      body.append(h('input', {
        class: 'input', type: 'search', placeholder: tx('Search surah'), 'data-testid': 'playlist-search',
        onInput: (e) => { renderGroups(e.target.value); markCurrent(); },
      }));
    }
    renderGroups();
    body.append(groupList);
    if (!queue.length) body.append(h('p', { class: 'muted' }, tx('Nothing in the playlist yet.')));

    // -- where we are
    const onScreen = (el) => {
      const view = body.closest('.panel-body');
      if (!el || !view) return false;
      const box = el.getBoundingClientRect();
      const viewBox = view.getBoundingClientRect();
      return box.bottom > viewBox.top && box.top < viewBox.bottom;
    };
    function markCurrent({ scroll = false } = {}) {
      for (const el of body.querySelectorAll('.list-row.current')) {
        el.classList.remove('current');
        el.removeAttribute('aria-current');
      }
      for (const group of groups.values()) group.classList.remove('current');
      const t = engine.track;
      if (!t) return;
      const group = groups.get(t.surah);
      if (group) {
        group.classList.add('current');
        group.open = true;
        fillGroup(group);
      }
      for (const el of body.querySelectorAll(`.list-row[data-ayah="${t.globalId}"]`)) {
        el.classList.add('current');
        el.setAttribute('aria-current', 'true');
      }
      if (scroll) (group ?? body).querySelector('.list-row.current')?.scrollIntoView({ block: 'center' });
    }

    openPanel('end', tx('Playlist'), body);
    markCurrent({ scroll: true });
    panelOff = engine.on('track', () => {
      // A mode change swaps the whole list (Full surah lists every surah): draw it again.
      if ((engine.mode === PlaybackMode.FULL_SURAH) !== fullSurah) openPlaylistPanel();
      else markCurrent({ scroll: onScreen(body.querySelector('.list-row.current')) });
    });
  }

  // -- background and share -----------------------------------------------------------------

  async function openBackgroundDialog() {
    const subjects = await photoSubjects();
    const current = () => store.settings.playerBackground;
    const pick = (value) => {
      // A background is the look of a shared video, translation included; turning one on brings
      // the translation with it. It can be switched off again from the three-dot menu.
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
      const theme = store.settings.theme;
      // append() skips a null child; the native replaceChildren would print it as "null".
      body.replaceChildren();
      append(body, [
        h('div', { class: 'chips' }, [['DARK', tx('Dark')], ['LIGHT', tx('Light')], ['SYSTEM', tx('Match system')]].map(([value, label]) =>
          chip(label, theme === value, () => { store.setSetting('theme', value); draw(); }, `theme-${value}`))),
        h('div', { class: 'small muted' }, tx('Background')),
        h('div', { class: 'chips' },
          chip(tx('Plain'), current() === PlayerBackgrounds.PLAIN, () => pick(PlayerBackgrounds.PLAIN), 'background-plain'),
          chip(tx('Matched scenes'), current() === PlayerBackgrounds.MATCHED, () => pick(PlayerBackgrounds.MATCHED), 'background-matched'),
          subjects.length ? chip(tx('Matched photos'), current() === PlayerBackgrounds.PHOTOS, () => pick(PlayerBackgrounds.PHOTOS), 'background-photos') : null),
        h('p', { class: 'small muted' }, tx('Matched follows what each ayah is about, as in a shared video. Or choose one for every ayah:')),
        h('div', { class: 'theme-row' }, THEMES.map((t) => tile(t.title, t.key, drawnThumb(t.key)))),
        subjects.length ? h('div', { class: 'theme-row' }, subjects.map((s) => tile(s.title, s.themeKey, h('img', { src: thumbUrl(s), alt: '', loading: 'lazy' })))) : null,
        h('p', { class: 'small muted' }, tx('Dark or Light also colours a scene or photo.')),
        h('p', { class: 'small muted' }, tx('Scenes of sky, land, water, light and pattern, and CC0 nature photos from Wikimedia Commons, never people or animals. A moving background uses a little more battery.')),
      ]);
    }
    draw();
    await dialog({ title: tx('Theme'), content: body, actions: [{ label: tx('Done'), value: true, primary: true }] });
    thumbs.release();
  }

  async function openShareDialog() {
    const t = engine.track;
    if (!t) return;
    let aspect = 'PORTRAIT';
    const shapes = h('div', { class: 'chips' });
    const drawShapes = () => shapes.replaceChildren(...ASPECTS.map((a) => chip(a.ratio, a.key === aspect, () => { aspect = a.key; drawShapes(); }, `share-aspect-${a.key}`)));
    drawShapes();
    let arabic = true;
    let english = true;
    const languages = h('div', { class: 'chips' });
    const drawLanguages = () => languages.replaceChildren(
      chip(tx('Arabic'), arabic, () => { if (arabic && !english) return; arabic = !arabic; drawLanguages(); }, 'share-text-arabic'),
      chip(translationLanguageSync(), english, () => { if (english && !arabic) return; english = !english; drawLanguages(); }, 'share-text-english'),
    );
    drawLanguages();
    const status = h('p', { class: 'small muted', 'data-testid': 'share-status' });
    const content = h('div', { class: 'stack', 'data-testid': 'share-dialog' },
      h('p', { class: 'small muted' }, tx('A video of it recited, a picture of it as it looks now, or the words themselves.')),
      h('div', { class: 'small muted' }, tx('Image shape')), shapes,
      h('div', { class: 'small muted' }, tx('Text')), languages,
      h('button', {
        class: 'btn', type: 'button', 'data-testid': 'share-text',
        onClick: async () => {
          status.textContent = '';
          try {
            await sharePlain(await shareTextOf(t.surah, t.ayah, t.ayah, arabic, english));
          } catch (e) {
            status.textContent = e.message || "The text couldn't be shared.";
          }
        },
      }, tx('Share text')),
      h('button', {
        class: 'btn', type: 'button', 'data-testid': 'copy-text',
        onClick: async () => {
          try { await copyPlain(shareTextOf(t.surah, t.ayah, t.ayah, arabic, english)); }
          catch (e) { status.textContent = e.message || "The text couldn't be copied."; }
        },
      }, tx('Copy text')),
      status);
    // The word being recited, unless an intro is playing or the ayah hasn't started. With word
    // clips the position is into the word's own file, not the ayah: use where the word starts.
    let atMs = null;
    if (!engine.introLabel) {
      if (engine.wordPlayback && engine.wordFileMode) {
        const start = engine.segments?.[engine.wordIndex]?.[0];
        if (start != null) atMs = start + 1;
      } else if (engine.positionMs > 0) {
        atMs = engine.positionMs;
      }
    }
    let drawing = false;
    const choice = await dialog({
      title: tx('Share this ayah'),
      content,
      actions: [
        { label: tx('Share video'), value: 'video' },
        {
          label: tx('Share image'), value: 'image', primary: true,
          onClick: async () => {
            if (drawing) return false; // a second tap while the first is still drawing
            drawing = true;
            status.textContent = tx('Drawing the image…');
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
      wordByWord: s.showWordTranslations, wordFocus: Boolean(s.wordView), fullTranslation: s.showAyahTranslation, opening: false, credits: false,
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
        await navigator.share({ files: [file] });
        return;
      } catch (e) {
        if (e?.name === 'AbortError') return; // closed the share sheet
        // NotAllowedError: drawing took long enough that the browser no longer counts the share
        // as following the tap. Save it instead of doing nothing.
      }
    }
    download(blob, name);
    toast(tx('Image saved to your downloads.'));
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
    if (e.target.closest('.player-menu[open]')) return;
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
  // The scene on screen colours the whole app, dark or light as the theme says (sceneColours.js).
  // Here it only decides how see-through the bars and the card are.
  function markLightScene() {
    const light = Boolean(currentPalette()?.isLight);
    el.classList.toggle('light-scene', light);
    document.getElementById('app')?.classList.toggle('light-scene', light);
  }
  background.hooks.onScene = (spec) => {
    // No scene yet is not "no background": keep the last colours until the first one is drawn.
    if (!spec && store.settings.playerBackground !== PlayerBackgrounds.PLAIN) return;
    setScene(spec);
    markLightScene();
  };
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
    store.on((what) => { if (what === 'settings' || what === 'all') {
      applyBackground();
      markLightScene(); // main.js has already re-applied the colours for a theme change
      renderReader();
      renderState();
    } }),
    // A translation or word meanings that finished downloading: show them. The word reader picks
    // new meanings up as it moves, so only the whole-ayah view is redrawn.
    onContentLoaded(() => { if (renderedKind === 'whole') renderReader(); }),
  ];
  applyBackground();
  markLightScene();
  renderReader();
  renderState();
  renderMore();
  renderTick();
  keepAwake();
  void go;

  return {
    el,
    title: tx('Revise'),
    destroy() {
      document.removeEventListener('click', closeMenus);
      document.removeEventListener('keydown', closeMenus);
      disposed = true;
      cancelAnimationFrame(fitFrame);
      readerResize.disconnect();
      document.fonts?.removeEventListener('loadingdone', fitAyah);
      offs.forEach((off) => off());
      reader?.destroy();
      background.destroy();
      document.getElementById('app')?.classList.remove('scene', 'light-scene');
      closePanel();
      document.removeEventListener('visibilitychange', onVisible);
      document.removeEventListener('keydown', onKey);
      wakeLock?.release?.().catch(() => {});
    },
  };
}

