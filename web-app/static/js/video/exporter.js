// Makes a video of a range of ayahs in the browser: fetches the chosen reciter's clips, builds the
// soundtrack, draws every frame and encodes the file. Port of the Android app's VideoExporter,
// AudioPipeline and VideoEncoder, on WebCodecs. Nothing is uploaded.
//
// MP4 (H.264 + AAC) where the browser can encode it (Chrome, Edge, Safari), otherwise WebM
// (VP9 + Opus), which YouTube and most players also take.

import { NO_BISMILLAH_INTRO_SURAHS, surahAyahToGlobal, surahName, verseCount } from '../core/quran.js';
import { audioUrls } from '../core/reciters.js';
import { loadText, loadTimings, loadTranslations, segmentsSync, wordsSync } from '../services/quranData.js';
import { ArrayBufferTarget as Mp4Target, Muxer as Mp4Muxer } from '../lib/mp4-muxer.mjs';
import { ArrayBufferTarget as WebmTarget, Muxer as WebmMuxer } from '../lib/webm-muxer.mjs';
import {
  isPhoto, loadPhoto, loadThemeMap, photoCredit, photoFor, photoSubjects, subjectFor, themeKeySync, translation,
} from './data.js';
import { planBackgrounds, buildTimeline } from './plan.js';
import { ARABIC_FONT, FrameRenderer, referenceOf } from './renderer.js';
import { FALLBACK } from './themes.js';
import { photoSpec, variant } from './scenes.js';

export const ASPECTS = [
  { key: 'PORTRAIT', label: 'Shorts, Reels, TikTok', ratio: '9:16', width: 1080, height: 1920 },
  { key: 'TALL', label: 'Feed post', ratio: '4:5', width: 1080, height: 1350 },
  { key: 'SQUARE', label: 'Square', ratio: '1:1', width: 1080, height: 1080 },
  { key: 'LANDSCAPE', label: 'YouTube', ratio: '16:9', width: 1920, height: 1080 },
];
export const aspectFromKey = (key) => ASPECTS.find((a) => a.key === key) ?? ASPECTS[0];

export const FPS = 30;
const SAMPLE_RATE = 48000;
const AUDIO_BITRATE = 128_000;
/** Long enough for every surah but the longest; beyond it, the file is too big to share. */
export const MAX_DURATION_MS = 30 * 60 * 1000;

const AUDHU_WORDS = [
  ['أَعُوذُ', 'I seek refuge'], ['بِٱللَّهِ', 'in Allah'], ['مِنَ', 'from'], ['ٱلشَّيْطَٰنِ', 'Satan'], ['ٱلرَّجِيمِ', 'the accursed'],
];
const AUDHU_TRANSLATION = 'I seek refuge in Allah from Satan, the accursed.';

/**
 * Everything the user chose for one video: {surah, fromAyah, toAyah, reciter, aspect (key),
 * wordByWord, fullTranslation, opening, themeKey (null: matched), photos, credits}.
 */
export function clipsOf(request) {
  const list = [];
  if (request.opening) list.push({ kind: 'AUDHU', surah: request.surah, ayah: 0 });
  if (request.opening && request.fromAyah === 1 && !NO_BISMILLAH_INTRO_SURAHS.has(request.surah)) list.push({ kind: 'BISMILLAH', surah: request.surah, ayah: 0 });
  for (let a = request.fromAyah; a <= request.toAyah; a++) list.push({ kind: 'AYAH', surah: request.surah, ayah: a });
  return list;
}

export function validate(request) {
  const count = verseCount(request.surah);
  if (!count) throw new Error(`No surah ${request.surah}`);
  if (request.fromAyah < 1 || request.toAyah > count || request.toAyah < request.fromAyah) throw new Error(`No ayahs ${request.fromAyah}-${request.toAyah} in surah ${request.surah}`);
}

/** Which formats this browser can make, best first. */
export async function supportedFormat({ width, height }) {
  if (typeof VideoEncoder === 'undefined' || typeof AudioEncoder === 'undefined') return null;
  const video = async (codec) => (await VideoEncoder.isConfigSupported({ codec, width, height, bitrate: 6e6, framerate: FPS }).catch(() => ({}))).supported;
  const audio = async (codec) => (await AudioEncoder.isConfigSupported({ codec, sampleRate: SAMPLE_RATE, numberOfChannels: 2, bitrate: AUDIO_BITRATE }).catch(() => ({}))).supported;
  for (const avc of ['avc1.640033', 'avc1.640028', 'avc1.4d0033', 'avc1.42003e']) {
    if ((await video(avc)) && (await audio('mp4a.40.2'))) return { container: 'mp4', video: avc, audio: 'mp4a.40.2', ext: 'mp4', mime: 'video/mp4' };
  }
  if ((await video('vp09.00.41.08')) && (await audio('opus'))) return { container: 'webm', video: 'vp09.00.41.08', audio: 'opus', ext: 'webm', mime: 'video/webm' };
  if ((await video('vp8')) && (await audio('opus'))) return { container: 'webm', video: 'vp8', audio: 'opus', ext: 'webm', mime: 'video/webm' };
  return null;
}

