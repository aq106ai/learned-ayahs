// Recite & review (beta): recite a learned ayah from memory while the browser listens; the coach
// plays the reciter's word only when you really say a wrong one. Port of
// ui/recitation/RecitationScreen.kt over the shared coach in core/recite/coach.js.

import { dialog, h, icon } from '../lib/dom.js';
import { globalToSurahAyah, isValidGlobalId, surahName, trackTitle } from '../core/quran.js';
import { Status } from '../core/recite/evaluator.js';
import { RecitationCoach } from '../core/recite/coach.js';
import * as quran from '../services/quranData.js';
import { SpeechSession, speechSupported } from '../services/speech.js';
import { WordPlayer } from '../player/wordPlayer.js';

export function reciteView({ store, engine, params, go }) {
  if (!store.settings.reciteBetaEnabled) {
    return {
      title: 'Recite & review',
      el: h('div', { class: 'page page-narrow' },
        h('section', { class: 'card empty' },
          h('h2', {}, 'Recite & review is a beta'),
          h('p', {}, 'Turn it on in Settings to practise reciting your learned ayahs from memory while the app listens.'),
          h('a', { class: 'btn primary', href: '#/settings' }, icon('settings'), 'Open Settings'))),
    };
  }

  const learned = [...store.learned].sort((a, b) => a - b);
  if (!learned.length) {
    return {
      title: 'Recite & review',
      el: h('div', { class: 'page page-narrow' }, h('section', { class: 'card empty' },
        h('h2', {}, 'Nothing to recite yet'),
        h('p', {}, 'Mark the ayahs you have memorised first; Recite & review walks through them.'),
        h('a', { class: 'btn primary', href: '#/surahs' }, icon('book'), 'Browse surahs'))),
    };
  }

  const words = new WordPlayer((s, a, i) => engine.urls.word(s, a, i));
  let listening = false;
  let hideUpcoming = false;
  let speechError = null;

  const speech = new SpeechSession({
    onTranscript: (text, committed, alternatives) => coach.acceptTranscript(text, committed, alternatives),
    onListening: (on) => {
      listening = on;
      render();
    },
    onError: (msg) => {
      speechError = msg;
      render();
    },
  });

  const coach = new RecitationCoach({
    loadWords: (s, a) => quran.words(s, a),
    settings: () => store.settings,
    playWordClip: (s, a, i) => words.playAndAwait(s, a, i),
    speech: {
      startListening: () => {
        speechError = null;
        speech.start();
      },
      stopListening: () => speech.stop(),
      pauseForCorrectionClip: () => speech.pauseForCorrectionClip(),
      resumeAfterCorrectionClip: () => speech.resumeAfterCorrectionClip(),
      updateRecognizedText: (t) => speech.updateRecognizedText(t),
    },
    onChange: () => render(),
  });

  // -- layout ------------------------------------------------------------------------------

  const title = h('h2', { 'data-testid': 'recite-title' });
  const select = h('select', { class: 'select', 'aria-label': 'Ayah to recite', style: { maxWidth: '260px' } },
    learned.map((id) => {
      const [s, a] = globalToSurahAyah(id);
      return h('option', { value: String(id) }, `${s}:${a} · ${surahName(s)}`);
    }));
  select.addEventListener('change', () => coach.selectAyah(Number(select.value), listening));

  const wordsBox = h('div', { class: 'ayah-flow recite-words', lang: 'ar', 'data-testid': 'recite-words' });
  const accuracy = h('div', { class: 'small muted', 'data-testid': 'recite-accuracy' });
  const heard = h('div', { class: 'heard arabic', 'data-testid': 'recite-heard' });
  const mistakeCard = h('div', { hidden: true });
  const wrongCard = h('div', { hidden: true });
  const errorBox = h('div', { class: 'notice error', hidden: true });
  const mic = h('button', { class: 'mic', type: 'button', 'data-testid': 'recite-mic', onClick: () => (listening ? coach.stopRecording() : coach.startRecording()) });
  const status = h('div', { class: 'muted small', style: { textAlign: 'center' } });

  const el = h('div', { class: 'page', 'data-testid': 'recite-screen' },
    h('header', { class: 'page-head' },
      h('div', {}, h('h1', {}, 'Recite & review'), h('p', { class: 'muted' }, 'Recite from memory. You are only interrupted for a word you really got wrong.')),
      h('span', { class: 'badge warn' }, 'Beta')),
    h('section', { class: 'card stack' },
      h('div', { class: 'row wrap' },
        h('button', { class: 'btn icon-only', type: 'button', 'aria-label': 'Previous ayah', 'data-testid': 'recite-prev', onClick: () => coach.previousAyah(listening) }, icon('chevronLeft')),
        title,
        h('span', { class: 'spacer' }),
        select,
        h('button', { class: 'btn', type: 'button', 'data-testid': 'recite-next', onClick: () => coach.nextAyah(listening) }, 'Next ayah', icon('next'))),
      h('label', { class: 'row small muted' },
        h('input', { type: 'checkbox', onChange: (e) => { hideUpcoming = e.target.checked; render(); } }),
        'Hide words until I recite them'),
      h('div', { style: { padding: '12px 0' } }, wordsBox),
      accuracy,
    ),
    wrongCard,
    mistakeCard,
    errorBox,
    h('section', { class: 'card stack', style: { alignItems: 'center' } },
      mic,
      status,
      heard,
      h('div', { class: 'row' },
        h('button', { class: 'btn small', type: 'button', 'data-testid': 'recite-retry', onClick: () => coach.retryCurrentAyah() }, icon('refresh'), 'Recite again')),
    ),
  );

  // -- rendering ---------------------------------------------------------------------------

  let reportShown = null;

  function render() {
    const st = coach.state;
    const ws = st.words;
    title.textContent = trackTitle(st.surah, st.ayah);
    select.value = String(st.currentGlobalId);
    const evaluations = st.evaluationResult?.evaluations ?? [];
    let content = 0;
    wordsBox.replaceChildren(...ws.map((w) => {
      if (w.isEnd) return h('span', { class: 'word end' }, h('span', { class: 'ar' }, w.text));
      const i = content++;
      const e = evaluations[i];
      const believedWrong = st.believedMistakeIndices.has(i) && e?.status !== Status.CORRECT;
      const cls = e?.status === Status.CORRECT ? 'correct' : believedWrong ? 'mistake' : '';
      const hidden = hideUpcoming && e?.status !== Status.CORRECT && !believedWrong;
      return h('span', { class: `word ${cls}`, 'data-index': i, style: hidden ? { filter: 'blur(9px)' } : undefined },
        h('span', { class: 'ar' }, w.text));
    }));
    const r = st.evaluationResult;
    accuracy.textContent = r?.totalContentWords ? `${r.correctCount} of ${r.totalContentWords} words recited` : '';
    heard.textContent = st.recognizedText ? `“${st.recognizedText}”` : '';

    // Only a corroborated mistake is shown — the evaluation alone is not believed.
    const m = st.confirmedMistake;
    mistakeCard.hidden = !m;
    if (m) {
      mistakeCard.replaceChildren(h('section', { class: 'card stack', 'data-testid': 'mistake-card' },
        h('strong', {}, 'Mistake detected'),
        h('div', { class: 'small' }, m.feedback ?? `Expected ${m.originalWord.text}`),
        h('div', {}, h('button', { class: 'btn small', type: 'button', onClick: () => coach.playWord(m.wordIndex) }, icon('volume'), 'Listen to the reciter'))));
    }
    wrongCard.hidden = !st.wrongAyahFeedback;
    if (st.wrongAyahFeedback) {
      const [ws2, wa] = globalToSurahAyah(st.detectedWrongAyahId);
      wrongCard.replaceChildren(h('section', { class: 'card stack' },
        h('strong', {}, 'Wrong ayah'),
        h('div', { class: 'small' }, st.wrongAyahFeedback),
        h('div', {}, h('button', { class: 'btn small', type: 'button', onClick: () => coach.selectAyah(st.detectedWrongAyahId, listening) }, `Switch to ${ws2}:${wa}`))));
    }
    errorBox.hidden = !speechError && speechSupported();
    errorBox.textContent = speechError ?? (speechSupported() ? '' : 'This browser has no speech recognition. Recite & review works in Chrome, Edge and Safari.');
    mic.classList.toggle('live', listening);
    mic.replaceChildren(icon(listening ? 'square' : 'mic', { filled: listening }));
    mic.setAttribute('aria-label', listening ? 'Stop reciting' : 'Start reciting');
    status.textContent = st.isAutoPlayingMistake
      ? 'Playing the correct word… keep going after'
      : listening
        ? 'Listening to your recitation…'
        : 'Tap the microphone and recite the ayah';

    if (st.sessionReport && st.sessionReport !== reportShown) {
      reportShown = st.sessionReport;
      showReport(st.sessionReport);
    }
  }

  async function showReport(report) {
    const rows = report.ayahs.map((o) => h('div', { class: 'stack', style: { gap: '6px' } },
      h('div', { class: 'row' }, h('strong', {}, `${o.surah}:${o.ayah}`), h('span', { class: `badge ${o.isPerfect ? 'ok' : 'warn'}` },
        o.isPerfect ? 'Perfect' : `${o.correctCount}/${o.totalContentWords}`)),
      o.mistakenWordIndices.length
        ? h('div', { class: 'chips' }, o.mistakenWordIndices.map((i) =>
          h('button', { class: 'chip', type: 'button', onClick: () => coach.playWordOf(o.surah, o.ayah, i) }, icon('volume'), ` Word ${i + 1}`)))
        : null));
    const choice = await dialog({
      title: report.mistakeCount === 0 && report.reachedEndOfSurah ? 'Perfect recitation!' : report.reachedEndOfSurah ? 'Surah complete' : 'Recitation report',
      content: h('div', { class: 'stack', 'data-testid': 'recite-report' },
        h('p', { class: 'muted', style: { margin: 0 } },
          `${report.accuracyPercent}% · ${report.perfectAyahs} of ${report.ayahs.length} ayahs perfect · ${report.mistakeCount} word${report.mistakeCount === 1 ? '' : 's'} to work on`),
        ...rows),
      actions: [
        { label: 'Done', value: 'done' },
        { label: 'Recite again', value: 'again', primary: true },
      ],
    });
    coach.dismissSessionReport();
    if (choice === 'again') {
      coach.retryCurrentAyah();
    }
  }

  // Start on the ayah the player was on (or the one in the URL), else the first learned ayah.
  const requested = Number(params[0]);
  const start = isValidGlobalId(requested) && store.learned.has(requested)
    ? requested
    : learned.includes(engine.track?.globalId) ? engine.track.globalId : learned[0];
  engine.pause();
  coach.setLearned(learned).then(() => coach.selectAyah(start));
  render();

  // Lets automated tests drive the coach without a microphone (same door the recogniser uses).
  if (new URLSearchParams(location.search).has('test')) {
    window.__reciteTest = { coach, feed: (text, committed = true, alts = []) => coach.acceptTranscript(text, committed, alts) };
  }
  void go;

  return {
    el,
    title: 'Recite & review',
    destroy() {
      speech.stop();
      words.stop();
      delete window.__reciteTest;
    },
  };
}
