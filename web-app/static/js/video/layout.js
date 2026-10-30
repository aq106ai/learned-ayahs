// Lays an ayah out right to left in a box, one word per column with its meaning under it, and
// splits it into pages when it doesn't fit. Port of the Android app's video/AyahLayout.kt; pure.
//
// Words: {arabic, gloss, contentIndex} where contentIndex numbers the recited words from 0 (the
// end-of-ayah glyph has -1 and is never highlighted). measure: {arabicWidth(text, px), glossWidth(text, px)}.

const SCALES = [1, 0.9, 0.8, 0.72, 0.66];
export const MIN_SCALE = SCALES[SCALES.length - 1];

export function layoutAyah(words, measure, boxWidth, boxHeight, arabicSize, showGloss) {
  let last = null;
  for (const scale of SCALES) {
    const attempt = layoutAt(words, measure, boxWidth, boxHeight, arabicSize * scale, showGloss);
    if (attempt.pages.length <= 1) return attempt;
    last = attempt;
  }
  return last;
}

function layoutAt(words, measure, boxWidth, boxHeight, arabicSize, showGloss) {
  const glossSize = arabicSize * 0.36;
  const lineHeight = arabicSize * 1.75 + (showGloss ? glossSize * 1.5 : 0);
  const gap = arabicSize * 0.32;
  const linesPerPage = Math.max(1, Math.floor(boxHeight / lineHeight));

  const columns = words.map((word) => {
    const arabic = measure.arabicWidth(word.arabic, arabicSize);
    const gloss = showGloss && word.gloss ? measure.glossWidth(word.gloss, glossSize) : 0;
    return [word, Math.min(Math.max(arabic, gloss), boxWidth)];
  });
  const lines = [];
  let used = 0;
  for (const column of columns) {
    const current = lines[lines.length - 1];
    const need = !current || !current.length ? column[1] : gap + column[1];
    if (!current || used + need > boxWidth) {
      lines.push([column]);
      used = column[1];
    } else {
      current.push(column);
      used += need;
    }
  }

  const pages = [];
  for (let i = 0; i < lines.length; i += linesPerPage) {
    const pageLines = lines.slice(i, i + linesPerPage);
    const placed = [];
    pageLines.forEach((line, lineIndex) => {
      const lineWidth = line.reduce((sum, [, w]) => sum + w, 0) + gap * (line.length - 1);
      // Right to left: the first word sits at the right end of the centred line.
      let right = (boxWidth + lineWidth) / 2;
      for (const [word, width] of line) {
        placed.push({ word, line: lineIndex, x: right - width, width });
        right -= width + gap;
      }
    });
    pages.push(page(placed, pageLines.length));
  }
  return { pages: pages.length ? pages : [page([], 0)], arabicSize, glossSize, lineHeight, showGloss };
}

function page(words, lines) {
  const content = words.filter((p) => p.word.contentIndex >= 0);
  return { words, lines, firstContentIndex: content[0]?.word.contentIndex ?? -1, contentCount: content.length };
}

/** The page showing [positionMs] into the ayah: a page turns as its first word is recited. */
export function pageAt(pages, positionMs, segments, durationMs) {
  if (pages.pages.length <= 1) return 0;
  const totalWords = Math.max(1, pages.pages.reduce((s, p) => s + p.contentCount, 0));
  let shown = 0;
  let wordsBefore = 0;
  pages.pages.forEach((p, index) => {
    const first = p.firstContentIndex;
    let startMs;
    if (index === 0) startMs = 0;
    else if (segments && first >= 0 && first < segments.length) startMs = segments[first][0];
    else startMs = Math.floor((durationMs * wordsBefore) / totalWords);
    if (positionMs >= startMs) shown = index;
    wordsBefore += p.contentCount;
  });
  return shown;
}

/** Splits a translation into [parts] pieces at word boundaries, in proportion. */
export function splitTranslation(text, parts) {
  if (parts <= 1) return [text];
  const tokens = text.split(/\s+/).filter(Boolean);
  if (tokens.length < parts) return [text, ...Array(parts - 1).fill('')];
  return Array.from({ length: parts }, (_, part) => tokens.slice(Math.floor((tokens.length * part) / parts), Math.floor((tokens.length * (part + 1)) / parts)).join(' '));
}

/** The word being recited at [positionMs] (see core WordSync): -1 before the first. */
export function activeWordIndex(positionMs, segments, wordCount) {
  if (!segments?.length || !wordCount) return -1;
  const count = Math.min(segments.length, wordCount);
  let active = -1;
  for (let i = 0; i < count; i++) {
    const [start, end] = segments[i];
    if (positionMs >= start && positionMs < end + 1) return i;
    if (positionMs >= end + 1) active = i;
  }
  return active;
}