export class VideoExporter {
  /**
   * [proxy]: fetch audio through the app's own server (server.py); without it straight from
   * everyayah.com, which allows that (it sends Access-Control-Allow-Origin: *).
   */
  constructor({ proxy }) {
    this.urls = audioUrls({ proxy });
    this.proxy = proxy;
  }

  async prepareText(request) {
    await Promise.all([loadText(), loadTranslations(), loadTimings(request.reciter), loadThemeMap()]);
  }

  async textFor(request, clip) {
    if (clip.kind === 'AUDHU') {
      return { words: AUDHU_WORDS.map(([arabic, gloss], i) => ({ arabic, gloss, contentIndex: i })), segments: null, translation: AUDHU_TRANSLATION, reference: referenceOf(clip, surahName) };
    }
    if (clip.kind === 'BISMILLAH') {
      const words = layoutWords(1, 1).filter((w) => w.contentIndex >= 0);
      return { words, segments: null, translation: await translation(1, 1), reference: referenceOf(clip, surahName) };
    }
    return {
      words: layoutWords(clip.surah, clip.ayah),
      segments: segmentsSync(request.reciter, clip.surah, clip.ayah),
      translation: await translation(clip.surah, clip.ayah),
      reference: referenceOf(clip, surahName),
    };
  }

  /** Background slots for [timeline], and the scenes and credits they need (photos loaded). */
  async backgrounds(request, timeline) {
    const map = await loadThemeMap();
    const subjects = await photoSubjects();
    const slots = planBackgrounds(
      timeline.clips,
      request.themeKey,
      (s, a) => {
        const drawn = themeKeySync(map, s, a);
        return request.photos ? subjectFor(subjects, drawn)?.themeKey ?? drawn : drawn;
      },
      (s, a) => surahAyahToGlobal(s, a),
      FALLBACK,
    );
    const scenes = new Map();
    for (const slot of slots) {
      const k = `${slot.themeKey}#${slot.variant}`;
      if (scenes.has(k)) continue;
      if (isPhoto(slot.themeKey)) {
        const photo = photoFor(subjects, slot.themeKey, slot.variant);
        if (!photo) throw new Error("That background photo isn't available.");
        scenes.set(k, { spec: photoSpec(photo.id, await loadPhoto(photo)), credit: photoCredit(photo) });
      } else {
        scenes.set(k, { spec: variant(slot.themeKey, slot.variant), credit: null });
      }
    }
    const sceneFor = (key, v) => scenes.get(`${key}#${v}`)?.spec ?? variant(null, v);
    const creditFor = (key, v) => scenes.get(`${key}#${v}`)?.credit ?? null;
    return { slots, sceneFor, creditFor };
  }

  /**
   * A frame of the first ayah of [request], [width] pixels wide, [atMs] into its recitation
   * (null: an example word a third of the way in). Returns a canvas.
   */
  async still(request, width, atMs = null) {
    validate(request);
    await this.prepareText(request);
    await fontReady();
    const aspect = aspectFromKey(request.aspect);
    const height = Math.round((width * aspect.height) / aspect.width);
    const first = { kind: 'AYAH', surah: request.surah, ayah: request.fromAyah };
    const text = await this.textFor(request, first);
    const durationMs = Math.max(text.segments?.length ? text.segments[text.segments.length - 1][1] + 300 : 5000, (atMs ?? 0) + 1);
    const timeline = buildTimeline([{ ...first, durationMs }]);
    const clip = timeline.clips[0];
    const bg = await this.backgrounds(request, timeline);
    const canvas = makeCanvas(width, height);
    const renderer = new FrameRenderer({
      width, height, request: { ...request, reciter: request.reciter }, timeline, slots: bg.slots,
      textFor: () => text, sceneFor: bg.sceneFor, creditFor: bg.creditFor,
    });
    const at = atMs ?? (text.segments?.length ? text.segments[Math.floor(text.segments.length / 3)][0] : 0);
    renderer.draw(canvas.getContext('2d'), clip.startMs + at);
    renderer.release();
    return canvas;
  }

