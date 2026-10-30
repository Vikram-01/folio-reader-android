package com.folio.reader

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.folio.reader.data.db.AppDatabase
import com.folio.reader.data.db.BookmarkEntity
import com.folio.reader.data.db.DocumentEntity
import com.folio.reader.data.db.FolderEntity
import com.folio.reader.data.db.ReadingProgressEntity
import com.folio.reader.data.repo.SearchRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class DatabaseTest {
    private lateinit var db: AppDatabase

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).allowMainThreadQueries().build()
    }

    @After fun tearDown() = db.close()

    private fun doc(title: String, sha: String, folder: Long? = null) = DocumentEntity(
        title = title, originalName = "$title.pdf", storedName = "$sha.pdf", format = "PDF", mime = "application/pdf",
        sizeBytes = 10, sha256 = sha, folderId = folder, addedAt = System.currentTimeMillis())

    @Test fun fullTextSearchFindsPagesWithSnippets() = runBlocking<Unit> {
        val id = db.documents().insert(doc("Physics", "aa"))
        db.search().replaceOwner("doc", id.toString(), listOf(
            Triple("0", "Page 1", "Introduction to classical mechanics"),
            Triple("41", "Page 42", "The quantum harmonic oscillator has evenly spaced levels"),
        ))
        val repo = SearchRepository(db)
        val hits = db.search().search(repo.ftsQuery("quant oscill")!!, 10)
        assertEquals(1, hits.size)
        assertEquals("41", hits[0].location)
        assertTrue(hits[0].snippet.contains("[[quantum]]"))
        // Hostile query syntax is neutralised rather than throwing.
        assertNotNull(repo.ftsQuery("\"quantum\" OR NEAR(* -x)"))
        db.search().search(repo.ftsQuery("\"quantum\" OR NEAR(* -x)")!!, 10)
    }

    @Test fun duplicateDetectionBySha() = runBlocking<Unit> {
        db.documents().insert(doc("A", "same"))
        assertNotNull(db.documents().findBySha("same"))
        assertNull(db.documents().findBySha("other"))
    }

    @Test fun deletingFolderKeepsDocuments() = runBlocking<Unit> {
        val folder = db.folders().insert(FolderEntity(name = "Work", createdAt = 0))
        val id = db.documents().insert(doc("Report", "r1", folder))
        db.folders().delete(db.folders().get(folder)!!)
        val d = db.documents().get(id)
        assertNotNull(d)
        assertNull(d!!.folderId)
    }

    @Test fun deletingDocumentRemovesItsProgress() = runBlocking<Unit> {
        val id = db.documents().insert(doc("Book", "b1"))
        db.reading().saveProgress(ReadingProgressEntity(id, 3, 0.2f, 0.4f, 1))
        assertEquals(1, db.documents().observeContinueReading(10).first().size)
        db.documents().delete(db.documents().get(id)!!)
        assertNull(db.reading().progress(id))
    }

    @Test fun bookmarkLookupByNormalisedUrl() = runBlocking<Unit> {
        db.bookmarks().insert(BookmarkEntity(url = "https://ex.com/a", normalizedUrl = "https://ex.com/a", title = "A", createdAt = 0, updatedAt = 0))
        assertNotNull(db.bookmarks().findByNormalized("https://ex.com/a"))
        assertEquals(1, db.bookmarks().searchLike("%ex.com%").size)
    }

    @Test fun integrityCheckPasses() {
        assertEquals("ok", db.integrityCheck())
    }
}
