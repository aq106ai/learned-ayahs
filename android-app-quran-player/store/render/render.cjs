#!/usr/bin/env node
/*
 * Renders the Google Play listing graphics with headless Chromium (Playwright):
 *
 *   node store/render/render.cjs            (from android-app-quran-player/)
 *
 * Needs Node 18+ and Playwright (`npm install playwright` and its Chromium).
 *
 * Inputs, all from this repo:
 *   - the launcher icon's vector (app/src/main/res/drawable/ic_launcher_foreground.xml) and its
 *     background colour (res/values/colors.xml), so the store icon is the launcher icon;
 *   - the app's Quran font (res/font/scheherazade_new.ttf) and Inter for Latin text;
 *   - the screenshots store-screenshots.yml captured from the real app (store/raw/<device>/);
 *   - captions.json, one caption per screenshot.
 *
 * Output, in the layout fastlane `supply` and F-Droid read:
 *   fastlane/metadata/android/en-US/images/icon.png                 512×512, 32-bit PNG
 *   fastlane/metadata/android/en-US/images/featureGraphic.png       1024×500, 24-bit PNG
 *   fastlane/metadata/android/en-US/images/phoneScreenshots/*.png    1080×1920 (9:16)
 *   fastlane/metadata/android/en-US/images/tenInchScreenshots/*.png  2560×1600 (16:10)
 *   fastlane/metadata/android/en-US/images/sevenInchScreenshots/*.png  same tablet shots
 *
 * Play wants the feature graphic and screenshots without an alpha channel; Chromium writes an
 * opaque page as RGB, so every page here paints its whole background.
 */
const fs = require('fs');
const path = require('path');
const { chromium } = require('playwright');

const { ROOT, RES, C, url, iconSvg, FONTS, starPattern, render } = require('./common.cjs');
const OUT = path.join(ROOT, 'fastlane/metadata/android/en-US/images');
const RAW = path.join(ROOT, 'store/raw');

function featureGraphicHtml(ayah) {
  return `<!doctype html><html><head><meta charset="utf-8"><style>${FONTS}
    body { background: radial-gradient(120% 140% at 85% 50%, #143322 0%, ${C.bg} 62%); color: ${C.text};
           position: relative; overflow: hidden; }
    .pattern { position: absolute; inset: 0; background-image: ${starPattern('rgba(69,212,131,0.10)')};
               background-size: 96px 96px; background-position: 24px 10px;
               -webkit-mask-image: linear-gradient(90deg, transparent 30%, #000 75%); }
    .left { position: absolute; left: 72px; top: 0; bottom: 0; width: 470px; display: flex;
            flex-direction: column; justify-content: center; gap: 22px; }
    .brand { display: flex; align-items: center; gap: 22px; }
    .brand svg { border-radius: 24px; box-shadow: 0 0 0 1px ${C.border}; }
    h1 { font-size: 54px; font-weight: 800; letter-spacing: -1.4px; line-height: 1.02; }
    .tag { font-size: 27px; font-weight: 500; color: ${C.muted}; line-height: 1.3; }
    .chips { display: flex; gap: 10px; flex-wrap: wrap; }
    .chip { font-size: 16px; font-weight: 600; color: ${C.green}; border: 1.5px solid ${C.border};
            background: rgba(17,42,28,0.8); border-radius: 999px; padding: 6px 14px; }
    .right { position: absolute; right: 64px; top: 0; bottom: 0; width: 420px; display: flex;
             flex-direction: column; justify-content: center; align-items: center; text-align: center; gap: 18px; }
    .ar { font-family: Quran; direction: rtl; font-size: 52px; line-height: 1.75; color: ${C.text}; }
    .ar .hl { color: ${C.green}; }
    .en { font-size: 19px; color: ${C.muted}; line-height: 1.45; font-style: italic; max-width: 380px; }
    .ref { font-size: 15px; font-weight: 700; letter-spacing: 2px; color: ${C.accent}; text-transform: uppercase; }
  </style></head><body>
    <div class="pattern"></div>
    <div class="left">
      <div class="brand">${iconSvg(104)}<h1>Learned<br>Ayahs</h1></div>
      <div class="tag">Revise the Qur'an<br>you have memorised.</div>
      <div class="chips"><span class="chip">Word by word</span><span class="chip">5 reciters</span><span class="chip">Open source</span></div>
    </div>
    <div class="right">
      <div class="ar">${ayah.arabic.map((w, i) => (i === 2 || i === 3) ? `<span class="hl">${w}</span>` : w).join(' ')}</div>
      <div class="en">“And We have certainly made the Qur'an easy for remembrance, so is there any who will remember?”</div>
      <div class="ref">Al-Qamar · 54:17</div>
    </div>
  </body></html>`;
}

