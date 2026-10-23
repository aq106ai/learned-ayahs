#!/usr/bin/env node
/*
 * Turns the recording made by store-screenshots.yml (store/capture-video.sh) into the video the
 * Play Console asks for with the foreground-service declaration:
 *
 *   node store/render/render-video.cjs            (from android-app-quran-player/)
 *
 * Needs Node 18+, Playwright and ffmpeg (with libx264). Writes
 * store/video/learned-ayahs-media-playback.mp4 — 1920×1080, H.264/AAC, a landscape video so
 * YouTube keeps it a normal video rather than a Short.
 *
 * Inputs, in store/raw/video/:
 *   screen.mp4     the phone's screen recording (Android's recorder has no sound);
 *   timeline.json  what the test did and when, and what was playing, sampled every 100ms;
 *   audio/         the clips that were playing, as the app downloaded them.
 * The soundtrack is rebuilt from the timeline: each stretch of playback is cut from its clip at
 * the position the player reported, and placed where it played.
 */
const fs = require('fs');
const path = require('path');
const { execFileSync } = require('child_process');
const { chromium } = require('playwright');
const { ROOT, C, iconSvg, FONTS, starPattern, render } = require('./common.cjs');

// Optional arguments, for trying it on other material: <recording dir> <output dir>.
const RAW = path.resolve(process.argv[2] || path.join(ROOT, 'store/raw/video'));
const OUT = path.resolve(process.argv[3] || path.join(ROOT, 'store/video'));
const WORK = path.join(OUT, '.work');
const FINAL = path.join(OUT, 'learned-ayahs-media-playback.mp4');

const W = 1920, H = 1080, FPS = 30;
// The phone: its 720×1600 recording shown 450×1000, in a 14px bezel.
const SCREEN = { x: 236, y: 40, w: 450, h: 1000 }, BEZEL = 14;
const TITLE_S = 4, END_S = 5;
// From logging the tap to the app redrawing: injecting the touch and one frame.
const TAP_MS = 50;

/** What each step shows, keyed by the test's events (PlayStoreFgsVideo). */
const STEPS = [
  { at: 'start', list: 'Open the app', title: 'The user opens their learned ayahs', body: 'Surah Al-Mulk, ready to recite.' },
  { at: 'play', list: 'Tap Play', title: 'They tap Play', body: 'Recitation begins: A\'udhu, Bismillah, then each ayah, with the word being recited highlighted.' },
  { at: 'home', list: 'Leave the app', title: 'They leave the app', body: 'The foreground service keeps the recitation playing.' },
  { at: 'shade', list: 'Notification controls', title: 'Controls in the notification', body: 'They pause, then resume, from the media notification.' },
  { at: 'screen_off', list: 'Screen off', title: 'The screen turns off', body: 'Recitation carries on.' },
  { at: 'screen_on', list: 'Lock screen', title: 'The lock screen', body: 'The playing ayah, with its controls.' },
  { at: 'back_to_app', list: 'Back in the app', title: 'Back in the app', body: 'Still reciting, further into the surah.' },
];

const ffmpeg = (args) => execFileSync('ffmpeg', ['-hide_banner', '-loglevel', 'error', '-y', ...args], { stdio: 'inherit' });
const probeDuration = (file) => parseFloat(execFileSync('ffprobe', ['-v', 'error', '-show_entries', 'format=duration', '-of', 'csv=p=0', file]).toString());

const page = (body, extraCss = '') => `<!doctype html><html><head><meta charset="utf-8"><style>${FONTS}
  body { width: ${W}px; height: ${H}px; overflow: hidden; position: relative; color: ${C.text};
         background: radial-gradient(120% 120% at 15% 0%, #163A26 0%, ${C.bg} 60%); }
  .pattern { position: absolute; inset: 0; background-image: ${starPattern('rgba(69,212,131,0.06)', 120)};
             background-size: 120px 120px; -webkit-mask-image: linear-gradient(90deg, transparent 35%, #000 100%); }
  ${extraCss}</style></head><body><div class="pattern"></div>${body}</body></html>`;

