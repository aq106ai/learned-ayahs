package com.quran.learnedplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WrongAyahDetectorTest {

    private val fatihaOpening = listOf("بسم", "الله", "الرحمن")
    private val ikhlasOpening = listOf("قل", "هو", "الله")

    @Test
    fun matchingOtherAyah_returnsNull_whenTooFewTokens() {
        val id = WrongAyahDetector.matchingOtherAyah(
            spokenTokens = listOf("قل"),
            currentGlobalId = 1,
            currentOpening = fatihaOpening,
            otherOpenings = listOf(WrongAyahDetector.Opening(6231, ikhlasOpening)),
        )
        assertNull(id)
    }

    @Test
    fun matchingOtherAyah_returnsNull_whenCurrentOpeningMatches() {
        val id = WrongAyahDetector.matchingOtherAyah(
            spokenTokens = listOf("بسم", "الله"),
            currentGlobalId = 1,
            currentOpening = fatihaOpening,
            otherOpenings = listOf(WrongAyahDetector.Opening(6231, ikhlasOpening)),
        )
        assertNull(id)
    }

    @Test
    fun matchingOtherAyah_detectsIkhlasWhileOnFatiha() {
        val ikhlasId = 6231 // 112:1
        val id = WrongAyahDetector.matchingOtherAyah(
            spokenTokens = listOf("قل", "هو"),
            currentGlobalId = 1,
            currentOpening = fatihaOpening,
            otherOpenings = listOf(WrongAyahDetector.Opening(ikhlasId, ikhlasOpening)),
        )
        assertEquals(ikhlasId, id)
    }

    @Test
    fun matchingOtherAyah_ignoresUnrelatedSpeech() {
        val id = WrongAyahDetector.matchingOtherAyah(
            spokenTokens = listOf("مالك", "يوم"),
            currentGlobalId = 1,
            currentOpening = fatihaOpening,
            otherOpenings = listOf(WrongAyahDetector.Opening(6231, ikhlasOpening)),
        )
        assertNull(id)
    }
}
