// Ayah reciters (whole-ayah MP3s from everyayah.com) and word reciters (isolated word clips).
// Port of data/Reciter.kt and data/WordReciter.kt.
//
// The ayah list is deliberately short: word highlighting is only honest with real per-word
// timings for that exact recording, so a reciter ships only when QUL has validated segments
// for it *and* everyayah's audio is the audio those timings describe. There is no estimated
// timing fallback anywhere in this app.

export const RECITERS = [
  {
    key: 'MAHER_AL_MUAIQLY',
    displayName: 'Maher Al Muaiqly',
    folder: 'MaherAlMuaiqly128kbps',
    timingsAsset: 'word_timings_maher.json',
  },
  {
    key: 'ABDUL_BASIT',
    displayName: 'Abdul Basit (Murattal)',
    folder: 'Abdul_Basit_Murattal_192kbps',
    timingsAsset: 'word_timings_abdulbasit_murattal.json',
  },
  {
    key: 'AL_HUSARY',
    displayName: 'Mahmoud Khalil Al-Husary',
    folder: 'Husary_128kbps',
    timingsAsset: 'word_timings_husary.json',
  },
  {
    key: 'AS_SUDAIS',
    displayName: 'Abdurrahman As-Sudais',
    folder: 'Abdurrahmaan_As-Sudais_192kbps',
    timingsAsset: 'word_timings_sudais.json',
  },
  {
    key: 'ASH_SHURAYM',
    displayName: 'Saud Ash-Shuraym',
    folder: 'Saood_ash-Shuraym_128kbps',
    timingsAsset: 'word_timings_shuraym.json',
  },
  // 1.13: pinned by .setup/pin_qul_ids.py and audited by timing-audit.yml (see data/Reciter.kt).
  {
    key: 'ABDUL_BASIT_MUJAWWAD',
    displayName: 'Abdul Basit (Mujawwad)',
    folder: 'Abdul_Basit_Mujawwad_128kbps',
    timingsAsset: 'word_timings_abdulbasit_mujawwad.json',
  },
  {
    key: 'AL_HUSARY_MUALLIM',
    displayName: 'Mahmoud Khalil Al-Husary (Muallim)',
    folder: 'Husary_Muallim_128kbps',
    timingsAsset: 'word_timings_husary_muallim.json',
  },
  {
    key: 'AL_HUSARY_MUJAWWAD',
    displayName: 'Mahmoud Khalil Al-Husary (Mujawwad)',
    folder: 'Husary_128kbps_Mujawwad',
    timingsAsset: 'word_timings_husary_mujawwad.json',
  },
  {
    key: 'AL_GHAMDI',
    displayName: 'Saad Al-Ghamdi',
    folder: 'Ghamadi_40kbps',
    timingsAsset: 'word_timings_ghamadi.json',
  },
  {
    key: 'AD_DUSSARY',
    displayName: 'Yasser Ad-Dussary',
    folder: 'Yasser_Ad-Dussary_128kbps',
    timingsAsset: 'word_timings_dussary.json',
  },
  {
    key: 'AL_TUNAIJI',
    displayName: 'Khalifa Al-Tunaiji',
    folder: 'khalefa_al_tunaiji_64kbps',
    timingsAsset: 'word_timings_tunaiji.json',
  },
  {
    key: 'AL_MINSHAWI',
    displayName: 'Mohamed Siddiq Al-Minshawi (Murattal)',
    folder: 'Minshawy_Murattal_128kbps',
    timingsAsset: 'word_timings_minshawi_murattal.json',
  },
  {
    key: 'ASH_SHATRI',
    displayName: 'Abu Bakr Ash-Shatri',
    folder: 'Abu_Bakr_Ash-Shaatree_128kbps',
    timingsAsset: 'word_timings_shatri.json',
  },
];

export const DEFAULT_RECITER = RECITERS[0];

export function reciterFromKey(key) {
  return RECITERS.find((r) => r.key === key) ?? DEFAULT_RECITER;
}

/** Word reciters have a complete per-word file set; never mixed with ayah reciters. */
export const WORD_RECITERS = [
  { key: 'QURAN_COM', displayName: 'Word pronunciation (Quran.com)', folder: 'quran_com_wbw' },
];

export const DEFAULT_WORD_RECITER = WORD_RECITERS[0];

const pad3 = (n) => String(n).padStart(3, '0');

/** Quran.com's word clips use a 1-based word index; `wordIndex` here is 0-based over content words. */
export const wordClipFilename = (surah, ayah, wordIndex) =>
  `${pad3(surah)}_${pad3(ayah)}_${pad3(wordIndex + 1)}.mp3`;

export const REMOTE = {
  everyayah: 'https://everyayah.com/data',
  wordClips: 'https://audio.qurancdn.com/wbw',
};

/**
 * Audio URLs. With `proxy` (the default when the app is served by server.py) audio comes from
 * the same origin, which the server fetches once and caches on disk; that also lets the service
 * worker keep it for offline use. Without it, audio streams straight from the upstream hosts.
 */
export function audioUrls({ proxy }) {
  const base = proxy ? 'audio/everyayah' : REMOTE.everyayah;
  const wbw = proxy ? 'audio/wbw' : REMOTE.wordClips;
  return {
    ayah: (reciter, surah, ayah) => `${base}/${reciter.folder}/${pad3(surah)}${pad3(ayah)}.mp3`,
    audhu: () => `${base}/audhubillah.mp3`,
    bismillah: () => `${base}/bismillah.mp3`,
    word: (surah, ayah, wordIndex) => `${wbw}/${wordClipFilename(surah, ayah, wordIndex)}`,
  };
}