const brand = `<div class="brand">${iconSvg(64)}<div><b>Learned Ayahs</b><span>Foreground service · media playback</span></div></div>`;
const brandCss = `
  .brand { position: absolute; left: 790px; top: 80px; display: flex; align-items: center; gap: 20px; }
  .brand svg { border-radius: 16px; box-shadow: 0 0 0 1px ${C.border}; }
  .brand b { display: block; font-size: 30px; font-weight: 800; letter-spacing: -0.5px; }
  .brand span { display: block; margin-top: 4px; font-size: 20px; font-weight: 600; color: ${C.green}; }`;

function stepHtml(index) {
  const step = STEPS[index];
  const list = STEPS.map((s, i) => `<li class="${i < index ? 'done' : i === index ? 'now' : ''}">
      <i>${i < index ? '✓' : i + 1}</i>${s.list}</li>`).join('');
  return page(`
    <div class="bezel"></div>
    ${brand}
    <div class="cap"><h1>${step.title}</h1><p>${step.body}</p></div>
    <ol>${list}</ol>`, `${brandCss}
    .bezel { position: absolute; left: ${SCREEN.x - BEZEL}px; top: ${SCREEN.y - BEZEL}px; width: ${SCREEN.w + 2 * BEZEL}px;
             height: ${SCREEN.h + 2 * BEZEL}px; border-radius: 62px; background: #050C08;
             box-shadow: 0 0 0 2px ${C.border}, 0 40px 100px rgba(0,0,0,0.55); }
    .cap { position: absolute; left: 790px; top: 250px; width: 1000px; }
    h1 { font-size: 66px; font-weight: 800; letter-spacing: -1.6px; line-height: 1.08; text-wrap: balance; }
    p { margin-top: 26px; font-size: 34px; line-height: 1.4; color: ${C.muted}; text-wrap: balance; }
    ol { position: absolute; left: 790px; bottom: 70px; list-style: none; padding: 0; display: grid; gap: 12px; }
    li { display: flex; align-items: center; gap: 16px; font-size: 24px; font-weight: 600; color: #4E6B5B; }
    li i { font-style: normal; width: 40px; height: 40px; border-radius: 50%; display: grid; place-items: center;
           font-size: 19px; border: 2px solid ${C.border}; }
    li.done { color: ${C.muted}; } li.done i { background: ${C.border}; color: ${C.green}; }
    li.now { color: ${C.text}; } li.now i { background: ${C.green}; border-color: ${C.green}; color: ${C.bg}; }`);
}

/** The bezel's inner corners, drawn over the recording so the screen has rounded corners. */
function cornersHtml() {
  return `<!doctype html><html><head><style>*{margin:0}body{background:transparent;width:${W}px;height:${H}px}
    div{position:absolute;left:${SCREEN.x}px;top:${SCREEN.y}px;width:${SCREEN.w}px;height:${SCREEN.h}px;
        border-radius:48px;box-shadow:0 0 0 ${BEZEL}px #050C08}</style></head>
    <body><div></div></body></html>`;
}

function screenOffHtml() {
  return `<!doctype html><html><head><meta charset="utf-8"><style>${FONTS}
    body { width: ${SCREEN.w}px; height: ${SCREEN.h}px; background: #000; color: ${C.muted};
           display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 26px; }
    b { font-size: 34px; color: ${C.text}; } span { font-size: 24px; }
    .wave { display: flex; gap: 8px; align-items: center; height: 60px; }
    .wave i { width: 10px; border-radius: 5px; background: ${C.green}; }</style></head><body>
    <div class="wave">${[22, 44, 60, 36, 52, 26, 40].map((h) => `<i style="height:${h}px"></i>`).join('')}</div>
    <b>Screen off</b><span>Recitation still playing</span></body></html>`;
}

