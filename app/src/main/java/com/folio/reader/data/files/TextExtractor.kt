package com.folio.reader.data.files

import android.content.Context
import com.folio.reader.core.format.DocFormat
import com.folio.reader.core.format.ReaderKind
import com.folio.reader.core.security.Limits
import com.folio.reader.data.epub.EpubBook
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ensureActive
import org.commonmark.parser.Parser
import org.commonmark.renderer.text.TextContentRenderer
import org.jsoup.Jsoup
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/** A searchable slice of a document: a PDF page, an EPUB chapter, a block of lines. */
data class TextBlock(val location: Int, val label: String, val text: String)

@Singleton
class TextExtractor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val files: FileStore,
) {
    @Volatile private var pdfBoxReady = false

    private fun ensurePdfBox() {
        if (!pdfBoxReady) synchronized(this) {
            if (!pdfBoxReady) { PDFBoxResourceLoader.init(context); pdfBoxReady = true }
        }
    }

    /**
     * Streams blocks to [sink]. Stops after [Limits.MAX_INDEX_CHARS_PER_DOC]
     * characters so a pathological file cannot exhaust storage. Cancellable.
     */
    suspend fun extract(file: File, format: DocFormat, cacheKey: String, onProgress: (Int, Int) -> Unit = { _, _ -> }, sink: suspend (TextBlock) -> Unit) {
        var budget = Limits.MAX_INDEX_CHARS_PER_DOC
        suspend fun emit(b: TextBlock) {
            if (budget <= 0 || b.text.isBlank()) return
            val t = if (b.text.length > budget) b.text.take(budget) else b.text
            budget -= t.length
            sink(b.copy(text = t))
        }
        when (format.kind) {
            ReaderKind.PDF -> {
                ensurePdfBox()
                PDDocument.load(file, MemoryUsageSetting.setupTempFileOnly().setTempDir(files.tmpDir)).use { doc ->
                    val stripper = PDFTextStripper().apply { sortByPosition = true }
                    val n = doc.numberOfPages
                    for (p in 1..n) {
                        coroutineContext.ensureActive()
                        stripper.startPage = p
                        stripper.endPage = p
                        val text = try { stripper.getText(doc) } catch (e: Exception) { "" }
                        emit(TextBlock(p - 1, "Page $p", text))
                        onProgress(p, n)
                        if (budget <= 0) break
                    }
                }
            }
            ReaderKind.EPUB -> {
                val book = EpubBook.open(file, File(files.epubCacheDir, cacheKey).apply { mkdirs() })
                book.chapters.forEach { ch ->
                    coroutineContext.ensureActive()
                    emit(TextBlock(ch.index, ch.title, book.chapterText(ch.index)))
                    onProgress(ch.index + 1, book.chapters.size)
                }
            }
            ReaderKind.TEXT -> {
                val (raw, _) = TextFormats.readText(file)
                val text = if (format == DocFormat.RTF) TextFormats.rtfToPlain(raw) else raw
                lineBlocks(text).forEach { coroutineContext.ensureActive(); emit(it) }
            }
            ReaderKind.MARKDOWN -> {
                val (raw, _) = TextFormats.readText(file)
                val plain = TextContentRenderer.builder().build().render(Parser.builder().build().parse(raw))
                lineBlocks(plain).forEach { emit(it) }
            }
            ReaderKind.HTML -> {
                val (raw, _) = TextFormats.readText(file)
                lineBlocks(Jsoup.parse(raw).body()?.wholeText() ?: "").forEach { emit(it) }
            }
            ReaderKind.TABLE -> {
                val (raw, _) = TextFormats.readText(file)
                val rows = TextFormats.parseDelimited(raw, if (format == DocFormat.TSV) '\t' else ',')
                rows.chunked(100).forEachIndexed { i, chunk ->
                    coroutineContext.ensureActive()
                    val first = i * 100 + 1
                    emit(TextBlock(i * 100, "Rows $first–${first + chunk.size - 1}", chunk.joinToString("\n") { it.joinToString(" · ") }))
                }
            }
            ReaderKind.IMAGE, null -> Unit
        }
    }

    /** Plain text of one page / chapter, used by text-to-speech. */
    suspend fun textAt(file: File, format: DocFormat, cacheKey: String, location: Int): String {
        if (format.kind == ReaderKind.PDF) {
            ensurePdfBox()
            return PDDocument.load(file, MemoryUsageSetting.setupTempFileOnly().setTempDir(files.tmpDir)).use { doc ->
                if (location !in 0 until doc.numberOfPages) "" else
                    PDFTextStripper().apply { startPage = location + 1; endPage = location + 1; sortByPosition = true }.getText(doc)
            }
        }
        if (format.kind == ReaderKind.EPUB) {
            return EpubBook.open(file, File(files.epubCacheDir, cacheKey).apply { mkdirs() }).chapterText(location)
        }
        val sb = StringBuilder()
        extract(file, format, cacheKey) { if (sb.length < 200_000) sb.appendLine(it.text) }
        return sb.toString()
    }

    private fun lineBlocks(text: String): List<TextBlock> {
        val lines = text.lines()
        return lines.chunked(120).mapIndexed { i, chunk ->
            val first = i * 120 + 1
            TextBlock(i * 120, "Line $first", chunk.joinToString("\n"))
        }
    }
}
