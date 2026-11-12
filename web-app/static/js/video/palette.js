// Chrome colours taken from a scene, matching ui/theme/Color.kt paletteFor: the sky, darkened,
// for the page and panels, and the scene's light colour for text. Photos share one neutral dark.

const TEXT_CONTRAST = 4.5;
const WHITE = [255, 255, 255];
const BLACK = [0, 0, 0];
const GREEN = [0x45, 0xd4, 0x83];

function channel(v) {
  const s = v / 255;
  return s <= 0.04045 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4;
}

/** Relative luminance of an sRGB colour, 0–1. */
export function luminance(c) {
  return 0.2126 * channel(c[0]) + 0.7152 * channel(c[1]) + 0.0722 * channel(c[2]);
}

/** WCAG contrast of [foreground] against [background]. 4.5 is the body-text minimum. */
export function contrastRatio(foreground, background) {
  const a = luminance(foreground);
  const b = luminance(background);
  const lighter = Math.max(a, b);
  const darker = Math.min(a, b);
  return (lighter + 0.05) / (darker + 0.05);
}

function mix(a, b, t) {
  return [a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t];
}

function darkenUntil(color, against, minContrast) {
  if (contrastRatio(against, color) >= minContrast) return color;
  let t = 0;
  let current = color;
  while (contrastRatio(against, current) < minContrast && t < 1) {
    t = Math.min(t + 0.04, 1);
    current = mix(color, BLACK, t);
  }
  return current;
}

function contrastingAccent(panel, candidates) {
  let best = candidates[0];
  let bestRatio = -1;
  for (const candidate of candidates) {
    const ratio = contrastRatio(candidate, panel);
    if (ratio > bestRatio) {
      best = candidate;
      bestRatio = ratio;
    }
  }
  if (bestRatio >= TEXT_CONTRAST) return best;
  const aim = luminance(panel) > 0.4 ? BLACK : WHITE;
  let t = 0;
  let color = best;
  while (contrastRatio(color, panel) < TEXT_CONTRAST && t < 1) {
    t = Math.min(t + 0.05, 1);
    color = mix(best, aim, t);
  }
  return color;
}

function lightenUntil(color, against, minContrast) {
  if (contrastRatio(against, color) >= minContrast) return color;
  let t = 0;
  let current = color;
  while (contrastRatio(against, current) < minContrast && t < 1) {
    t = Math.min(t + 0.04, 1);
    current = mix(color, WHITE, t);
  }
  return current;
}

/**
 * The Light theme's version of a scene's chrome: the sky, paled, for the page and panels, and the
 * sky, darkened, for text. Port of ui/theme/Color.kt lightPaletteFor.
 */
function lightPaletteFor(scene) {
  const sky = scene.colors[0];
  const text = mix(sky, BLACK, 0.85);
  const bg = lightenUntil(mix(sky, WHITE, 0.88), text, 9);
  const panel = mix(bg, WHITE, 0.6);
  const border = mix(panel, text, 0.14);
  // The accent is also text on border-coloured chips, the darkest surface it sits on.
  const accent = darkenUntil(scene.colors[1], border, TEXT_CONTRAST);
  const onAccent = contrastRatio(WHITE, accent) >= contrastRatio(panel, accent) ? WHITE : panel;
  return { bg, panel, text, muted: darkenUntil(mix(text, panel, 0.22), bg, TEXT_CONTRAST), accent, onAccent, border, isLight: true };
}

/**
 * A palette for [scene] ({colors: [[r,g,b,a] x4]}): bg, panel, text, muted, accent, border.
 * Dark by default, so light text reads on the picture; [light] gives the Light theme's version.
 */
export function paletteFor(scene, light = false) {
  if (light) return lightPaletteFor(scene);
  const sky = scene.colors[0];
  const mid = scene.colors[1];
  const rawLight = scene.colors[3] ?? WHITE;
  const text = luminance(rawLight) >= 0.7 ? rawLight : WHITE;
  const bg = darkenUntil(sky, text, 7.5);
  let panel = mix(bg, WHITE, 0.08);
  if (contrastRatio(text, panel) < TEXT_CONTRAST) panel = darkenUntil(panel, text, TEXT_CONTRAST);
  const accent = contrastingAccent(panel, [rawLight, mid, GREEN, WHITE]);
  // Labels and icons drawn on the accent (play button, selected chips): a light accent (a photo's
  // highlight, often near white) needs the dark panel colour, or they vanish into it.
  const onAccent = contrastRatio(WHITE, accent) >= contrastRatio(panel, accent) ? WHITE : panel;
  return {
    bg,
    panel,
    text,
    muted: mix(text, panel, 0.42),
    accent,
    onAccent,
    border: mix(panel, text, 0.18),
    isLight: false,
  };
}

function byte(v) {
  return Math.round(Math.min(255, Math.max(0, v)));
}

function hex(c) {
  const n = (v) => byte(v).toString(16).padStart(2, '0');
  return `#${n(c[0])}${n(c[1])}${n(c[2])}`;
}

function rgba(c, a) {
  return `rgba(${byte(c[0])},${byte(c[1])},${byte(c[2])},${a})`;
}

const ROLES = ['--bg', '--bg-2', '--panel', '--panel-2', '--panel-3', '--text', '--muted', '--accent', '--accent-2', '--accent-ink', '--on-accent', '--border', '--highlight'];

/** Puts [palette] on [el] so it wins over the stylesheet. Null clears it. */
export function applyPalette(el, palette) {
  if (!el) return;
  if (!palette) {
    for (const name of ROLES) el.style.removeProperty(name);
    return;
  }
  el.style.setProperty('--bg', hex(palette.bg));
  el.style.setProperty('--bg-2', rgba(palette.bg, 0.72));
  el.style.setProperty('--panel', hex(palette.panel));
  // Raised surfaces step toward the text: lighter on a dark palette, darker on a light one.
  const raise = palette.isLight ? palette.text : WHITE;
  el.style.setProperty('--panel-2', hex(mix(palette.panel, raise, palette.isLight ? 0.04 : 0.06)));
  el.style.setProperty('--panel-3', hex(mix(palette.panel, raise, palette.isLight ? 0.09 : 0.12)));
  el.style.setProperty('--text', hex(palette.text));
  el.style.setProperty('--muted', hex(palette.muted));
  el.style.setProperty('--accent', hex(palette.accent));
  el.style.setProperty('--accent-2', hex(palette.accent));
  el.style.setProperty('--accent-ink', hex(palette.panel));
  el.style.setProperty('--on-accent', hex(palette.onAccent));
  el.style.setProperty('--border', hex(palette.border));
  el.style.setProperty('--highlight', rgba(palette.accent, 0.18));
}
