// The video backgrounds, painted on a canvas. Port of the Android app's video/BackgroundScenes.kt:
// a still layer painted once per scene and cached, plus a light moving layer drawn every frame.
//
// Content norms: sky, land, water, plants, light and pattern only, never a person or an animal.

import { FALLBACK, THEMES } from './themes.js';

const KINDS = ['SKY', 'RAYS', 'WATER', 'RAIN', 'GARDEN', 'EMBERS', 'STORM', 'RUINS', 'NEBULA', 'MIST', 'STARS', 'SEA', 'DUNES',
  'MOUNTAINS', 'FIELDS', 'ARCHES', 'LANTERN', 'GEOMETRIC', 'MANUSCRIPT', 'PHOTO'];
const PI = Math.PI;

const byKey = new Map(THEMES.map((t) => [t.key, t]));
const specCache = new Map();

/** A scene to paint: {kind, colors: [[r,g,b,a] x4], features: Set, photo?: ImageBitmap|HTMLImageElement, id}. */
function toSpec(raw, id) {
  const [kind, colors, features] = raw;
  return { kind, colors: colors.map(parseColor), features: new Set(features), id };
}

export function theme(key) {
  return byKey.get(key) ?? byKey.get(FALLBACK);
}

/** Variant [index] of theme [key], as a scene spec (cached, so the still cache recognises it). */
export function variant(key, index) {
  const t = theme(key);
  const i = ((index % t.variants.length) + t.variants.length) % t.variants.length;
  const id = `${t.key}#${i}`;
  if (!specCache.has(id)) specCache.set(id, toSpec(t.variants[i], id));
  return specCache.get(id);
}

/** A photo scene for an already loaded image; [id] identifies it in the still cache. */
export function photoSpec(id, image) {
  return { kind: 'PHOTO', colors: [[16, 20, 24, 255], [16, 20, 24, 255], [16, 20, 24, 255], [0, 0, 0, 255]], features: new Set(), photo: image, id: `photo:${id}` };
}

export { THEMES };

// -- colours -----------------------------------------------------------------------------------

function parseColor(hex) {
  const n = parseInt(hex.slice(1), 16);
  return [(n >> 16) & 255, (n >> 8) & 255, n & 255, 255];
}
const css = ([r, g, b, a]) => `rgba(${r},${g},${b},${(a / 255).toFixed(4)})`;
const withAlpha = ([r, g, b, a], alpha) => [r, g, b, Math.trunc(a * Math.min(Math.max(alpha, 0), 1))];
const blend = (a, b, f) => [0, 1, 2].map((i) => Math.trunc(a[i] + (b[i] - a[i]) * f)).concat(255);
const darker = (c, f) => [Math.trunc(c[0] * f), Math.trunc(c[1] * f), Math.trunc(c[2] * f), c[3]];
const WHITE = [255, 255, 255, 255];

/** Seeded random numbers, so a scene looks the same in every frame and every preview. */
function random(seed) {
  let s = seed >>> 0;
  return {
    nextFloat() {
      s = (s + 0x6d2b79f5) >>> 0;
      let t = s;
      t = Math.imul(t ^ (t >>> 15), t | 1);
      t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
      return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
    },
  };
}

function seedOf(spec) {
  let h = KINDS.indexOf(spec.kind) * 7919;
  for (const c of spec.colors) for (const v of c) h = (Math.imul(h, 31) + v) | 0;
  return h;
}

// -- the painter -------------------------------------------------------------------------------

export class BackgroundScenes {
  constructor(width, height) {
    this.width = width;
    this.height = height;
    this.w = width;
    this.h = height;
    this.u = Math.min(width, height) / 1080;
    this.stills = new Map();
  }

  /** Draws [spec] as it looks [ms] in, at [alpha] (0-1) for crossfades. */
  draw(ctx, spec, ms, alpha = 1) {
    const t = ms / 1000;
    ctx.save();
    ctx.globalAlpha = alpha;
    if (spec.kind === 'PHOTO') {
      // A slow zoom and drift, so a still photo doesn't look frozen.
      const ease = (1 - Math.cos((t * 2 * PI) / 32)) / 2;
      const scale = 1.01 + 0.06 * ease;
      const cx = this.w / 2 + Math.sin(t / 11) * this.w * 0.04;
      const cy = this.h / 2;
      ctx.translate(cx, cy);
      ctx.scale(scale, scale);
      ctx.translate(-cx, -cy);
      ctx.drawImage(this.still(spec), 0, 0);
    } else {
      ctx.drawImage(this.still(spec), 0, 0);
      this.animate(ctx, spec, t);
    }
    ctx.restore();
  }

  release() {
    this.stills.clear();
  }

  still(spec) {
    const cached = this.stills.get(spec.id);
    if (cached) return cached;
    // A crossfade needs two; a third is never in use at once.
    while (this.stills.size >= 2) this.stills.delete(this.stills.keys().next().value);
    const canvas = typeof OffscreenCanvas !== 'undefined' ? new OffscreenCanvas(this.width, this.height) : Object.assign(document.createElement('canvas'), { width: this.width, height: this.height });
    this.paintStill(canvas.getContext('2d'), spec);
    this.stills.set(spec.id, canvas);
    return canvas;
  }

