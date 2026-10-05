package com.quran.learnedplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the written text and the heard tokens disagree about how many words there are.
 *
 * The case that matters is the attached vocative — يَـٰٓأَيُّهَا is one word in the Uthmani text and
 * two in every recognizer's output — because it opens 469 ayahs, including most of the ones a
 * student is likely to have memorised early.
 */
class ArabicWordAlignerTest {

    private val yaAyyuha = listOf("يَـٰٓأَيُّهَا", "ٱلنَّاسُ", "ٱعْبُدُوا۟")

    @Test
    fun oneWrittenWord_matchesTwoHeardTokens() {
        val step = ArabicWordAligner.matchAt(yaAyyuha, 0, listOf("يا", "ايها", "الناس"), 0)
        assertEquals(ArabicWordAligner.Step(words = 1, tokens = 2), step)
    }

    @Test
    fun twoWrittenWords_matchOneHeardToken() {
        // The reverse: a recognizer running a particle into the word after it.
        val words = listOf("مِن", "بَعْدِ")
        val step = ArabicWordAligner.matchAt(words, 0, listOf("منبعد"), 0)
        assertEquals(ArabicWordAligner.Step(words = 2, tokens = 1), step)
    }

    /** A plain match must always win, so a joined reading can never displace one. */
    @Test
    fun oneToOneIsPreferred() {
        val step = ArabicWordAligner.matchAt(yaAyyuha, 1, listOf("الناس", "اعبدوا"), 0)
        assertEquals(ArabicWordAligner.Step(words = 1, tokens = 1), step)
    }

    @Test
    fun unrelatedWord_doesNotAlign() {
        assertNull(ArabicWordAligner.matchAt(yaAyyuha, 0, listOf("الحمد", "لله"), 0))
    }

    @Test
    fun prefixConsumesTheTokensItActuallyUsed() {
        // Two written words, three heard tokens.
        assertEquals(3, ArabicWordAligner.consumedByPrefix(yaAyyuha, 2, listOf("يا", "ايها", "الناس", "اعبدوا")))
        assertEquals(-1, ArabicWordAligner.consumedByPrefix(yaAyyuha, 2, listOf("الحمد", "لله")))
    }

    /**
     * "Has the student started the next ayah?" reads the end of the transcript, so the tail it
     * compares cannot be a fixed number of tokens once يا is in play.
     */
    @Test
    fun endsWithPrefix_findsAJoinedOpeningAtTheEndOfATranscript() {
        val spoken = listOf("لله", "رب", "العالمين", "يا", "ايها", "الناس")
        assertTrue(ArabicWordAligner.endsWithPrefix(yaAyyuha, 2, spoken))
        assertFalse(
            "the opening must be the most recent thing said, not buried mid-transcript",
            ArabicWordAligner.endsWithPrefix(yaAyyuha, 2, spoken + listOf("الحمد", "لله")),
        )
    }
}
