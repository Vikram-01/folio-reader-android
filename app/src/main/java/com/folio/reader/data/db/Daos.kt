package com.folio.reader.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

data class ContinueReadingRow(
    val id: Long,
    val title: String,
    val format: String,
    val thumbnailName: String?,
    val fraction: Float,
    val position: Int,
    val pageCount: Int?,
    val updatedAt: Long,
)

data class TagCount(val id: Long, val name: String, val count: Int)

data class SearchHit(
    val ownerType: String,
    val ownerId: String,
    val location: String,
    val locationLabel: String,
    val snippet: String,
)

@Dao
interface DocumentDao {
    @Insert suspend fun insert(doc: DocumentEntity): Long
    @Update suspend fun update(doc: DocumentEntity)
    @Delete suspend fun delete(doc: DocumentEntity)

    @Query("SELECT * FROM documents WHERE id = :id") suspend fun get(id: Long): DocumentEntity?
    @Query("SELECT * FROM documents WHERE id = :id") fun observe(id: Long): Flow<DocumentEntity?>
    @Query("SELECT * FROM documents WHERE sha256 = :sha LIMIT 1") suspend fun findBySha(sha: String): DocumentEntity?
    @Query("SELECT * FROM documents ORDER BY addedAt DESC") fun observeAll(): Flow<List<DocumentEntity>>
    @Query("SELECT * FROM documents") suspend fun all(): List<DocumentEntity>
    @Query("SELECT * FROM documents WHERE id IN (:ids)") suspend fun getMany(ids: List<Long>): List<DocumentEntity>
    @Query("SELECT * FROM documents WHERE lastOpenedAt IS NOT NULL ORDER BY lastOpenedAt DESC LIMIT :limit")
    fun observeRecentlyOpened(limit: Int): Flow<List<DocumentEntity>>
    @Query("SELECT * FROM documents ORDER BY addedAt DESC LIMIT :limit") fun observeRecentlyAdded(limit: Int): Flow<List<DocumentEntity>>
    @Query("SELECT * FROM documents WHERE isFavorite = 1 ORDER BY title COLLATE NOCASE") fun observeFavorites(): Flow<List<DocumentEntity>>
    @Query("SELECT COUNT(*) FROM documents") fun observeCount(): Flow<Int>

    @Query(
        """SELECT d.id, d.title, d.format, d.thumbnailName, p.fraction, p.position, d.pageCount, p.updatedAt
           FROM documents d JOIN reading_progress p ON p.documentId = d.id
           WHERE p.fraction < 0.98 ORDER BY p.updatedAt DESC LIMIT :limit"""
    )
    fun observeContinueReading(limit: Int): Flow<List<ContinueReadingRow>>

    @Query("SELECT * FROM documents WHERE indexedAt IS NULL AND indexError IS NULL") suspend fun unindexed(): List<DocumentEntity>
    @Query("UPDATE documents SET indexedAt = :at, indexError = :error WHERE id = :id") suspend fun markIndexed(id: Long, at: Long?, error: String?)
    @Query("UPDATE documents SET indexedAt = NULL, indexError = NULL") suspend fun resetIndexState()
    @Query("UPDATE documents SET lastOpenedAt = :at WHERE id = :id") suspend fun touchOpened(id: Long, at: Long)
    @Query("UPDATE documents SET isFavorite = :fav WHERE id = :id") suspend fun setFavorite(id: Long, fav: Boolean)
    @Query("UPDATE documents SET title = :title WHERE id = :id") suspend fun rename(id: Long, title: String)
    @Query("UPDATE documents SET folderId = :folderId WHERE id IN (:ids)") suspend fun moveToFolder(ids: List<Long>, folderId: Long?)
    @Query("UPDATE documents SET pageCount = :count WHERE id = :id") suspend fun setPageCount(id: Long, count: Int)
    @Query("SELECT COALESCE(SUM(sizeBytes), 0) FROM documents") fun observeTotalSize(): Flow<Long>
}

@Dao
interface FolderDao {
    @Insert suspend fun insert(folder: FolderEntity): Long
    @Update suspend fun update(folder: FolderEntity)
    @Delete suspend fun delete(folder: FolderEntity)
    @Query("SELECT * FROM folders ORDER BY name COLLATE NOCASE") fun observeAll(): Flow<List<FolderEntity>>
    @Query("SELECT * FROM folders") suspend fun all(): List<FolderEntity>
    @Query("SELECT * FROM folders WHERE id = :id") suspend fun get(id: Long): FolderEntity?
    @Query("SELECT COUNT(*) FROM documents WHERE folderId = :id") suspend fun documentCount(id: Long): Int
}

