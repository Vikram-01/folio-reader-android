package com.folio.reader.data.repo

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.folio.reader.core.security.BackupCrypto
import com.folio.reader.core.security.Limits
import com.folio.reader.core.security.SafeZip
import com.folio.reader.core.security.UrlSafety
import com.folio.reader.data.db.AnnotationEntity
import com.folio.reader.data.db.AppDatabase
import com.folio.reader.data.db.BookmarkEntity
import com.folio.reader.data.db.BookmarkTagRef
import com.folio.reader.data.db.CollectionEntity
import com.folio.reader.data.db.DocumentCollectionRef
import com.folio.reader.data.db.DocumentEntity
import com.folio.reader.data.db.DocumentTagRef
import com.folio.reader.data.db.FolderEntity
import com.folio.reader.data.db.NoteEntity
import com.folio.reader.data.db.ReadingProgressEntity
import com.folio.reader.data.db.TagEntity
import com.folio.reader.data.files.FileStore
import com.folio.reader.work.IndexScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class BackupManifest(
    val format: String = "folio-backup",
    val version: Int = 1,
    val exportedAt: Long,
    val documents: List<DocumentEntity>,
    val folders: List<FolderEntity>,
    val collections: List<CollectionEntity>,
    val bookmarks: List<BookmarkEntity>,
    val tags: List<TagEntity>,
    val documentTags: List<DocumentTagRef>,
    val bookmarkTags: List<BookmarkTagRef>,
    val documentCollections: List<DocumentCollectionRef>,
    val notes: List<NoteEntity>,
    val progress: List<ReadingProgressEntity>,
    val annotations: List<AnnotationEntity>,
)

data class RestoreSummary(val documents: Int, val bookmarks: Int, val skipped: Int)

/**
 * Backups are a ZIP holding manifest.json plus the stored files, optionally
 * wrapped in password-based AES-256-GCM (see [BackupCrypto]).
 * Restore MERGES into the current library: nothing already in the app is
 * deleted or overwritten; duplicates (same file hash / same URL) are skipped.
 */
