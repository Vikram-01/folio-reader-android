package com.folio.reader.data.repo

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.room.withTransaction
import com.folio.reader.core.security.Limits
import com.folio.reader.core.security.SafeZip
import com.folio.reader.core.security.UrlSafety
import com.folio.reader.data.db.AppDatabase
import com.folio.reader.data.db.BookmarkEntity
import com.folio.reader.data.db.BookmarkTagRef
import com.folio.reader.data.db.BookmarkType
import com.folio.reader.data.db.CollectionEntity
import com.folio.reader.data.db.TagEntity
import com.folio.reader.data.files.FileStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.safety.Safelist
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton

sealed interface SaveResult {
    data class Saved(val id: Long) : SaveResult
    data class Duplicate(val existing: BookmarkEntity) : SaveResult
    data class Invalid(val reason: String) : SaveResult
}

data class ImportSummary(val added: Int, val skippedDuplicates: Int, val invalid: Int)

@Singleton
class BookmarkRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: AppDatabase,
    private val files: FileStore,
) {
    private val dao = db.bookmarks()

    fun observeAll() = dao.observeAll()
    fun observe(id: Long) = dao.observe(id)
    fun observeRecent(limit: Int = 10) = dao.observeRecent(limit)
    fun observeFavorites() = dao.observeFavorites()
    fun observeInCollection(id: Long) = dao.observeInCollection(id)
    fun observeTags(id: Long) = db.tags().observeBookmarkTags(id)
    fun observeAllTagRefs() = db.tags().observeAllBookmarkTagRefs()
    suspend fun get(id: Long) = dao.get(id)
    suspend fun findDuplicate(url: String) = dao.findByNormalized(UrlSafety.normalize(url))

    /** Classifies a link by its URL only — no network request is made. */
    fun guessType(url: String): BookmarkType {
        val l = url.lowercase()
        val host = UrlSafety.hostOf(l)
        val path = l.substringBefore('?').substringBefore('#')
        return when {
            host.endsWith("youtube.com") || host == "youtu.be" || host.endsWith("vimeo.com") || host.endsWith("dailymotion.com") ||
                host.endsWith("twitch.tv") || path.endsWith(".mp4") || path.endsWith(".webm") -> BookmarkType.VIDEO
            path.endsWith(".pdf") -> BookmarkType.PDF
            listOf(".jpg", ".jpeg", ".png", ".gif", ".webp", ".svg", ".bmp").any { path.endsWith(it) } -> BookmarkType.IMAGE
            host.endsWith("medium.com") || host.endsWith("substack.com") || path.contains("/blog/") ||
                path.contains("/article") || path.contains("/news/") || path.contains("/post/") -> BookmarkType.ARTICLE
            else -> BookmarkType.LINK
        }
    }

    suspend fun save(
        url: String, title: String?, note: String = "", collectionId: Long? = null, tags: List<String> = emptyList(),
        type: BookmarkType? = null, allowDuplicate: Boolean = false,
    ): SaveResult {
        val clean = url.trim()
        if (!UrlSafety.isWebUrl(clean) || clean.length > 4096) return SaveResult.Invalid("Only http and https links can be saved.")
        val norm = UrlSafety.normalize(clean)
        if (!allowDuplicate) dao.findByNormalized(norm)?.let { return SaveResult.Duplicate(it) }
        val now = System.currentTimeMillis()
        val id = db.withTransaction {
            val id = dao.insert(
                BookmarkEntity(
                    url = clean, normalizedUrl = norm,
                    title = title?.trim()?.take(500)?.ifBlank { null } ?: UrlSafety.hostOf(clean),
                    note = note.take(20_000), type = (type ?: guessType(clean)).name,
                    collectionId = collectionId, createdAt = now, updatedAt = now,
                )
            )
            setTagsInternal(id, tags)
            id
        }
        return SaveResult.Saved(id)
    }

    suspend fun update(b: BookmarkEntity, tags: List<String>?) = db.withTransaction {
        val url = b.url.trim()
        dao.update(b.copy(url = url, normalizedUrl = UrlSafety.normalize(url), title = b.title.take(500), note = b.note.take(20_000),
            updatedAt = System.currentTimeMillis()))
        if (tags != null) setTagsInternal(b.id, tags)
    }

    private suspend fun setTagsInternal(id: Long, names: List<String>) {
        db.tags().clearBookmarkTags(id)
        for (n in names.map { it.trim().take(60) }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }) {
            val tag = db.tags().findByName(n) ?: TagEntity(id = db.tags().insert(TagEntity(name = n)), name = n)
            db.tags().addBookmarkTag(BookmarkTagRef(id, tag.id))
        }
        db.tags().deleteOrphans()
    }

    suspend fun delete(ids: List<Long>) = withContext(Dispatchers.IO) {
        val items = dao.getMany(ids)
        db.withTransaction {
            ids.forEach { db.search().deleteOwner("bookmark", it.toString()) }
            dao.deleteMany(ids)
        }
        items.forEach { b ->
            b.offlineName?.let { runCatching { files.pageFile(it).delete() } }
            b.thumbnailName?.let { runCatching { files.thumbFile(it).delete() } }
        }
        db.tags().deleteOrphans()
    }

    suspend fun move(ids: List<Long>, collectionId: Long?) = dao.moveMany(ids, collectionId, System.currentTimeMillis())
    suspend fun setFavorite(ids: List<Long>, fav: Boolean) = dao.setFavorite(ids, fav, System.currentTimeMillis())

    /** Stores the favicon the WebView already received; no extra request is made. */
    suspend fun saveFavicon(url: String, icon: Bitmap) = withContext(Dispatchers.IO) {
        val b = dao.findByNormalized(UrlSafety.normalize(url)) ?: return@withContext
        val name = "fav_" + UrlSafety.hostOf(url).replace(Regex("[^a-zA-Z0-9.-]"), "_").take(80) + ".png"
        runCatching {
            files.faviconFile(name).outputStream().use { icon.compress(Bitmap.CompressFormat.PNG, 100, it) }
            dao.update(b.copy(faviconName = name))
        }
    }

    suspend fun saveThumbnail(id: Long, bmp: Bitmap) = withContext(Dispatchers.IO) {
        val b = dao.get(id) ?: return@withContext
        val name = "bm_$id.jpg"
        runCatching {
            files.thumbFile(name).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 80, it) }
            dao.update(b.copy(thumbnailName = name))
        }
    }

    /**
     * Saves a web page for offline reading. The HTML is reduced to readable
     * article markup with jsoup's allow-list: scripts, iframes, forms, event
     * handlers and remote resources are removed before anything is stored.
     */
    suspend fun saveOffline(id: Long, html: String, pageUrl: String): Boolean = withContext(Dispatchers.IO) {
        val b = dao.get(id) ?: return@withContext false
        if (html.length > Limits.MAX_SAVED_PAGE_BYTES * 2) return@withContext false
        val article = Readability.extract(html, pageUrl)
        val name = "page_$id.html"
        files.pageFile(name).writeText(article.html)
        dao.update(b.copy(offlineName = name, offlineSavedAt = System.currentTimeMillis(),
            title = if (b.title == UrlSafety.hostOf(b.url) && article.title.isNotBlank()) article.title.take(500) else b.title,
            type = if (b.type == BookmarkType.LINK.name) BookmarkType.ARTICLE.name else b.type))
        db.search().replaceOwner("bookmark", id.toString(), listOf(Triple("0", "Saved page", article.text.take(2_000_000))))
        true
    }

    fun offlineHtml(b: BookmarkEntity): String? = b.offlineName?.let { n ->
        runCatching { files.pageFile(n).takeIf { it.isFile }?.readText() }.getOrNull()
    }

    suspend fun removeOffline(b: BookmarkEntity) = withContext(Dispatchers.IO) {
        b.offlineName?.let { runCatching { files.pageFile(it).delete() } }
        dao.update(b.copy(offlineName = null, offlineSavedAt = null))
        db.search().deleteOwner("bookmark", b.id.toString())
    }

    // Collections
    fun observeCollections() = db.collections().observeAll()
    suspend fun createCollection(name: String, parentId: Long?, color: Long = 0xFFE07A4F) =
        db.collections().insert(CollectionEntity(name = name.trim().take(120).ifBlank { "Collection" }, parentId = parentId, color = color, createdAt = System.currentTimeMillis()))
    suspend fun updateCollection(c: CollectionEntity) = db.collections().update(c)
    /** Bookmarks in a deleted collection are kept and become unsorted. */
    suspend fun deleteCollection(c: CollectionEntity) = db.collections().delete(c)

    /** Netscape bookmark file import (exported by Chrome, Firefox, Edge, Safari, Raindrop). */
    suspend fun importHtml(uri: Uri): ImportSummary = withContext(Dispatchers.IO) {
        val bytes = ByteArrayOutputStream().also { out ->
            context.contentResolver.openInputStream(uri)?.use { SafeZip.copyBounded(it, out, Limits.MAX_BOOKMARK_IMPORT_BYTES) }
                ?: throw java.io.IOException("Couldn't read the file.")
        }.toByteArray()
        val doc = Jsoup.parse(String(bytes, Charsets.UTF_8))
        var added = 0; var dup = 0; var bad = 0
        val folderCache = HashMap<String, Long?>()

        suspend fun collectionFor(path: List<String>): Long? {
            if (path.isEmpty()) return null
            val key = path.joinToString("\u0001")
            if (folderCache.containsKey(key)) return folderCache[key]
            val parent = collectionFor(path.dropLast(1))
            val existing = db.collections().findByName(path.last(), parent)
            val id = existing?.id ?: db.collections().insert(CollectionEntity(name = path.last().take(120), parentId = parent, createdAt = System.currentTimeMillis()))
            folderCache[key] = id
            return id
        }

        for (a in doc.select("a[href]")) {
            val href = a.attr("href")
            if (!UrlSafety.isWebUrl(href)) { bad++; continue }
            val path = folderPath(a)
            val tags = a.attr("tags").split(',').map { it.trim() }.filter { it.isNotEmpty() }
            val note = (a.parent()?.nextElementSibling()?.takeIf { it.tagName() == "dd" }?.text()).orEmpty()
            when (save(href, a.text(), note, collectionFor(path), tags)) {
                is SaveResult.Saved -> added++
                is SaveResult.Duplicate -> dup++
                is SaveResult.Invalid -> bad++
            }
        }
        ImportSummary(added, dup, bad)
    }

    private fun folderPath(a: Element): List<String> {
        val names = ArrayList<String>()
        var el: Element? = a.parent()
        while (el != null) {
            if (el.tagName() == "dl") {
                val header = el.previousElementSibling()?.takeIf { it.tagName() == "h3" }
                    ?: el.parent()?.takeIf { it.tagName() == "dt" }?.selectFirst("> h3")
                header?.text()?.trim()?.takeIf { it.isNotEmpty() && !it.equals("Bookmarks bar", true) && !it.equals("Bookmarks Toolbar", true) }
                    ?.let { names.add(0, it) }
            }
            el = el.parent()
        }
        return names.distinct().take(8)
    }

    suspend fun exportHtml(dest: Uri): Int = withContext(Dispatchers.IO) {
        val all = dao.all()
        val collections = db.collections().all()
        val tagsById = db.tags().all().associateBy { it.id }
        val tagRefs = db.tags().allBookmarkTagRefs().groupBy { it.bookmarkId }
        fun esc(s: String) = org.jsoup.nodes.Entities.escape(s)
        val sb = StringBuilder()
        sb.append("<!DOCTYPE NETSCAPE-Bookmark-file-1>\n<META HTTP-EQUIV=\"Content-Type\" CONTENT=\"text/html; charset=UTF-8\">\n<TITLE>Bookmarks</TITLE>\n<H1>Folio bookmarks</H1>\n<DL><p>\n")
        fun writeBookmarks(list: List<BookmarkEntity>, indent: String) {
            for (b in list) {
                val tags = tagRefs[b.id].orEmpty().mapNotNull { tagsById[it.tagId]?.name }.joinToString(",")
                sb.append("$indent<DT><A HREF=\"${esc(b.url)}\" ADD_DATE=\"${b.createdAt / 1000}\"")
                if (tags.isNotEmpty()) sb.append(" TAGS=\"${esc(tags)}\"")
                sb.append(">${esc(b.title)}</A>\n")
                if (b.note.isNotBlank()) sb.append("$indent<DD>${esc(b.note)}\n")
            }
        }
        fun writeCollection(parent: Long?, indent: String) {
            for (c in collections.filter { it.parentId == parent }.sortedBy { it.name.lowercase() }) {
                sb.append("$indent<DT><H3>${esc(c.name)}</H3>\n$indent<DL><p>\n")
                writeCollection(c.id, "$indent    ")
                writeBookmarks(all.filter { it.collectionId == c.id }, "$indent    ")
                sb.append("$indent</DL><p>\n")
            }
        }
        writeCollection(null, "    ")
        writeBookmarks(all.filter { it.collectionId == null }, "    ")
        sb.append("</DL><p>\n")
        context.contentResolver.openOutputStream(dest, "w")?.use { it.write(sb.toString().toByteArray()) }
            ?: throw java.io.IOException("Couldn't write the file.")
        all.size
    }
}