  // -- the still layer -------------------------------------------------------------------------

  paintStill(c, s) {
    const { w, h, u } = this;
    const [top, middle, land, light] = s.colors;
    const has = (f) => s.features.has(f);
    c.fillStyle = css([top[0], top[1], top[2], 255]);
    c.fillRect(0, 0, w, h);
    const r = random(seedOf(s));
    switch (s.kind) {
      case 'SKY':
        this.sky(c, top, middle, h * 0.8);
        this.band(c, h * 0.8, h, middle, land);
        if (has('SUN')) this.glow(c, w * 0.5, h * 0.78, 520 * u, light, 0.9);
        this.hills(c, r, h * 0.82, 60 * u, land, 3);
        break;
      case 'RAYS':
        this.sky(c, top, land, h);
        this.glow(c, w * 0.5, -h * 0.05, Math.max(w, h) * 0.7, middle, 0.55);
        break;
      case 'WATER':
        this.sky(c, top, middle, h * 0.42);
        this.band(c, h * 0.42, h, middle, land);
        this.glow(c, w * 0.5, h * 0.42, 420 * u, light, 0.45);
        if (has('SUN')) this.glow(c, w * 0.5, h * 0.4, 200 * u, light, 0.8);
        break;
      case 'RAIN':
        this.sky(c, top, land, h);
        this.clouds(c, r, 0, h * 0.35, middle, 0.35, 9);
        this.hills(c, r, h * 0.86, 50 * u, darker(land, 0.8), 2);
        break;
      case 'GARDEN':
        this.garden(c, r, s);
        break;
      case 'EMBERS':
        this.sky(c, top, land, h);
        this.glow(c, w * 0.5, h * 1.05, Math.max(w, h) * 0.75, middle, 0.9);
        this.glow(c, w * 0.5, h * 1.1, Math.max(w, h) * 0.45, light, 0.25);
        break;
      case 'STORM':
        this.sky(c, top, middle, h);
        this.clouds(c, r, -h * 0.05, h * 0.45, darker(middle, 0.7), 0.55, 12);
        this.hills(c, r, h * 0.88, 40 * u, land, 2);
        break;
      case 'RUINS':
        this.sky(c, top, middle, h * 0.7);
        this.band(c, h * 0.7, h, middle, land);
        if (has('STARS')) this.stars(c, r, 220, h * 0.6, light);
        else this.glow(c, w * 0.7, h * 0.66, 360 * u, light, 0.7);
        this.dunes(c, r, h * 0.72, land, middle, 2);
        this.columns(c, r, h * 0.8, darker(land, 0.65));
        this.dunes(c, r, h * 0.86, darker(land, 0.85), land, 1);
        break;
      case 'NEBULA':
        this.sky(c, top, land, h);
        for (let i = 0; i < 6; i++) this.glow(c, r.nextFloat() * w, r.nextFloat() * h, (300 + r.nextFloat() * 500) * u, middle, 0.35 + r.nextFloat() * 0.3);
        for (let i = 0; i < 3; i++) this.glow(c, r.nextFloat() * w, r.nextFloat() * h, (150 + r.nextFloat() * 250) * u, light, 0.18);
        this.stars(c, r, 380, h, light);
        break;
      case 'MIST':
        this.sky(c, top, middle, h * 0.75);
        this.band(c, h * 0.75, h, middle, land);
        this.hills(c, r, h * 0.78, 70 * u, blend(middle, land, 0.5), 2);
        this.hills(c, r, h * 0.86, 50 * u, land, 2);
        break;
      case 'STARS':
        this.sky(c, top, middle, h);
        this.stars(c, r, 420, h * 0.9, light);
        this.moon(c, s);
        this.hills(c, r, h * 0.9, 45 * u, land, 2);
        break;
      case 'SEA': {
        const horizon = h * 0.55;
        this.sky(c, top, middle, horizon);
        if (has('CLOUDS')) this.clouds(c, r, h * 0.05, horizon * 0.8, light, 0.18, 6);
        if (has('SUN')) this.glow(c, w * 0.5, horizon, 300 * u, light, 0.95);
        this.moon(c, s, horizon * 0.45);
        this.band(c, horizon, h, blend(middle, land, 0.5), land);
        if (has('SUN') || has('FULL_MOON')) {
          const g = c.createLinearGradient(0, horizon, 0, h);
          g.addColorStop(0, css(withAlpha(light, 0.55)));
          g.addColorStop(1, css(withAlpha(light, 0)));
          c.fillStyle = g;
          c.beginPath();
          c.moveTo(w * 0.47, horizon);
          c.lineTo(w * 0.53, horizon);
          c.lineTo(w * 0.68, h);
          c.lineTo(w * 0.32, h);
          c.closePath();
          c.fill();
        }
        break;
      }
      case 'DUNES':
        this.sky(c, top, middle, h * 0.66);
        this.band(c, h * 0.66, h, middle, land);
        if (has('STARS')) this.stars(c, r, 300, h * 0.6, light);
        this.moon(c, s);
        if (has('SUN')) this.glow(c, w * 0.72, h * 0.58, 380 * u, light, 0.9);
        this.dunes(c, r, h * 0.62, blend(middle, land, 0.55), middle, 1);
        this.dunes(c, r, h * 0.72, land, blend(land, light, 0.25), 1);
        this.dunes(c, r, h * 0.84, darker(land, 0.82), land, 1);
        break;
      case 'MOUNTAINS':
        this.sky(c, top, middle, h * 0.7);
        this.band(c, h * 0.7, h, middle, land);
        if (has('STARS')) this.stars(c, r, 260, h * 0.55, light);
        if (has('SUN')) this.glow(c, w * 0.3, h * 0.5, 360 * u, light, 0.85);
        for (let layer = 0; layer < 4; layer++) {
          this.ridge(c, r, h * (0.5 + layer * 0.09), (180 - layer * 25) * u, blend(middle, land, 0.35 + layer * 0.22));
        }
        break;
      case 'FIELDS':
        this.sky(c, top, middle, h * 0.62);
        if (has('CLOUDS')) this.clouds(c, r, h * 0.05, h * 0.4, light, 0.25, 6);
        if (has('SUN')) this.glow(c, w * 0.65, h * 0.55, 380 * u, light, 0.85);
        this.band(c, h * 0.6, h, blend(middle, land, 0.5), darker(land, 0.8));
        this.hills(c, r, h * 0.62, 50 * u, blend(middle, land, 0.6), 2);
        break;
      case 'ARCHES':
        this.arches(c, s);
        break;
      case 'LANTERN':
        this.sky(c, top, land, h);
        this.niche(c, middle);
        break;
      case 'GEOMETRIC':
        this.sky(c, top, land, h);
        this.glow(c, w * 0.5, h * 0.45, Math.max(w, h) * 0.6, middle, 0.8);
        this.starPattern(c, light);
        this.vignette(c);
        break;
      case 'MANUSCRIPT':
        this.manuscript(c, r, s);
        break;
      case 'PHOTO':
        this.photo(c, s);
        break;
      default:
        break;
    }
  }