/** A phone screenshot on a 1080×1920 canvas, under its caption. */
function phoneFrameHtml(shot, caption) {
  const light = caption.theme === 'light';
  const bg = light
    ? `linear-gradient(180deg, #FFFFFF 0%, ${C.lightBg} 55%, #DBEDE2 100%)`
    : `radial-gradient(130% 80% at 50% 0%, #163A26 0%, ${C.bg} 60%)`;
  return `<!doctype html><html><head><meta charset="utf-8"><style>${FONTS}
    body { background: ${bg}; color: ${light ? C.lightText : C.text}; position: relative; overflow: hidden;
           display: flex; flex-direction: column; align-items: center; }
    .pattern { position: absolute; inset: 0; background-image: ${starPattern(light ? 'rgba(14,124,67,0.07)' : 'rgba(69,212,131,0.07)', 120)};
               background-size: 120px 120px; -webkit-mask-image: linear-gradient(180deg, #000 0%, transparent 40%); }
    .cap { position: relative; text-align: center; padding: 104px 70px 0; height: 400px; }
    h1 { font-size: 66px; font-weight: 800; letter-spacing: -1.6px; line-height: 1.08; }
    h1 em { font-style: normal; color: ${light ? C.lightGreen : C.green}; }
    p { margin-top: 24px; font-size: 34px; font-weight: 500; line-height: 1.35; color: ${light ? C.lightMuted : C.muted}; }
    .device { position: relative; height: 1440px; padding: 12px; border-radius: 64px;
              background: ${light ? '#13281B' : '#050C08'};
              box-shadow: 0 0 0 2px ${light ? '#2C4A38' : C.border}, 0 40px 90px rgba(0,0,0,${light ? 0.28 : 0.55}); }
    .device img { display: block; height: 100%; border-radius: 52px; }
  </style></head><body>
    <div class="pattern"></div>
    <div class="cap"><h1>${caption.title}</h1><p>${caption.subtitle}</p></div>
    <div class="device"><img src="${url(shot)}"></div>
  </body></html>`;
}

/**
 * A tablet screenshot on a 2560×1600 canvas, caption on the left. Outside the immersive reader the
 * tablet shows its taskbar — a light strip of other apps' icons, 120px tall — which has no place
 * in a store listing, so the frame crops it when it is there.
 */
