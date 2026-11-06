// Tafsir HTML → plain blocks (core/tafsirText.js); the same cases as TafsirTextTest.kt.
import test from 'node:test';
import assert from 'node:assert/strict';
import { blocksText, coveredRange, tafsirBlocks } from '../../static/js/core/tafsirText.js';

test('headings, paragraphs and emphasis become blocks and runs; attributes never survive', () => {
  const blocks = tafsirBlocks('<h2>The Virtue of Ayat Al-Kursi</h2><p>This is <strong style="color: red">the greatest</strong> Ayah, <em>as</em> recorded.</p>');
  assert.deepEqual(blocks, [
    { kind: 'heading', spans: [{ text: 'The Virtue of Ayat Al-Kursi', bold: false, italic: false }] },
    { kind: 'paragraph', spans: [
      { text: 'This is ', bold: false, italic: false },
      { text: 'the greatest', bold: true, italic: false },
      { text: ' Ayah, ', bold: false, italic: false },
      { text: 'as', bold: false, italic: true },
      { text: ' recorded.', bold: false, italic: false },
    ] },
  ]);
  assert.doesNotMatch(JSON.stringify(blocks), /style|color/);
});

test('scripts, styles and embeds are dropped with their content; links keep only their text', () => {
  const blocks = tafsirBlocks('<p>a<script>alert(1)</script>b<style>p{}</style><iframe src="x">frame</iframe> <a href="javascript:alert(1)" onclick="x()">link</a></p><img src=x onerror=alert(1)>');
  assert.equal(blocksText(blocks), 'ab link');
});

test('nested and unclosed markup, comments and entities are handled', () => {
  assert.equal(blocksText(tafsirBlocks('<div lang="ur" class="ur"><p class="ur">اللہ <span class="saw">ﷺ</span> نے</p></div>')), 'اللہ ﷺ نے');
  assert.equal(blocksText(tafsirBlocks('<p>one<p>two')), 'one\n\ntwo');
  assert.equal(blocksText(tafsirBlocks('<!-- <script>x</script> --><p>&lt;b&gt; &amp; &#1575;&nbsp;x</p>')), '<b> & ا x');
  assert.equal(blocksText(tafsirBlocks('<p>line<br>next</p>')), 'line\nnext');
  assert.deepEqual(tafsirBlocks(''), []);
  assert.deepEqual(tafsirBlocks(null), []);
  assert.deepEqual(tafsirBlocks('<p>  </p><p></p>'), []);
});

test('lists and quotes keep their kind', () => {
  const blocks = tafsirBlocks('<ul><li>first</li><li>second</li></ul><blockquote><p>quoted</p></blockquote>');
  assert.deepEqual(blocks.map((b) => b.kind), ['item', 'item', 'quote']);
});

test('the ayahs an entry covers read as a range', () => {
  assert.equal(coveredRange(['2:255']), '2:255');
  assert.equal(coveredRange(['112:3', '112:1', '112:2', '112:4']), '112:1–4');
  assert.equal(coveredRange(['2:286', '3:1']), '2:286–3:1');
  assert.equal(coveredRange([]), '');
});
