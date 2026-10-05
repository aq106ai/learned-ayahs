// Qur'an constants and global-ayah-id math. Port of the Android app's QuranConstants,
// AyahMapping, SurahNames and QueueBuilder (android-app-quran-player/.../data/QuranConstants.kt).
//
// Ayahs are identified by a 1-based "global id" across the whole Qur'an (1..6236). Audio files
// follow everyayah.com's SSSAAA.mp3 naming.

export const VERSE_COUNTS = [
  7, 286, 200, 176, 120, 165, 206, 75, 129, 109, 123, 111, 43, 52, 99, 128, 111, 110,
  98, 135, 112, 78, 118, 64, 77, 227, 93, 88, 69, 60, 34, 30, 73, 54, 45, 83, 182, 88,
  75, 85, 54, 53, 89, 59, 37, 35, 38, 29, 18, 45, 60, 49, 62, 55, 78, 96, 29, 22, 24,
  13, 14, 11, 11, 18, 12, 12, 30, 52, 52, 44, 28, 28, 20, 56, 40, 31, 50, 40, 46, 42,
  29, 19, 36, 25, 22, 17, 19, 26, 30, 20, 15, 21, 11, 8, 8, 19, 5, 8, 8, 11, 11, 8,
  3, 9, 5, 4, 7, 3, 6, 3, 5, 4, 5, 6,
];

export const SURAH_COUNT = 114;
export const TOTAL_AYAHS = 6236;

/**
 * Surahs whose intro skips the standalone Bismillah clip: surah 9 (At-Tawbah) is recited without
 * it, and surah 1 counts the Bismillah as its own ayah 1, so the clip would say it twice.
 */
export const NO_BISMILLAH_INTRO_SURAHS = new Set([1, 9]);

const SURAH_NAMES = [
  'Al-Fatihah', 'Al-Baqarah', 'Al-Imran', 'An-Nisa', 'Al-Maidah', 'Al-Anam', 'Al-Araf', 'Al-Anfal',
  'At-Tawbah', 'Yunus', 'Hud', 'Yusuf', 'Ar-Raad', 'Ibrahim', 'Al-Hijr', 'An-Nahl', 'Al-Isra', 'Al-Kahf',
  'Maryam', 'Ta-Ha', 'Al-Anbiya', 'Al-Hajj', 'Al-Muminun', 'An-Nur', 'Al-Furqan', 'Ash-Shuara', 'An-Naml',
  'Al-Qasas', 'Al-Ankabut', 'Ar-Rum', 'Luqman', 'As-Sajdah', 'Al-Ahzab', 'Saba', 'Fatir', 'Ya-Sin',
  'As-Saffat', 'Sad', 'Az-Zumar', 'Ghafir', 'Fussilat', 'Ash-Shura', 'Az-Zukhruf', 'Ad-Dukhan',
  'Al-Jathiyah', 'Al-Ahqaf', 'Muhammad', 'Al-Fath', 'Al-Hujurat', 'Qaf', 'Adh-Dhariyat', 'At-Tur', 'An-Najm',
  'Al-Qamar', 'Ar-Rahman', 'Al-Waqiah', 'Al-Hadid', 'Al-Mujadila', 'Al-Hashr', 'Al-Mumtahanah', 'As-Saff',
  'Al-Jumuah', 'Al-Munafiqun', 'At-Taghabun', 'At-Talaq', 'At-Tahrim', 'Al-Mulk', 'Al-Qalam', 'Al-Haqqah',
  'Al-Maarij', 'Nuh', 'Al-Jinn', 'Al-Muzzammil', 'Al-Muddaththir', 'Al-Qiyamah', 'Al-Insan', 'Al-Mursalat',
  'An-Naba', 'An-Naziat', 'Abasa', 'At-Takwir', 'Al-Infitar', 'Al-Mutaffifin', 'Al-Inshiqaq', 'Al-Buruj',
  'At-Tariq', 'Al-Ala', 'Al-Ghashiyah', 'Al-Fajr', 'Al-Balad', 'Ash-Shams', 'Al-Layl', 'Ad-Duha', 'Ash-Sharh',
  'At-Tin', 'Al-Alaq', 'Al-Qadr', 'Al-Bayyinah', 'Az-Zalzalah', 'Al-Adiyat', 'Al-Qariah', 'At-Takathur',
  'Al-Asr', 'Al-Humazah', 'Al-Fil', 'Quraysh', 'Al-Maun', 'Al-Kawthar', 'Al-Kafirun', 'An-Nasr', 'Al-Masad',
  'Al-Ikhlas', 'Al-Falaq', 'An-Nas',
];

// Global id of the ayah just before each surah: OFFSETS[s - 1] + ayah == global id.
const OFFSETS = (() => {
  const offsets = [];
  let total = 0;
  for (const count of VERSE_COUNTS) {
    offsets.push(total);
    total += count;
  }
  return offsets;
})();

export const surahName = (surah) => SURAH_NAMES[surah - 1] ?? `Surah ${surah}`;
export const surahLabel = (surah) => `${surah} · ${surahName(surah)}`;
export const surahTitle = (surah) => `Surah ${surah} · ${surahName(surah)}`;
export const trackTitle = (surah, ayah) => `${surahTitle(surah)} · Ayah ${ayah}`;
export const verseCount = (surah) => VERSE_COUNTS[surah - 1] ?? 0;