  /** The photo cropped to fill the frame, darkened a little so the text on it stays readable. */
  photo(c, s) {
    const img = s.photo;
    if (!img) return;
    const iw = img.width || img.naturalWidth;
    const ih = img.height || img.naturalHeight;
    if (!iw || !ih) return;
    const cover = Math.max(this.w / iw, this.h / ih);
    const dw = iw * cover;
    const dh = ih * cover;
    c.imageSmoothingQuality = 'high';
    c.drawImage(img, (this.w - dw) / 2, (this.h - dh) / 2, dw, dh);
    c.fillStyle = 'rgba(0,0,0,0.235)';
    c.fillRect(0, 0, this.w, this.h);
    this.vignette(c);
  }

  sky(c, topColor, bottom, to) {
    this.band(c, 0, to, topColor, bottom);
  }

  band(c, from, to, topColor, bottom) {
    const g = c.createLinearGradient(0, from, 0, to);
    g.addColorStop(0, css(topColor));
    g.addColorStop(1, css(bottom));
    c.fillStyle = g;
    c.fillRect(0, from, this.w, to - from);
  }

  glow(c, x, y, radius, color, strength) {
    if (radius <= 0) return;
    const g = c.createRadialGradient(x, y, 0, x, y, radius);
    g.addColorStop(0, css(withAlpha(color, strength)));
    g.addColorStop(1, css(withAlpha(color, 0)));
    c.fillStyle = g;
    c.beginPath();
    c.arc(x, y, radius, 0, 2 * PI);
    c.fill();
  }

  vignette(c) {
    const { w, h } = this;
    const radius = Math.max(w, h) * 0.75;
    const g = c.createRadialGradient(w / 2, h / 2, 0, w / 2, h / 2, radius);
    g.addColorStop(0, 'rgba(0,0,0,0)');
    g.addColorStop(0.6, 'rgba(0,0,0,0)');
    g.addColorStop(1, 'rgba(0,0,0,0.588)');
    c.fillStyle = g;
    c.fillRect(0, 0, w, h);
  }

  circle(c, x, y, radius, color) {
    c.fillStyle = css(color);
    c.beginPath();
    c.arc(x, y, radius, 0, 2 * PI);
    c.fill();
  }

  ellipse(c, left, top, right, bottom) {
    c.beginPath();
    c.ellipse((left + right) / 2, (top + bottom) / 2, Math.abs(right - left) / 2, Math.abs(bottom - top) / 2, 0, 0, 2 * PI);
  }

  line(c, x1, y1, x2, y2, color, width) {
    c.strokeStyle = css(color);
    c.lineWidth = width;
    c.lineCap = 'round';
    c.beginPath();
    c.moveTo(x1, y1);
    c.lineTo(x2, y2);
    c.stroke();
  }