@Dao
interface CollectionDao {
    @Insert suspend fun insert(c: CollectionEntity): Long
    @Update suspend fun update(c: CollectionEntity)
    @Delete suspend fun delete(c: CollectionEntity)
    @Query("SELECT * FROM collections ORDER BY name COLLATE NOCASE") fun observeAll(): Flow<List<CollectionEntity>>
    @Query("SELECT * FROM collections") suspend fun all(): List<CollectionEntity>
    @Query("SELECT * FROM collections WHERE id = :id") suspend fun get(id: Long): CollectionEntity?
    @Query("SELECT * FROM collections WHERE name = :name COLLATE NOCASE AND ((:parentId IS NULL AND parentId IS NULL) OR parentId = :parentId) LIMIT 1")
    suspend fun findByName(name: String, parentId: Long?): CollectionEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun addDocument(ref: DocumentCollectionRef)
    @Delete suspend fun removeDocument(ref: DocumentCollectionRef)
    @Query("SELECT d.* FROM documents d JOIN document_collections r ON r.documentId = d.id WHERE r.collectionId = :id ORDER BY d.title COLLATE NOCASE")
    fun observeDocuments(id: Long): Flow<List<DocumentEntity>>
    @Query("SELECT collectionId FROM document_collections WHERE documentId = :docId") fun observeCollectionsOfDocument(docId: Long): Flow<List<Long>>
    @Query("SELECT * FROM document_collections") suspend fun allDocumentRefs(): List<DocumentCollectionRef>
    @Query("SELECT collectionId AS id, '' AS name, COUNT(*) AS count FROM bookmarks WHERE collectionId IS NOT NULL GROUP BY collectionId")
    fun observeBookmarkCounts(): Flow<List<TagCount>>
    @Query("SELECT collectionId AS id, '' AS name, COUNT(*) AS count FROM document_collections GROUP BY collectionId")
    fun observeDocumentCounts(): Flow<List<TagCount>>
}

@Dao
interface BookmarkDao {
    @Insert suspend fun insert(b: BookmarkEntity): Long
    @Update suspend fun update(b: BookmarkEntity)
    @Delete suspend fun delete(b: BookmarkEntity)
    @Query("DELETE FROM bookmarks WHERE id IN (:ids)") suspend fun deleteMany(ids: List<Long>)
    @Query("SELECT * FROM bookmarks WHERE id = :id") suspend fun get(id: Long): BookmarkEntity?
    @Query("SELECT * FROM bookmarks WHERE id = :id") fun observe(id: Long): Flow<BookmarkEntity?>
    @Query("SELECT * FROM bookmarks WHERE normalizedUrl = :norm LIMIT 1") suspend fun findByNormalized(norm: String): BookmarkEntity?
    @Query("SELECT * FROM bookmarks ORDER BY createdAt DESC") fun observeAll(): Flow<List<BookmarkEntity>>
    @Query("SELECT * FROM bookmarks") suspend fun all(): List<BookmarkEntity>
    @Query("SELECT * FROM bookmarks WHERE id IN (:ids)") suspend fun getMany(ids: List<Long>): List<BookmarkEntity>
    @Query("SELECT * FROM bookmarks ORDER BY createdAt DESC LIMIT :limit") fun observeRecent(limit: Int): Flow<List<BookmarkEntity>>
    @Query("SELECT * FROM bookmarks WHERE isFavorite = 1 ORDER BY updatedAt DESC") fun observeFavorites(): Flow<List<BookmarkEntity>>
    @Query("SELECT * FROM bookmarks WHERE collectionId = :id ORDER BY createdAt DESC") fun observeInCollection(id: Long): Flow<List<BookmarkEntity>>
    @Query("UPDATE bookmarks SET collectionId = :collectionId, updatedAt = :at WHERE id IN (:ids)") suspend fun moveMany(ids: List<Long>, collectionId: Long?, at: Long)
    @Query("UPDATE bookmarks SET isFavorite = :fav, updatedAt = :at WHERE id IN (:ids)") suspend fun setFavorite(ids: List<Long>, fav: Boolean, at: Long)
    @Query("SELECT COUNT(*) FROM bookmarks") fun observeCount(): Flow<Int>
    @Query(
        """SELECT * FROM bookmarks WHERE title LIKE :q ESCAPE '\' OR url LIKE :q ESCAPE '\' OR note LIKE :q ESCAPE '\'
           ORDER BY updatedAt DESC LIMIT 500"""
    )
    suspend fun searchLike(q: String): List<BookmarkEntity>
}