export function isValidGlobalId(id) {
  return Number.isInteger(id) && id >= 1 && id <= TOTAL_AYAHS;
}

/** Throws above 6236 or below 1, like the Android mapping (which would otherwise crash later). */
export function globalToSurahAyah(globalId) {
  if (!isValidGlobalId(globalId)) throw new RangeError(`Invalid global ayah id: ${globalId}`);
  // Binary search over the surah offsets.
  let lo = 0;
  let hi = SURAH_COUNT - 1;
  while (lo < hi) {
    const mid = (lo + hi + 1) >> 1;
    if (OFFSETS[mid] < globalId) lo = mid;
    else hi = mid - 1;
  }
  return [lo + 1, globalId - OFFSETS[lo]];
}

export function surahAyahToGlobal(surah, ayah) {
  return OFFSETS[surah - 1] + ayah;
}

const pad3 = (n) => String(n).padStart(3, '0');
export const ayahFilename = (surah, ayah) => `${pad3(surah)}${pad3(ayah)}.mp3`;
export const refKey = (surah, ayah) => `${surah}:${ayah}`;

export function makeTrack(globalId, index = 0) {
  const [surah, ayah] = globalToSurahAyah(globalId);
  return { index, globalId, surah, ayah, label: `${surahName(surah)} · Ayah ${ayah}` };
}

/**
 * The master playlist: learned ids, range-checked, sorted and re-indexed from 1. Queues are
 * identified by their first/last id, so ordering and de-duplication matter.
 */
export function buildMaster(learnedIds) {
  return [...new Set(learnedIds)]
    .filter(isValidGlobalId)
    .sort((a, b) => a - b)
    .map((id, i) => makeTrack(id, i + 1));
}

export const PlaybackMode = Object.freeze({
  REVISE: 'REVISE',
  FULL_SURAH: 'FULL_SURAH',
  WORD_BY_WORD: 'WORD_BY_WORD',
});

export const RepeatMode = Object.freeze({ OFF: 'OFF', SURAH: 'SURAH', AYAH: 'AYAH' });

export const PLAYBACK_MODE_LABELS = {
  REVISE: 'Learned ayahs',
  FULL_SURAH: 'Full surah',
  WORD_BY_WORD: 'Word by word',
};

export function repeatLabel(repeat, mode) {
  if (mode === PlaybackMode.WORD_BY_WORD && repeat === RepeatMode.AYAH) return 'Word';
  return { OFF: 'Off', SURAH: 'Surah', AYAH: 'Ayah' }[repeat];
}

/** The label shown wherever the active mode is surfaced (media session, mini bar, playlist). */
export function playbackModeLabel(mode, repeat) {
  if (mode === PlaybackMode.FULL_SURAH) {
    return { OFF: 'Full surah', SURAH: 'Full surah · repeat', AYAH: 'Full surah · repeat ayah' }[repeat];
  }
  if (mode === PlaybackMode.REVISE) {
    return {
      OFF: 'Learned ayahs',
      SURAH: 'Learned ayahs · repeat surah',
      AYAH: 'Learned ayahs · repeat ayah',
    }[repeat];
  }
  return repeat === RepeatMode.AYAH ? 'Word by word · repeat word' : 'Word by word';
}

export const QueueBuilder = {
  /** Distinct surahs that contain at least one learned ayah, ascending. */
  learnedSurahs(master) {
    return [...new Set(master.map((t) => t.surah))].sort((a, b) => a - b);
  },

  /**
   * - REVISE / WORD_BY_WORD + repeat SURAH: only the learned ayahs of `surah`.
   * - REVISE / WORD_BY_WORD otherwise: the whole learned (master) list.
   * - FULL_SURAH: every ayah of the surah, learned or not.
   */
  buildQueue(master, mode, repeat, surah) {
    if (mode === PlaybackMode.FULL_SURAH) return QueueBuilder.surahAllAyahs(surah);
    if (repeat === RepeatMode.SURAH) return master.filter((t) => t.surah === surah);
    return master;
  },

  surahAllAyahs(surah) {
    if (surah < 1 || surah > SURAH_COUNT) return [];
    const tracks = [];
    for (let ayah = 1; ayah <= VERSE_COUNTS[surah - 1]; ayah++) {
      tracks.push(makeTrack(surahAyahToGlobal(surah, ayah), ayah));
    }
    return tracks;
  },

  /** Next/previous surah among those with a learned ayah — used by REVISE. */
  nextSurah(master, surah) {
    const list = QueueBuilder.learnedSurahs(master);
    if (!list.length) return surah;
    const idx = list.indexOf(surah);
    return idx === -1 ? list[0] : list[(idx + 1) % list.length];
  },

  prevSurah(master, surah) {
    const list = QueueBuilder.learnedSurahs(master);
    if (!list.length) return surah;
    const idx = list.indexOf(surah);
    return idx === -1 ? list[list.length - 1] : list[(idx - 1 + list.length) % list.length];
  },

  /** Next/previous across all 114 — used by FULL_SURAH, which can stream any surah. */
  nextSurahAll(surah) {
    if (surah < 1 || surah > SURAH_COUNT) return 1;
    return (surah % SURAH_COUNT) + 1;
  },

  prevSurahAll(surah) {
    if (surah < 1 || surah > SURAH_COUNT) return SURAH_COUNT;
    return ((surah - 2 + SURAH_COUNT) % SURAH_COUNT) + 1;
  },
};
