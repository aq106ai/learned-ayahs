// Recite lab — a developer instrument, not a user feature. English only, no nav entry: open it
// at #/lab, or #/lab/<globalAyahId>.
//
// Recite & review was removed from the Android app because the coach could only ever be as good
// as what the speech engine returned, and nobody could see what that was. The only instrument
// was a person with a phone saying "it is stuck on the first word". This page is the missing
// instrument: it shows, word by word, exactly what the recogniser answers — every reading it
// offered, not just the one it picked — and it drives the same word-by-word flow the app would.
//
// It runs without a microphone too. The simulate box feeds text straight into the flow, so the
// whole thing can be driven from a test, or from a transcript somebody pasted in.

import { downloadText, h, icon } from '../lib/dom.js';
import { globalToSurahAyah, surahAyahToGlobal, surahNameLatin, verseCount } from '../core/quran.js';
import { createWordFlow, WordStatus } from '../core/recite2/wordFlow.js';
import { Listener, listenerSupported } from '../core/recite2/listener.js';
import * as quran from '../services/quranData.js';
import { WordPlayer } from '../player/wordPlayer.js';

/** The Arabic tags the engines offer. Which one hears Qur'anic Arabic best is to be measured. */
const LOCALES = ['ar-SA', 'ar-EG', 'ar-AE', 'ar-JO', 'ar-MA'];