const TASKBAR_PX = 120;
function tabletFrameHtml(shot, caption) {
  return `<!doctype html><html><head><meta charset="utf-8"><style>${FONTS}
    body { background: radial-gradient(120% 120% at 0% 0%, #163A26 0%, ${C.bg} 60%); color: ${C.text};
           display: flex; align-items: center; gap: 90px; padding: 0 110px; overflow: hidden; }
    .cap { flex: 0 0 640px; }
    h1 { font-size: 84px; font-weight: 800; letter-spacing: -2px; line-height: 1.06; }
    h1 em { font-style: normal; color: ${C.green}; }
    p { margin-top: 30px; font-size: 40px; font-weight: 500; line-height: 1.35; color: ${C.muted}; }
    .device { padding: 16px; border-radius: 52px; background: #050C08;
              box-shadow: 0 0 0 2px ${C.border}, 0 40px 100px rgba(0,0,0,0.55); }
    .screen { width: 1560px; border-radius: 38px; overflow: hidden; }
    .screen img { display: block; width: 100%; }
  </style></head><body>
    <div class="cap"><h1>${caption.title}</h1><p>${caption.subtitle}</p></div>
    <div class="device"><div class="screen"><img src="${url(shot)}"></div></div>
    <script>
      window.frameReady = document.querySelector('.screen img').decode().then(() => {
        const img = document.querySelector('.screen img');
        const c = Object.assign(document.createElement('canvas'), { width: img.naturalWidth, height: img.naturalHeight });
        const g = c.getContext('2d');
        g.drawImage(img, 0, 0);
        const [r, gr, b] = g.getImageData(20, img.naturalHeight - ${TASKBAR_PX / 2}, 1, 1).data;
        if (r > 235 && gr > 235 && b > 235) {
          const scale = 1560 / img.naturalWidth;
          document.querySelector('.screen').style.height = ((img.naturalHeight - ${TASKBAR_PX}) * scale) + 'px';
        }
      });
    </script>
  </body></html>`;
}

/**
 * The 512×512 store icon. Play asks for a 32-bit PNG, and Chromium's screenshots drop the alpha
 * channel from an opaque page, so this one goes through a canvas, whose PNGs keep it.
 */
async function renderIcon(browser, out) {
  const page = await browser.newPage();
  const svg = `data:image/svg+xml;base64,${Buffer.from(iconSvg(512)).toString('base64')}`;
  const png = await page.evaluate(async (src) => {
    const img = new Image();
    img.src = src;
    await img.decode();
    const canvas = Object.assign(document.createElement('canvas'), { width: 512, height: 512 });
    canvas.getContext('2d').drawImage(img, 0, 0, 512, 512);
    return canvas.toDataURL('image/png').split(',')[1];
  }, svg);
  fs.mkdirSync(path.dirname(out), { recursive: true });
  fs.writeFileSync(out, Buffer.from(png, 'base64'));
  await page.close();
  console.log(`  ${path.relative(ROOT, out)}  512×512`);
}

function shotsIn(device) {
  const dir = path.join(RAW, device);
  if (!fs.existsSync(dir)) return [];
  return fs.readdirSync(dir).filter((f) => f.endsWith('.png')).sort().map((f) => path.join(dir, f));
}

(async () => {
  const captions = JSON.parse(fs.readFileSync(path.join(__dirname, 'captions.json'), 'utf8'));
  const quran = JSON.parse(fs.readFileSync(path.join(ROOT, 'app/src/main/assets/quran_text.json'), 'utf8'));
  const ayah = { arabic: quran['54:17'].words.filter((w) => w.char_type === 'word').map((w) => w.text) };

  const browser = await chromium.launch();
  console.log('Rendering Play Store graphics:');

  await renderIcon(browser, path.join(OUT, 'icon.png'));
  await render(browser, featureGraphicHtml(ayah), path.join(OUT, 'featureGraphic.png'), 1024, 500);

  for (const [device, dirs, w, h, frame] of [
    ['phone', ['phoneScreenshots'], 1080, 1920, phoneFrameHtml],
    ['tablet', ['tenInchScreenshots', 'sevenInchScreenshots'], 2560, 1600, tabletFrameHtml],
  ]) {
    const shots = shotsIn(device);
    if (shots.length === 0) console.log(`  (no ${device} screenshots in store/raw/${device}; run store-screenshots.yml)`);
    for (const shot of shots) {
      const name = path.basename(shot, '.png');
      const caption = captions[name];
      if (!caption || (caption.devices && !caption.devices.includes(device))) continue;
      for (const dir of dirs) await render(browser, frame(shot, caption), path.join(OUT, dir, `${name}.png`), w, h);
    }
  }
  await browser.close();
})().catch((e) => { console.error(e); process.exit(1); });
