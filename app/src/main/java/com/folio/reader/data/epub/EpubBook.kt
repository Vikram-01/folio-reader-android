package com.folio.reader.data.epub

import com.folio.reader.core.security.SafeZip
import com.folio.reader.core.security.UnsafeArchiveException
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.parser.Parser
import java.io.File
import java.io.IOException
import java.net.URLDecoder

data class EpubChapter(val index: Int, val href: String, val title: String)
data class EpubTocEntry(val title: String, val chapterIndex: Int, val depth: Int)

/**
 * Minimal, defensive EPUB 2/3 reader. The archive is unpacked once into a
 * private cache folder (zip-slip and zip-bomb checked). XML is parsed with
 * jsoup's XML parser, which never resolves external entities (no XXE).
 */
class EpubBook private constructor(
    val root: File,
    val title: String?,
    val author: String?,
    val chapters: List<EpubChapter>,
    val toc: List<EpubTocEntry>,
    val coverHref: String?,
) {
    fun chapterFile(index: Int): File? = chapters.getOrNull(index)?.let { resolve(it.href) }

    /** Resolves a book-relative path inside the unpacked folder, or null if it escapes. */
    fun resolve(path: String): File? = try {
        val clean = path.substringBefore('#').substringBefore('?')
        val decoded = URLDecoder.decode(clean.replace("+", "%2B"), "UTF-8")
        SafeZip.resolveInside(root, decoded).takeIf { it.isFile }
    } catch (e: Exception) { null }

    fun chapterText(index: Int): String {
        val f = chapterFile(index) ?: return ""
        if (f.length() > 16L shl 20) return ""
        return Jsoup.parse(f, "UTF-8").body()?.wholeText()?.replace(Regex("[ \\t\\x0B\\f\\r]+"), " ")
            ?.replace(Regex("\\n\\s*\\n+"), "\n\n")?.trim() ?: ""
    }

    companion object {
        private const val MARKER = ".folio-unpacked"

        fun open(epub: File, cacheDir: File): EpubBook {
            val root = File(cacheDir, epub.nameWithoutExtension)
            val marker = File(root, MARKER)
            if (!marker.exists() || marker.readText() != "${epub.length()}:${epub.lastModified()}") {
                root.deleteRecursively()
                SafeZip.extractAll(epub, root)
                marker.writeText("${epub.length()}:${epub.lastModified()}")
            }
            return parse(root)
        }

        private fun xml(file: File): Document {
            if (file.length() > 8L shl 20) throw UnsafeArchiveException("Book metadata is too large.")
            return Jsoup.parse(file.readText(), "", Parser.xmlParser())
        }

        private fun parse(root: File): EpubBook {
            val container = File(root, "META-INF/container.xml")
            if (!container.isFile) throw IOException("Not a valid EPUB (container.xml missing).")
            val opfPath = xml(container).selectFirst("rootfile")?.attr("full-path")
                ?: throw IOException("Not a valid EPUB (no package file).")
            val opfFile = SafeZip.resolveInside(root, opfPath)
            if (!opfFile.isFile) throw IOException("EPUB package file missing.")
            val opfDir = opfPath.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
            val opf = xml(opfFile)

            val title = opf.selectFirst("metadata > *|title, metadata title")?.text()?.takeIf { it.isNotBlank() }
            val author = opf.selectFirst("metadata > *|creator, metadata creator")?.text()?.takeIf { it.isNotBlank() }

            data class Item(val id: String, val href: String, val type: String, val props: String)
            val items = opf.select("manifest > item").map {
                Item(it.attr("id"), normalize(opfDir + it.attr("href")), it.attr("media-type"), it.attr("properties"))
            }
            val byId = items.associateBy { it.id }

            val spineIds = opf.select("spine > itemref").filter { it.attr("linear") != "no" }.map { it.attr("idref") }
            val chapterHrefs = spineIds.mapNotNull { byId[it] }
                .filter { it.type.contains("html") }
                .map { it.href }
                .distinct()
            if (chapterHrefs.isEmpty()) throw IOException("This EPUB has no readable chapters.")
            val hrefIndex = chapterHrefs.withIndex().associate { (i, h) -> h to i }

            val toc = mutableListOf<EpubTocEntry>()
            val navItem = items.firstOrNull { it.props.split(' ').contains("nav") }
            val ncxItem = opf.selectFirst("spine")?.attr("toc")?.let { byId[it] }
                ?: items.firstOrNull { it.type == "application/x-dtbncx+xml" }
            try {
                if (navItem != null) {
                    val navFile = SafeZip.resolveInside(root, navItem.href)
                    val navDir = navItem.href.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
                    val doc = Jsoup.parse(navFile, "UTF-8")
                    val nav = doc.select("nav").firstOrNull { it.attr("epub:type") == "toc" } ?: doc.selectFirst("nav")
                    nav?.select("a[href]")?.forEach { a ->
                        val target = normalize(navDir + a.attr("href").substringBefore('#'))
                        val idx = hrefIndex[target] ?: return@forEach
                        toc += EpubTocEntry(a.text().trim().ifBlank { "Chapter ${idx + 1}" }, idx, a.parents().count { it.tagName() == "ol" } - 1)
                    }
                } else if (ncxItem != null) {
                    val ncxFile = SafeZip.resolveInside(root, ncxItem.href)
                    val ncxDir = ncxItem.href.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
                    xml(ncxFile).select("navPoint").forEach { np ->
                        val src = np.selectFirst("content")?.attr("src") ?: return@forEach
                        val idx = hrefIndex[normalize(ncxDir + src.substringBefore('#'))] ?: return@forEach
                        val label = np.selectFirst("navLabel text")?.text()?.trim().orEmpty()
                        toc += EpubTocEntry(label.ifBlank { "Chapter ${idx + 1}" }, idx, np.parents().count { it.tagName() == "navPoint" })
                    }
                }
            } catch (e: Exception) {
                // A broken table of contents should not stop the book from opening.
            }

            val chapterTitles = HashMap<Int, String>()
            toc.forEach { chapterTitles.putIfAbsent(it.chapterIndex, it.title) }
            val chapters = chapterHrefs.mapIndexed { i, h -> EpubChapter(i, h, chapterTitles[i] ?: "Section ${i + 1}") }

            val coverId = opf.select("metadata meta[name=cover]").attr("content")
            val cover = items.firstOrNull { it.props.split(' ').contains("cover-image") }?.href
                ?: byId[coverId]?.takeIf { it.type.startsWith("image/") }?.href

            return EpubBook(root, title, author, chapters,
                toc.ifEmpty { chapters.map { EpubTocEntry(it.title, it.index, 0) } }, cover)
        }

        /** Collapses "a/./b/../c" style paths; percent-decoding happens on resolve. */
        fun normalize(path: String): String {
            val out = ArrayDeque<String>()
            for (seg in path.replace('\\', '/').split('/')) {
                when (seg) {
                    "", "." -> {}
                    ".." -> if (out.isNotEmpty()) out.removeLast()
                    else -> out.addLast(seg)
                }
            }
            return out.joinToString("/")
        }
    }
}