  stars(c, r, count, below, color) {
    for (let i = 0; i < count; i++) {
      const col = withAlpha(color, 0.25 + r.nextFloat() * 0.6);
      this.circle(c, r.nextFloat() * this.w, r.nextFloat() * below, (0.8 + r.nextFloat() * 1.8) * this.u, col);
    }
  }

  moon(c, s, y = this.h * 0.2) {
    const x = this.w * 0.72;
    const radius = 70 * this.u;
    const light = s.colors[3];
    if (s.features.has('FULL_MOON')) {
      this.glow(c, x, y, radius * 4, light, 0.35);
      this.circle(c, x, y, radius, light);
    } else if (s.features.has('CRESCENT')) {
      this.glow(c, x, y, radius * 3, light, 0.25);
      c.save();
      c.beginPath();
      c.arc(x, y, radius, 0, 2 * PI);
      c.clip();
      c.beginPath();
      c.rect(x - radius, y - radius, radius * 2, radius * 2);
      c.arc(x + radius * 0.45, y - radius * 0.2, radius * 0.88, 0, 2 * PI);
      c.fillStyle = css(light);
      c.fill('evenodd');
      c.restore();
    }
  }

  /** Soft rolling hills along [baseline], filled to the bottom. */
  hills(c, r, baseline, amplitude, color, waves) {
    const { w, h, u } = this;
    const phase = r.nextFloat() * 2 * PI;
    c.beginPath();
    c.moveTo(0, h);
    for (let x = 0; x <= w; x += 8 * u) c.lineTo(x, baseline + amplitude * Math.sin((x / w) * waves * PI + phase));
    c.lineTo(w, h);
    c.closePath();
    c.fillStyle = css(color);
    c.fill();
  }

  dunes(c, r, baseline, color, crest, layers) {
    const { w, h, u } = this;
    for (let i = 0; i < layers; i++) {
      c.beginPath();
      c.moveTo(0, h);
      c.lineTo(0, baseline + r.nextFloat() * 60 * u);
      for (let x = 0; x < w;) {
        const span = (300 + r.nextFloat() * 400) * u;
        const peak = baseline - (20 + r.nextFloat() * 80) * u;
        c.quadraticCurveTo(x + span / 2, peak, x + span, baseline + (r.nextFloat() - 0.5) * 60 * u);
        x += span;
      }
      c.lineTo(w, h);
      c.closePath();
      const g = c.createLinearGradient(0, baseline - 100 * u, 0, h);
      g.addColorStop(0, css(crest));
      g.addColorStop(1, css(color));
      c.fillStyle = g;
      c.fill();
    }
  }

  ridge(c, r, baseline, height, color) {
    const { w, h, u } = this;
    c.beginPath();
    c.moveTo(0, h);
    let x = 0;
    let y = baseline - r.nextFloat() * height;
    c.lineTo(0, y);
    while (x < w) {
      x += (30 + r.nextFloat() * 70) * u;
      y = Math.min(Math.max(y + (r.nextFloat() - 0.5) * height * 0.6, baseline - height), baseline);
      c.lineTo(x, y);
    }
    c.lineTo(w, h);
    c.closePath();
    c.fillStyle = css(color);
    c.fill();
  }

  clouds(c, r, from, to, color, alpha, count) {
    for (let i = 0; i < count; i++) {
      const x = r.nextFloat() * this.w;
      const y = from + r.nextFloat() * (to - from);
      this.cloud(c, x, y, (120 + r.nextFloat() * 160) * this.u, color, alpha);
    }
  }

  cloud(c, x, y, size, color, alpha) {
    c.fillStyle = css(withAlpha(color, alpha));
    this.ellipse(c, x - size, y - size * 0.28, x + size, y + size * 0.28);
    c.fill();
    this.ellipse(c, x - size * 0.55, y - size * 0.5, x + size * 0.25, y + size * 0.1);
    c.fill();
    this.ellipse(c, x - size * 0.1, y - size * 0.42, x + size * 0.7, y + size * 0.12);
    c.fill();
  }

