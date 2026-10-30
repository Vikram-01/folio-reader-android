package com.folio.reader.data.repo

import com.folio.reader.core.format.DocFormat
import com.folio.reader.data.db.AppDatabase
import com.folio.reader.data.db.BookmarkEntity
import com.folio.reader.data.db.DocumentEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

enum class ResultKind { DOCUMENT, BOOKMARK, NOTE, COLLECTION, TAG }

data class SearchFilters(
    val kinds: Set<ResultKind> = emptySet(),          // empty = all
    val formats: Set<DocFormat> = emptySet(),
    val bookmarkTypes: Set<String> = emptySet(),
    val collectionId: Long? = null,
    val folderId: Long? = null,
    val tagIds: Set<Long> = emptySet(),
    val favoritesOnly: Boolean = false,
    val offlineOnly: Boolean = false,
    val addedAfter: Long? = null,
    val openedAfter: Long? = null,
)

data class SearchResult(
    val kind: ResultKind,
    val id: Long,
    val title: String,
    val subtitle: String,
    /** Text with matches wrapped in [[ ]]. */
    val snippet: String? = null,
    val location: Int? = null,
    val locationLabel: String? = null,
    val format: DocFormat? = null,
    val score: Int = 0,
    val url: String? = null,
)

@Singleton
class SearchRepository @Inject constructor(private val db: AppDatabase) {

