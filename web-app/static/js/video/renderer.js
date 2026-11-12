// Draws one frame of a video: the background, then a translucent card holding the Arabic with the
// word being recited lit up, the word meanings and the translation when they are on, and the
// reference above. Port of the Android app's video/FrameRenderer.kt.

import qrcode from '../lib/qrcode.js';
import { activeWordIndex, layoutAyah, pageAt, splitTranslation } from './layout.js';
import { PLAY_STORE_URL, WEB_APP_URL, backgroundAt, clipAt, showsEndCard } from './plan.js';
import { BackgroundScenes } from './scenes.js';

export const ARABIC_FONT = '"Scheherazade New", "Amiri", serif';
export const LATIN_FONT = 'system-ui, -apple-system, "Segoe UI", Roboto, sans-serif';

const HIGHLIGHT = 'rgb(255,214,102)';
const GLOSS = 'rgba(214,232,222,0.843)';
const END_GLYPH = 'rgba(255,236,190,0.784)';
const TRANSLATION = 'rgba(240,244,240,0.94)';

/**
 * [request]: {aspect: {width, height}, wordByWord, fullTranslation, credits, reciter,
 *   translationCredit, translationRtl} (the last two are filled in by VideoExporter.prepareText).
 * [textFor(clip)] → {words, segments, translation, reference}. [sceneFor(key, variant)] → a scene
 * spec. [creditFor(key, variant)] → "Photo: …" or null.
 */
export class FrameRenderer {
  constructor({ width, height, request, timeline, slots, textFor, sceneFor, creditFor = () => null }) {
    Object.assign(this, { width, height, request, timeline, slots, textFor, sceneFor, creditFor });
    const w = width;
    const h = height;
    this.u = Math.min(width, height) / 1080;
    this.landscape = width > height;
    this.scenes = new BackgroundScenes(width, height);
    const credits = request.credits;
    if (this.landscape) this.card = rect(w * 0.08, h * 0.15, w * 0.92, h * (credits ? 0.84 : 0.9));
    else if (height > width * 1.5) this.card = rect(w * 0.06, h * 0.17, w * 0.94, h * (credits ? 0.83 : 0.87));
    else this.card = rect(w * 0.06, h * 0.13, w * 0.94, h * (credits ? 0.85 : 0.9));
    const pad = 36 * this.u;
    this.pad = pad;
    const share = request.fullTranslation ? (this.landscape ? 0.3 : 0.32) : 0;
    const c = this.card;
    this.arabicBox = rect(c.left + pad, c.top + pad, c.right - pad, c.bottom - pad - c.height * share);
    this.translationBox = rect(c.left + pad * 1.2, this.arabicBox.bottom + 10 * this.u, c.right - pad * 1.2, c.bottom - pad);
    this.arabicSize = (this.landscape ? 64 : height > width * 1.5 ? 82 : 72) * this.u;
    this.pagesCache = new Map();
    this.translationCache = new Map();
    // The mushaf style's face first (VideoExporter.prepareText sets it), then the bundled default.
    this.arabicFont = request.arabicFont ? `${request.arabicFont}, ${ARABIC_FONT}` : ARABIC_FONT;
    this.measureCtx = null;
    this.qr = timeline.endCardMs > 0 ? { android: qrModules(PLAY_STORE_URL), web: qrModules(WEB_APP_URL) } : null;
  }

  measurer(ctx) {
    return {
      arabicWidth: (text, px) => {
        ctx.font = `${px}px ${this.arabicFont}`;
        return ctx.measureText(text).width;
      },
      glossWidth: (text, px) => {
        ctx.font = `${px}px ${LATIN_FONT}`;
        return ctx.measureText(text).width;
      },
    };
  }