@Singleton
class BackupRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: AppDatabase,
    private val files: FileStore,
    private val indexer: IndexScheduler,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    suspend fun export(dest: Uri, password: CharArray?): Int = withContext(Dispatchers.IO) {
        val manifest = BackupManifest(
            exportedAt = System.currentTimeMillis(),
            documents = db.documents().all(), folders = db.folders().all(), collections = db.collections().all(),
            bookmarks = db.bookmarks().all(), tags = db.tags().all(), documentTags = db.tags().allDocTagRefs(),
            bookmarkTags = db.tags().allBookmarkTagRefs(), documentCollections = db.collections().allDocumentRefs(),
            notes = db.notes().all(), progress = db.reading().allProgress(), annotations = db.annotations().all(),
        )
        added.clear()
        val zipTmp = files.newTempFile(".zip")
        try {
            ZipOutputStream(zipTmp.outputStream().buffered()).use { zip ->
                zip.putNextEntry(ZipEntry("manifest.json"))
                zip.write(json.encodeToString(BackupManifest.serializer(), manifest).toByteArray())
                zip.closeEntry()
                for (d in manifest.documents) addFile(zip, "library/${d.storedName}", files.libraryFile(d.storedName))
                for (d in manifest.documents) d.thumbnailName?.let { addFile(zip, "thumbs/$it", files.thumbFile(it)) }
                for (b in manifest.bookmarks) {
                    b.offlineName?.let { addFile(zip, "pages/$it", files.pageFile(it)) }
                    b.faviconName?.let { addFile(zip, "favicons/$it", files.faviconFile(it)) }
                    b.thumbnailName?.let { addFile(zip, "thumbs/$it", files.thumbFile(it)) }
                }
            }
            val out = context.contentResolver.openOutputStream(dest, "w") ?: throw IOException("Couldn't write the backup.")
            out.use { o ->
                zipTmp.inputStream().use { i -> if (password != null && password.isNotEmpty()) BackupCrypto.encrypt(i, o, password) else i.copyTo(o) }
            }
            manifest.documents.size + manifest.bookmarks.size
        } finally {
            zipTmp.delete()
            password?.fill('\u0000')
        }
    }

    private val added = HashSet<String>()
    private fun addFile(zip: ZipOutputStream, name: String, file: File) {
        if (!file.isFile || !added.add(name)) return
        zip.putNextEntry(ZipEntry(name))
        file.inputStream().use { it.copyTo(zip) }
        zip.closeEntry()
    }

    /** Returns true if the chosen file is password-protected. */
    suspend fun isEncrypted(src: Uri): Boolean = withContext(Dispatchers.IO) {
        val head = ByteArray(8)
        context.contentResolver.openInputStream(src)?.use { it.read(head) } ?: return@withContext false
        BackupCrypto.isEncrypted(head)
    }

    suspend fun restore(src: Uri, password: CharArray?): RestoreSummary = withContext(Dispatchers.IO) {
        added.clear()
        val zipTmp = files.newTempFile(".zip")
        try {
            val input = context.contentResolver.openInputStream(src) ?: throw IOException("Couldn't read the backup.")
            input.buffered().use { inp ->
                inp.mark(16)
                val head = ByteArray(8); inp.read(head); inp.reset()
                zipTmp.outputStream().use { out ->
                    if (BackupCrypto.isEncrypted(head)) {
                        if (password == null || password.isEmpty()) throw IOException("This backup needs a password.")
                        BackupCrypto.decrypt(inp, out, password)
                    } else inp.copyTo(out)
                }
            }
            ZipFile(zipTmp).use { zip ->
                SafeZip.preflight(zip, maxEntries = 500_000, maxTotal = 200L shl 30, maxEntry = Limits.MAX_IMPORT_BYTES + 1)
                val manifestBytes = SafeZip.readEntry(zip, "manifest.json", 256L shl 20) ?: throw IOException("Not a ReadStash backup.")
                val m = json.decodeFromString(BackupManifest.serializer(), String(manifestBytes))
                if (m.format != "folio-backup" || m.version > 1) throw IOException("This backup was made by a newer version of ReadStash.")
                merge(zip, m)
            }
        } finally {
            zipTmp.delete()
            password?.fill('\u0000')
        }
    }

    private fun extractTo(zip: ZipFile, entryName: String, target: File): Boolean {
        if (!SafeZip.isSafeEntryName(entryName)) return false
        val e = zip.getEntry(entryName) ?: return false
        target.outputStream().use { o -> zip.getInputStream(e).use { SafeZip.copyBounded(it, o, Limits.MAX_IMPORT_BYTES) } }
        return true
    }

    private suspend fun merge(zip: ZipFile, m: BackupManifest): RestoreSummary {
        var docsAdded = 0; var bmAdded = 0; var skipped = 0
        val folderMap = HashMap<Long, Long>()
        val collectionMap = HashMap<Long, Long>()
        val tagMap = HashMap<Long, Long>()
        val docMap = HashMap<Long, Long>()
        val newDocs = HashSet<Long>()
        val bmMap = HashMap<Long, Long>()

        db.withTransaction {
            // Folders and collections: parents first, re-using same-named siblings.
            val existingFolders = db.folders().all().toMutableList()
            var remaining = m.folders
            var guard = 0
            while (remaining.isNotEmpty() && guard++ < 64) {
                val ready = remaining.filter { it.parentId == null || folderMap.containsKey(it.parentId) }
                for (f in ready) {
                    val parent = f.parentId?.let { folderMap[it] }
                    val match = existingFolders.firstOrNull { it.name.equals(f.name, true) && it.parentId == parent }
                    folderMap[f.id] = match?.id ?: db.folders().insert(f.copy(id = 0, parentId = parent)).also { id ->
                        existingFolders += f.copy(id = id, parentId = parent)
                    }
                }
                remaining = remaining - ready.toSet()
            }
            var remainingC = m.collections
            guard = 0
            while (remainingC.isNotEmpty() && guard++ < 64) {
                val ready = remainingC.filter { it.parentId == null || collectionMap.containsKey(it.parentId) }
                for (c in ready) {
                    val parent = c.parentId?.let { collectionMap[it] }
                    collectionMap[c.id] = db.collections().findByName(c.name, parent)?.id
                        ?: db.collections().insert(c.copy(id = 0, parentId = parent))
                }
                remainingC = remainingC - ready.toSet()
            }
            for (t in m.tags) {
                tagMap[t.id] = db.tags().findByName(t.name)?.id ?: db.tags().insert(TagEntity(name = t.name.take(60)))
            }
        }

        for (d in m.documents) {
            val existing = db.documents().findBySha(d.sha256)
            if (existing != null) { docMap[d.id] = existing.id; skipped++; continue }
            val ext = d.storedName.substringAfterLast('.', "bin")
            val stored = files.newStoredName(ext)
            val target = files.libraryFile(stored)
            if (!extractTo(zip, "library/${d.storedName}", target)) { skipped++; continue }
            var thumb: String? = null
            val newId = db.documents().insert(d.copy(id = 0, storedName = stored, thumbnailName = null, folderId = d.folderId?.let { folderMap[it] },
                indexedAt = null, indexError = null, sourceUrl = d.sourceUrl?.takeIf { UrlSafety.isWebUrl(it) }))
            d.thumbnailName?.let { old ->
                val name = "doc_$newId.jpg"
                if (extractTo(zip, "thumbs/$old", files.thumbFile(name))) thumb = name
            }
            if (thumb != null) db.documents().get(newId)?.let { db.documents().update(it.copy(thumbnailName = thumb)) }
            docMap[d.id] = newId; newDocs += newId; docsAdded++
        }

        for (b in m.bookmarks) {
            if (!UrlSafety.isWebUrl(b.url)) { skipped++; continue }
            val existing = db.bookmarks().findByNormalized(UrlSafety.normalize(b.url))
            if (existing != null) { bmMap[b.id] = existing.id; skipped++; continue }
            val newId = db.bookmarks().insert(b.copy(id = 0, collectionId = b.collectionId?.let { collectionMap[it] },
                offlineName = null, faviconName = null, thumbnailName = null, normalizedUrl = UrlSafety.normalize(b.url)))
            var updated = db.bookmarks().get(newId)!!
            b.offlineName?.let { old -> val n = "page_$newId.html"; if (extractTo(zip, "pages/$old", files.pageFile(n))) updated = updated.copy(offlineName = n) }
            b.faviconName?.let { old -> val n = UrlSafety.safeFileName(old); if (extractTo(zip, "favicons/$old", files.faviconFile(n))) updated = updated.copy(faviconName = n) }
            db.bookmarks().update(updated)
            bmMap[b.id] = newId; bmAdded++
        }

        db.withTransaction {
            m.documentTags.forEach { r -> val d = docMap[r.documentId]; val t = tagMap[r.tagId]; if (d != null && t != null) db.tags().addDocTag(DocumentTagRef(d, t)) }
            m.bookmarkTags.forEach { r -> val b = bmMap[r.bookmarkId]; val t = tagMap[r.tagId]; if (b != null && t != null) db.tags().addBookmarkTag(BookmarkTagRef(b, t)) }
            m.documentCollections.forEach { r -> val d = docMap[r.documentId]; val c = collectionMap[r.collectionId]; if (d != null && c != null) db.collections().addDocument(DocumentCollectionRef(d, c)) }
            // Per-document reading data only for documents that were new to this device.
            m.progress.forEach { p -> docMap[p.documentId]?.takeIf { it in newDocs }?.let { db.reading().saveProgress(p.copy(documentId = it)) } }
            m.annotations.forEach { a -> docMap[a.documentId]?.takeIf { it in newDocs }?.let { db.annotations().insert(a.copy(id = 0, documentId = it)) } }
            m.notes.forEach { n ->
                val d = n.documentId?.let { docMap[it] }?.takeIf { it in newDocs }
                val b = n.bookmarkId?.let { bmMap[it] }
                if (d != null || (n.documentId == null && b != null)) db.notes().insert(n.copy(id = 0, documentId = d, bookmarkId = if (d != null) null else b))
            }
            db.tags().deleteOrphans()
        }
        indexer.rebuild()
        return RestoreSummary(docsAdded, bmAdded, skipped)
    }
}
