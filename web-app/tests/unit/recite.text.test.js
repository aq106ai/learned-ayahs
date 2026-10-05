// Ported from ArabicTextNormalizerTest, ArabicWordAlignerTest, RecitationIntroFilterTest and
// WrongAyahDetectorTest.
import test from 'node:test';
import assert from 'node:assert/strict';
import {
  isIncompletePrefix,
  isWordMatch,
  normalize,
} from '../../static/js/core/recite/normalizer.js';
import { consumedByPrefix, endsWithPrefix, matchAt } from '../../static/js/core/recite/aligner.js';
import {
  openingTokensOf,
  stripLeadingIntrosFromTranscript,
} from '../../static/js/core/recite/introFilter.js';
import { evaluateContinuing } from '../../static/js/core/recite/evaluator.js';
import { matchingOtherAyah } from '../../static/js/core/recite/wrongAyah.js';
import { w } from './helpers.js';

// --- normalizer -----------------------------------------------------------------------------

test('normalize strips harakat and diacritics', () => {
  assert.equal(normalize('ٱلْحَمْدُ لِلَّهِ رَبِّ ٱلْعَـٰلَمِينَ'), 'الحمد لله رب العالمين');
});

test('normalize folds wasla and hamza forms', () => {
  assert.equal(normalize('أَحْسَنَ إِيمَانَ ٱلَّذِينَ آَمَنُوا'), 'احسن ايمان الذين امنوا');
});

test('isWordMatch matches equivalents', () => {
  assert.ok(isWordMatch('ٱلْحَمْدُ', 'الحمد'));
  assert.ok(isWordMatch('ٱلرَّحْمَـٰنِ', 'الرحمن'));
  assert.ok(isWordMatch('ٱلرَّحِيمِ', 'الرحيم'));
  assert.ok(!isWordMatch('ٱلْحَمْدُ', 'العالمين'));
  assert.ok(isWordMatch('مُسْتَهْزِئُونَ', 'مستهزيون'));
  assert.ok(isWordMatch('مُؤْمِنُونَ', 'مومنون'));
});

test('normalize expands lam-alef presentation forms', () => {
  assert.equal(normalize('وﻻ'), 'ولا');
  assert.equal(normalize('ﻷ'), 'لا');
  assert.ok(isWordMatch('وَلَا', 'وﻻ'));
});

test('normalize folds standalone hamza', () => {
  assert.ok(isWordMatch('ٱلسَّمَآءِ', 'السماء'));
  assert.equal(normalize('ٱلسَّمَآءِ'), normalize('السماء'));
});

test('normalize handles decomposed input', () => {
  const decomposed = 'أحسن';
  assert.equal(normalize(decomposed), 'احسن');
  assert.equal(normalize('أحسن'), normalize(decomposed));
});

test('normalize drops digits in both scripts', () => {
  assert.equal(normalize('الحمد ٢'), 'الحمد');
  assert.equal(normalize('الحمد 2'), 'الحمد');
});

test('isIncompletePrefix only for proper prefixes', () => {
  assert.ok(isIncompletePrefix('الرح', 'ٱلرَّحْمَٰنِ'));
  assert.ok(!isIncompletePrefix('الرحمن', 'ٱلرَّحْمَٰنِ'));
  assert.ok(!isIncompletePrefix('رب', 'ٱلرَّحْمَٰنِ'));
  assert.ok(!isIncompletePrefix('', 'ٱلرَّحْمَٰنِ'));
});

test('short words require an exact match', () => {
  assert.ok(!isWordMatch('مِن', 'عَن'));
  assert.ok(!isWordMatch('رَبِّ', 'رَدِّ'));
  assert.ok(isWordMatch('مِن', 'من'));
  assert.ok(isWordMatch('رَبِّ', 'رب'));
});

test('one edit is tolerated on longer words', () => {
  assert.ok(isWordMatch('العالمين', 'العلمين'));
  assert.ok(!isWordMatch('الصابرين', 'الظبرين'));
});

test('written long vowels are forgiven, consonants never', () => {
  assert.ok(isWordMatch('فَسْـَٔلْ', 'فاسأل'));
  assert.ok(isWordMatch('العالمين', 'العلمن'));
  assert.ok(!isWordMatch('المومنون', 'لممنن'));
  assert.ok(!isWordMatch('إِلَّا', 'أُو۟لُوا۟'));
});

test('runs of alif collapse', () => {
  assert.equal(normalize('ءَاتَىٰهُمْ'), 'اتياهم');
  assert.ok(isWordMatch('ءَاتَىٰهُمْ', 'آتاهم'));
  assert.ok(isWordMatch('ءَالَ', 'آل'));
});

// --- aligner --------------------------------------------------------------------------------

const yaAyyuha = ['يَـٰٓأَيُّهَا', 'ٱلنَّاسُ', 'ٱعْبُدُوا۟'];

test('one written word matches two heard tokens', () => {
  assert.deepEqual(matchAt(yaAyyuha, 0, ['يا', 'ايها', 'الناس'], 0), { words: 1, tokens: 2 });
});

test('two written words match one heard token', () => {
  assert.deepEqual(matchAt(['مِن', 'بَعْدِ'], 0, ['منبعد'], 0), { words: 2, tokens: 1 });
});

test('one-to-one is preferred', () => {
  assert.deepEqual(matchAt(yaAyyuha, 1, ['الناس', 'اعبدوا'], 0), { words: 1, tokens: 1 });
});

