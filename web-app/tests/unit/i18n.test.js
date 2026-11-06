// The interface language (core/i18n.js) and its tables; the same rules as UiStringsTest.kt.
import test from 'node:test';
import assert from 'node:assert/strict';
import { UI_LANGUAGES, isRtlUi, resolveLanguage, tx, uiLanguage, useLanguage } from '../../static/js/core/i18n.js';
import ur from '../../static/js/i18n/ur.js';
import ar from '../../static/js/i18n/ar.js';

test('the choice wins, else the browser languages, else English', () => {
  assert.equal(resolveLanguage('ur', ['en-GB']), 'ur');
  assert.equal(resolveLanguage('', ['fr-FR', 'ar-EG']), 'ar');
  assert.equal(resolveLanguage('', ['fr-FR']), 'en');
  assert.equal(resolveLanguage('xx', []), 'en');
});

test('English is the key; a missing translation falls back to it; placeholders are filled', () => {
  useLanguage('en');
  assert.equal(tx('Settings'), 'Settings');
  assert.equal(tx('{n} learned ayahs', { n: 3 }), '3 learned ayahs');
  useLanguage('ur');
  assert.equal(uiLanguage(), 'ur');
  assert.equal(isRtlUi(), true);
  assert.equal(tx('Settings'), 'سیٹنگز');
  assert.equal(tx('{n} learned ayahs', { n: 3 }), '3 یاد کی ہوئی آیات');
  assert.equal(tx('A sentence nobody translated'), 'A sentence nobody translated');
  useLanguage('ar');
  assert.equal(tx('Tafsir'), 'التفسير');
  useLanguage('en');
  assert.equal(isRtlUi(), false);
});

test('both tables cover the same keys, with the placeholders of the English', () => {
  assert.deepEqual(Object.keys(ur).sort(), Object.keys(ar).sort());
  assert.ok(Object.keys(ur).length > 500);
  const ph = (s) => (s.match(/\{\w+\}/g) ?? []).sort();
  for (const [key, value] of Object.entries(ur)) {
    assert.deepEqual(ph(value), ph(key), `ur: ${key}`);
    assert.deepEqual(ph(ar[key]), ph(key), `ar: ${key}`);
    assert.ok(value.trim() && ar[key].trim(), key);
  }
  assert.deepEqual(UI_LANGUAGES.map((l) => [l.code, l.rtl]), [['en', false], ['ur', true], ['ar', true]]);
});
