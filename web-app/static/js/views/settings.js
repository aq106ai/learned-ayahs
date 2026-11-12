// Settings: reciter, reading, gestures, playback, Recite & review, offline, your data.
// Port of ui/settings/SettingsScreen.kt, plus web-only options.

import { confirmDialog, h, icon, toast } from '../lib/dom.js';
import { reciterFromKey } from '../core/reciters.js';
import { buildMaster } from '../core/quran.js';
import * as quran from '../services/quranData.js';
import { clearOfflineAudio, countCached, saveForOffline } from '../services/offline.js';
import { speechSupported } from '../services/speech.js';
import { exportFlow, importFlow } from './common.js';
import { sourcesAndDisclaimer } from './terms.js';
import { contentChoices } from './contentChoices.js';
import { uiLanguagePicker } from './uiLanguage.js';
import { clearDownloaded, downloadedCount } from '../services/content.js';

import { tx } from '../core/i18n.js';
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

  const offlineStatus = h('p', { class: 'small muted', 'data-testid': 'offline-status' }, tx('Checking…'));
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
      offlineStatus.textContent = tx('This browser cannot store audio for offline use.');
      return;
    }
    const urls = learnedUrls();
    const cached = await countCached(urls);
    offlineStatus.textContent = tx("Saved offline: {n} of {total} learned ayahs ({reciter}). The Qur'an text, meanings and timings are kept automatically once loaded.", { n: Math.max(cached - 2, 0), total: urls.length - 2, reciter: reciterFromKey(s().reciter).displayName });
  }
  async function runSave(getUrls, label) {
    const urls = await getUrls();
    if (!urls.length) {
      toast(tx('Mark some ayahs first.'));
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
    else if (result.failed) toast(tx('Saved, but {n} files could not be downloaded. Try again when online.', { n: result.failed }), { error: true });
    else toast(tx('Saved for offline use.'));
    refreshOffline();
  }

  // -- Recite beta -------------------------------------------------------------------------

  const confirmRecite = () =>
    confirmDialog(
      tx('Turn on Recite & review?'),
      tx("This is a beta feature. It listens through your browser's Arabic speech recognition, which is built for everyday speech rather than Qur'anic recitation. It will sometimes miss a real mistake, and it can occasionally flag a word you recited correctly. Treat it as practice help, never as a check on whether your recitation is correct. Your microphone is only used while you are on the Recite screen."),
      { confirm: tx('Turn on') },
    );

  const el = h(
    'div',
    { class: 'page page-narrow' },
    h('header', { class: 'page-head' }, h('div', {}, h('h1', {}, tx('Settings')))),
    card(tx('App language'), tx("The language of the app's menus and messages. The Qur'an, translations and tafsir have their own choices below."), uiLanguagePicker()),
    card(
      tx('Translation and tafsir'),
      tx('Shown beside the Arabic in the player, the surah pages, shared text and videos. Saheeh International and the English word meanings come with the app; other editions download from Quran.com when chosen and are kept for offline use.'),
      contentChoices(store),
      h('div', { class: 'row', style: { marginTop: '10px' } },
        h('button', {
          class: 'btn small ghost', type: 'button', 'data-testid': 'content-clear',
          onClick: async () => {
            const n = await downloadedCount().catch(() => 0);
            if (!n) {
              toast(tx('Nothing is downloaded. The bundled translation and meanings always stay.'));
              return;
            }
            if (await confirmDialog(tx('Remove downloaded translations?'), tx('They will download again the next time they are shown.'), { confirm: tx('Remove'), danger: true })) {
              await clearDownloaded();
              toast(tx('Downloaded translations removed.'));
            }
          },
        }, icon('trash'), tx('Remove downloaded translations'))),
    ),
    card(
      tx('Reading'),
      null,
      h('div', { class: 'toggle-row' }, h('span', { class: 'text' }, h('strong', {}, tx('Long ayahs')), h('span', {}, tx('When a whole ayah is too long to fit on screen while playing:')))),
      radios('overflow', [['AUTO_SCROLL', tx('Auto scroll')], ['FIT_TO_SCREEN', tx('Fit to screen')]], s().ayahOverflowMode, (v) => {
        store.setSetting('ayahOverflowMode', v);
      }),
    ),
    card(
      tx('Gestures'),
      tx('Swipe or tap on the player to move between ayahs.'),
      toggle('swipeToNavigate', tx('Swipe to navigate'), tx('Swipe left/right on the player to move between ayahs (or words).')),
      toggle('tapToAdvance', tx('Tap to advance'), tx('Tap the player to move to the next ayah (or word).')),
    ),
    card(
      tx('Playback'),
      null,
      toggle('seekInsideAyahAudio', tx('Seek to the word in the reader'), tx('When you move through words in the word-by-word reader, jump the ayah audio to that word.')),
      server.available
        ? toggle('streamDirect', tx('Stream directly from everyayah.com'), tx("Bypass this server's audio cache. Turn on if the server has no internet access."))
        : null,
    ),
    card(
      tx('Recitation & AI review'),
      null,
      h('div', { class: 'row' }, h('strong', {}, tx('Recite & review')), h('span', { class: 'badge warn' }, tx('Beta'))),
      h('p', { class: 'small muted' }, speechSupported()
        ? tx('Listens while you recite a learned ayah and plays the correct word back when you say a wrong one.')
        : tx('This browser has no speech recognition. Recite & review works in Chrome, Edge and Safari.')),
      toggle('reciteBetaEnabled', tx('Enable Recite & review'), tx('Adds a Recite button to the player and a Recite tab.'), { onTurnOn: confirmRecite }),
      toggle('autoPlayMistakeAudio', tx('Auto-play audio on mistake'), tx('Play the reciter’s word when a mistake is detected, then let you re-recite.')),
      toggle('autoAdvanceSuccess', tx('Auto-advance on success'), tx('Move to the next ayah once every word has been recited correctly.')),
      h('div', { class: 'row', style: { marginTop: '8px' } },
        h('button', { class: 'btn small', type: 'button', onClick: () => runSave(wordClipUrls, 'Word clips') }, icon('download'), tx('Save word clips for Recite & review'))),
    ),
    card(
      tx('Offline'),
      tx('Keep your learned ayahs playable without a connection.'),
      offlineStatus,
      offlineBar,
      h('div', { class: 'row wrap' },
        h('button', { class: 'btn primary', type: 'button', 'data-testid': 'save-offline', onClick: () => runSave(learnedUrls, 'Saving') }, icon('download'), tx('Save learned ayahs for offline')),
        h('button', {
          class: 'btn ghost',
          type: 'button',
          onClick: async () => {
            if (await confirmDialog(tx('Remove offline audio?'), tx('Saved recitations will stream again next time.'), { confirm: tx('Remove'), danger: true })) {
              await clearOfflineAudio();
              refreshOffline();
            }
          },
        }, icon('trash'), tx('Remove offline audio')),
      ),
    ),
    card(
      tx('Your learned ayahs'),
      tx('Back up your list, move it between devices, or bring it in from the Android app. Files are interchangeable with the Android app.'),
      h('div', { class: 'row wrap' },
        h('button', { class: 'btn', type: 'button', onClick: () => exportFlow(store) }, icon('download'), tx('Export')),
        h('button', { class: 'btn', type: 'button', onClick: () => importFlow(store) }, icon('upload'), tx('Import')),
        h('button', {
          class: 'btn danger',
          type: 'button',
          onClick: async () => {
            if (await confirmDialog(tx('Unmark every ayah?'), store.user ? tx('This clears all {n} learned ayahs from your account. Export first if you might want them back.', { n: store.learned.size }) : tx('This clears all {n} learned ayahs in this browser. Export first if you might want them back.', { n: store.learned.size }), { confirm: tx('Unmark all'), danger: true })) {
              store.setLearnedIds([]);
              toast(tx('All ayahs unmarked.'));
            }
          },
        }, tx('Unmark all')),
      ),
    ),
    card(
      tx('About'),
      null,
      h('p', { class: 'small', style: { margin: 0 } }, tx('Learned Ayahs web v{version} · open source (MIT).', { version: VERSION })),
      h('p', { class: 'small muted' }, tx('Recitation: everyayah.com · Word audio, text and meanings: Quran.com · Word timings: QUL by Tarteel · Arabic: Scheherazade New (SIL OFL).')),
      h('p', { class: 'small' }, h('a', { href: 'https://github.com/aq106ai/learned-ayahs', target: '_blank', rel: 'noopener' }, tx('Source code and Android app'))),
    ),
    card(tx('Sources and disclaimer'), null, h('div', { class: 'small' }, sourcesAndDisclaimer())),
  );

  refreshOffline();
  return { el, title: tx('Settings') };
}
