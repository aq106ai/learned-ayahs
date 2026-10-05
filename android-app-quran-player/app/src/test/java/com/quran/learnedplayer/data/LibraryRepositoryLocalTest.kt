package com.quran.learnedplayer.data

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import java.io.IOException
import java.io.InputStream

@RunWith(RobolectricTestRunner::class)
class LibraryRepositoryLocalTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val repository = LibraryRepository(context)

    @Test
    fun no_selection_means_an_empty_playlist() = runBlocking {
        // The app used to inject the owner's 1,216-ayah "supplement" into everyone's list.
        // A new user's list must now contain only what they marked themselves — nothing.
        val snapshot = repository.buildLocalSnapshot(emptySet())
        assertEquals(LibraryRepository.LOCAL_SOURCE_NAME, snapshot.sourceFileName)
        assertTrue("a fresh user has nobody else's ayahs", snapshot.tracks.isEmpty())
    }

    @Test
    fun snapshot_contains_exactly_what_was_marked() = runBlocking {
        val marked = setOf(
            AyahMapping.surahAyahToGlobal(2, 255),
            AyahMapping.surahAyahToGlobal(112, 1),
        )
        val snapshot = repository.buildLocalSnapshot(marked)
        assertEquals(marked, snapshot.tracks.map { it.globalId }.toSet())
    }

    @Test
    fun tracks_are_ordered_and_reindexed_from_one() = runBlocking {
        // DefaultSupplement.merge used to do this; buildLocalSnapshot owns it now.
        val marked = setOf(
            AyahMapping.surahAyahToGlobal(36, 1),
            AyahMapping.surahAyahToGlobal(2, 255),
            AyahMapping.surahAyahToGlobal(112, 1),
        )
        val snapshot = repository.buildLocalSnapshot(marked)
        val ids = snapshot.tracks.map { it.globalId }
        assertEquals("sorted by global id", ids.sorted(), ids)
        assertEquals(listOf(1, 2, 3), snapshot.tracks.map { it.index })
    }

    @Test
    fun invalid_ids_cannot_reach_the_playlist() = runBlocking {
        val snapshot = repository.buildLocalSnapshot(setOf(7000, 0, AyahMapping.surahAyahToGlobal(1, 1)))
        assertEquals(listOf(1), snapshot.tracks.map { it.globalId })
    }

    @Test
    fun unreadable_file_yields_a_friendly_message_not_a_raw_exception() = runBlocking {
        val bogus = Uri.parse("content://com.example.provider/missing.json")
        Shadows.shadowOf(context.contentResolver).registerInputStream(
            bogus,
            object : InputStream() {
                override fun read(): Int = throw IOException("simulated read failure")
            },
        )
        val result = repository.readExportedIds(bogus)
        assertTrue(result.isFailure)
        val message = result.exceptionOrNull()?.message.orEmpty()
        assertTrue("expected an actionable message, got: $message", "Pick it again" in message)
        assertFalse("must not leak raw exception text", "IOException" in message)
    }

    @Test
    fun importing_a_file_with_no_ayahs_is_reported() = runBlocking {
        val uri = Uri.parse("content://com.example.provider/empty.json")
        Shadows.shadowOf(context.contentResolver).registerInputStream(
            uri,
            """{"format":"learned-ayahs","ayahs":[]}""".byteInputStream(),
        )
        val result = repository.readExportedIds(uri)
        assertTrue(result.isFailure)
        assertTrue("No ayahs found" in result.exceptionOrNull()?.message.orEmpty())
    }

    @Test
    fun exported_file_can_be_read_back() = runBlocking {
        val ids = setOf(AyahMapping.surahAyahToGlobal(36, 1), AyahMapping.surahAyahToGlobal(2, 255))
        val uri = Uri.parse("content://com.example.provider/export.json")
        Shadows.shadowOf(context.contentResolver).registerInputStream(
            uri,
            LearnedAyahsExport.export(ids).byteInputStream(),
        )
        assertEquals(ids, repository.readExportedIds(uri).getOrNull())
    }
}