@Dao
interface TagDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(tag: TagEntity): Long
    @Query("SELECT * FROM tags WHERE name = :name COLLATE NOCASE LIMIT 1") suspend fun findByName(name: String): TagEntity?
    @Query("SELECT * FROM tags ORDER BY name COLLATE NOCASE") fun observeAll(): Flow<List<TagEntity>>
    @Query("SELECT * FROM tags") suspend fun all(): List<TagEntity>
    @Query("DELETE FROM tags WHERE id NOT IN (SELECT tagId FROM document_tags) AND id NOT IN (SELECT tagId FROM bookmark_tags)")
    suspend fun deleteOrphans()

    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun addDocTag(ref: DocumentTagRef)
    @Query("DELETE FROM document_tags WHERE documentId = :docId") suspend fun clearDocTags(docId: Long)
    @Query("SELECT t.* FROM tags t JOIN document_tags r ON r.tagId = t.id WHERE r.documentId = :docId ORDER BY t.name")
    fun observeDocTags(docId: Long): Flow<List<TagEntity>>
    @Query("SELECT * FROM document_tags") fun observeAllDocTagRefs(): Flow<List<DocumentTagRef>>
    @Query("SELECT * FROM document_tags") suspend fun allDocTagRefs(): List<DocumentTagRef>

    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun addBookmarkTag(ref: BookmarkTagRef)
    @Query("DELETE FROM bookmark_tags WHERE bookmarkId = :id") suspend fun clearBookmarkTags(id: Long)
    @Query("SELECT t.* FROM tags t JOIN bookmark_tags r ON r.tagId = t.id WHERE r.bookmarkId = :id ORDER BY t.name")
    fun observeBookmarkTags(id: Long): Flow<List<TagEntity>>
    @Query("SELECT * FROM bookmark_tags") fun observeAllBookmarkTagRefs(): Flow<List<BookmarkTagRef>>
    @Query("SELECT * FROM bookmark_tags") suspend fun allBookmarkTagRefs(): List<BookmarkTagRef>

    @Query(
        """SELECT t.id, t.name,
           (SELECT COUNT(*) FROM document_tags d WHERE d.tagId = t.id) + (SELECT COUNT(*) FROM bookmark_tags b WHERE b.tagId = t.id) AS count
           FROM tags t ORDER BY t.name COLLATE NOCASE"""
    )
    fun observeTagCounts(): Flow<List<TagCount>>
}

@Dao
interface NoteDao {
    @Insert suspend fun insert(n: NoteEntity): Long
    @Update suspend fun update(n: NoteEntity)
    @Delete suspend fun delete(n: NoteEntity)
    @Query("SELECT * FROM notes WHERE documentId = :docId ORDER BY updatedAt DESC") fun observeForDocument(docId: Long): Flow<List<NoteEntity>>
    @Query("SELECT * FROM notes") suspend fun all(): List<NoteEntity>
    @Query("SELECT * FROM notes WHERE body LIKE :q ESCAPE '\\' ORDER BY updatedAt DESC LIMIT 300") suspend fun searchLike(q: String): List<NoteEntity>
}

@Dao
interface ReadingDao {
    @Upsert suspend fun saveProgress(p: ReadingProgressEntity)
    @Query("SELECT * FROM reading_progress WHERE documentId = :docId") suspend fun progress(docId: Long): ReadingProgressEntity?
    @Query("SELECT * FROM reading_progress") suspend fun allProgress(): List<ReadingProgressEntity>
    @Query("SELECT * FROM reading_progress") fun observeAllProgress(): Flow<List<ReadingProgressEntity>>
    @Insert suspend fun addHistory(h: ReadingHistoryEntity)
    @Query("SELECT * FROM reading_history ORDER BY openedAt DESC LIMIT :limit") fun observeHistory(limit: Int): Flow<List<ReadingHistoryEntity>>
    @Query("DELETE FROM reading_history WHERE openedAt < :before") suspend fun pruneHistory(before: Long)
    @Query("DELETE FROM reading_history") suspend fun clearHistory()
}