export function reciteLabView({ engine, params }) {
  const startId = Number(params?.[0]) || surahAyahToGlobal(112, 1);
  let [surah, ayah] = globalToSurahAyah(startId);
  let flow = createWordFlow([]);
  let listening = false;
  let error = null;
  let locale = LOCALES[0];
  /** Every utterance of the session, newest first. The evidence. */
  let log = [];
  let startedAt = 0;
  /** Each typed line is an utterance of its own, so it is never read as a continuation. */
  let typed = 0;

  const clips = new WordPlayer((s, a, i) => engine.urls.word(s, a, i));

  const listener = new Listener({
    lang: locale,
    onUtterance: (u) => record(u),
    onListening: (on) => { listening = on; render(); },
    onError: (message) => { error = message; render(); },
  });

  // -- the flow ------------------------------------------------------------------------------

  function record({ text, alternatives = [], final = true, source = 'mic', id = null }) {
    const at = startedAt ? Date.now() - startedAt : 0;
    const waitingFor = flow.current?.text ?? null;
    const events = flow.offer(text, { final, id: id ?? `${source}-${typed++}` });
    log.unshift({ at, text, alternatives, final, source, waitingFor, events });
    if (log.length > 400) log.length = 400;
    render();
  }

  async function loadAyah() {
    flow = createWordFlow(await quran.words(surah, ayah));
    log = [];
    error = null;
    render();
  }

  function selectAyah(s, a) {
    surah = Math.min(Math.max(1, s), 114);
    ayah = Math.min(Math.max(1, a), verseCount(surah));
    history.replaceState(null, '', `#/lab/${surahAyahToGlobal(surah, ayah)}`);
    loadAyah();
  }

  // -- layout --------------------------------------------------------------------------------

  const surahInput = h('input', { class: 'input', type: 'number', min: '1', max: '114', 'data-testid': 'lab-surah', style: { width: '5.5rem' } });
  const ayahInput = h('input', { class: 'input', type: 'number', min: '1', 'data-testid': 'lab-ayah', style: { width: '5.5rem' } });
  surahInput.addEventListener('change', () => selectAyah(Number(surahInput.value), 1));
  ayahInput.addEventListener('change', () => selectAyah(surah, Number(ayahInput.value)));

  const localeSelect = h('select', { class: 'select', 'aria-label': 'Recognition language' },
    LOCALES.map((code) => h('option', { value: code }, code)));
  localeSelect.addEventListener('change', () => {
    locale = localeSelect.value;
    listener.setLanguage(locale);
    render();
  });

  const simulateInput = h('input', {
    class: 'input',
    type: 'text',
    lang: 'ar',
    placeholder: 'Type what the recogniser returned, then Enter',
    'data-testid': 'lab-simulate',
    style: { flex: '1 1 16rem', minWidth: '12rem' },
  });
  simulateInput.addEventListener('keydown', (e) => {
    if (e.key !== 'Enter') return;
    const text = simulateInput.value.trim();
    if (!text) return;
    simulateInput.value = '';
    record({ text, source: 'typed' });
  });

  const card = h('div', { class: 'lab-card', 'data-testid': 'lab-card' });
  const saidBox = h('div', { class: 'lab-chips', 'data-testid': 'lab-said' });
  const statusLine = h('div', { class: 'muted small', 'data-testid': 'lab-status' });
  const evidence = h('tbody', { 'data-testid': 'lab-evidence' });
  const logBody = h('tbody', { 'data-testid': 'lab-log' });
  const errorBox = h('div', { class: 'notice error', hidden: true, 'data-testid': 'lab-error' });

  const micButton = h('button', {
    class: 'btn primary', type: 'button', 'data-testid': 'lab-mic',
    onClick: () => (listening ? listener.stop() : startListening()),
  });

  function startListening() {
    error = null;
    startedAt = Date.now();
    listener.start();
  }

  const el = h('div', { class: 'page', 'data-testid': 'recite-lab' },
    h('header', { class: 'page-head' },
      h('div', {},
        h('h1', {}, 'Recite lab'),
        h('p', { class: 'muted' },
          'What the speech engine actually returns, word by word. A developer instrument: not translated, not in the menu.')),
      h('span', { class: 'badge warn' }, 'Lab')),

    h('section', { class: 'card stack' },
      h('div', { class: 'row wrap' },
        h('label', { class: 'row small' }, 'Surah', surahInput),
        h('label', { class: 'row small' }, 'Ayah', ayahInput),
        h('span', { class: 'spacer' }),
        h('label', { class: 'row small' }, 'Language', localeSelect)),
      card,
      saidBox,
      h('div', { class: 'row wrap', style: { justifyContent: 'center' } },
        micButton,
        h('button', { class: 'btn', type: 'button', 'data-testid': 'lab-play', onClick: () => playCurrent() }, icon('play'), 'Hear this word'),
        h('button', { class: 'btn', type: 'button', 'data-testid': 'lab-back', onClick: () => { flow.stepBack(); render(); } }, 'Back a word'),
        h('button', { class: 'btn', type: 'button', 'data-testid': 'lab-skip', onClick: () => { flow.skipCurrent(); render(); } }, 'Skip this word'),
        h('button', { class: 'btn', type: 'button', 'data-testid': 'lab-reset', onClick: () => { flow.reset(); log = []; render(); } }, icon('refresh'), 'Start over')),
      statusLine,
      errorBox,
      h('div', { class: 'row wrap' }, simulateInput)),

    h('section', { class: 'card stack' },
      h('h2', {}, 'Per word'),
      h('p', { class: 'muted small' },
        'What was heard for each word, and everything the engine offered while that word was the one being waited for.'),
      h('div', { class: 'table-wrap' },
        h('table', { class: 'table' },
          h('thead', {}, h('tr', {},
            h('th', {}, '#'), h('th', {}, 'Word'), h('th', {}, 'Status'),
            h('th', {}, 'Heard as'), h('th', {}, 'Offered instead'))),
          evidence))),

    h('section', { class: 'card stack' },
      h('div', { class: 'row' },
        h('h2', {}, 'Every utterance'),
        h('span', { class: 'spacer' }),
        h('button', { class: 'btn small', type: 'button', 'data-testid': 'lab-export', onClick: () => exportLog() }, 'Download JSON')),
      h('p', { class: 'muted small' },
        'Newest first. "Also offered" is the readings the engine ranked below the one it chose.'),
      h('div', { class: 'table-wrap' },
        h('table', { class: 'table' },
          h('thead', {}, h('tr', {},
            h('th', {}, 'At'), h('th', {}, 'Kind'), h('th', {}, 'Waiting for'),
            h('th', {}, 'Heard'), h('th', {}, 'Also offered'), h('th', {}, 'Result'))),
          logBody))),
  );

  async function playCurrent() {
    const i = flow.index;
    if (i >= flow.words.length) return;
    await clips.playAndAwait(surah, ayah, i);
  }

  function exportLog() {
    downloadText(`recite-lab-${surah}-${ayah}.json`, JSON.stringify({
      surah,
      ayah,
      locale,
      words: flow.words.map((w) => w.text),
      outcomes: flow.outcomes,
      utterances: [...log].reverse(),
    }, null, 2));
  }

  // -- rendering -----------------------------------------------------------------------------

  function render() {
    surahInput.value = String(surah);
    ayahInput.max = String(verseCount(surah));
    ayahInput.value = String(ayah);
    localeSelect.value = locale;

    const outcomes = flow.outcomes;
    const current = flow.current;

    card.replaceChildren(
      current
        ? h('div', { class: 'lab-word', lang: 'ar', 'data-testid': 'lab-word' }, current.text)
        : h('div', { class: 'lab-word done', 'data-testid': 'lab-word' }, 'Ayah finished'),
      h('div', { class: 'muted small', 'data-testid': 'lab-progress' },
        current
          ? `Word ${flow.index + 1} of ${flow.words.length} · ${surah}:${ayah} ${surahNameLatin(surah)}`
          : `${flow.correctCount} of ${flow.words.length} recognised · ${surah}:${ayah}`),
    );

    saidBox.replaceChildren(...outcomes.map((o, i) => h('span', {
      class: `lab-chip ${o.status}`,
      lang: 'ar',
      'data-index': String(i),
      title: o.status === WordStatus.CORRECT ? `heard as ${o.heard}` : o.misheard.join(' · '),
    }, o.word)));

    statusLine.textContent = listening
      ? `Listening (${locale}) — say the word above`
      : listenerSupported()
        ? 'Not listening. Press Listen, or type into the box below to drive it without a microphone.'
        : 'This browser has no speech recognition; the box below still works.';

    errorBox.hidden = !error;
    errorBox.textContent = error ?? '';
    micButton.replaceChildren(icon('mic'), listening ? 'Stop' : 'Listen');

    evidence.replaceChildren(...outcomes.map((o, i) => h('tr', {},
      h('td', {}, String(i + 1)),
      h('td', { lang: 'ar' }, o.word),
      h('td', {}, o.status),
      h('td', { lang: 'ar' }, o.heard ?? '—'),
      h('td', { lang: 'ar' }, o.misheard.length ? o.misheard.join(' · ') : '—'))));

    logBody.replaceChildren(...log.map((entry) => h('tr', {},
      h('td', {}, `${(entry.at / 1000).toFixed(1)}s`),
      h('td', {}, entry.source === 'typed' ? 'typed' : entry.final ? 'final' : 'partial'),
      h('td', { lang: 'ar' }, entry.waitingFor ?? '—'),
      h('td', { lang: 'ar' }, entry.text),
      h('td', { lang: 'ar' }, entry.alternatives.length ? entry.alternatives.join(' · ') : '—'),
      h('td', {}, entry.events.length
        ? entry.events.map((e) => (e.type === 'correct' ? `✓ word ${e.index + 1}` : `✗ ${e.heard}`)).join(', ')
        : '—'))));
  }

  loadAyah();
  render();

  return { title: 'Recite lab', el, destroy: () => listener.stop() };
}