  draw(ctx, ms) {
    const { current, previous, fade } = backgroundAt(this.slots, ms);
    ctx.save();
    ctx.fillStyle = '#000';
    ctx.fillRect(0, 0, this.width, this.height);
    if (previous) this.scenes.draw(ctx, this.sceneFor(previous.themeKey, previous.variant), ms);
    this.scenes.draw(ctx, this.sceneFor(current.themeKey, current.variant), ms, fade);
    ctx.restore();

    if (showsEndCard(this.timeline, ms)) {
      this.drawEndCard(ctx);
      return;
    }

    const clip = clipAt(this.timeline, ms);
    if (!clip) return;
    let entry = this.pagesCache.get(clip);
    if (!entry) {
      const text = this.textFor(clip);
      const pages = layoutAyah(text.words, this.measurer(ctx), this.arabicBox.width, this.arabicBox.height, this.arabicSize, this.request.wordByWord);
      entry = { text, pages };
      this.pagesCache.set(clip, entry);
    }
    const { text, pages } = entry;
    const position = Math.min(Math.max(ms - clip.startMs, 0), clip.durationMs);
    const pageIndex = pageAt(pages, position, text.segments, clip.durationMs);
    const page = pages.pages[pageIndex];
    const recited = text.words.filter((w) => w.contentIndex >= 0).length;
    const active = ms >= clip.startMs && ms < clip.endMs && text.segments ? activeWordIndex(position, text.segments, recited) : -1;

    this.drawCard(ctx);
    this.drawArabic(ctx, pages, page, active);
    if (this.request.fullTranslation && text.translation) this.drawTranslation(ctx, clip, text.translation, pageIndex, pages.pages.length);
    this.drawLabels(ctx, text, this.creditFor(current.themeKey, current.variant));
  }

  release() {
    this.scenes.release();
  }

  drawCard(ctx) {
    const c = this.card;
    const r = 34 * this.u;
    ctx.save();
    roundRect(ctx, c, r);
    ctx.fillStyle = 'rgba(4,8,10,0.463)';
    ctx.fill();
    ctx.lineWidth = 2 * this.u;
    ctx.strokeStyle = 'rgba(255,236,190,0.275)';
    ctx.stroke();
    ctx.restore();
  }

  drawArabic(ctx, pages, page, active) {
    const box = this.arabicBox;
    const used = page.lines * pages.lineHeight;
    const top = box.top + (box.height - used) / 2;
    ctx.save();
    ctx.textAlign = 'center';
    ctx.textBaseline = 'alphabetic';
    ctx.shadowColor = 'rgba(0,0,0,0.63)';
    ctx.shadowBlur = 8 * this.u;
    ctx.shadowOffsetY = 2 * this.u;
    for (const placed of page.words) {
      const lineTop = top + placed.line * pages.lineHeight;
      const baseline = lineTop + pages.arabicSize * 1.2;
      const centre = box.left + placed.x + placed.width / 2;
      const isActive = placed.word.contentIndex >= 0 && placed.word.contentIndex === active;
      const isEnd = placed.word.contentIndex < 0;
      ctx.direction = 'rtl';
      ctx.font = `${pages.arabicSize}px ${this.arabicFont}`;
      ctx.fillStyle = isActive ? HIGHLIGHT : isEnd ? END_GLYPH : '#fff';
      ctx.fillText(placed.word.arabic, centre, baseline);
      if (pages.showGloss && placed.word.gloss) {
        ctx.direction = 'ltr';
        ctx.font = `${pages.glossSize}px ${LATIN_FONT}`;
        ctx.fillStyle = isActive ? HIGHLIGHT : GLOSS;
        ctx.fillText(placed.word.gloss, centre, baseline + pages.arabicSize * 0.48 + pages.glossSize);
      }
    }
    ctx.restore();
  }

  drawTranslation(ctx, clip, translation, pageIndex, pageCount) {
    const key = `${clip.startMs}:${pageIndex}:${pageCount}`;
    let layout = this.translationCache.get(key);
    if (!layout) {
      const part = splitTranslation(translation, pageCount)[pageIndex];
      layout = this.fitLines(ctx, part, this.translationBox.width, this.translationBox.height);
      this.translationCache.set(key, layout);
    }
    const box = this.translationBox;
    const total = layout.lines.length * layout.lineHeight;
    let y = box.top + (box.height - total) / 2 + layout.size;
    ctx.save();
    ctx.direction = this.request.translationRtl ? 'rtl' : 'ltr';
    ctx.textAlign = 'center';
    ctx.fillStyle = TRANSLATION;
    ctx.font = `${layout.size}px ${LATIN_FONT}`;
    ctx.shadowColor = 'rgba(0,0,0,0.5)';
    ctx.shadowBlur = 6 * this.u;
    for (const line of layout.lines) {
      ctx.fillText(line, box.left + box.width / 2, y);
      y += layout.lineHeight;
    }
    ctx.restore();
  }