  /** Makes the video. Resolves to {blob, format}. onProgress({stage, fraction}). */
  async export(request, { scale = 1, fps = FPS, signal, onProgress = () => {} } = {}) {
    validate(request);
    const check = () => {
      if (signal?.aborted) throw new DOMException('Cancelled', 'AbortError');
    };
    const aspect = aspectFromKey(request.aspect);
    const width = even(aspect.width * scale);
    const height = even(aspect.height * scale);
    const format = await supportedFormat({ width, height });
    if (!format) throw new Error('This browser can\'t make videos. Use a recent Chrome, Edge or Safari.');
    await this.prepareText(request);
    await fontReady();

    // 1. The recitation, decoded and laid on the timeline.
    const plan = clipsOf(request);
    const decoded = [];
    for (let i = 0; i < plan.length; i++) {
      check();
      onProgress({ stage: 'DOWNLOADING', fraction: (0.15 * i) / plan.length });
      decoded.push(await this.decode(this.urlOf(request, plan[i])));
    }
    const timeline = buildTimeline(plan.map((c, i) => ({ ...c, durationMs: Math.round((decoded[i].length / SAMPLE_RATE) * 1000) })));
    if (timeline.totalMs > MAX_DURATION_MS) throw new Error(`That's over ${MAX_DURATION_MS / 60000} minutes of recitation. Choose fewer ayahs.`);
    onProgress({ stage: 'AUDIO', fraction: 0.15 });
    const mix = mixdown(timeline, decoded);

    // 2. The pictures.
    const bg = await this.backgrounds(request, timeline);
    const texts = new Map();
    for (const clip of timeline.clips) texts.set(clip, await this.textFor(request, clip));
    const canvas = makeCanvas(width, height);
    const ctx = canvas.getContext('2d', { alpha: false });
    const renderer = new FrameRenderer({ width, height, request, timeline, slots: bg.slots, textFor: (c) => texts.get(c), sceneFor: bg.sceneFor, creditFor: bg.creditFor });

    // 3. Encode and mux.
    const target = format.container === 'mp4' ? new Mp4Target() : new WebmTarget();
    const muxer = format.container === 'mp4'
      ? new Mp4Muxer({ target, fastStart: 'in-memory', video: { codec: 'avc', width, height, frameRate: fps }, audio: { codec: 'aac', numberOfChannels: 2, sampleRate: SAMPLE_RATE } })
      : new WebmMuxer({ target, video: { codec: format.video === 'vp8' ? 'V_VP8' : 'V_VP9', width, height, frameRate: fps }, audio: { codec: 'A_OPUS', numberOfChannels: 2, sampleRate: SAMPLE_RATE } });
    let failure = null;
    const videoEncoder = new VideoEncoder({ output: (chunk, meta) => muxer.addVideoChunk(chunk, meta), error: (e) => { failure = e; } });
    videoEncoder.configure({ codec: format.video, width, height, bitrate: Math.round(width * height * fps * 0.07), framerate: fps, ...(format.container === 'mp4' ? { avc: { format: 'avc' } } : {}) });
    const audioEncoder = new AudioEncoder({ output: (chunk, meta) => muxer.addAudioChunk(chunk, meta), error: (e) => { failure = e; } });
    audioEncoder.configure({ codec: format.audio, sampleRate: SAMPLE_RATE, numberOfChannels: 2, bitrate: AUDIO_BITRATE });

    try {
      encodeAudio(audioEncoder, mix);
      const frames = Math.ceil((timeline.totalMs * fps) / 1000);
      for (let i = 0; i < frames; i++) {
        check();
        if (failure) throw failure;
        const ms = (i * 1000) / fps;
        renderer.draw(ctx, ms);
        const frame = new VideoFrame(canvas, { timestamp: Math.round(ms * 1000), duration: Math.round(1e6 / fps) });
        videoEncoder.encode(frame, { keyFrame: i % (fps * 2) === 0 });
        frame.close();
        if (videoEncoder.encodeQueueSize > 6) await drain(videoEncoder);
        if (i % fps === 0) {
          onProgress({ stage: 'VIDEO', fraction: 0.2 + (0.8 * i) / frames });
          await new Promise((r) => setTimeout(r, 0)); // let the page breathe
        }
      }
      await videoEncoder.flush();
      await audioEncoder.flush();
      if (failure) throw failure;
      muxer.finalize();
      onProgress({ stage: 'VIDEO', fraction: 1 });
      return { blob: new Blob([target.buffer], { type: format.mime }), format, durationMs: timeline.totalMs };
    } finally {
      renderer.release();
      if (videoEncoder.state !== 'closed') videoEncoder.close();
      if (audioEncoder.state !== 'closed') audioEncoder.close();
    }
  }

