package com.quran.learnedplayer.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Word timings estimated from the sound of A'udhu (and a Bismillah with no timings), for
 * one-word-at-a-time videos. The web app's `introTimings.js` has the same cases.
 */
class IntroWordTimingsTest {

    private val frame = IntroWordTimings.FRAME_MS

    /** Frames of [level] for [voiced] frames, then silence for [silent] frames, in turn. */
    private fun levels(vararg runs: Pair<Int, Double>): DoubleArray =
        runs.flatMap { (count, level) -> List(count) { level } }.toDoubleArray()

    @Test
    fun aPauseBetweenEveryWord_putsEachWordAfterItsPause() {
        // Half a second of silence, then three words of 0.4 s with 0.2 s pauses.
        val levels = levels(25 to 0.0, 20 to 1000.0, 10 to 0.0, 20 to 1000.0, 10 to 0.0, 20 to 1000.0, 25 to 0.0)
        val segments = IntroWordTimings.estimate(levels, listOf(5, 5, 5))!!
        assertEquals(3, segments.size)
        assertEquals("the first word starts with the voice, not the file", 25 * frame, segments[0].first)
        assertEquals(55 * frame, segments[1].first)
        assertEquals(85 * frame, segments[2].first)
        assertEquals("the last word ends with the voice", 105 * frame, segments[2].last)
    }

    @Test
    fun connectedRecitation_isSharedByLetters_andSnapsToTheQuietestJoint() {
        // One unbroken phrase of 100 frames from frame 10; words of 2 and 8 letters; a dip at
        // frame 32 of the clip.
        val voice = DoubleArray(100) { 1000.0 }.also { it[22] = 300.0 }
        val levels = DoubleArray(10) + voice + DoubleArray(10)
        val segments = IntroWordTimings.estimate(levels, listOf(2, 8))!!
        // Letters put the joint at frame 10 + 20 = 30; the dip two frames later is the joint.
        assertEquals(10 * frame, segments[0].first)
        assertEquals(32 * frame, segments[1].first)
        assertEquals(32 * frame - 1, segments[0].last)
    }

    @Test
    fun everyWordGetsTimeInOrder() {
        val levels = DoubleArray(5) + DoubleArray(60) { 800.0 + (it % 7) * 50 } + DoubleArray(5)
        val segments = IntroWordTimings.estimate(levels, listOf(5, 6, 2, 8, 7))!!
        assertEquals(5, segments.size)
        for (i in 1 until segments.size) {
            assertTrue("word $i starts after word ${i - 1}", segments[i].first > segments[i - 1].first)
            assertEquals(segments[i].first - 1, segments[i - 1].last)
        }
    }

    @Test
    fun silence_hasNothingToMeasure() {
        assertNull(IntroWordTimings.estimate(DoubleArray(100), listOf(3, 3)))
        assertNull(IntroWordTimings.estimate(DoubleArray(0), listOf(3)))
    }

    @Test
    fun letterWeight_countsLettersNotMarks() {
        assertEquals(4, IntroWordTimings.letterWeight("أَعُوذُ"))
        assertEquals(6, IntroWordTimings.letterWeight("ٱلرَّجِيمِ"))
        assertEquals(1, IntroWordTimings.letterWeight("ۛ"))
    }
}
