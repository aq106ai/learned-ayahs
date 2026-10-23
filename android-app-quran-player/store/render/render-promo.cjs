#!/usr/bin/env node
/*
 * The promo video for the store listing and social media: a short tour of the app made from the
 * phone screenshots, over a recitation of the opening of Al-Mulk.
 *
 *   node store/render/render-promo.cjs            (from android-app-quran-player/)
 *
 * Needs Node 18+, Playwright and ffmpeg (with libx264). Writes
 * store/video/learned-ayahs-promo.mp4 — 1920×1080, H.264/AAC, about 37 seconds.
 *
 * Inputs: the raw phone screenshots in store/raw/phone/ (store-screenshots.yml), their captions
 * in captions.json, and the clips the store video recorded in store/raw/video/audio/.
 * Unlike render-video.cjs this is not the foreground-service declaration video: that one has to
 * show only the service, so Play reviewers can see it.
 */
const fs = require('fs');
const path = require('path');
const { execFileSync } = require('child_process');
const { chromium } = require('playwright');
const { ROOT, C, url, iconSvg, FONTS, starPattern, render } = require('./common.cjs');

const SHOTS = path.join(ROOT, 'store/raw/phone');
const AUDIO = path.join(ROOT, 'store/raw/video/audio');
const OUT = path.join(ROOT, 'store/video');
const WORK = path.join(OUT, '.promo-work');
const FINAL = path.join(OUT, 'learned-ayahs-promo.mp4');
const CAPTIONS = JSON.parse(fs.readFileSync(path.join(__dirname, 'captions.json'), 'utf8'));

const W = 1920, H = 1080, FPS = 30;
const TITLE_S = 4, SCENE_S = 4.5, END_S = 6, FADE_S = 0.6;
const SCENES = ['01_reader', '02_word_by_word', '03_playback_options', '05_mark_ayahs', '06_add_by_description', '07_reciters'];
// Whole ayahs only, never cut off: Bismillah and Al-Mulk 1–3 fit the scenes, ending on the end card.
const RECITATION = ['bismillah.mp3', '067001.mp3', '067002.mp3', '067003.mp3'];
const RECITATION_AT_S = 1;

const ffmpeg = (args) => execFileSync('ffmpeg', ['-hide_banner', '-loglevel', 'error', '-y', ...args], { stdio: 'inherit' });
const probeDuration = (file) => parseFloat(execFileSync('ffprobe', ['-v', 'error', '-show_entries', 'format=duration', '-of', 'csv=p=0', file]).toString());

const page = (body, extraCss = '') => `<!doctype html><html><head><meta charset="utf-8"><style>${FONTS}
  body { width: ${W}px; height: ${H}px; overflow: hidden; position: relative; color: ${C.text};
         background: radial-gradient(120% 120% at 15% 0%, #163A26 0%, ${C.bg} 60%); }
  .pattern { position: absolute; inset: 0; background-image: ${starPattern('rgba(69,212,131,0.06)', 120)};
             background-size: 120px 120px; -webkit-mask-image: linear-gradient(90deg, transparent 35%, #000 100%); }
  em { font-style: normal; color: ${C.green}; }
  ${extraCss}</style></head><body><div class="pattern"></div>${body}</body></html>`;

function titleHtml() {
  return page(`<div class="t">${iconSvg(160, { radius: 18 })}<h1>Learned Ayahs</h1>
      <h2>Revise the Qur'an <em>you have memorised</em></h2></div>`, `
    .t { position: absolute; inset: 0; display: flex; flex-direction: column; align-items: center; justify-content: center; text-align: center; }
    .t svg { box-shadow: 0 0 0 2px ${C.border}, 0 30px 80px rgba(0,0,0,0.5); border-radius: 40px; margin-bottom: 44px; }
    h1 { font-size: 104px; font-weight: 800; letter-spacing: -2.5px; }
    h2 { margin-top: 20px; font-size: 48px; font-weight: 600; color: ${C.muted}; }`);
}

// The phone: its 1080×2400 screenshot shown 432×960, in a 14px bezel.
function sceneHtml(name, index) {
  const { title, subtitle } = CAPTIONS[name];
  const dots = SCENES.map((_, i) => `<i class="${i === index ? 'on' : ''}"></i>`).join('');
  return page(`
    <div class="phone"><img src="${url(path.join(SHOTS, `${name}.png`))}"></div>
    <div class="brand">${iconSvg(56, { radius: 18 })}<b>Learned Ayahs</b></div>
    <div class="cap"><h1>${title}</h1><p>${subtitle}</p></div>
    <div class="dots">${dots}</div>`, `
    .phone { position: absolute; left: 270px; top: 46px; width: 460px; height: 988px; padding: 14px; border-radius: 60px;
             background: #050C08; box-shadow: 0 0 0 2px ${C.border}, 0 40px 100px rgba(0,0,0,0.55); }
    .phone img { display: block; width: 432px; height: 960px; border-radius: 46px; }
    .brand { position: absolute; left: 860px; top: 120px; display: flex; align-items: center; gap: 18px; }
    .brand svg { border-radius: 14px; box-shadow: 0 0 0 1px ${C.border}; }
    .brand b { font-size: 30px; font-weight: 800; letter-spacing: -0.5px; }
    .cap { position: absolute; left: 860px; top: 340px; width: 900px; }
    h1 { font-size: 84px; font-weight: 800; letter-spacing: -2px; line-height: 1.06; }
    p { margin-top: 32px; font-size: 38px; line-height: 1.4; color: ${C.muted}; }
    .dots { position: absolute; left: 860px; bottom: 120px; display: flex; gap: 14px; }
    .dots i { width: 14px; height: 14px; border-radius: 50%; background: ${C.border}; }
    .dots i.on { width: 44px; border-radius: 7px; background: ${C.green}; }`);
}