  urlOf(request, clip) {
    if (clip.kind === 'AUDHU') return this.urls.audhu();
    if (clip.kind === 'BISMILLAH') return this.urls.bismillah();
    return this.urls.ayah(request.reciter, clip.surah, clip.ayah);
  }

  /** One clip, decoded to 48 kHz stereo. */
  async decode(url) {
    let response;
    try {
      response = await fetch(url);
    } catch {
      throw new Error("Couldn't download the recitation. Check your connection and try again.");
    }
    if (!response.ok) throw new Error(`Couldn't download the recitation (${response.status}).`);
    const data = await response.arrayBuffer();
    const ctx = new OfflineAudioContext(2, 1, SAMPLE_RATE);
    const buffer = await ctx.decodeAudioData(data);
    const left = buffer.getChannelData(0);
    const right = buffer.numberOfChannels > 1 ? buffer.getChannelData(1) : left;
    return { left: new Float32Array(left), right: new Float32Array(right), length: buffer.length };
  }
}

function layoutWords(surah, ayah) {
  let index = 0;
  return wordsSync(surah, ayah).map((w) => (w.isEnd ? { arabic: w.text, gloss: null, contentIndex: -1 } : { arabic: w.text, gloss: w.translation, contentIndex: index++ }));
}

function mixdown(timeline, decoded) {
  const total = Math.ceil((timeline.totalMs / 1000) * SAMPLE_RATE);
  const left = new Float32Array(total);
  const right = new Float32Array(total);
  timeline.clips.forEach((clip, i) => {
    const at = Math.round((clip.startMs / 1000) * SAMPLE_RATE);
    const d = decoded[i];
    left.set(d.left.subarray(0, Math.min(d.length, total - at)), at);
    right.set(d.right.subarray(0, Math.min(d.length, total - at)), at);
  });
  return { left, right, length: total };
}

function encodeAudio(encoder, mix) {
  const chunk = 4800; // 100 ms
  for (let start = 0; start < mix.length; start += chunk) {
    const n = Math.min(chunk, mix.length - start);
    const planar = new Float32Array(n * 2);
    planar.set(mix.left.subarray(start, start + n), 0);
    planar.set(mix.right.subarray(start, start + n), n);
    const data = new AudioData({ format: 'f32-planar', sampleRate: SAMPLE_RATE, numberOfFrames: n, numberOfChannels: 2, timestamp: Math.round((start / SAMPLE_RATE) * 1e6), data: planar });
    encoder.encode(data);
    data.close();
  }
}

function drain(encoder) {
  return new Promise((resolve) => {
    const done = () => (encoder.encodeQueueSize <= 2 ? resolve() : setTimeout(done, 5));
    if ('ondequeue' in encoder) encoder.addEventListener('dequeue', () => done(), { once: true });
    setTimeout(done, 5);
  });
}

function makeCanvas(width, height) {
  if (typeof OffscreenCanvas !== 'undefined') return new OffscreenCanvas(width, height);
  return Object.assign(document.createElement('canvas'), { width, height });
}

const even = (v) => Math.floor(v / 2) * 2;

async function fontReady() {
  if (!document.fonts) return;
  await Promise.all([document.fonts.load(`80px ${ARABIC_FONT}`, 'بِسْمِ'), document.fonts.ready]).catch(() => {});
}

/** The text shared with a video or picture: what it is, then its sources. */
export function caption(request, reciterName) {
  const range = request.fromAyah === request.toAyah ? `${request.fromAyah}` : `${request.fromAyah}-${request.toAyah}`;
  const lines = [`${surahName(request.surah)} ${request.surah}:${range} · ${reciterName}`, '', `Recitation: ${reciterName} (everyayah.com)`];
  if (request.fullTranslation) lines.push('Translation: Saheeh International (tanzil.net)');
  if (request.wordByWord) lines.push('Word meanings: Quran.com');
  if (request.photos || isPhoto(request.themeKey)) lines.push('Background photos: Wikimedia Commons (CC0)');
  lines.push('Made with Learned Ayahs');
  return lines.join('\n');
}

export function fileName(request, ext) {
  const p = (n) => String(n).padStart(3, '0');
  return `LearnedAyahs_${p(request.surah)}_${p(request.fromAyah)}-${p(request.toAyah)}_${aspectFromKey(request.aspect).ratio.replace(':', 'x')}.${ext}`;
}
