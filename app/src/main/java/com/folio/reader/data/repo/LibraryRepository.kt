package com.folio.reader.data.repo

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.room.withTransaction
import com.folio.reader.core.format.DocFormat
import com.folio.reader.core.format.FormatDetector
import com.folio.reader.core.security.Limits
import com.folio.reader.core.security.SafeZip
import com.folio.reader.core.security.UrlSafety
import com.folio.reader.data.db.AppDatabase
import com.folio.reader.data.db.DocumentCollectionRef
import com.folio.reader.data.db.DocumentEntity
import com.folio.reader.data.db.DocumentTagRef
import com.folio.reader.data.db.FolderEntity
import com.folio.reader.data.db.ReadingHistoryEntity
import com.folio.reader.data.db.ReadingProgressEntity
import com.folio.reader.data.db.TagEntity
import com.folio.reader.data.files.FileStore
import com.folio.reader.data.files.ThumbnailGenerator
import com.folio.reader.work.IndexScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

sealed interface ImportResult {
    data class Imported(val doc: DocumentEntity) : ImportResult
    data class Duplicate(val existing: DocumentEntity) : ImportResult
    data class Failed(val name: String, val reason: String) : ImportResult
}

@Singleton
class LibraryRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: AppDatabase,
    private val files: FileStore,
    private val thumbs: ThumbnailGenerator,
    private val indexer: IndexScheduler,
) {
    private val docs = db.documents()
    private val resolver: ContentResolver get() = context.contentResolver

    fun observeAll(): Flow<List<DocumentEntity>> = docs.observeAll()
    fun observe(id: Long) = docs.observe(id)
    fun observeRecentlyOpened(limit: Int = 12) = docs.observeRecentlyOpened(limit)
    fun observeRecentlyAdded(limit: Int = 12) = docs.observeRecentlyAdded(limit)
    fun observeFavorites() = docs.observeFavorites()
    fun observeContinueReading(limit: Int = 10) = docs.observeContinueReading(limit)
    fun observeFolders() = db.folders().observeAll()
    fun observeDocTags(id: Long) = db.tags().observeDocTags(id)
    fun observeAllDocTagRefs() = db.tags().observeAllDocTagRefs()
    fun observeTags() = db.tags().observeAll()
    fun observeProgress() = db.reading().observeAllProgress()
    fun observeTotalSize() = docs.observeTotalSize()
    suspend fun get(id: Long) = docs.get(id)
    fun fileOf(doc: DocumentEntity): File = files.libraryFile(doc.storedName)
    fun formatOf(doc: DocumentEntity): DocFormat = DocFormat.entries.firstOrNull { it.name == doc.format } ?: DocFormat.TXT

    /** Imports from a content:// Uri (system picker, share sheet, "open with"). */
    suspend fun importUri(uri: Uri, sourceUrl: String? = null, allowDuplicate: Boolean = false): ImportResult = withContext(Dispatchers.IO) {
        var name = "document"
        var size = -1L
        try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getString(0)?.let { name = it }
                    if (!c.isNull(1)) size = c.getLong(1)
                }
            }
        } catch (e: Exception) { /* some providers don't support queries */ }
        if (size > Limits.MAX_IMPORT_BYTES) return@withContext ImportResult.Failed(name, "File is larger than the 1 GB limit.")
        val mime = runCatching { resolver.getType(uri) }.getOrNull()
        val input = try { resolver.openInputStream(uri) } catch (e: Exception) { null }
            ?: return@withContext ImportResult.Failed(name, "Couldn't read the file.")
        input.use { importStream(it, name, mime, sourceUrl, allowDuplicate) }
    }

    /** Imports a file the app itself produced (browser download). The source file is consumed. */
    suspend fun importLocalFile(file: File, name: String, mime: String?, sourceUrl: String?, allowDuplicate: Boolean = false): ImportResult =
        withContext(Dispatchers.IO) {
            try { file.inputStream().use { importStream(it, name, mime, sourceUrl, allowDuplicate) } } finally { file.delete() }
        }

    private suspend fun importStream(input: InputStream, rawName: String, mime: String?, sourceUrl: String?, allowDuplicate: Boolean): ImportResult {
        val name = UrlSafety.safeFileName(rawName, "document")
        val tmp = files.newTempFile()
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            val header = ByteArray(4096)
            var headerLen = 0
            DigestInputStream(input, digest).use { din ->
                tmp.outputStream().use { out ->
                    // Capture the first bytes for format sniffing while copying.
                    while (headerLen < header.size) {
                        val n = din.read(header, headerLen, header.size - headerLen)
                        if (n < 0) break
                        headerLen += n
                    }
                    out.write(header, 0, headerLen)
                    SafeZip.copyBounded(din, out, Limits.MAX_IMPORT_BYTES - headerLen)
                }
            }
            if (tmp.length() == 0L) return ImportResult.Failed(name, "The file is empty.")
            val sha = digest.digest().joinToString("") { "%02x".format(it) }

            val format = when (val r = FormatDetector.detect(header.copyOf(headerLen), name, mime)) {
                is FormatDetector.Result.Ok -> r.format
                is FormatDetector.Result.Unsupported -> r.format // kept in library, opened with a clear "no viewer" notice
                is FormatDetector.Result.Rejected -> return ImportResult.Failed(name, r.reason)
            }

            if (!allowDuplicate) docs.findBySha(sha)?.let { return ImportResult.Duplicate(it) }

            val ext = name.substringAfterLast('.', "").takeIf { it.isNotEmpty() && format.extensions.contains(it.lowercase()) }
                ?: format.extensions.first()
            val stored = files.newStoredName(ext)
            val target = files.libraryFile(stored)
            if (!tmp.renameTo(target)) {
                tmp.inputStream().use { i -> target.outputStream().use { o -> i.copyTo(o) } }
            }
            val now = System.currentTimeMillis()
            val entity = DocumentEntity(
                title = name.substringBeforeLast('.').ifBlank { name },
                originalName = name,
                storedName = stored,
                format = format.name,
                mime = format.mime,
                sizeBytes = target.length(),
                sha256 = sha,
                sourceUrl = sourceUrl,
                addedAt = now,
            )
            val id = docs.insert(entity)
            val info = thumbs.inspect(id, target, format, id.toString())
            val updated = entity.copy(
                id = id,
                title = info.title?.take(300) ?: entity.title,
                author = info.author?.take(200),
                pageCount = info.pageCount,
                thumbnailName = info.thumbnailName,
            )
            docs.update(updated)
            if (format.supported) indexer.indexPending()
            return ImportResult.Imported(updated)
        } catch (e: Exception) {
            return ImportResult.Failed(name, e.message ?: "Import failed.")
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    suspend fun rename(id: Long, title: String) = docs.rename(id, title.trim().take(300).ifBlank { "Untitled" })
    suspend fun setFavorite(id: Long, fav: Boolean) = docs.setFavorite(id, fav)
    suspend fun moveToFolder(ids: List<Long>, folderId: Long?) = docs.moveToFolder(ids, folderId)

    /** Deletes the record and its stored file. Only ever called after explicit user confirmation. */
    suspend fun delete(id: Long) = withContext(Dispatchers.IO) {
        val doc = docs.get(id) ?: return@withContext
        db.withTransaction {
            db.search().deleteOwner("doc", id.toString())
            docs.delete(doc)
        }
        files.libraryFile(doc.storedName).delete()
        doc.thumbnailName?.let { files.thumbFile(it).delete() }
        java.io.File(files.epubCacheDir, id.toString()).deleteRecursively()
        db.tags().deleteOrphans()
    }

    /** Copies a library file to a user-chosen location (Storage Access Framework). */
    suspend fun export(id: Long, dest: Uri): Boolean = withContext(Dispatchers.IO) {
        val doc = docs.get(id) ?: return@withContext false
        runCatching {
            resolver.openOutputStream(dest, "w")?.use { out -> fileOf(doc).inputStream().use { it.copyTo(out) } } != null
        }.getOrDefault(false)
    }

    suspend fun markOpened(id: Long) {
        val now = System.currentTimeMillis()
        docs.touchOpened(id, now)
        db.reading().addHistory(ReadingHistoryEntity(documentId = id, openedAt = now))
    }

    suspend fun progress(id: Long) = db.reading().progress(id)
    suspend fun saveProgress(id: Long, position: Int, offset: Float, fraction: Float) =
        db.reading().saveProgress(ReadingProgressEntity(id, position, offset.coerceIn(0f, 1f), fraction.coerceIn(0f, 1f), System.currentTimeMillis()))

    suspend fun setPageCount(id: Long, count: Int) = docs.setPageCount(id, count)

    // Folders
    suspend fun createFolder(name: String, parentId: Long?) =
        db.folders().insert(FolderEntity(name = name.trim().take(120).ifBlank { "Folder" }, parentId = parentId, createdAt = System.currentTimeMillis()))
    suspend fun renameFolder(f: FolderEntity, name: String) = db.folders().update(f.copy(name = name.trim().take(120).ifBlank { f.name }))
    /** Deleting a folder keeps its documents: they move to the library root (FK SET NULL). Sub-folders are removed. */
    suspend fun deleteFolder(f: FolderEntity) = db.folders().delete(f)

    // Tags
    suspend fun setDocumentTags(docId: Long, names: List<String>) = db.withTransaction {
        db.tags().clearDocTags(docId)
        for (n in names.map { it.trim().take(60) }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }) {
            val tag = db.tags().findByName(n) ?: TagEntity(id = db.tags().insert(TagEntity(name = n)), name = n)
            db.tags().addDocTag(DocumentTagRef(docId, tag.id))
        }
        db.tags().deleteOrphans()
    }

    // Collections (documents can belong to several)
    suspend fun addToCollection(docId: Long, collectionId: Long) = db.collections().addDocument(DocumentCollectionRef(docId, collectionId))
    suspend fun removeFromCollection(docId: Long, collectionId: Long) = db.collections().removeDocument(DocumentCollectionRef(docId, collectionId))
    fun observeCollectionsOf(docId: Long) = db.collections().observeCollectionsOfDocument(docId)
}
