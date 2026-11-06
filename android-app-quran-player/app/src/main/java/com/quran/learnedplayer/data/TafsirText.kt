package com.quran.learnedplayer.data

/**
 * Tafsir HTML from Quran.com, turned into plain blocks the UI renders itself
 * (`ui/player/TafsirSheet.kt`). The web app's `core/tafsirText.js` is the same; TafsirTextTest
 * holds the same cases.
 *
 * Nothing of the provider's markup is ever shown: the text is read into headings, paragraphs,
 * list items and quotes with bold/italic runs, and every element, attribute, style and link is
 * dropped. Script, style and other embedded content goes with what is inside it.
 */
object TafsirText {
    enum class Kind { HEADING, PARAGRAPH, ITEM, QUOTE }

    data class Span(val text: String, val bold: Boolean = false, val italic: Boolean = false)

    data class Block(val kind: Kind, val spans: List<Span>) {
        val text: String get() = spans.joinToString("") { it.text }
    }

    private val DROP = setOf("script", "style", "iframe", "object", "embed", "svg", "math", "noscript", "template", "head", "title", "select", "textarea", "button", "form")
    private val BLOCK_KIND = mapOf(
        "h1" to Kind.HEADING, "h2" to Kind.HEADING, "h3" to Kind.HEADING, "h4" to Kind.HEADING, "h5" to Kind.HEADING, "h6" to Kind.HEADING,
        "p" to Kind.PARAGRAPH, "div" to Kind.PARAGRAPH, "section" to Kind.PARAGRAPH, "article" to Kind.PARAGRAPH,
        "tr" to Kind.PARAGRAPH, "dd" to Kind.PARAGRAPH, "dt" to Kind.PARAGRAPH,
        "li" to Kind.ITEM, "blockquote" to Kind.QUOTE,
    )
    private val BOLD = setOf("b", "strong")
    private val ITALIC = setOf("i", "em", "cite")
    private val TOKENS = Regex("(<!--[\\s\\S]*?-->|</?[a-zA-Z][^>]*>)")
    private val TAG = Regex("^<(/?)([a-zA-Z][a-zA-Z0-9]*)\\b[^>]*?(/?)>$")
    private val SPACE = Regex("[\\s\\u00a0]+")

    fun blocks(html: String?): List<Block> {
        val out = mutableListOf<Block>()
        var kind: Kind? = null
        var spans = mutableListOf<Span>()
        val stack = mutableListOf<String>()
        var dropping: String? = null
        var dropDepth = 0

        fun kindNow(): Kind {
            for (i in stack.indices.reversed()) {
                val k = BLOCK_KIND[stack[i]] ?: continue
                return if (k == Kind.PARAGRAPH && "blockquote" in stack) Kind.QUOTE else k
            }
            return Kind.PARAGRAPH
        }

        fun close() {
            if (kind != null) {
                while (spans.isNotEmpty() && spans.first().text.isBlank()) spans.removeAt(0)
                while (spans.isNotEmpty() && spans.last().text.isBlank()) spans.removeAt(spans.size - 1)
                if (spans.isNotEmpty()) {
                    spans[0] = spans[0].copy(text = spans[0].text.trimStart())
                    spans[spans.size - 1] = spans.last().copy(text = spans.last().text.trimEnd())
                    out += Block(kind!!, spans.map { it.copy(text = it.text.replace(Regex(" ?\n ?"), "\n")) })
                }
            }
            kind = null
            spans = mutableListOf()
        }

        fun addText(raw: String, lineBreak: Boolean = false) {
            val text = if (lineBreak) "\n" else SPACE.replace(ContentText.decodeEntities(raw), " ")
            if (text.isEmpty()) return
            if (kind == null) {
                if (text.isBlank()) return
                kind = kindNow()
            }
            val bold = stack.any { it in BOLD }
            val italic = stack.any { it in ITALIC }
            val last = spans.lastOrNull()
            if (last != null && last.bold == bold && last.italic == italic) {
                spans[spans.size - 1] = last.copy(text = (last.text + text).replace(Regex(" {2,}"), " "))
            } else {
                spans += Span(text, bold, italic)
            }
        }

        val source = html.orEmpty()
        var at = 0
        val pieces = mutableListOf<String>()
        for (m in TOKENS.findAll(source)) {
            if (m.range.first > at) pieces += source.substring(at, m.range.first)
            pieces += m.value
            at = m.range.last + 1
        }
        if (at < source.length) pieces += source.substring(at)

        for (token in pieces) {
            if (token.startsWith("<!--")) continue
            val m = TAG.find(token)
            if (m == null) {
                if (dropping == null) addText(token)
                continue
            }
            val closing = m.groupValues[1] == "/"
            val name = m.groupValues[2].lowercase()
            val selfClosing = m.groupValues[3] == "/" || name == "br" || name == "hr" || name == "img"
            if (dropping != null) {
                if (name == dropping) dropDepth += if (closing) -1 else if (selfClosing) 0 else 1
                if (dropDepth == 0) dropping = null
                continue
            }
            if (!closing && name in DROP) {
                if (!selfClosing) {
                    dropping = name
                    dropDepth = 1
                }
                continue
            }
            if (name == "br") {
                if (kind != null) addText("", lineBreak = true)
                continue
            }
            if (name == "hr" || name == "img") continue
            if (name in BLOCK_KIND || name == "ul" || name == "ol" || name == "table") close()
            if (closing) {
                val i = stack.lastIndexOf(name)
                if (i >= 0) while (stack.size > i) stack.removeAt(stack.size - 1)
            } else if (!selfClosing) {
                stack += name
            }
        }
        close()
        return out
    }

    /** "2:255" or "112:1–4" for the ayahs a tafsir entry covers. */
    fun coveredRange(keys: List<String>): String {
        val parsed = keys.mapNotNull { k ->
            val p = k.split(":")
            val s = p.getOrNull(0)?.toIntOrNull()
            val a = p.getOrNull(1)?.toIntOrNull()
            if (s != null && a != null && s > 0 && a > 0) s to a else null
        }.sortedWith(compareBy({ it.first }, { it.second }))
        if (parsed.isEmpty()) return ""
        val (s1, a1) = parsed.first()
        val (s2, a2) = parsed.last()
        return when {
            s1 == s2 && a1 == a2 -> "$s1:$a1"
            s1 == s2 -> "$s1:$a1–$a2"
            else -> "$s1:$a1–$s2:$a2"
        }
    }

    fun text(blocks: List<Block>): String = blocks.joinToString("\n\n") { it.text }
}