  garden(c, r, s) {
    const { w, h, u } = this;
    const [top, middle, land, light] = s.colors;
    const horizon = h * 0.55;
    this.sky(c, top, middle, horizon);
    this.glow(c, w * 0.5, horizon, 420 * u, light, 0.6);
    this.band(c, horizon, h, blend(middle, land, 0.6), darker(land, 0.75));
    this.hills(c, r, horizon + 10 * u, 40 * u, blend(middle, land, 0.55), 3);
    this.hills(c, r, horizon + 90 * u, 55 * u, land, 2);
    if (s.features.has('RIVER')) {
      const g = c.createLinearGradient(0, horizon + 60 * u, 0, h);
      g.addColorStop(0, css(withAlpha(light, 0.75)));
      g.addColorStop(1, css(withAlpha(blend(light, middle, 0.5), 0.9)));
      c.fillStyle = g;
      c.beginPath();
      c.moveTo(w * 0.49, horizon + 70 * u);
      c.bezierCurveTo(w * 0.4, h * 0.7, w * 0.7, h * 0.8, w * 0.3, h);
      c.lineTo(w * 0.8, h);
      c.bezierCurveTo(w * 0.95, h * 0.8, w * 0.5, h * 0.7, w * 0.51, horizon + 70 * u);
      c.closePath();
      c.fill();
    }
    const treeColor = darker(land, 0.6);
    if (s.features.has('PALMS')) {
      this.palm(c, w * 0.12, h * 0.92, 520 * u, treeColor, 1);
      this.palm(c, w * 0.88, h * 0.95, 600 * u, treeColor, -1);
      this.palm(c, w * 0.25, h * 0.8, 320 * u, blend(treeColor, land, 0.4), -0.6);
    }
    if (s.features.has('TREES') || !s.features.has('PALMS')) {
      for (let i = 0; i < 7; i++) {
        const x = r.nextFloat() * w;
        const base = horizon + (60 + r.nextFloat() * 120) * u;
        this.tree(c, x, base, (50 + r.nextFloat() * 50) * u, blend(treeColor, land, 0.5));
      }
      this.tree(c, w * 0.08, h * 0.9, 170 * u, treeColor);
      this.tree(c, w * 0.93, h * 0.86, 190 * u, treeColor);
    }
  }

  tree(c, x, base, size, color) {
    c.fillStyle = css(color);
    c.fillRect(x - size * 0.08, base - size * 1.2, size * 0.16, size * 1.2);
    this.circle(c, x, base - size * 1.5, size * 0.8, color);
    this.circle(c, x - size * 0.5, base - size * 1.15, size * 0.55, color);
    this.circle(c, x + size * 0.5, base - size * 1.2, size * 0.6, color);
  }

  palm(c, x, base, height, color, lean) {
    c.strokeStyle = css(color);
    c.lineCap = 'round';
    c.lineWidth = height * 0.045;
    const topX = x + lean * height * 0.18;
    const topY = base - height;
    c.beginPath();
    c.moveTo(x, base);
    c.quadraticCurveTo(x + lean * height * 0.02, base - height * 0.5, topX, topY);
    c.stroke();
    c.lineWidth = height * 0.03;
    for (let i = 0; i < 7; i++) {
      const angle = ((-160 + i * 23) * PI) / 180;
      const len = height * (0.32 + (i % 2) * 0.06);
      const ex = topX + Math.cos(angle) * len;
      const ey = topY + Math.sin(angle) * len * 0.55 + len * 0.25;
      c.beginPath();
      c.moveTo(topX, topY);
      c.quadraticCurveTo((topX + ex) / 2, topY - len * 0.2, ex, ey);
      c.stroke();
    }
  }

  columns(c, r, base, color) {
    const { w, u } = this;
    c.fillStyle = css(color);
    const count = 6;
    const spacing = w / (count + 1);
    for (let i = 1; i <= count; i++) {
      const x = spacing * i + (r.nextFloat() - 0.5) * 30 * u;
      const full = (260 + r.nextFloat() * 160) * u;
      const standing = r.nextFloat() < 0.4 ? full * (0.3 + r.nextFloat() * 0.4) : full;
      const cw = 34 * u;
      c.fillRect(x - cw / 2, base - standing, cw, standing);
      if (standing === full) c.fillRect(x - cw, base - standing - 18 * u, cw * 2, 18 * u);
    }
    // A lintel across two of the columns that still stand.
    c.fillRect(spacing * 2 - 40 * u, base - 440 * u, spacing + 80 * u, 30 * u);
  }

  arches(c, s) {
    const { w, h, u } = this;
    const [top, middle, land, light] = s.colors;
    this.band(c, 0, h, top, land);
    const rows = 3;
    for (let row = 0; row < rows; row++) {
      const depth = row / rows;
      const scale = 1 - depth * 0.38;
      const count = 4 + row;
      const archW = w / count;
      const archTop = h * (0.18 + depth * 0.14);
      const bottom = h * (0.92 - depth * 0.12);
      for (let i = 0; i < count; i++) {
        const left = i * archW + archW * 0.14;
        const right = (i + 1) * archW - archW * 0.14;
        const mid = (left + right) / 2;
        const shoulder = archTop + (right - left) * 0.75;
        const g = c.createLinearGradient(0, archTop, 0, bottom);
        g.addColorStop(0, css(withAlpha(light, 0.55 - depth * 0.2)));
        g.addColorStop(1, css(withAlpha(middle, 0.35)));
        c.fillStyle = g;
        c.beginPath();
        c.moveTo(left, bottom);
        c.lineTo(left, shoulder);
        c.quadraticCurveTo(left, archTop, mid, archTop - 30 * u * scale);
        c.quadraticCurveTo(right, archTop, right, shoulder);
        c.lineTo(right, bottom);
        c.closePath();
        c.fill();
      }
    }
    this.band(c, h * 0.9, h, withAlpha(land, 0), land);
    this.vignette(c);
  }