  /** The translation at the largest size, down to a floor, that fits its box. */
  fitLines(ctx, text, width, height) {
    let size = (this.landscape ? 34 : 38) * this.u;
    let result;
    do {
      ctx.font = `${size}px ${LATIN_FONT}`;
      result = { size, lineHeight: size * 1.32, lines: wrap(ctx, text, width) };
      size *= 0.92;
    } while (result.lines.length * result.lineHeight > height && size > 18 * this.u);
    return result;
  }

  drawLabels(ctx, text, backgroundCredit) {
    const { u, width: w, height: h } = this;
    ctx.save();
    ctx.direction = 'ltr';
    ctx.textAlign = 'center';
    ctx.shadowColor = 'rgba(0,0,0,0.67)';
    ctx.shadowBlur = 8 * u;
    ctx.shadowOffsetY = 2 * u;
    ctx.fillStyle = '#fff';
    ctx.font = `${40 * u}px ${LATIN_FONT}`;
    ctx.fillText(text.reference, w / 2, this.card.top - 42 * u);
    // Off by default: the picture stays clean, and the sources stay in the app.
    if (!this.request.credits) {
      ctx.restore();
      return;
    }
    ctx.font = `${26 * u}px ${LATIN_FONT}`;
    ctx.fillStyle = 'rgba(255,255,255,0.88)';
    const credits = [`Recitation: ${this.request.reciter.displayName} · everyayah.com`];
    if (this.request.fullTranslation) credits.push(`Translation: ${this.request.translationCredit ?? 'Saheeh International · Tanzil.net'}`);
    if (this.request.wordByWord) credits.push('Word meanings: Quran.com');
    if (backgroundCredit) credits.push(backgroundCredit);
    let lines = credits;
    if (this.landscape) {
      // Room below the card for a line or two, not four: pack the credits side by side.
      lines = [];
      let line = '';
      for (const credit of credits) {
        const joined = line ? `${line}   ·   ${credit}` : credit;
        if (line && ctx.measureText(joined).width > w * 0.92) {
          lines.push(line);
          line = credit;
        } else line = joined;
      }
      if (line) lines.push(line);
    }
    let y = this.card.bottom + (this.landscape ? 34 : 50) * u;
    for (const line of lines) {
      ctx.fillText(line, w / 2, y);
      y += 34 * u;
    }
    ctx.fillStyle = 'rgba(255,255,255,0.59)';
    ctx.font = `${22 * u}px ${LATIN_FONT}`;
    ctx.fillText('Made with Learned Ayahs', w / 2, h - 34 * u);
    ctx.restore();
  }