function titleHtml() {
  return page(`<div class="t">${iconSvg(150)}<h1>Learned Ayahs</h1>
      <h2>Foreground service: media playback</h2>
      <p>Recitation that the user starts keeps playing when they leave the app or turn the screen off,
         and is controlled from the notification and the lock screen.</p></div>`, `
    .t { position: absolute; inset: 0; display: flex; flex-direction: column; align-items: center; justify-content: center; text-align: center; }
    .t svg { border-radius: 36px; box-shadow: 0 0 0 2px ${C.border}; margin-bottom: 36px; }
    h1 { font-size: 84px; font-weight: 800; letter-spacing: -2px; }
    h2 { margin-top: 14px; font-size: 40px; font-weight: 700; color: ${C.green}; }
    p { margin-top: 34px; width: 1180px; font-size: 32px; line-height: 1.45; color: ${C.muted}; text-wrap: balance; }`);
}

function endHtml() {
  const points = [
    'Runs only while recitation the user started is playing.',
    'Shows a media notification with Previous, Pause and Next.',
    'Leaves the foreground once playback is paused or finished.',
  ];
  return page(`<div class="e">${brand.replace('class="brand"', 'class="brand2"')}
      <h1>What the service does</h1><ul>${points.map((p) => `<li>${p}</li>`).join('')}</ul></div>`, `
    .e { position: absolute; left: 240px; top: 200px; width: 1440px; }
    .brand2 { display: flex; align-items: center; gap: 20px; margin-bottom: 56px; }
    .brand2 svg { border-radius: 16px; box-shadow: 0 0 0 1px ${C.border}; }
    .brand2 b { display: block; font-size: 30px; font-weight: 800; }
    .brand2 span { display: block; margin-top: 4px; font-size: 20px; font-weight: 600; color: ${C.green}; }
    h1 { font-size: 72px; font-weight: 800; letter-spacing: -1.8px; }
    ul { margin-top: 40px; padding: 0; list-style: none; display: grid; gap: 26px; }
    li { font-size: 38px; color: ${C.muted}; padding-left: 56px; position: relative; }
    li::before { content: '✓'; position: absolute; left: 0; color: ${C.green}; font-weight: 800; }`);
}

/**
 * The stretches of playback in the timeline: which clip, from where in it, when, for how long.
 * A stretch ends when the clip changes, playback stops, or the position jumps (a pause or seek
 * the samples happened to straddle) — not merely because two samples were far apart.
 */
function playbackRuns(samples) {
  const runs = [];
  let run = null;
  const close = () => { if (run) runs.push(run); run = null; };
  let previous = null;
  for (const s of samples) {
    const key = s.playing ? (s.intro || (s.globalId ? String(s.globalId) : null)) : null;
    if (!key || (run && key !== run.key)) close();
    if (key && !run) {
      // Fresh: it followed another clip without a pause, so it started from the beginning.
      run = { key, first: s.t, last: s.t, clock: Infinity, fresh: previous !== null && previous !== key };
    }
    previous = key;
    if (!key) continue;
    // The reported position trails the true one by up to a progress tick, so the smallest
    // (time − position) is the clip's true start on the test's clock. For a while after a clip
    // changes, the player still reports how far the previous one had got; a fresh clip cannot
    // be further in than the time since it started, which gives those readings away.
    const stale = run.fresh && s.positionMs > s.t - run.first + 600;
    if (s.positionMs > 0 && !stale) {
      const clock = s.t - s.positionMs;
      if (Number.isFinite(run.clock) && Math.abs(clock - run.clock) > 1000) {
        run.last = s.t - 100; // seamless: the earlier stretch plays right up to this sample
        close();
        run = { key, first: s.t, last: s.t, clock };
      }
      run.clock = Math.min(run.clock, clock);
    }
    run.last = s.t;
  }
  close();
  return runs.map((r) => {
    const clock = Number.isFinite(r.clock) ? r.clock : r.first;
    return { key: r.key, start: r.first, end: r.last + 100, offset: Math.max(0, r.first - clock) };
  });
}