  niche(c, color) {
    const { w, h, u } = this;
    const left = w * 0.22;
    const right = w * 0.78;
    const top = h * 0.12;
    const bottom = h * 0.92;
    const shoulder = top + (right - left) * 0.6;
    c.beginPath();
    c.moveTo(left, bottom);
    c.lineTo(left, shoulder);
    c.quadraticCurveTo(left, top, w / 2, top - 40 * u);
    c.quadraticCurveTo(right, top, right, shoulder);
    c.lineTo(right, bottom);
    c.closePath();
    c.fillStyle = css(withAlpha(color, 0.45));
    c.fill();
    c.strokeStyle = css(withAlpha(color, 0.9));
    c.lineWidth = 6 * u;
    c.stroke();
    // The chain the lamp hangs from.
    this.line(c, w / 2, top - 40 * u, w / 2, this.lampY() - 120 * u, withAlpha(color, 0.9), 3 * u);
  }

  lampY() {
    return this.h * 0.4;
  }

  starPattern(c, color) {
    const { w, h, u } = this;
    c.strokeStyle = css(withAlpha(color, 0.28));
    c.lineWidth = 2.2 * u;
    const cell = 180 * u;
    for (let y = -cell; y < h + cell; y += cell) {
      for (let x = -cell; x < w + cell; x += cell) this.eightPointStar(c, x, y, cell * 0.42);
    }
  }

  eightPointStar(c, cx, cy, radius) {
    // Two overlapping squares, one turned 45°: the khatam.
    for (let turn = 0; turn <= 1; turn++) {
      c.beginPath();
      for (let k = 0; k <= 3; k++) {
        const angle = ((k * 90 + turn * 45 + 45) * PI) / 180;
        const x = cx + Math.cos(angle) * radius;
        const y = cy + Math.sin(angle) * radius;
        if (k === 0) c.moveTo(x, y);
        else c.lineTo(x, y);
      }
      c.closePath();
      c.stroke();
    }
    c.beginPath();
    c.arc(cx, cy, radius * 0.38, 0, 2 * PI);
    c.stroke();
  }

  manuscript(c, r, s) {
    const { w, h, u } = this;
    const [, middle, land, light] = s.colors;
    this.band(c, 0, h, middle, blend(middle, land, 0.25));
    // The paper's grain.
    for (let i = 0; i < 2500; i++) {
      const col = withAlpha(land, r.nextFloat() * 0.08);
      this.circle(c, r.nextFloat() * w, r.nextFloat() * h, (1 + r.nextFloat() * 2.5) * u, col);
    }
    this.vignette(c);
    // An illuminated frame: double rule with corner ornaments.
    c.strokeStyle = css(withAlpha(light, 0.85));
    const inset = 46 * u;
    c.lineWidth = 5 * u;
    c.strokeRect(inset, inset, w - inset * 2, h - inset * 2);
    c.lineWidth = 2 * u;
    c.strokeRect(inset + 18 * u, inset + 18 * u, w - (inset + 18 * u) * 2, h - (inset + 18 * u) * 2);
    for (const [x, y] of [[inset, inset], [w - inset, inset], [inset, h - inset], [w - inset, h - inset]]) this.eightPointStar(c, x, y, 34 * u);
  }

  // -- the moving layer, painted every frame. Kept cheap: a few dozen shapes at most. -----------

  animate(c, s, t) {
    const { w, h } = this;
    const [, middle, , light] = s.colors;
    const has = (f) => s.features.has(f);
    const r = random(seedOf(s) ^ 0x5f3759df);
    switch (s.kind) {
      case 'SKY':
      case 'FIELDS':
        if (has('CLOUDS') || s.kind === 'SKY') this.driftClouds(c, r, t, light, 0.22, h * 0.05, h * 0.55);
        if (s.kind === 'FIELDS') this.wheat(c, r, t, s);
        break;
      case 'RAYS':
        this.rays(c, t, light, w * 0.5, -h * 0.05);
        break;
      case 'WATER':
        this.ripples(c, t, light, w * 0.5, h * 0.68);
        if (has('RAIN')) this.rain(c, r, t, light, 0.3);
        break;
      case 'RAIN':
        this.rain(c, r, t, light, 0.45);
        break;
      case 'GARDEN':
        this.motes(c, r, t, light, 26, h * 0.4, h);
        break;
      case 'EMBERS':
        this.embers(c, r, t, light);
        break;
      case 'STORM':
        this.driftClouds(c, r, t, darker(middle, 0.6), 0.35, 0, h * 0.35);
        if (has('RAIN')) this.rain(c, r, t, light, 0.35);
        if (has('LIGHTNING')) this.lightning(c, t, light);
        break;
      case 'RUINS':
      case 'DUNES':
        if (has('STARS')) this.twinkle(c, r, t, light, 50, h * 0.6);
        else this.driftSand(c, r, t, light);
        break;
      case 'NEBULA':
      case 'STARS':
        this.twinkle(c, r, t, light, 70, h * 0.9);
        break;
      case 'MIST':
        this.mist(c, r, t, light);
        break;
      case 'SEA':
        this.waves(c, t, s);
        if (has('RAIN')) this.rain(c, r, t, light, 0.3);
        break;
      case 'MOUNTAINS':
        if (has('MIST')) this.mist(c, r, t, light);
        else if (has('STARS')) this.twinkle(c, r, t, light, 40, h * 0.5);
        break;
      case 'ARCHES':
        this.motes(c, r, t, light, 30, h * 0.2, h * 0.9);
        break;
      case 'LANTERN':
        this.lantern(c, t, s);
        break;
      case 'GEOMETRIC':
      case 'MANUSCRIPT':
        this.sweep(c, t, light);
        break;
      default:
        break;
    }
  }

