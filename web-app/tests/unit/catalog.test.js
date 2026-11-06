// The content catalog and its rules (core/catalog.js); the same cases as ContentCatalogTest.kt.
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import path from 'node:path';
import {
  TRANSLATION_STYLES, alignWordMeanings, creditOf, isRtl, plainTranslation, searchTranslations, styleOf,
  switchesFor, tafsirById, tafsirsFor, translationById, translationLanguages, translationsIn, wordMeaningsById,
} from '../../static/js/core/catalog.js';
import { RECITERS } from '../../static/js/core/reciters.js';
import { ASSETS, quranText } from './helpers.js';

const catalog = JSON.parse(readFileSync(path.join(ASSETS, 'content_catalog.json'), 'utf8'));

test('the catalog has stable, unique ids and every edition names its source and licence', () => {
  assert.equal(catalog.format, 'learned-ayahs-content-catalog');
  for (const list of [catalog.translations, catalog.tafsirs, catalog.wordMeanings, catalog.reciters]) {
    const ids = list.map((x) => x.id);
    assert.equal(new Set(ids).size, ids.length);
  }
  for (const t of catalog.translations) {
    assert.match(t.id, /^(en-saheeh|qc-\d+)$/);
    assert.ok(t.name && t.author && t.source && t.licence, t.id);
    assert.ok(catalog.languages[t.language], `${t.id}: language ${t.language}`);
    if (!t.bundled) assert.ok(Number.isInteger(t.resourceId), t.id);
  }
  for (const t of catalog.tafsirs) {
    assert.match(t.id, /^qc-tafsir-\d+$/);
    assert.ok(t.name && t.author && t.source && t.licence && catalog.languages[t.language], t.id);
  }
  // Only what already shipped is bundled: nothing new is redistributed.
  assert.deepEqual(catalog.translations.filter((t) => t.bundled).map((t) => t.id), ['en-saheeh']);
  assert.deepEqual(catalog.wordMeanings.filter((w) => w.bundled).map((w) => w.id), ['en']);
  assert.ok(catalog.translations.length > 100);
  assert.ok(catalog.tafsirs.length >= 20);
});

test('the default edition is the bundled Saheeh International, and every language has a recommended edition', () => {
  assert.equal(catalog.defaults.translation, 'en-saheeh');
  assert.equal(translationById(catalog, 'en-saheeh').bundled, true);
  for (const code of translationLanguages(catalog)) {
    assert.ok(translationsIn(catalog, code).some((t) => t.recommended), code);
  }
  assert.equal(translationLanguages(catalog)[0], 'en');
  assert.equal(translationsIn(catalog, 'en')[0].id, 'en-saheeh');
});

test('the five reciters are the catalog reciters, by the same saved keys, all with highlighting', () => {
  assert.deepEqual(
    catalog.reciters.map((r) => [r.id, r.folder, r.timingsAsset, r.highlighting]),
    RECITERS.map((r) => [r.key, r.folder, r.timingsAsset, true]),
  );
  assert.equal(catalog.defaults.reciter, 'MAHER_AL_MUAIQLY');
});

test('saved ids resolve: unknown falls back to the default, retired follows its replacement, none means none', () => {
  assert.equal(translationById(catalog, 'qc-999999').id, 'en-saheeh');
  assert.equal(translationById(catalog, undefined).id, 'en-saheeh');
  assert.equal(translationById({ ...catalog, retired: { 'qc-1': 'qc-54' } }, 'qc-1').id, 'qc-54');
  assert.equal(translationById({ ...catalog, retired: { 'qc-1': 'qc-2', 'qc-2': 'qc-1' } }, 'qc-1').id, 'en-saheeh', 'a cycle does not hang');
  assert.equal(tafsirById(catalog, ''), null);
  assert.equal(tafsirById(catalog, 'qc-tafsir-169').name, 'Ibn Kathir (Abridged)');
  assert.equal(tafsirById(catalog, 'qc-tafsir-0'), null);
  assert.equal(wordMeaningsById(catalog, 'ur').id, 'ur');
  assert.equal(wordMeaningsById(catalog, 'xx').id, 'en');
});

test('right-to-left languages are marked', () => {
  for (const code of ['ar', 'ur', 'fa']) assert.equal(isRtl(catalog, code), true, code);
  for (const code of ['en', 'id', 'tr']) assert.equal(isRtl(catalog, code), false, code);
});

test('search finds editions by name, author, language or native language name', () => {
  const ids = (q) => searchTranslations(catalog, q).map((t) => t.id);
  assert.ok(ids('junagarhi').includes('qc-54'));
  assert.ok(ids('urdu').includes('qc-54'));
  assert.ok(ids('اردو').includes('qc-54'));
  assert.ok(ids('HALEEM').includes('qc-85'));
  assert.equal(ids('').length, catalog.translations.length);
});

test('tafsirs list the translation language first', () => {
  assert.equal(tafsirsFor(catalog, 'ur')[0].language, 'ur');
  assert.equal(tafsirsFor(catalog, 'en')[0].language, 'en');
});

test('the translation style is the two switches, both ways', () => {
  for (const style of Object.values(TRANSLATION_STYLES)) assert.equal(styleOf(switchesFor(style)), style);
  assert.equal(styleOf({ showWordTranslations: true, showAyahTranslation: false }), 'words');
  assert.equal(styleOf({ showWordTranslations: false, showAyahTranslation: false }), 'words');
  assert.equal(styleOf({ showWordTranslations: true, showAyahTranslation: true }), 'both');
});

test('provider text becomes plain text: footnote markers dropped, tags removed, entities decoded', () => {
  assert.equal(plainTranslation('الم<sup foot_note=160812>1</sup>'), 'الم');
  assert.equal(plainTranslation('the Ever-Living,<sup foot_note=196031>1</sup> the Self-Sustaining.'), 'the Ever-Living, the Self-Sustaining.');
  assert.equal(plainTranslation('<i>Allah</i> &amp; His Messenger&#39;s <script>alert(1)</script>word'), "Allah & His Messenger's alert(1)word");
  assert.equal(plainTranslation('a<br/>b  c'), 'a b c');
  assert.equal(plainTranslation(null), '');
  assert.equal(creditOf(translationById(catalog, 'en-saheeh')), 'Saheeh International · Tanzil.net');
});

test('word meanings align by position, so 2:181\'s number glyph never shifts a meaning', () => {
  const ours = quranText()['2:181'].words;
  const content = ours.filter((w) => w.char_type !== 'end');
  // Quran.com calls every one of these a word, including the one our text marks as the end.
  const verse = { words: ours.map((w) => ({ position: w.position, char_type_name: 'word', translation: { text: `m${w.position}`, language_name: 'urdu' } })) };
  const aligned = alignWordMeanings(ours, verse, 'urdu');
  assert.equal(aligned.length, content.length);
  assert.deepEqual(aligned, content.map((w) => `m${w.position}`));
});

test('a word with no meaning in the chosen language is null, so the reader falls back to English', () => {
  const ours = quranText()['1:2'].words;
  const verse = {
    words: [
      { position: 1, char_type_name: 'word', translation: { text: 'سب تعریف', language_name: 'urdu' } },
      { position: 2, char_type_name: 'word', translation: { text: '(be) to Allah', language_name: 'english' } },
      { position: 3, char_type_name: 'word', translation: { text: '' } },
      { position: 5, char_type_name: 'end', translation: { text: '(۲)' } },
    ],
  };
  assert.deepEqual(alignWordMeanings(ours, verse, 'urdu'), ['سب تعریف', null, null, null]);
});