function buildSoundtrack(timeline, lagMs, duration, out) {
  const runs = playbackRuns(timeline.samples).filter((r) => timeline.clips[r.key]);
  for (const r of runs) {
    const file = path.join(RAW, 'audio', path.basename(timeline.clips[r.key]));
    if (!fs.existsSync(file)) throw new Error(`${file} is missing: the recording is incomplete, so re-run store-video.yml`);
  }
  if (runs.length === 0) {
    ffmpeg(['-f', 'lavfi', '-t', String(duration), '-i', 'anullsrc=r=48000:cl=stereo', out]);
    return runs;
  }
  const inputs = [], filters = [];
  runs.forEach((r, i) => {
    const file = path.join(RAW, 'audio', path.basename(timeline.clips[r.key]));
    const dur = (r.end - r.start) / 1000;
    inputs.push('-ss', (r.offset / 1000).toFixed(3), '-t', dur.toFixed(3), '-i', file);
    const delay = Math.max(0, Math.round(r.start - lagMs));
    filters.push(`[${i}:a]aresample=48000,aformat=channel_layouts=stereo,` +
      `afade=t=in:d=0.02,afade=t=out:st=${Math.max(0, dur - 0.04).toFixed(3)}:d=0.04,adelay=${delay}|${delay}[a${i}]`);
  });
  filters.push(`${runs.map((_, i) => `[a${i}]`).join('')}amix=inputs=${runs.length}:normalize=0:dropout_transition=0,` +
    `apad,atrim=0:${duration.toFixed(3)}[out]`);
  ffmpeg([...inputs, '-filter_complex', filters.join(';'), '-map', '[out]', out]);
  return runs;
}

