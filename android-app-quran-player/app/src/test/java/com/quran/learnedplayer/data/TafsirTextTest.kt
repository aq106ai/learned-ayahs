package com.quran.learnedplayer.data

import com.quran.learnedplayer.data.TafsirText.Block
import com.quran.learnedplayer.data.TafsirText.Kind
import com.quran.learnedplayer.data.TafsirText.Span
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Tafsir HTML → plain blocks; the same cases as web-app/tests/unit/tafsirText.test.js. */
class TafsirTextTest {
    @Test
    fun headings_paragraphs_and_emphasis_become_blocks_and_attributes_never_survive() {
        val blocks = TafsirText.blocks(
            "<h2>The Virtue of Ayat Al-Kursi</h2><p>This is <strong style=\"color: red\">the greatest</strong> Ayah, <em>as</em> recorded.</p>",
        )
        assertEquals(
            listOf(
                Block(Kind.HEADING, listOf(Span("The Virtue of Ayat Al-Kursi"))),
                Block(
                    Kind.PARAGRAPH,
                    listOf(Span("This is "), Span("the greatest", bold = true), Span(" Ayah, "), Span("as", italic = true), Span(" recorded.")),
                ),
            ),
            blocks,
        )
        assertFalse(blocks.toString().contains("color"))
    }

    @Test
    fun scripts_styles_and_embeds_are_dropped_with_their_content_links_keep_only_text() {
        val blocks = TafsirText.blocks(
            "<p>a<script>alert(1)</script>b<style>p{}</style><iframe src=\"x\">frame</iframe> <a href=\"javascript:alert(1)\" onclick=\"x()\">link</a></p><img src=x onerror=alert(1)>",
        )
        assertEquals("ab link", TafsirText.text(blocks))
    }

    @Test
    fun nested_unclosed_comments_and_entities() {
        assertEquals("اللہ ﷺ نے", TafsirText.text(TafsirText.blocks("<div lang=\"ur\" class=\"ur\"><p class=\"ur\">اللہ <span class=\"saw\">ﷺ</span> نے</p></div>")))
        assertEquals("one\n\ntwo", TafsirText.text(TafsirText.blocks("<p>one<p>two")))
        assertEquals("<b> & ا x", TafsirText.text(TafsirText.blocks("<!-- <script>x</script> --><p>&lt;b&gt; &amp; &#1575;&nbsp;x</p>")))
        assertEquals("line\nnext", TafsirText.text(TafsirText.blocks("<p>line<br>next</p>")))
        assertEquals(emptyList<Block>(), TafsirText.blocks(""))
        assertEquals(emptyList<Block>(), TafsirText.blocks(null))
        assertEquals(emptyList<Block>(), TafsirText.blocks("<p>  </p><p></p>"))
    }

    @Test
    fun lists_and_quotes_keep_their_kind() {
        val blocks = TafsirText.blocks("<ul><li>first</li><li>second</li></ul><blockquote><p>quoted</p></blockquote>")
        assertEquals(listOf(Kind.ITEM, Kind.ITEM, Kind.QUOTE), blocks.map { it.kind })
    }

    @Test
    fun covered_ayahs_read_as_a_range() {
        assertEquals("2:255", TafsirText.coveredRange(listOf("2:255")))
        assertEquals("112:1–4", TafsirText.coveredRange(listOf("112:3", "112:1", "112:2", "112:4")))
        assertEquals("2:286–3:1", TafsirText.coveredRange(listOf("2:286", "3:1")))
        assertEquals("", TafsirText.coveredRange(emptyList()))
    }
}
