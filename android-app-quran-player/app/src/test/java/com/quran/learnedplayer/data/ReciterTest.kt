package com.quran.learnedplayer.data

import androidx.test.core.app.ApplicationProvider
import com.quran.learnedplayer.player.PlayerSettings
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ReciterTest {

    private val repo = QuranDataRepository(ApplicationProvider.getApplicationContext())

    @Before
    fun setUp() {
        PlayerSettings.init(ApplicationProvider.getApplicationContext())
        PlayerSettings.reciter = Reciter.DEFAULT
    }

    @After
    fun tearDown() {
        PlayerSettings.reciter = Reciter.DEFAULT
    }

    @Test
    fun default_reciter_is_maher_al_muaiqly() {
        assertEquals(Reciter.MAHER_AL_MUAIQLY, Reciter.DEFAULT)
        assertEquals(Reciter.DEFAULT, Reciter.fromKey(null))
        assertEquals(Reciter.DEFAULT, Reciter.fromKey("not-a-reciter"))
    }

    @Test
    fun fromKey_roundtrips_and_folders_are_unique() {
        Reciter.entries.forEach { assertEquals(it, Reciter.fromKey(it.name)) }
        assertEquals(Reciter.entries.size, Reciter.entries.map { it.folder }.toSet().size)
        assertEquals(Reciter.entries.size, Reciter.entries.map { it.timingsAsset }.toSet().size)
    }

    @Test
    fun audio_urls_follow_the_selected_reciter() {
        assertEquals(
            "https://everyayah.com/data/MaherAlMuaiqly128kbps/001001.mp3",
            AyahMapping.remoteUrl(1, 1),
        )

        PlayerSettings.reciter = Reciter.AL_HUSARY
        assertEquals(
            "https://everyayah.com/data/Husary_128kbps/001001.mp3",
            AyahMapping.remoteUrl(1, 1),
        )
        assertEquals("Husary_128kbps", QuranConstants.RECITER_FOLDER)
    }

    @Test
    fun every_shipped_reciter_has_its_own_exact_timings() {
        // The whole point of keeping the list short: no reciter ships without real timings,
        // because the app no longer estimates them.
        Reciter.entries.forEach { reciter ->
            val segments = repo.timingsFor(reciter, 112, 1)
            assertNotNull("${reciter.displayName} has no timings for 112:1", segments)
            assertEquals("${reciter.displayName}: 112:1 has 4 words", 4, segments!!.size)
        }
    }

    /**
     * No two reciters may share a timing set.
     *
     * QUL's API does not name its reciters, so an asset is only as good as the id it was
     * generated from — and picking the wrong id is not a build failure, it is a reciter silently
     * highlighted with somebody else's word boundaries. Two people do not recite an ayah to the
     * same millisecond, so identical segments mean a mistake upstream.
     */
    @Test
    fun timings_differ_per_reciter() {
        val segments = Reciter.entries.associateWith { repo.timingsFor(it, 112, 1) }
        for (first in Reciter.entries) {
            for (second in Reciter.entries) {
                if (first.ordinal >= second.ordinal) continue
                assertTrue(
                    "${first.displayName} and ${second.displayName} ship the same segments for " +
                        "112:1 — one of them was generated from the wrong QUL recitation id",
                    segments[first] != segments[second],
                )
            }
        }
    }

    @Test
    fun the_selected_reciter_decides_which_timings_are_used() = runBlocking {
        val viaMaher = repo.timingsFor(112, 1)
        PlayerSettings.reciter = Reciter.AL_HUSARY
        val viaHusary = repo.timingsFor(112, 1)
        assertEquals(repo.timingsFor(Reciter.MAHER_AL_MUAIQLY, 112, 1), viaMaher)
        assertEquals(repo.timingsFor(Reciter.AL_HUSARY, 112, 1), viaHusary)
    }
}
