/* The brand pieces both renderers share: palette, fonts, the icon and the star pattern. */
const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '..', '..');
const RES = path.join(ROOT, 'app/src/main/res');
// Everything is inlined: a page set with setContent() lives at about:blank, which may not load
// file:// URLs — fonts then silently fall back, and the fallback lacks the Uthmani letters.
const MIME = { '.woff2': 'font/woff2', '.ttf': 'font/ttf', '.png': 'image/png' };
const url = (p) => `data:${MIME[path.extname(p)]};base64,${fs.readFileSync(p).toString('base64')}`;

// The app's dark palette (ui/theme/Color.kt).
const C = {
  bg: '#0A1710', panel: '#112A1C', text: '#E6F4EB', muted: '#8DB29B',
  accent: '#27AE60', green: '#45D483', border: '#1F4030',
  lightBg: '#F1F8F3', lightText: '#13281B', lightMuted: '#4E6B5B', lightGreen: '#0E7C43',
};

const iconXml = fs.readFileSync(path.join(RES, 'drawable/ic_launcher_foreground.xml'), 'utf8');
const GLYPH = iconXml.match(/android:pathData="([^"]+)"/)[1];
const GLYPH_FILL = iconXml.match(/android:fillColor="([^"]+)"/)[1];
const ICON_BG = fs.readFileSync(path.join(RES, 'values/colors.xml'), 'utf8')
  .match(/name="ic_launcher_background">([^<]+)</)[1];

/** The adaptive icon as SVG: its visible 72dp of the 108dp canvas, full bleed. */
function iconSvg(size, { radius = 0 } = {}) {
  return `<svg xmlns="http://www.w3.org/2000/svg" width="${size}" height="${size}" viewBox="18 18 72 72">
    <defs>
      <radialGradient id="g" cx="50%" cy="42%" r="70%">
        <stop offset="0" stop-color="${C.panel}"/><stop offset="1" stop-color="${ICON_BG}"/>
      </radialGradient>
      <clipPath id="c"><rect x="18" y="18" width="72" height="72" rx="${radius}"/></clipPath>
    </defs>
    <g clip-path="url(#c)">
      <rect x="18" y="18" width="72" height="72" fill="url(#g)"/>
      <path d="${GLYPH}" fill="${GLYPH_FILL}"/>
    </g>
  </svg>`;
}

const FONTS = `
  @font-face { font-family: Inter; src: url('${url(path.join(__dirname, 'fonts/Inter-latin.woff2'))}'); font-weight: 100 900; }
  @font-face { font-family: Quran; src: url('${url(path.join(RES, 'font/scheherazade_new.ttf'))}'); }
  * { box-sizing: border-box; margin: 0; }
  html, body { width: 100%; height: 100%; }
  body { font-family: Inter, sans-serif; -webkit-font-smoothing: antialiased; }
  h1, p { text-wrap: balance; }`;

/** An eight-pointed star lattice, the common Islamic geometric motif, as a CSS background. */
function starPattern(color, cell = 96) {
  const s = cell, h = s / 2, r1 = s * 0.30, r2 = s * 0.13;
  const pts = [];
  for (let i = 0; i < 16; i++) {
    const r = i % 2 === 0 ? r1 : r2, a = (Math.PI / 8) * i;
    pts.push(`${(h + r * Math.cos(a)).toFixed(2)},${(h + r * Math.sin(a)).toFixed(2)}`);
  }
  const svg = `<svg xmlns='http://www.w3.org/2000/svg' width='${s}' height='${s}'>
    <polygon points='${pts.join(' ')}' fill='none' stroke='${color}' stroke-width='1.4'/>
    <rect x='${h - s * 0.14}' y='${h - s * 0.14}' width='${s * 0.28}' height='${s * 0.28}' transform='rotate(45 ${h} ${h})' fill='none' stroke='${color}' stroke-width='1'/>
  </svg>`;
  return `url("data:image/svg+xml,${encodeURIComponent(svg)}")`;
}

/** Renders [html] at [width]×[height] to [out], once its fonts and any frameReady promise settle. */
async function render(browser, html, out, width, height) {
  const page = await browser.newPage({ viewport: { width, height }, deviceScaleFactor: 1 });
  await page.setContent(html, { waitUntil: 'load' });
  const fonts = await page.evaluate(async () => {
    await document.fonts.ready;
    return [...document.fonts].map((f) => `${f.family}:${f.status}`);
  });
  if (fonts.some((f) => !f.endsWith(':loaded') && !f.endsWith(':unloaded'))) throw new Error(`fonts: ${fonts}`);
  await page.evaluate(() => window.frameReady);
  fs.mkdirSync(path.dirname(out), { recursive: true });
  await page.screenshot({ path: out });
  await page.close();
  console.log(`  ${path.relative(ROOT, out)}  ${width}×${height}`);
}

module.exports = { ROOT, RES, C, url, iconSvg, FONTS, starPattern, render };
