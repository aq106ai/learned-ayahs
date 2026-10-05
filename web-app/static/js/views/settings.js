// Settings: reciter, theme, reading, gestures, playback, Recite & review, offline, your data.
// Port of ui/settings/SettingsScreen.kt, plus web-only options.

import { confirmDialog, h, icon, toast } from '../lib/dom.js';
import { RECITERS, WORD_RECITERS, reciterFromKey } from '../core/reciters.js';
import { buildMaster } from '../core/quran.js';
import * as quran from '../services/quranData.js';
import { clearOfflineAudio, countCached, saveForOffline } from '../services/offline.js';
import { speechSupported } from '../services/speech.js';
import { exportFlow, importFlow } from './common.js';

const VERSION = '1.0.0';

export function settingsView({ store, engine, server }) {
  const s = () => store.settings;

  function toggle(key, title, description, { onTurnOn } = {}) {
    const input = h('input', {
      class: 'switch',
      type: 'checkbox',
      checked: s()[key],
      'data-testid': `setting-${key}`,
      onChange: async (e) => {
        if (e.target.checked && onTurnOn) {
          const ok = await onTurnOn();
          if (!ok) {
            e.target.checked = false;
            return;
          }
        }
        store.setSetting(key, e.target.checked);
      },
    });
    return h('label', { class: 'toggle-row' }, h('span', { class: 'text' }, h('strong', {}, title), h('span', {}, description)), input);
  }

  function radios(name, options, current, onChange) {
    const group = h('div', { class: 'chips', role: 'radiogroup' });
    group.append(...options.map(([value, label]) =>
      h('button', {
        class: 'chip',
        type: 'button',
        role: 'radio',
        'aria-checked': String(value === current),
        'aria-pressed': String(value === current),
        'data-testid': `${name}-${value}`,
        onClick: (e) => {
          for (const b of group.children) {
            const on = b === e.currentTarget;
            b.setAttribute('aria-checked', String(on));
            b.setAttribute('aria-pressed', String(on));
          }
          onChange(value);
        },
      }, label)));
    return group;
  }

  const card = (title, sub, ...children) =>
    h('section', { class: 'card' }, h('h2', {}, title), sub ? h('p', { class: 'card-sub' }, sub) : null, ...children);

  // -- offline -----------------------------------------------------------------------------

  const offlineStatus = h('p', { class: 'small muted', 'data-testid': 'offline-status' }, 'Checking…');
  const offlineBar = h('div', { class: 'progress', hidden: true }, h('span'));
  const learnedUrls = () => {
    const reciter = reciterFromKey(s().reciter);
    const urls = engine.urls;
    const tracks = buildMaster(store.learned);
    return [urls.audhu(), urls.bismillah(), ...tracks.map((t) => urls.ayah(reciter, t.surah, t.ayah))];
  };
  async function wordClipUrls() {
    await quran.loadText();
    const urls = engine.urls;
    const out = [];
    for (const t of buildMaster(store.learned)) {
      const n = quran.contentWords(quran.wordsSync(t.surah, t.ayah)).length;
      for (let i = 0; i < n; i++) out.push(urls.word(t.surah, t.ayah, i));
    }
    return out;
  }
  async function refreshOffline() {
    if (!('caches' in globalThis)) {
      offlineStatus.textContent = 'This browser cannot store audio for offline use.';
      return;
    }
    const urls = learnedUrls();
    const cached = await countCached(urls);
    offlineStatus.textContent = `Saved offline: ${Math.max(cached - 2, 0)} of ${urls.length - 2} learned ayahs (${reciterFromKey(s().reciter).displayName}). The Qur'an text, meanings and timings are kept automatically once loaded.`;
  }
  async function runSave(getUrls, label) {
    const urls = await getUrls();
    if (!urls.length) {
      toast('Mark some ayahs first.');
      return;
    }
    offlineBar.hidden = false;
    const bar = offlineBar.firstChild;
    const result = await saveForOffline(urls, {
      onProgress: (done, total) => {
        bar.style.width = `${Math.round((done / total) * 100)}%`;
        offlineStatus.textContent = `${label}: ${done} / ${total}…`;
      },
    }).catch((err) => ({ error: err.message }));
    offlineBar.hidden = true;
    if (result.error) toast(result.error, { error: true });
    else if (result.failed) toast(`Saved, but ${result.failed} files could not be downloaded. Try again when online.`, { error: true });
    else toast('Saved for offline use.');
    refreshOffline();
  }

  // -- Recite beta -------------------------------------------------------------------------

  const confirmRecite = () =>
    confirmDialog(
      'Turn on Recite & review?',
      "This is a beta feature. It listens through your browser's Arabic speech recognition, which is built for everyday speech rather than Qur'anic recitation. It will sometimes miss a real mistake, and it can occasionally flag a word you recited correctly. Treat it as practice help, never as a check on whether your recitation is correct. Your microphone is only used while you are on the Recite screen.",
      { confirm: 'Turn on' },
    );

  const el = h(
    'div',
    { class: 'page page-narrow' },
    h('header', { class: 'page-head' }, h('div', {}, h('h1', {}, 'Settings'))),
    card(
      'Ayah reciter',
      'Whole-ayah recitations from everyayah.com. Each one ships exact per-word timings, so the word being recited is highlighted.',
      radios('reciter', RECITERS.map((r) => [r.key, r.displayName]), s().reciter, (v) => {
        store.setSetting('reciter', v);
        refreshOffline();
      }),
      h('p', { class: 'tiny muted', style: { marginTop: '10px' } }, `Word clips: ${WORD_RECITERS[0].displayName}.`),
    ),
    card('Theme', null, radios('theme', [['DARK', 'Dark'], ['LIGHT', 'Light'], ['SYSTEM', 'Match system']], s().theme, (v) => {
      store.setSetting('theme', v);
    })),
    card(
      'Reading',
      null,
      toggle('showWordTranslations', 'Word-by-word translation', "Show each word's meaning beneath it in the ayah views."),
      h('div', { class: 'toggle-row' }, h('span', { class: 'text' }, h('strong', {}, 'Long ayahs'), h('span', {}, 'When a whole ayah is too long to fit on screen while playing:'))),
      radios('overflow', [['AUTO_SCROLL', 'Auto scroll'], ['FIT_TO_SCREEN', 'Fit to screen']], s().ayahOverflowMode, (v) => {
        store.setSetting('ayahOverflowMode', v);
      }),
    ),
    card(
      'Gestures',
      'Swipe or tap on the player to move between ayahs.',
      toggle('swipeToNavigate', 'Swipe to navigate', 'Swipe left/right on the player to move between ayahs (or words).'),
      toggle('tapToAdvance', 'Tap to advance', 'Tap the player to move to the next ayah (or word).'),
    ),
    card(
      'Playback',
      null,
      toggle('useWordClips', 'Word clips (Quran.com)', 'In Word by word mode, play isolated pronunciation clips instead of cutting each word from your ayah reciter.'),
      toggle('seekInsideAyahAudio', 'Seek to the word in the reader', 'When you move through words in the word-by-word reader, jump the ayah audio to that word.'),
      server.available
        ? toggle('streamDirect', 'Stream directly from everyayah.com', "Bypass this server's audio cache. Turn on if the server has no internet access.")
        : null,
    ),
    card(
      'Recitation & AI review',
      null,
      h('div', { class: 'row' }, h('strong', {}, 'Recite & review'), h('span', { class: 'badge warn' }, 'Beta')),
      h('p', { class: 'small muted' }, speechSupported()
        ? 'Listens while you recite a learned ayah and plays the correct word back when you say a wrong one.'
        : 'This browser has no speech recognition. Recite & review works in Chrome, Edge and Safari.'),
      toggle('reciteBetaEnabled', 'Enable Recite & review', 'Adds a Recite button to the player and a Recite tab.', { onTurnOn: confirmRecite }),
      toggle('autoPlayMistakeAudio', 'Auto-play audio on mistake', 'Play the reciter’s word when a mistake is detected, then let you re-recite.'),
      toggle('autoAdvanceSuccess', 'Auto-advance on success', 'Move to the next ayah once every word has been recited correctly.'),
      h('div', { class: 'row', style: { marginTop: '8px' } },
        h('button', { class: 'btn small', type: 'button', onClick: () => runSave(wordClipUrls, 'Word clips') }, icon('download'), 'Save word clips for Recite & review')),
    ),
    card(
      'Offline',
      'Keep your learned ayahs playable without a connection.',
      offlineStatus,
      offlineBar,
      h('div', { class: 'row wrap' },
        h('button', { class: 'btn primary', type: 'button', 'data-testid': 'save-offline', onClick: () => runSave(learnedUrls, 'Saving') }, icon('download'), 'Save learned ayahs for offline'),
        h('button', {
          class: 'btn ghost',
          type: 'button',
          onClick: async () => {
            if (await confirmDialog('Remove offline audio?', 'Saved recitations will stream again next time.', { confirm: 'Remove', danger: true })) {
              await clearOfflineAudio();
              refreshOffline();
            }
          },
        }, icon('trash'), 'Remove offline audio'),
      ),
    ),
    card(
      'Your learned ayahs',
      'Back up your list, move it between devices, or bring it in from the Android app. Files are interchangeable with the Android app.',
      h('div', { class: 'row wrap' },
        h('button', { class: 'btn', type: 'button', onClick: () => exportFlow(store) }, icon('download'), 'Export'),
        h('button', { class: 'btn', type: 'button', onClick: () => importFlow(store) }, icon('upload'), 'Import'),
        h('button', {
          class: 'btn danger',
          type: 'button',
          onClick: async () => {
            if (await confirmDialog('Unmark every ayah?', `This clears all ${store.learned.size} learned ayahs${store.user ? ' from your account' : ' in this browser'}. Export first if you might want them back.`, { confirm: 'Unmark all', danger: true })) {
              store.setLearnedIds([]);
              toast('All ayahs unmarked.');
            }
          },
        }, 'Unmark all'),
      ),
    ),
    card(
      'About',
      null,
      h('p', { class: 'small', style: { margin: 0 } }, `Learned Ayahs web v${VERSION} · open source (MIT).`),
      h('p', { class: 'small muted' }, 'Recitation: everyayah.com · Word audio, text and meanings: Quran.com · Word timings: QUL by Tarteel · Arabic: Scheherazade New (SIL OFL).'),
      h('p', { class: 'small' }, h('a', { href: 'https://github.com/aq106ai/learned-ayahs', target: '_blank', rel: 'noopener' }, 'Source code and Android app')),
    ),
  );

  refreshOffline();
  return { el, title: 'Settings' };
}