function endHtml() {
  const points = ['Free', 'No ads', 'No sign-up', 'Works offline'];
  return page(`<div class="e">${iconSvg(130, { radius: 18 })}<h1>Learned Ayahs</h1>
      <ul>${points.map((p) => `<li>${p}</li>`).join('')}</ul>
      <div class="where"><span>Android</span><span>aq106ai.github.io/learned-ayahs</span></div></div>`, `
    .e { position: absolute; inset: 0; display: flex; flex-direction: column; align-items: center; justify-content: center; text-align: center; }
    .e svg { border-radius: 32px; box-shadow: 0 0 0 2px ${C.border}; margin-bottom: 36px; }
    h1 { font-size: 92px; font-weight: 800; letter-spacing: -2.2px; }
    ul { margin-top: 36px; padding: 0; list-style: none; display: flex; gap: 22px; }
    li { font-size: 34px; font-weight: 600; color: ${C.text}; padding: 14px 30px; border-radius: 40px;
         background: ${C.panel}; box-shadow: 0 0 0 2px ${C.border}; }
    li::before { content: '✓  '; color: ${C.green}; font-weight: 800; }
    .where { margin-top: 56px; display: flex; gap: 40px; font-size: 36px; font-weight: 700; color: ${C.green}; }
    .where span + span::before { content: '·'; margin-right: 40px; color: ${C.muted}; }`);
}

(async () => {
  fs.mkdirSync(WORK, { recursive: true });
  console.log('Rendering the frames:');
  const browser = await chromium.launch();
  const frames = [path.join(WORK, 'title.png')];
  await render(browser, titleHtml(), frames[0], W, H);
  for (let i = 0; i < SCENES.length; i++) {
    const out = path.join(WORK, `scene${i}.png`);
    await render(browser, sceneHtml(SCENES[i], i), out, W, H);
    frames.push(out);
  }
  frames.push(path.join(WORK, 'end.png'));
  await render(browser, endHtml(), frames[frames.length - 1], W, H);
  await browser.close();

  // Each frame is held for its length plus the crossfade that overlaps the next one.
  const lengths = [TITLE_S, ...SCENES.map(() => SCENE_S), END_S];
  const total = lengths.reduce((a, b) => a + b, 0);
  const inputs = [], filters = [];
  frames.forEach((f, i) => {
    const hold = lengths[i] + (i < frames.length - 1 ? FADE_S : 0);
    inputs.push('-loop', '1', '-framerate', String(FPS), '-t', hold.toFixed(3), '-i', f);
    filters.push(`[${i}:v]fps=${FPS},format=yuv420p,setsar=1[f${i}]`);
  });
  let last = 'f0', offset = 0;
  for (let i = 1; i < frames.length; i++) {
    offset += lengths[i - 1];
    const label = i === frames.length - 1 ? 'xf' : `x${i}`;
    filters.push(`[${last}][f${i}]xfade=transition=fadeblack:duration=${FADE_S}:offset=${offset.toFixed(3)}[${label}]`);
    last = label;
  }
  filters.push(`[xf]fade=t=in:d=0.5,fade=t=out:st=${(total - 0.8).toFixed(3)}:d=0.8[v]`);

  console.log('Building the soundtrack:');
  const clips = RECITATION.map((f) => path.join(AUDIO, f));
  const recited = clips.reduce((s, f) => s + probeDuration(f), 0);
  if (RECITATION_AT_S + recited > total - 1) throw new Error(`The recitation (${recited.toFixed(1)}s) outruns the video (${total}s)`);
  const a = frames.length;
  clips.forEach((f) => inputs.push('-i', f));
  filters.push(clips.map((_, i) => `[${a + i}:a]aresample=48000,aformat=channel_layouts=stereo[c${i}]`).join(';'));
  const delay = RECITATION_AT_S * 1000;
  filters.push(`${clips.map((_, i) => `[c${i}]`).join('')}concat=n=${clips.length}:v=0:a=1,` +
    `adelay=${delay}|${delay},apad,atrim=0:${total.toFixed(3)}[a]`);
  console.log(`  ${RECITATION.join(', ')}: ${recited.toFixed(1)}s from ${RECITATION_AT_S}s`);

  console.log('Encoding:');
  ffmpeg([
    ...inputs,
    '-filter_complex', filters.join(';'),
    '-map', '[v]', '-map', '[a]',
    '-c:v', 'libx264', '-preset', 'medium', '-crf', '20', '-pix_fmt', 'yuv420p', '-r', String(FPS),
    '-c:a', 'aac', '-b:a', '160k', '-movflags', '+faststart', FINAL,
  ]);
  console.log(`  ${path.relative(ROOT, FINAL)}  ${probeDuration(FINAL).toFixed(1)}s`);
})().catch((e) => { console.error(e); process.exit(1); });