@Dao
interface AnnotationDao {
    @Insert suspend fun insert(a: AnnotationEntity): Long
    @Update suspend fun update(a: AnnotationEntity)
    @Delete suspend fun delete(a: AnnotationEntity)
    @Query("SELECT * FROM annotations WHERE documentId = :docId ORDER BY position, createdAt") fun observeForDocument(docId: Long): Flow<List<AnnotationEntity>>
    @Query("SELECT * FROM annotations") suspend fun all(): List<AnnotationEntity>
    @Query("SELECT * FROM annotations WHERE note LIKE :q ESCAPE '\\' OR quote LIKE :q ESCAPE '\\' LIMIT 300") suspend fun searchLike(q: String): List<AnnotationEntity>
}

@Dao
interface BrowserDao {
    @Insert suspend fun addHistory(h: BrowserHistoryEntity): Long
    @Query("SELECT * FROM browser_history ORDER BY visitedAt DESC LIMIT :limit") fun observeHistory(limit: Int): Flow<List<BrowserHistoryEntity>>
    @Query("SELECT * FROM browser_history WHERE url = :url ORDER BY visitedAt DESC LIMIT 1") suspend fun lastVisit(url: String): BrowserHistoryEntity?
    @Query("UPDATE browser_history SET title = :title WHERE id = :id") suspend fun setTitle(id: Long, title: String)
    @Query("DELETE FROM browser_history") suspend fun clearHistory()
    @Query("DELETE FROM browser_history WHERE id = :id") suspend fun deleteHistory(id: Long)
    @Query("DELETE FROM browser_history WHERE visitedAt < :before") suspend fun pruneHistory(before: Long)
    @Query("SELECT * FROM browser_history WHERE url LIKE :q ESCAPE '\\' OR title LIKE :q ESCAPE '\\' ORDER BY visitedAt DESC LIMIT 8")
    suspend fun suggest(q: String): List<BrowserHistoryEntity>

    @Insert suspend fun insertDownload(d: DownloadEntity): Long
    @Update suspend fun updateDownload(d: DownloadEntity)
    @Query("SELECT * FROM downloads WHERE id = :id") suspend fun download(id: Long): DownloadEntity?
    @Query("SELECT * FROM downloads ORDER BY createdAt DESC LIMIT 200") fun observeDownloads(): Flow<List<DownloadEntity>>
    @Query("DELETE FROM downloads WHERE status != 'RUNNING'") suspend fun clearFinishedDownloads()
}

@Dao
abstract class SearchDao {
    @Query("INSERT INTO search_index(ownerType, ownerId, location, locationLabel, content) VALUES (:ownerType, :ownerId, :location, :label, :content)")
    abstract suspend fun insert(ownerType: String, ownerId: String, location: String, label: String, content: String)

    @Query("DELETE FROM search_index WHERE ownerType = :ownerType AND ownerId = :ownerId")
    abstract suspend fun deleteOwner(ownerType: String, ownerId: String)

    @Query("DELETE FROM search_index") abstract suspend fun clear()

    @Query(
        """SELECT ownerType, ownerId, location, locationLabel,
           snippet(search_index, '[[', ']]', '…', 4, 14) AS snippet
           FROM search_index WHERE search_index MATCH :query LIMIT :limit"""
    )
    abstract suspend fun search(query: String, limit: Int): List<SearchHit>

    @Query(
        """SELECT ownerType, ownerId, location, locationLabel,
           snippet(search_index, '[[', ']]', '…', 4, 14) AS snippet
           FROM search_index WHERE search_index MATCH :query AND ownerType = 'doc' AND ownerId = :docId LIMIT 500"""
    )
    abstract suspend fun searchInDocument(query: String, docId: String): List<SearchHit>

    @Query("SELECT COUNT(*) FROM search_index") abstract fun observeRowCount(): Flow<Int>

    @Transaction
    open suspend fun replaceOwner(ownerType: String, ownerId: String, blocks: List<Triple<String, String, String>>) {
        deleteOwner(ownerType, ownerId)
        for ((location, label, content) in blocks) insert(ownerType, ownerId, location, label, content)
    }
}