    /** Builds a safe FTS4 MATCH expression: every word must match, as a prefix. */
    fun ftsQuery(raw: String): String? {
        val tokens = raw.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotBlank() }.take(12)
        if (tokens.isEmpty()) return null
        return tokens.joinToString(" ") { "$it*" }
    }

    private fun like(raw: String) = "%" + raw.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"

    suspend fun search(raw: String, f: SearchFilters): List<SearchResult> = withContext(Dispatchers.IO) {
        val q = raw.trim()
        if (q.isEmpty()) return@withContext emptyList()
        val want = { k: ResultKind -> f.kinds.isEmpty() || k in f.kinds }
        val results = ArrayList<SearchResult>()
        val fts = ftsQuery(q)
        val hits = if (fts != null) runCatching { db.search().search(fts, 400) }.getOrDefault(emptyList()) else emptyList()

        val allDocs: Map<Long, DocumentEntity> = db.documents().all().associateBy { it.id }
        val docTags = db.tags().allDocTagRefs().groupBy({ it.documentId }, { it.tagId })
        val docCollections = db.collections().allDocumentRefs().groupBy({ it.documentId }, { it.collectionId })

        fun docPasses(d: DocumentEntity): Boolean {
            val fmt = DocFormat.entries.firstOrNull { it.name == d.format }
            if (f.formats.isNotEmpty() && fmt !in f.formats) return false
            if (f.folderId != null && d.folderId != f.folderId) return false
            if (f.collectionId != null && f.collectionId !in docCollections[d.id].orEmpty()) return false
            if (f.tagIds.isNotEmpty() && docTags[d.id].orEmpty().none { it in f.tagIds }) return false
            if (f.favoritesOnly && !d.isFavorite) return false
            if (f.addedAfter != null && d.addedAt < f.addedAfter) return false
            if (f.openedAfter != null && (d.lastOpenedAt ?: 0) < f.openedAfter) return false
            if (f.bookmarkTypes.isNotEmpty() && f.kinds.isEmpty()) return false
            return true
        }

        if (want(ResultKind.DOCUMENT)) {
            val ql = q.lowercase()
            val seen = HashSet<Long>()
            allDocs.values.filter { (it.title.lowercase().contains(ql) || it.originalName.lowercase().contains(ql) ||
                    it.author?.lowercase()?.contains(ql) == true || it.sourceUrl?.lowercase()?.contains(ql) == true) && docPasses(it) }
                .forEach { d ->
                    seen += d.id
                    results += SearchResult(ResultKind.DOCUMENT, d.id, d.title, "Title match · ${fmtLabel(d)}", format = DocFormat.entries.firstOrNull { it.name == d.format }, score = 100)
                }
            hits.filter { it.ownerType == "doc" }.forEach { h ->
                val id = h.ownerId.toLongOrNull() ?: return@forEach
                val d = allDocs[id] ?: return@forEach
                if (!docPasses(d)) return@forEach
                results += SearchResult(ResultKind.DOCUMENT, id, d.title, "${h.locationLabel} · ${fmtLabel(d)}", h.snippet,
                    h.location.toIntOrNull(), h.locationLabel, DocFormat.entries.firstOrNull { it.name == d.format }, if (id in seen) 60 else 50)
            }
        }

        if (want(ResultKind.BOOKMARK)) {
            val bmTags = db.tags().allBookmarkTagRefs().groupBy({ it.bookmarkId }, { it.tagId })
            fun bmPasses(b: BookmarkEntity): Boolean {
                if (f.bookmarkTypes.isNotEmpty() && b.type !in f.bookmarkTypes) return false
                if (f.collectionId != null && b.collectionId != f.collectionId) return false
                if (f.tagIds.isNotEmpty() && bmTags[b.id].orEmpty().none { it in f.tagIds }) return false
                if (f.favoritesOnly && !b.isFavorite) return false
                if (f.offlineOnly && b.offlineName == null) return false
                if (f.addedAfter != null && b.createdAt < f.addedAfter) return false
                if (f.formats.isNotEmpty() && f.kinds.isEmpty()) return false
                if (f.folderId != null) return false
                return true
            }
            val direct = db.bookmarks().searchLike(like(q)).filter(::bmPasses)
            val seen = direct.map { it.id }.toHashSet()
            direct.forEach { b -> results += SearchResult(ResultKind.BOOKMARK, b.id, b.title, b.url, b.note.takeIf { it.isNotBlank() }?.take(160), score = 90, url = b.url) }
            val ids = hits.filter { it.ownerType == "bookmark" }.mapNotNull { it.ownerId.toLongOrNull() }.filter { it !in seen }
            if (ids.isNotEmpty()) {
                val byId = db.bookmarks().getMany(ids).associateBy { it.id }
                hits.filter { it.ownerType == "bookmark" }.forEach { h ->
                    val b = h.ownerId.toLongOrNull()?.let { byId[it] } ?: return@forEach
                    if (bmPasses(b)) results += SearchResult(ResultKind.BOOKMARK, b.id, b.title, "Saved page · ${b.url}", h.snippet, score = 45, url = b.url)
                }
            }
        }

        if (want(ResultKind.NOTE) && f.formats.isEmpty() && f.bookmarkTypes.isEmpty()) {
            db.notes().searchLike(like(q)).forEach { n ->
                val owner = n.documentId?.let { allDocs[it]?.title } ?: "Bookmark note"
                results += SearchResult(ResultKind.NOTE, n.documentId ?: n.bookmarkId ?: 0, owner, "Note", n.body.take(200), score = 40)
            }
            db.annotations().searchLike(like(q)).forEach { a ->
                val d = allDocs[a.documentId] ?: return@forEach
                results += SearchResult(ResultKind.NOTE, d.id, d.title, "Note · ${a.label}", (a.note.ifBlank { a.quote }).take(200), a.position, a.label, score = 40)
            }
        }

        if (want(ResultKind.COLLECTION) && f.formats.isEmpty() && f.bookmarkTypes.isEmpty()) {
            db.collections().all().filter { it.name.contains(q, ignoreCase = true) }.forEach {
                results += SearchResult(ResultKind.COLLECTION, it.id, it.name, "Collection", score = 80)
            }
        }
        if (want(ResultKind.TAG) && f.formats.isEmpty() && f.bookmarkTypes.isEmpty()) {
            db.tags().all().filter { it.name.contains(q, ignoreCase = true) }.forEach {
                results += SearchResult(ResultKind.TAG, it.id, "#${it.name}", "Tag", score = 70)
            }
        }
        results.sortedByDescending { it.score }
    }

    suspend fun searchInDocument(docId: Long, raw: String) = withContext(Dispatchers.IO) {
        val fts = ftsQuery(raw) ?: return@withContext emptyList()
        runCatching { db.search().searchInDocument(fts, docId.toString()) }.getOrDefault(emptyList())
            .sortedBy { it.location.toIntOrNull() ?: 0 }
    }

    private fun fmtLabel(d: DocumentEntity) = DocFormat.entries.firstOrNull { it.name == d.format }?.label ?: d.format
}
