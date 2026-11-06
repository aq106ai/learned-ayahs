// Tafsir HTML from Quran.com, turned into plain blocks the views render themselves.
// Port of data/TafsirText.kt; the same cases are tested on both sides.
//
// Nothing of the provider's markup reaches the page: no element, attribute, style or link is
// ever inserted. The text is read into {kind, spans} blocks — headings, paragraphs, list items
// and quotes, with bold/italic runs — and the views build their own elements from those, setting
// only textContent. Script, style and other embedded content is dropped with what is inside it.

import { decodeEntities } from './catalog.js';

const DROP = new Set(['script', 'style', 'iframe', 'object', 'embed', 'svg', 'math', 'noscript', 'template', 'head', 'title', 'select', 'textarea', 'button', 'form']);
const BLOCK_KIND = { h1: 'heading', h2: 'heading', h3: 'heading', h4: 'heading', h5: 'heading', h6: 'heading', p: 'paragraph', div: 'paragraph', section: 'paragraph', article: 'paragraph', li: 'item', blockquote: 'quote', tr: 'paragraph', dd: 'paragraph', dt: 'paragraph' };
const BOLD = new Set(['b', 'strong']);
const ITALIC = new Set(['i', 'em', 'cite']);

/** [{kind: 'heading'|'paragraph'|'item'|'quote', spans: [{text, bold, italic}]}] */
export function tafsirBlocks(html) {
  const blocks = [];
  let current = null;
  const stack = []; // open tag names, for bold/italic/kind
  let dropping = null; // name of the element whose content is being dropped
  let dropDepth = 0;

  const kindNow = () => {
    for (let i = stack.length - 1; i >= 0; i--) if (BLOCK_KIND[stack[i]]) return BLOCK_KIND[stack[i]] === 'paragraph' && stack.includes('blockquote') ? 'quote' : BLOCK_KIND[stack[i]];
    return 'paragraph';
  };
  const close = () => {
    if (current) {
      // Trim the block's edges, drop empty runs.
      const spans = current.spans;
      while (spans.length && !spans[0].text.trim()) spans.shift();
      while (spans.length && !spans[spans.length - 1].text.trim()) spans.pop();
      if (spans.length) {
        spans[0].text = spans[0].text.replace(/^\s+/, '');
        spans[spans.length - 1].text = spans[spans.length - 1].text.replace(/\s+$/, '');
        blocks.push(current);
      }
    }
    current = null;
  };
  const addText = (raw, { lineBreak = false } = {}) => {
    const text = lineBreak ? '\n' : decodeEntities(raw).replace(/[\s\u00a0]+/g, ' ');
    if (!text) return;
    if (!current) {
      if (!text.trim()) return;
      current = { kind: kindNow(), spans: [] };
    }
    const bold = stack.some((t) => BOLD.has(t));
    const italic = stack.some((t) => ITALIC.has(t));
    const last = current.spans[current.spans.length - 1];
    if (last && last.bold === bold && last.italic === italic) {
      last.text = (last.text + text).replace(/ {2,}/g, ' ');
    } else if (lineBreak) {
      current.spans.push({ text, bold, italic });
    } else {
      current.spans.push({ text, bold, italic });
    }
  };

  const tokens = String(html ?? '').split(/(<!--[\s\S]*?-->|<\/?[a-zA-Z][^>]*>)/);
  for (const token of tokens) {
    if (!token) continue;
    if (token.startsWith('<!--')) continue;
    const m = token.match(/^<(\/?)([a-zA-Z][a-zA-Z0-9]*)\b[^>]*?(\/?)>$/);
    if (!m) {
      if (!dropping) addText(token);
      continue;
    }
    const closing = m[1] === '/';
    const name = m[2].toLowerCase();
    const selfClosing = m[3] === '/' || name === 'br' || name === 'hr' || name === 'img';
    if (dropping) {
      if (name === dropping) dropDepth += closing ? -1 : selfClosing ? 0 : 1;
      if (dropDepth === 0) dropping = null;
      continue;
    }
    if (!closing && DROP.has(name)) {
      if (!selfClosing) {
        dropping = name;
        dropDepth = 1;
      }
      continue;
    }
    if (name === 'br') {
      if (current) addText('', { lineBreak: true });
      continue;
    }
    if (name === 'hr' || name === 'img') continue;
    if (BLOCK_KIND[name] || name === 'ul' || name === 'ol' || name === 'table') close();
    if (closing) {
      const at = stack.lastIndexOf(name);
      if (at >= 0) stack.splice(at);
    } else if (!selfClosing) {
      stack.push(name);
    }
  }
  close();
  // A <br> leaves "\n" in a run; keep it as a line break without surrounding spaces.
  for (const b of blocks) for (const s of b.spans) s.text = s.text.replace(/ ?\n ?/g, '\n');
  return blocks;
}

/** "2:255" or "112:1–4" for the ayahs a tafsir entry covers. */
export function coveredRange(keys) {
  if (!keys?.length) return '';
  const parsed = keys.map((k) => k.split(':').map(Number)).filter(([s, a]) => s && a).sort((x, y) => x[0] - y[0] || x[1] - y[1]);
  if (!parsed.length) return '';
  const [s1, a1] = parsed[0];
  const [s2, a2] = parsed[parsed.length - 1];
  if (s1 === s2 && a1 === a2) return `${s1}:${a1}`;
  return s1 === s2 ? `${s1}:${a1}–${a2}` : `${s1}:${a1}–${s2}:${a2}`;
}

/** Plain text of the blocks (for copying, and for tests). */
export const blocksText = (blocks) => blocks.map((b) => b.spans.map((s) => s.text).join('')).join('\n\n');