test('an unrelated word does not align', () => {
  assert.equal(matchAt(yaAyyuha, 0, ['الحمد', 'لله'], 0), null);
});

test('a prefix consumes the tokens it actually used', () => {
  assert.equal(consumedByPrefix(yaAyyuha, 2, ['يا', 'ايها', 'الناس', 'اعبدوا']), 3);
  assert.equal(consumedByPrefix(yaAyyuha, 2, ['الحمد', 'لله']), -1);
});

test('endsWithPrefix finds a joined opening at the end of a transcript', () => {
  const spoken = ['لله', 'رب', 'العالمين', 'يا', 'ايها', 'الناس'];
  assert.ok(endsWithPrefix(yaAyyuha, 2, spoken));
  assert.ok(!endsWithPrefix(yaAyyuha, 2, [...spoken, 'الحمد', 'لله']));
});

// --- intro filter ---------------------------------------------------------------------------

const FATIHA_1_1 = [w('بِسْمِ'), w('ٱللَّهِ'), w('ٱلرَّحْمَٰنِ'), w('ٱلرَّحِيمِ'), w('١', true)];
const FATIHA_1_2 = [w('ٱلْحَمْدُ'), w('لِلَّهِ'), w('رَبِّ'), w('ٱلْعَـٰلَمِينَ'), w('٢', true)];

test('strips a’udhu and bismillah, leaving the content', () => {
  assert.equal(
    stripLeadingIntrosFromTranscript('اعوذ بالله من الشيطان الرجيم بسم الله الرحمن الرحيم الحمد لله رب العالمين'),
    'الحمد لله رب العالمين',
  );
  assert.equal(stripLeadingIntrosFromTranscript('اعوذ بالله السميع العليم من الشيطان الرجيم قل هو الله احد'), 'قل هو الله احد');
  assert.equal(stripLeadingIntrosFromTranscript('اعوذ بالله العظيم من الشيطان الرجيم قل هو الله احد'), 'قل هو الله احد');
  assert.equal(stripLeadingIntrosFromTranscript('استعيذ بالله من الشيطان الرجيم قل هو الله احد'), 'قل هو الله احد');
  assert.equal(stripLeadingIntrosFromTranscript('بسم الله الحمد لله'), 'الحمد لله');
  assert.equal(stripLeadingIntrosFromTranscript('الحمد لله رب'), 'الحمد لله رب');
});

test('an in-progress intro is dropped when it is not the ayah’s opening', () => {
  const opening = openingTokensOf([w('قُلْ'), w('هُوَ'), w('ٱللَّهُ'), w('أَحَدٌ')]);
  assert.equal(stripLeadingIntrosFromTranscript('اعوذ', opening), '');
  assert.equal(stripLeadingIntrosFromTranscript('بسم', opening), '');
});

test('an intro before Fatihah 1:2 costs nothing', () => {
  const r = evaluateContinuing(
    FATIHA_1_2,
    'اعوذ بالله من الشيطان الرجيم بسم الله الرحمن الرحيم الحمد لله رب العالمين',
    true,
    0,
    true,
  );
  assert.equal(r.mistakeCount, 0);
  assert.ok(r.isComplete);
  assert.equal(r.correctCount, 4);
});

test('Fatihah 1:1 is scored, not stripped as an intro', () => {
  const r = evaluateContinuing(FATIHA_1_1, 'بسم الله الرحمن الرحيم', true, 0, true);
  assert.equal(r.mistakeCount, 0);
  assert.ok(r.isComplete);
  assert.equal(r.correctCount, 4);
  const withAudhu = evaluateContinuing(FATIHA_1_1, 'اعوذ بالله من الشيطان الرجيم بسم الله الرحمن الرحيم', true, 0, true);
  assert.ok(withAudhu.isComplete);
});

test('the ayah’s own opening vetoes the strip; without one it strips as before', () => {
  assert.equal(stripLeadingIntrosFromTranscript('بسم الله الرحمن الرحيم', openingTokensOf(FATIHA_1_1)), 'بسم الله الرحمن الرحيم');
  assert.equal(stripLeadingIntrosFromTranscript('بسم الله الرحمن الرحيم'), '');
});

test('evaluation is idempotent when the intro is already absent', () => {
  const a = evaluateContinuing(FATIHA_1_1, 'اعوذ بالله بسم الله الرحمن الرحيم', true, 0, true);
  const b = evaluateContinuing(FATIHA_1_1, 'بسم الله الرحمن الرحيم', true, 0, true);
  assert.equal(a.correctCount, b.correctCount);
  assert.equal(a.mistakeCount, b.mistakeCount);
});

// --- wrong ayah -----------------------------------------------------------------------------

const fatihaOpening = ['بسم', 'الله', 'الرحمن'];
const ikhlas = { globalId: 6231, tokens: ['قل', 'هو', 'الله'] };

test('wrong-ayah detection needs enough speech and ignores the current ayah', () => {
  assert.equal(matchingOtherAyah(['قل'], 1, fatihaOpening, [ikhlas]), null);
  assert.equal(matchingOtherAyah(['بسم', 'الله'], 1, fatihaOpening, [ikhlas]), null);
});

test('detects Al-Ikhlas while on Al-Fatihah, but not unrelated speech', () => {
  assert.equal(matchingOtherAyah(['قل', 'هو'], 1, fatihaOpening, [ikhlas]), 6231);
  assert.equal(matchingOtherAyah(['مالك', 'يوم'], 1, fatihaOpening, [ikhlas]), null);
});
