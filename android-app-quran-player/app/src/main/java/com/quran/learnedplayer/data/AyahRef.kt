package com.quran.learnedplayer.data

/** Outcome of parsing free-form ayah references. */
data class AyahRefResult(
    val ids: Set<Int>,
    /** Tokens that matched nothing, so the UI can say what it ignored rather than drop it silently. */
    val unparsed: List<String>,
)

/**
 * The reference grammar shared by the quick-add box, the sync export/import, and the LLM prompt.
 *
 * Accepted tokens, comma- or newline-separated:
 * - `2:255`      a single ayah
 * - `36:1-83`    a range within a surah
 * - `112`        a whole surah
 * - `78-114`     a range of whole surahs
 * - `Al-Baqarah 255`, `Yasin 1-20`, `An-Nas`   by name, with an optional ayah/range
 *
 * Surah names are matched loosely (case, spaces, hyphens and a leading "Al" are ignored) because
 * the input may be typed by hand or written by an LLM.
 */
object AyahRef {

    fun parse(input: String): AyahRefResult {
        val ids = sortedSetOf<Int>()
        val unparsed = mutableListOf<String>()

        for (token in tokenize(input)) {
            val matched = parseToken(token, ids)
            if (!matched) unparsed += token
        }
        return AyahRefResult(ids, unparsed)
    }

    /**
     * Global ids as compact references: a fully-learned surah collapses to `112`, runs collapse
     * to `36:1-83`. Inverse of [parse].
     */
    fun format(ids: Set<Int>): List<String> {
        val bySurah = sortedMapOf<Int, MutableList<Int>>()
        ids.filter { it in 1..TOTAL_AYAHS }
            .forEach { id ->
                val (surah, ayah) = AyahMapping.globalToSurahAyah(id)
                bySurah.getOrPut(surah) { mutableListOf() } += ayah
            }

        val refs = mutableListOf<String>()
        for ((surah, ayahs) in bySurah) {
            ayahs.sort()
            if (ayahs.size == QuranConstants.VERSE_COUNTS[surah - 1]) {
                refs += surah.toString()
                continue
            }
            var start = ayahs.first()
            var prev = start
            for (ayah in ayahs.drop(1)) {
                if (ayah == prev + 1) {
                    prev = ayah
                    continue
                }
                refs += range(surah, start, prev)
                start = ayah
                prev = ayah
            }
            refs += range(surah, start, prev)
        }
        return refs
    }

    private fun range(surah: Int, from: Int, to: Int): String =
        if (from == to) "$surah:$from" else "$surah:$from-$to"

    /** Splits on separators and strips JSON punctuation, so a pasted LLM reply parses as-is. */
    private fun tokenize(input: String): List<String> =
        input.split(',', '\n', '\r', ';', '[', ']', '{', '}')
            .map { it.trim().trim('"', '\'', '.', '·') .trim() }
            .filter { it.isNotEmpty() }
            // Drop the JSON scaffolding around the ayah list itself.
            .filterNot { it.startsWith("\"") || it.contains(':') && it.substringBefore(':').trim()
                .let { key -> key.equals("format", true) || key.equals("version", true) ||
                    key.equals("count", true) || key.equals("exportedAt", true) ||
                    key.equals("ayahs", true) || key.equals("learnedAyahs", true) } }

    private fun parseToken(token: String, into: MutableSet<Int>): Boolean {
        NUMERIC_AYAH.matchEntire(token)?.let { m ->
            val (s, from, to) = m.destructured
            return addRange(s.toInt(), from.toInt(), (to.ifEmpty { from }).toInt(), into)
        }
        SURAH_RANGE.matchEntire(token)?.let { m ->
            val (from, to) = m.destructured
            var any = false
            for (surah in from.toInt()..to.toInt()) any = addWholeSurah(surah, into) || any
            return any
        }
        WHOLE_SURAH.matchEntire(token)?.let { m ->
            return addWholeSurah(m.groupValues[1].toInt(), into)
        }
        NAMED.matchEntire(token)?.let { m ->
            val surah = surahByName(m.groupValues[1]) ?: return false
            val from = m.groupValues[2]
            val to = m.groupValues[3]
            return if (from.isEmpty()) {
                addWholeSurah(surah, into)
            } else {
                addRange(surah, from.toInt(), (to.ifEmpty { from }).toInt(), into)
            }
        }
        return false
    }

    private fun addWholeSurah(surah: Int, into: MutableSet<Int>): Boolean {
        if (surah !in 1..114) return false
        return addRange(surah, 1, QuranConstants.VERSE_COUNTS[surah - 1], into)
    }

    /** Clamps to the surah's real length so "36:1-999" adds Ya-Sin rather than failing. */
    private fun addRange(surah: Int, from: Int, to: Int, into: MutableSet<Int>): Boolean {
        if (surah !in 1..114) return false
        val last = QuranConstants.VERSE_COUNTS[surah - 1]
        val lo = minOf(from, to).coerceAtLeast(1)
        val hi = maxOf(from, to).coerceAtMost(last)
        if (lo > last) return false
        for (ayah in lo..hi) into += AyahMapping.surahAyahToGlobal(surah, ayah)
        return true
    }

    private fun surahByName(raw: String): Int? {
        val needle = normalise(raw)
        if (needle.isEmpty()) return null
        val exact = (1..114).firstOrNull { normalise(SurahNames.name(it)) == needle }
        if (exact != null) return exact
        val matches = (1..114).filter { normalise(SurahNames.name(it)).contains(needle) }
        return matches.singleOrNull()
    }

    /** "Al-Baqarah" / "al baqarah" / "Baqarah" all reduce to the same key. */
    private fun normalise(name: String): String =
        name.lowercase()
            .replace(Regex("[^a-z]"), "")
            .removePrefix("al")
            .removePrefix("as")
            .removePrefix("ash")
            .removePrefix("an")
            .removePrefix("ar")
            .removePrefix("at")
            .removePrefix("az")

    private const val TOTAL_AYAHS = 6236
    private val NUMERIC_AYAH = Regex("""(\d{1,3})\s*:\s*(\d{1,3})(?:\s*-\s*(\d{1,3}))?""")
    private val SURAH_RANGE = Regex("""(\d{1,3})\s*-\s*(\d{1,3})""")
    private val WHOLE_SURAH = Regex("""(\d{1,3})""")
    private val NAMED = Regex(
        """([A-Za-z][A-Za-z\-' ]*?)\s*(?:(\d{1,3})(?:\s*-\s*(\d{1,3}))?)?""",
    )
}