  /**
   * The closing page, the same as Android's (FrameRenderer.drawEndCard): what made the video, what
   * the app is for, a code for each way to get it (Android on the left, the web app on the right),
   * and where the words and recitations come from.
   */
  drawEndCard(ctx) {
    const { u, width: w } = this;
    this.drawCard(ctx);
    ctx.save();
    ctx.direction = 'ltr';
    ctx.textAlign = 'center';
    ctx.textBaseline = 'alphabetic';
    ctx.shadowColor = 'rgba(0,0,0,0.67)';
    ctx.shadowBlur = 8 * u;
    ctx.shadowOffsetY = 2 * u;
    ctx.fillStyle = '#fff';
    // Both codes on one grid, so they come out the same size whatever their URLs' lengths.
    const size = Math.max(64, Math.round(Math.min(this.width, this.height) * 0.24));
    const modules = this.qr ? Math.max(this.qr.android.length, this.qr.web.length) : 0;
    const cell = Math.max(1, Math.floor(size / (modules + 8)));
    const block = this.qr ? cell * (modules + 8) + 44 * u + 46 * u : 0;
    // Centred in the card: the lines above the codes, the codes and their captions, the two below.
    const height = (54 + 52 + 40) * u + block + (46 + 40) * u;
    let y = this.card.top + Math.max(76 * u, (this.card.height - height) / 2 + 54 * u);
    ctx.font = `${54 * u}px ${LATIN_FONT}`;
    ctx.fillText('Made with Learned Ayahs', w / 2, y);
    ctx.fillStyle = 'rgba(255,255,255,0.9)';
    ctx.font = `${30 * u}px ${LATIN_FONT}`;
    y += 52 * u;
    ctx.fillText('A free, open-source tool to revise', w / 2, y);
    y += 40 * u;
    ctx.fillText('the ayahs you have memorised', w / 2, y);
    if (this.qr) {
      const gap = Math.round(size * 0.45);
      const top = Math.round(y + 44 * u);
      ctx.shadowColor = 'transparent';
      const bottom = Math.max(
        drawQr(ctx, this.qr.android, w / 2 - gap / 2 - size / 2, top, cell, modules),
        drawQr(ctx, this.qr.web, w / 2 + gap / 2 + size / 2, top, cell, modules),
      );
      y = bottom + 46 * u;
      ctx.shadowColor = 'rgba(0,0,0,0.67)';
      ctx.fillStyle = '#fff';
      ctx.font = `${28 * u}px ${LATIN_FONT}`;
      ctx.fillText('Android app', w / 2 - gap / 2 - size / 2, y);
      ctx.fillText('Web app', w / 2 + gap / 2 + size / 2, y);
    }
    ctx.fillStyle = 'rgba(255,255,255,0.8)';
    y += 46 * u;
    fitText(ctx, WEB_APP_URL.replace('https://', '').replace(/\/$/, ''), w / 2, y, w * 0.86, 24 * u);
    ctx.fillStyle = 'rgba(255,255,255,0.66)';
    y += 40 * u;
    fitText(ctx, "The Qur'an text, meanings and recitations come from public sources.", w / 2, y, w * 0.86, 20 * u);
    ctx.restore();
  }
}

/**
 * One code on its white mat, centred on cx. The mat is sized for `span` modules, so two codes of
 * different versions still sit on mats of one size. Returns the mat's bottom edge.
 */
function drawQr(ctx, modules, cx, top, cell, span) {
  const n = modules.length;
  const matSize = cell * (span + 8);
  const matLeft = Math.round(cx - matSize / 2);
  ctx.fillStyle = '#fff';
  ctx.fillRect(matLeft, top, matSize, matSize);
  const left = Math.round(cx - (cell * n) / 2);
  const y = Math.round(top + (matSize - cell * n) / 2);
  ctx.fillStyle = '#000';
  for (let r = 0; r < n; r++) {
    for (let c = 0; c < n; c++) {
      if (modules[r][c]) ctx.fillRect(left + c * cell, y + r * cell, cell, cell);
    }
  }
  return top + matSize;
}

/** Centred text, shrunk until it fits `maxWidth` (never below 12 px). */
function fitText(ctx, text, x, y, maxWidth, px) {
  let size = px;
  ctx.font = `${size}px ${LATIN_FONT}`;
  while (ctx.measureText(text).width > maxWidth && size > 12) {
    size *= 0.92;
    ctx.font = `${size}px ${LATIN_FONT}`;
  }
  ctx.fillText(text, x, y);
}

function qrModules(text) {
  const qr = qrcode(0, 'M');
  qr.addData(text);
  qr.make();
  const n = qr.getModuleCount();
  return Array.from({ length: n }, (_, r) => Array.from({ length: n }, (__, c) => qr.isDark(r, c)));
}

function rect(left, top, right, bottom) {
  return { left, top, right, bottom, width: right - left, height: bottom - top };
}

function roundRect(ctx, r, radius) {
  ctx.beginPath();
  if (ctx.roundRect) ctx.roundRect(r.left, r.top, r.width, r.height, radius);
  else ctx.rect(r.left, r.top, r.width, r.height);
}

function wrap(ctx, text, width) {
  const lines = [];
  let line = '';
  for (const word of text.split(/\s+/).filter(Boolean)) {
    const next = line ? `${line} ${word}` : word;
    if (line && ctx.measureText(next).width > width) {
      lines.push(line);
      line = word;
    } else line = next;
  }
  if (line) lines.push(line);
  return lines;
}

export function referenceOf(clip, surahName) {
  if (clip.kind === 'AUDHU') return "Isti'adhah";
  if (clip.kind === 'BISMILLAH') return 'Basmalah';
  return `${surahName(clip.surah)} · ${clip.surah}:${clip.ayah}`;
}
