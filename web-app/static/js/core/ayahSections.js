// Consecutive runs of learned ayahs — the sections the "revise by section" screen lists.
// Port of data/AyahSections.kt — keep the two in step.
//
// A section is one unbroken stretch of ayahs the user has marked inside a surah. Al-Baqarah
// marked in 18 separate places is 18 sections, however many ayahs those hold, and a section is
// named by its numbers ("Ayahs 255-257") rather than its text, so it can be recited from memory.
//
// The run-collapsing rule lives here and nowhere else: core/ayahRef.js builds its compact
// references on top of it, so a reference and a section heading can never disagree about where a
// run breaks.

import { TOTAL_AYAHS, globalToSurahAyah, surahAyahToGlobal } from './quran.js';

/** Each surah's learned ayah numbers, deduped and sorted, in surah order: `[[surah, ayahs]]`. */
function learnedBySurah(ids) {
  const bySurah = new Map();
  for (const id of ids) {
    if (!(Number.isInteger(id) && id >= 1 && id <= TOTAL_AYAHS)) continue;
    const [surah, ayah] = globalToSurahAyah(id);
    if (!bySurah.has(surah)) bySurah.set(surah, []);
    bySurah.get(surah).push(ayah);
  }
  return [...bySurah.keys()].sort((a, b) => a - b)
    .map((surah) => [surah, [...new Set(bySurah.get(surah))].sort((a, b) => a - b)]);
}

/** The unbroken stretches of one surah's sorted ayah numbers, as `[from, to]` pairs. */
function runs(ayahs) {
  const out = [];
  let start = ayahs[0];
  let prev = start;
  for (const ayah of ayahs.slice(1)) {
    if (ayah === prev + 1) {
      prev = ayah;
      continue;
    }
    out.push([start, prev]);
    start = ayah;
    prev = ayah;
  }
  out.push([start, prev]);
  return out;
}

/**
 * Every learned section, surah ascending and then ayah ascending. `index` is the section's
 * number within its own surah — the 1, 2, 3 the screen counts them off by — and `total` how
 * many that surah has.
 *
 * A surah learned in full is **one** section (1..verseCount), never a bare surah number the way
 * `formatRefs` writes it: a section always has ayah numbers to show.
 */
export function learnedSections(ids) {
  const sections = [];
  for (const [surah, ayahs] of learnedBySurah(ids)) {
    const found = runs(ayahs);
    found.forEach(([from, to], i) => sections.push({
      surah,
      index: i + 1,
      total: found.length,
      from,
      to,
      count: to - from + 1,
      firstGlobalId: surahAyahToGlobal(surah, from),
      lastGlobalId: surahAyahToGlobal(surah, to),
    }));
  }
  return sections;
}

/** The sections of each surah, in surah order, for the per-surah headings and counts. */
export function sectionsBySurah(ids) {
  const bySurah = new Map();
  for (const section of learnedSections(ids)) {
    if (!bySurah.has(section.surah)) bySurah.set(section.surah, []);
    bySurah.get(section.surah).push(section);
  }
  return bySurah;
}

/** The section an ayah falls in, or null when that ayah isn't learned. */
export function sectionAt(ids, globalId) {
  if (!(Number.isInteger(globalId) && globalId >= 1 && globalId <= TOTAL_AYAHS)) return null;
  const [surah, ayah] = globalToSurahAyah(globalId);
  return (sectionsBySurah(ids).get(surah) ?? []).find((s) => ayah >= s.from && ayah <= s.to) ?? null;
}

/** A section's identity, for its row in the list and for "is this the one playing?". */
export const sectionKey = (section) => (section ? `${section.surah}:${section.from}-${section.to}` : null);
