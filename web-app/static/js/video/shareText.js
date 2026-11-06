// An ayah, or a run of them, as plain text: the Arabic, then the chosen translation, then the
// surah:ayah reference. (The parameter is still called `english`; it is whichever edition is chosen.) Either language can be left out. Nothing else — no reciter credit.

import { toast } from '../lib/dom.js';
import { surahName } from '../core/quran.js';
import { contentWords, loadText, wordsSync } from '../services/quranData.js';
import { translation } from './data.js';

export function formatShareText(name, surah, ayahs, arabic, english) {
  if (!ayahs.length) throw new Error('Choose at least one ayah');
  if (!arabic && !english) throw new Error('Share at least the Arabic or the translation');
  const body = ayahs.map((ayah) => {
    const lines = [];
    if (arabic && ayah.arabic) lines.push(ayah.arabic);
    if (english && ayah.english) lines.push(ayah.english);
    return lines.join('\n');
  }).join('\n\n').trim();
  const numbers = ayahs.map((ayah) => ayah.number);
  const range = numbers.length === 1 ? `${numbers[0]}` : `${numbers[0]}-${numbers[numbers.length - 1]}`;
  const reference = `${name} ${surah}:${range}`;
  return body ? `${body}\n\n${reference}` : reference;
}

/** The ayahs [from]..[to] of [surah], from the bundled text. */
export async function shareTextOf(surah, from, to, arabic, english) {
  if (arabic) await loadText();
  const ayahs = await Promise.all(Array.from({ length: to - from + 1 }, async (_, i) => {
    const number = from + i;
    const words = arabic ? contentWords(wordsSync(surah, number)).map((w) => w.text).join(' ') : '';
    if (arabic && !words) throw new Error('The Arabic text is unavailable. Please try again.');
    const translated = english ? await translation(surah, number) : null;
    if (english && !translated?.trim()) throw new Error('The translation is unavailable. Please try again.');
    return { number, arabic: words, english: translated };
  }));
  return formatShareText(surahName(surah), surah, ayahs, arabic, english);
}

/** The system share sheet, or the clipboard when this browser can't share plain text. */
export async function sharePlain(text) {
  if (navigator.share) {
    try {
      await navigator.share({ text });
      return 'shared';
    } catch (e) {
      if (e?.name === 'AbortError') return 'cancelled';
    }
  }
  return copyPlain(text);
}

/**
 * Explicit copy is available even on browsers with a native share sheet. [text] may be a
 * promise: it is handed to the clipboard straight away, inside the tap, so Safari still counts
 * the copy as user-initiated while the words load. A failure to produce the text is thrown
 * back to the caller to report; only a clipboard failure is reported here.
 */
export async function copyPlain(text) {
  const pending = Promise.resolve(text);
  let failure = null;
  pending.catch((e) => { failure = e; });
  try {
    if (typeof text !== 'string' && typeof ClipboardItem !== 'undefined' && navigator.clipboard?.write) {
      const blob = pending.then((t) => new Blob([t], { type: 'text/plain' }));
      await navigator.clipboard.write([new ClipboardItem({ 'text/plain': blob })]);
    } else {
      await navigator.clipboard.writeText(await pending);
    }
    toast('Text copied.');
    return 'copied';
  } catch (e) {
    await pending.catch(() => {});
    if (failure) throw failure;
    toast("The text couldn't be copied.", { error: true });
    return 'failed';
  }
}
