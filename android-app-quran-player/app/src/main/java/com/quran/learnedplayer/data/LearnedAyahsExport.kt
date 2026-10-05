package com.quran.learnedplayer.data

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The app's own portable format for backing up / syncing a learned selection:
 *
 * ```json
 * { "format": "learned-ayahs", "version": 1, "exportedAt": "2026-07-17T06:00:00Z",
 *   "count": 1842, "ayahs": ["1", "2:255", "36:1-83"] }
 * ```
 *
 * References rather than raw ids so the file stays readable, hand-editable, and writable by an
 * LLM. Parsing is deliberately lenient — anything [AyahRef] understands is accepted, including a
 * bare list of ids or refs — because these files are meant to be typed and pasted by people.
 */
object LearnedAyahsExport {

    const val FORMAT = "learned-ayahs"
    const val VERSION = 1

    fun export(ids: Set<Int>, now: Date = Date()): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(now)
        return JSONObject().apply {
            put("format", FORMAT)
            put("version", VERSION)
            put("exportedAt", stamp)
            put("count", ids.size)
            put("ayahs", JSONArray(AyahRef.format(ids)))
        }.toString(2)
    }

    /** Suggested filename for the SAF create-document picker. */
    fun suggestedFileName(now: Date = Date()): String =
        "LearnedAyahs-" +
            SimpleDateFormat("yyyy-MM-dd", Locale.US).format(now) +
            ".json"

    fun parse(text: String): AyahRefResult {
        // Structured file first: read the ayah/id array out of the JSON object.
        runCatching { JSONObject(text) }.getOrNull()?.let { json ->
            arrayOf("ayahs", "learnedAyahs").forEach { key ->
                json.optJSONArray(key)?.let { return fromArray(it) }
            }
        }
        // A bare array of refs or ids.
        runCatching { JSONArray(text) }.getOrNull()?.let { return fromArray(it) }
        // Otherwise treat the whole thing as free text — covers a pasted LLM reply or a
        // hand-typed list, and AyahRef already strips JSON punctuation.
        return AyahRef.parse(text)
    }

    private fun fromArray(array: JSONArray): AyahRefResult {
        val ids = sortedSetOf<Int>()
        val unparsed = mutableListOf<String>()
        for (i in 0 until array.length()) {
            when (val value = array.opt(i)) {
                is Int -> if (value in 1..TOTAL_AYAHS) ids += value else unparsed += value.toString()
                is String -> {
                    val parsed = AyahRef.parse(value)
                    ids += parsed.ids
                    unparsed += parsed.unparsed
                }
                else -> value?.let { unparsed += it.toString() }
            }
        }
        return AyahRefResult(ids, unparsed)
    }

    private const val TOTAL_AYAHS = 6236
}