  driftClouds(c, r, t, color, alpha, from, to) {
    for (let i = 0; i < 5; i++) {
      const speed = (8 + r.nextFloat() * 14) * this.u;
      const size = (140 + r.nextFloat() * 160) * this.u;
      const span = this.w + size * 2;
      const x = ((r.nextFloat() * span + t * speed) % span) - size;
      this.cloud(c, x, from + r.nextFloat() * (to - from), size, color, alpha);
    }
  }

  rain(c, r, t, color, alpha) {
    const { w, h, u } = this;
    c.strokeStyle = css(withAlpha(color, alpha));
    c.lineWidth = 2 * u;
    c.lineCap = 'round';
    const length = 40 * u;
    c.beginPath();
    for (let i = 0; i < 140; i++) {
      const x0 = r.nextFloat() * w;
      const speed = (900 + r.nextFloat() * 500) * u;
      const y = ((r.nextFloat() * h + t * speed) % (h + length)) - length;
      c.moveTo(x0, y);
      c.lineTo(x0 - length * 0.18, y + length);
    }
    c.stroke();
  }

  twinkle(c, r, t, color, count, below) {
    for (let i = 0; i < count; i++) {
      const x = r.nextFloat() * this.w;
      const y = r.nextFloat() * below;
      const phase = r.nextFloat() * 6.28;
      const speed = 0.8 + r.nextFloat() * 1.6;
      const amount = (Math.sin(t * speed + phase) + 1) / 2;
      this.circle(c, x, y, (1.4 + amount * 1.8) * this.u, withAlpha(color, 0.15 + 0.75 * amount));
    }
  }

  motes(c, r, t, color, count, from, to) {
    for (let i = 0; i < count; i++) {
      const x = r.nextFloat() * this.w + Math.sin(t * 0.3 + i) * 30 * this.u;
      const span = to - from;
      const y = to - ((r.nextFloat() * span + t * (10 + r.nextFloat() * 20) * this.u) % span);
      this.circle(c, x, y, (2 + r.nextFloat() * 3) * this.u, withAlpha(color, 0.25 + 0.3 * ((Math.sin(t + i) + 1) / 2)));
    }
  }

  embers(c, r, t, color) {
    const { w, h, u } = this;
    for (let i = 0; i < 90; i++) {
      const speed = (40 + r.nextFloat() * 90) * u;
      const x = r.nextFloat() * w + Math.sin(t * (0.5 + r.nextFloat()) + i) * 24 * u;
      const y = h - ((r.nextFloat() * h + t * speed) % h);
      const life = y / h;
      const alpha = (0.15 + 0.7 * (1 - life)) * (0.6 + 0.4 * Math.sin(t * 6 + i));
      this.circle(c, x, y, (1.5 + r.nextFloat() * 3.5) * u, withAlpha(color, alpha));
    }
  }

  lightning(c, t, color) {
    const { w, h, u } = this;
    const period = 5.5;
    const cycle = (t % period) / period;
    const flashIndex = Math.trunc(t / period);
    if (cycle > 0.06) return;
    const strength = 1 - cycle / 0.06;
    c.fillStyle = css(withAlpha(color, 0.35 * strength));
    c.fillRect(0, 0, w, h);
    const bolt = random(flashIndex * 31 + 7);
    c.strokeStyle = css(withAlpha(color, strength));
    c.lineWidth = 5 * u;
    let x = w * (0.2 + bolt.nextFloat() * 0.6);
    let y = 0;
    c.beginPath();
    c.moveTo(x, y);
    while (y < h * 0.6) {
      x += (bolt.nextFloat() - 0.5) * 90 * u;
      y += (40 + bolt.nextFloat() * 70) * u;
      c.lineTo(x, y);
    }
    c.stroke();
  }

  driftSand(c, r, t, color) {
    const { w, h, u } = this;
    c.strokeStyle = css(withAlpha(color, 0.12));
    c.lineWidth = 2 * u;
    c.beginPath();
    for (let i = 0; i < 24; i++) {
      const y = h * (0.6 + r.nextFloat() * 0.35);
      const len = (80 + r.nextFloat() * 160) * u;
      const x = ((r.nextFloat() * (w + len) + t * (30 + r.nextFloat() * 50) * u) % (w + len)) - len;
      c.moveTo(x, y);
      c.lineTo(x + len, y - 6 * u);
    }
    c.stroke();
  }