/** Small readability heuristic: picks the densest text container and sanitises it. */
object Readability {
    data class Article(val title: String, val html: String, val text: String)

    private val SAFE: Safelist = Safelist.relaxed()
        .removeTags("img") // offline pages never load remote images
        .addTags("figure", "figcaption", "hr", "pre", "code")
        .removeProtocols("a", "href", "ftp", "mailto")

    fun extract(html: String, baseUrl: String): Article {
        val doc = Jsoup.parse(html, baseUrl)
        val title = doc.selectFirst("meta[property=og:title]")?.attr("content")?.takeIf { it.isNotBlank() } ?: doc.title()
        doc.select("script, style, noscript, iframe, form, nav, header, footer, aside, button, input, svg, canvas, video, audio, object, embed").remove()
        val candidates = doc.select("article, main, [role=main], div, section")
        val best = candidates.maxByOrNull { el ->
            val text = el.ownText().length + el.select("> p").sumOf { it.text().length }
            text - el.select("a").sumOf { it.text().length } / 2
        }?.takeIf { it.select("p").size >= 2 } ?: doc.body()
        val cleanBody = Jsoup.clean(best?.html() ?: "", baseUrl, SAFE)
        val text = Jsoup.parse(cleanBody).text()
        return Article(title.trim(), cleanBody, text)
    }
}
