package com.quran.learnedplayer.data

/**
 * One unbroken run of learned ayahs inside a surah — the unit "Revise by section" lists.
 *
 * [index] is the section's number within its own surah, the 1, 2, 3… the screen counts them off
 * by, and [total] how many that surah has.
 */
data class AyahSection(
    val surah: Int,
    val index: Int,
    val total: Int,
    val from: Int,
    val to: Int,
    val count: Int,
    val firstGlobalId: Int,
    val lastGlobalId: Int,
)

/**
 * The learned ayahs as the sections they fall into. Al-Baqarah marked in 18 separate places is
 * 18 sections, however many ayahs those hold, and a section is named by its numbers
 * ("Ayahs 255-257") rather than its text, so it can be recited from memory.
 *
 * The run-collapsing rule lives here and nowhere else: [AyahRef.format] builds its compact
 * references on top of it, so a reference and a section heading can never disagree about where a
 * run breaks. The one thing refs do and sections don't is collapse a surah learned in full to its
 * bare number — a section there keeps its ayah numbers, because a heading needs numbers to show.
 *
 * Web port: `web-app/static/js/core/ayahSections.js`.
 */
object AyahSections {

    /** Every section, surah ascending and then ayah ascending. */
    fun of(ids: Set<Int>): List<AyahSection> {
        val bySurah = sortedMapOf<Int, MutableList<Int>>()
        ids.filter { it in 1..LearnedAyahsStore.TOTAL_AYAHS }
            .forEach { id ->
                val (surah, ayah) = AyahMapping.globalToSurahAyah(id)
                bySurah.getOrPut(surah) { mutableListOf() } += ayah
            }

        val sections = mutableListOf<AyahSection>()
        for ((surah, ayahs) in bySurah) {
            val sorted = ayahs.distinct().sorted()
            val runs = mutableListOf<Pair<Int, Int>>()
            var start = sorted.first()
            var prev = start
            for (ayah in sorted.drop(1)) {
                if (ayah == prev + 1) {
                    prev = ayah
                    continue
                }
                runs += start to prev
                start = ayah
                prev = ayah
            }
            runs += start to prev
            runs.forEachIndexed { i, (from, to) ->
                sections += AyahSection(
                    surah = surah,
                    index = i + 1,
                    total = runs.size,
                    from = from,
                    to = to,
                    count = to - from + 1,
                    firstGlobalId = AyahMapping.surahAyahToGlobal(surah, from),
                    lastGlobalId = AyahMapping.surahAyahToGlobal(surah, to),
                )
            }
        }
        return sections
    }

    /** The sections of each surah, for the per-surah headings and counts. */
    fun bySurah(ids: Set<Int>): Map<Int, List<AyahSection>> = of(ids).groupBy { it.surah }

    /** The section an ayah falls in, or null when that ayah isn't learned. */
    fun at(ids: Set<Int>, globalId: Int): AyahSection? {
        if (globalId !in 1..LearnedAyahsStore.TOTAL_AYAHS) return null
        val (surah, ayah) = AyahMapping.globalToSurahAyah(globalId)
        return bySurah(ids)[surah]?.firstOrNull { ayah in it.from..it.to }
    }

    /** A section's identity, for its row in the list and for "is this the one playing?". */
    fun key(section: AyahSection?): String? = section?.let { "${it.surah}:${it.from}-${it.to}" }
}