  mist(c, r, t, color) {
    const { w, h, u } = this;
    for (let i = 0; i < 6; i++) {
      const y = h * (0.45 + r.nextFloat() * 0.5);
      const bandW = w * (0.8 + r.nextFloat() * 0.8);
      const x = ((r.nextFloat() * (w + bandW) + t * (10 + r.nextFloat() * 18) * u) % (w + bandW)) - bandW;
      const cx = x + bandW / 2;
      c.save();
      c.translate(cx, y);
      c.scale(1, 0.22);
      const g = c.createRadialGradient(0, 0, 0, 0, 0, bandW / 2);
      g.addColorStop(0, css(withAlpha(color, 0.22)));
      g.addColorStop(1, css(withAlpha(color, 0)));
      c.fillStyle = g;
      c.beginPath();
      c.arc(0, 0, bandW / 2, 0, 2 * PI);
      c.fill();
      c.restore();
    }
  }

  rays(c, t, color, sx, sy) {
    const length = Math.max(this.w, this.h) * 1.3;
    for (let i = 0; i < 7; i++) {
      const angle = ((60 + i * 10 + Math.sin(t * 0.25 + i) * 2) * PI) / 180;
      const spread = (3.2 * PI) / 180;
      const alpha = 0.07 + 0.05 * ((Math.sin(t * 0.7 + i * 1.3) + 1) / 2);
      c.fillStyle = css(withAlpha(color, alpha));
      c.beginPath();
      c.moveTo(sx, sy);
      c.lineTo(sx + Math.cos(angle - spread) * length, sy + Math.sin(angle - spread) * length);
      c.lineTo(sx + Math.cos(angle + spread) * length, sy + Math.sin(angle + spread) * length);
      c.closePath();
      c.fill();
    }
  }

  ripples(c, t, color, x, y) {
    c.lineWidth = 2.5 * this.u;
    for (let i = 0; i < 5; i++) {
      const progress = (t * 0.18 + i / 5) % 1;
      const radius = progress * this.w * 0.7;
      c.strokeStyle = css(withAlpha(color, 0.35 * (1 - progress)));
      this.ellipse(c, x - radius, y - radius * 0.22, x + radius, y + radius * 0.22);
      c.stroke();
    }
  }

  waves(c, t, s) {
    const { w, h, u } = this;
    const light = s.colors[3];
    const horizon = h * 0.55;
    const rough = s.features.has('ROUGH');
    c.lineWidth = (rough ? 3.5 : 2.2) * u;
    for (let i = 0; i < 14; i++) {
      const depth = i / 14;
      const y = horizon + (h - horizon) * depth * depth + 8 * u;
      const amp = (rough ? 22 : 7) * u * (0.4 + depth);
      c.strokeStyle = css(withAlpha(light, 0.08 + 0.18 * depth));
      c.beginPath();
      for (let x = 0; x <= w; x += 12 * u) {
        const yy = y + amp * Math.sin(x / (120 * u * (0.5 + depth)) + t * (1.2 + i * 0.1));
        if (x === 0) c.moveTo(x, yy);
        else c.lineTo(x, yy);
      }
      c.stroke();
    }
  }

  wheat(c, r, t, s) {
    const { w, h, u } = this;
    const [, , land, light] = s.colors;
    const color = blend(land, light, 0.35);
    c.strokeStyle = css(color);
    c.fillStyle = css(color);
    c.lineWidth = 3 * u;
    c.lineCap = 'round';
    for (let i = 0; i < 70; i++) {
      const x = r.nextFloat() * w;
      const height = (180 + r.nextFloat() * 220) * u;
      const sway = Math.sin(t * 1.3 + x / (90 * u)) * 26 * u;
      c.beginPath();
      c.moveTo(x, h);
      c.lineTo(x + sway, h - height);
      c.stroke();
      this.ellipse(c, x + sway - 6 * u, h - height - 30 * u, x + sway + 6 * u, h - height + 6 * u);
      c.fill();
    }
  }

  lantern(c, t, s) {
    const { w, u } = this;
    const light = s.colors[3];
    const y = this.lampY();
    const flicker = 0.85 + 0.1 * Math.sin(t * 3.1) + 0.05 * Math.sin(t * 7.3);
    this.glow(c, w / 2, y, 520 * u * flicker, light, 0.55);
    // The glass, "as if it were a pearly star".
    c.fillStyle = css(withAlpha(light, 0.9));
    c.beginPath();
    c.moveTo(w / 2, y - 120 * u);
    c.bezierCurveTo(w / 2 + 120 * u, y - 60 * u, w / 2 + 110 * u, y + 90 * u, w / 2, y + 120 * u);
    c.bezierCurveTo(w / 2 - 110 * u, y + 90 * u, w / 2 - 120 * u, y - 60 * u, w / 2, y - 120 * u);
    c.closePath();
    c.fill();
    this.glow(c, w / 2, y, 90 * u, WHITE, 0.8 * flicker);
  }

  sweep(c, t, color) {
    const { w, h, u } = this;
    const x = ((t * 60 * u) % (w + h)) - h * 0.2;
    this.glow(c, x, h * 0.35 + Math.sin(t * 0.2) * h * 0.15, Math.max(w, h) * 0.35, color, 0.08);
  }
}