(async () => {
  const timeline = JSON.parse(fs.readFileSync(path.join(RAW, 'timeline.json'), 'utf8'));
  const screen = path.join(RAW, 'screen.mp4');
  fs.mkdirSync(WORK, { recursive: true });

  const at = (name) => timeline.events.find((e) => e.event === name)?.t;
  const lagMs = recordingLagMs(timeline, screen);
  const mainDuration = Math.min(probeDuration(screen), ((at('end') ?? 1e9) - lagMs) / 1000 + 0.3);
  console.log(`Recording: ${mainDuration.toFixed(1)}s used, starts ${lagMs}ms after the test's clock`);

  console.log('Rendering the frames:');
  const browser = await chromium.launch();
  for (let i = 0; i < STEPS.length; i++) await render(browser, stepHtml(i), path.join(WORK, `step${i}.png`), W, H);
  await render(browser, titleHtml(), path.join(WORK, 'title.png'), W, H);
  await render(browser, endHtml(), path.join(WORK, 'end.png'), W, H);
  await render(browser, screenOffHtml(), path.join(WORK, 'screen-off.png'), SCREEN.w, SCREEN.h);
  const corners = await browser.newPage({ viewport: { width: W, height: H } });
  await corners.setContent(cornersHtml());
  await corners.screenshot({ path: path.join(WORK, 'corners.png'), omitBackground: true });
  await browser.close();

  // The caption track: each step from its event to the next.
  const starts = STEPS.map((s) => (s.at === 'start' ? 0 : Math.max(0, ((at(s.at) ?? 0) - lagMs) / 1000)));
  const concat = STEPS.map((_, i) => {
    const end = i + 1 < STEPS.length ? starts[i + 1] : mainDuration;
    return `file 'step${i}.png'\nduration ${Math.max(0.04, end - starts[i]).toFixed(3)}`;
  }).join('\n') + `\nfile 'step${STEPS.length - 1}.png'\n`;
  fs.writeFileSync(path.join(WORK, 'steps.txt'), concat);

  console.log('Rebuilding the soundtrack from the playback log:');
  const runs = buildSoundtrack(timeline, lagMs, mainDuration, path.join(WORK, 'main.wav'));
  for (const r of runs) {
    console.log(`  ${((r.start - lagMs) / 1000).toFixed(1).padStart(5)}s  ${r.key.padEnd(14)} from ${(r.offset / 1000).toFixed(1)}s for ${((r.end - r.start) / 1000).toFixed(1)}s`);
  }

  const off = [((at('screen_off') ?? 0) - lagMs) / 1000 + 0.2, ((at('screen_on') ?? 0) - lagMs) / 1000 + 0.3];
  console.log('Encoding:');
  ffmpeg([
    '-loop', '1', '-framerate', String(FPS), '-t', String(TITLE_S), '-i', path.join(WORK, 'title.png'),
    '-f', 'concat', '-safe', '0', '-i', path.join(WORK, 'steps.txt'),
    '-i', screen,
    '-loop', '1', '-i', path.join(WORK, 'corners.png'),
    '-loop', '1', '-i', path.join(WORK, 'screen-off.png'),
    '-loop', '1', '-framerate', String(FPS), '-t', String(END_S), '-i', path.join(WORK, 'end.png'),
    '-i', path.join(WORK, 'main.wav'),
    '-f', 'lavfi', '-t', String(TITLE_S), '-i', 'anullsrc=r=48000:cl=stereo',
    '-f', 'lavfi', '-t', String(END_S), '-i', 'anullsrc=r=48000:cl=stereo',
    '-filter_complex', [
      `[0:v]fps=${FPS},format=yuv420p,setsar=1,fade=t=out:st=${TITLE_S - 0.4}:d=0.4[title]`,
      `[1:v]fps=${FPS},scale=${W}:${H},setsar=1[bg]`,
      `[2:v]fps=${FPS},scale=${SCREEN.w}:${SCREEN.h},setsar=1[phone]`,
      `[bg][phone]overlay=${SCREEN.x}:${SCREEN.y}:eof_action=repeat[v1]`,
      `[v1][3:v]overlay=0:0:shortest=1[v2]`,
      `[v2][4:v]overlay=${SCREEN.x}:${SCREEN.y}:shortest=1:enable='between(t,${off[0].toFixed(2)},${off[1].toFixed(2)})',` +
        `trim=0:${mainDuration.toFixed(3)},setpts=PTS-STARTPTS,format=yuv420p,fade=t=in:d=0.4[main]`,
      `[5:v]fps=${FPS},format=yuv420p,setsar=1,fade=t=in:d=0.4[end]`,
      `[title][7:a][main][6:a][end][8:a]concat=n=3:v=1:a=1[v][a]`,
    ].join(';'),
    '-map', '[v]', '-map', '[a]',
    '-c:v', 'libx264', '-preset', 'medium', '-crf', '20', '-pix_fmt', 'yuv420p', '-r', String(FPS),
    '-c:a', 'aac', '-b:a', '160k', '-movflags', '+faststart', FINAL,
  ]);
  console.log(`  ${path.relative(ROOT, FINAL)}  ${probeDuration(FINAL).toFixed(1)}s`);
})().catch((e) => { console.error(e); process.exit(1); });

/**
 * How late the recording started relative to the test's clock, in ms. The recorder writes a frame
 * only when the screen changes, and tapping Play changes it at once (Play becomes Pause), so the
 * first frame after the tap pins the two clocks together — to within a frame or two. Zero if
 * there is no such frame.
 */
function recordingLagMs(timeline, screen) {
  const play = timeline.events.find((e) => e.event === 'play');
  if (!play) return 0;
  const out = execFileSync('ffprobe', ['-v', 'error', '-select_streams', 'v', '-show_entries', 'frame=pts_time',
    '-of', 'csv=p=0', screen]).toString();
  const frames = out.split(/\s+/).filter(Boolean).map((t) => parseFloat(t) * 1000);
  const first = frames.find((t) => t > 100 && t > play.t - 1500);
  if (first === undefined) return 0;
  const lag = Math.round(play.t + TAP_MS - first);
  return Math.abs(lag) > 2000 ? 0 : lag;
}
