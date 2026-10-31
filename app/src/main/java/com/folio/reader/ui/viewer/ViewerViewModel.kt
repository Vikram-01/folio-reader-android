package com.folio.reader.ui.viewer

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.folio.reader.core.format.FormatDetector
import com.folio.reader.core.format.ReaderKind
import com.folio.reader.core.security.Limits
import com.folio.reader.core.security.SafeZip
import com.folio.reader.data.epub.EpubBook
import com.folio.reader.data.files.FileStore
import com.folio.reader.data.files.TextFormats
import com.folio.reader.data.prefs.PrefsRepository
import com.folio.reader.data.prefs.ReaderTheme
import com.folio.reader.data.prefs.UserPrefs
import com.folio.reader.ui.reader.ContentBuilder
import com.folio.reader.ui.reader.PdfDocumentHolder
import com.folio.reader.ui.reader.PdfSource
import com.folio.reader.ui.reader.ReaderContent
import com.folio.reader.ui.reader.open
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import javax.inject.Inject

/**
 * Read-only viewer for a file handed over by another app ("Open with", or the
 * "Open a file" action). The file is read in place through its content:// Uri:
 * nothing is added to the library, no thumbnail, search index, reading
 * progress or history is written.
 *
 * The one exception is EPUB, whose chapters must be served to the WebView from
 * disk: it is unpacked into the private cache and deleted as soon as the viewer
 * closes (and again on the next app start, in case the app was killed).
 */
@HiltViewModel
class ViewerViewModel @Inject constructor(
    handle: SavedStateHandle,
    @ApplicationContext private val context: Context,
    private val prefsRepo: PrefsRepository,
    private val files: FileStore,
) : ViewModel() {

    val uri: Uri = handle.get<String>("uri")?.let(Uri::parse) ?: Uri.EMPTY
    val prefs = prefsRepo.prefs.stateIn(viewModelScope, SharingStarted.Eagerly, UserPrefs())
    val title = MutableStateFlow("")
    val formatLabel = MutableStateFlow<String?>(null)
    val content = MutableStateFlow<ReaderContent>(ReaderContent.Loading)
    /** Current page / chapter / line, in memory only. */
    val position = MutableStateFlow(0)
    val jump = MutableStateFlow<Int?>(null)

    @Volatile private var tempDir: File? = null

    init {
        viewModelScope.launch { content.value = withContext(Dispatchers.IO) { load() } }
    }

    private fun stream(): InputStream =
        context.contentResolver.openInputStream(uri) ?: throw FileNotFoundException("The file is no longer available.")

    private fun load(): ReaderContent {
        if (uri.scheme != "content") return ReaderContent.Failed("This file can't be opened. Open it from a file manager or another app.")
        val resolver = context.contentResolver
        var name: String? = null
        try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) name = c.getString(0)
            }
        } catch (e: Exception) { /* some providers don't support queries */ }
        title.value = name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "Document"
        val mime = runCatching { resolver.getType(uri) }.getOrNull()

        return try {
            val header = stream().use { input ->
                val buf = ByteArray(4096)
                var n = 0
                while (n < buf.size) { val r = input.read(buf, n, buf.size - n); if (r < 0) break; n += r }
                buf.copyOf(n)
            }
            if (header.isEmpty()) return ReaderContent.Failed("The file is empty.")
            val format = when (val r = FormatDetector.detect(header, name, mime)) {
                is FormatDetector.Result.Ok -> r.format
                is FormatDetector.Result.Unsupported -> { formatLabel.value = r.format.label; return ReaderContent.Unsupported(r.format) }
                is FormatDetector.Result.Rejected -> return ReaderContent.Failed(r.reason)
            }
            formatLabel.value = format.label
            when (format.kind) {
                ReaderKind.PDF -> {
                    val source = PdfSource.FromUri(uri)
                    val n = PdfDocumentHolder.pageCount(source.open(context))
                    if (n > 0) ReaderContent.Pdf(source, n) else ReaderContent.Failed("This PDF has no pages.")
                }
                ReaderKind.IMAGE -> ReaderContent.Image(uri)
                ReaderKind.TEXT, ReaderKind.MARKDOWN, ReaderKind.HTML, ReaderKind.TABLE -> {
                    val (raw, truncated) = stream().use { TextFormats.readStream(it) }
                    ContentBuilder.fromText(format, raw, truncated)
                }
                ReaderKind.OFFICE -> stream().buffered().use { ContentBuilder.fromOffice(format, it) }
                ReaderKind.EPUB -> openEpub()
                null -> ReaderContent.Unsupported(format)
            }
        } catch (e: SecurityException) {
            ReaderContent.Failed("ReadStash wasn't allowed to read this file, or the PDF is password-protected.")
        } catch (e: FileNotFoundException) {
            ReaderContent.Failed("The file is no longer available. It may have been moved or deleted.")
        } catch (e: OutOfMemoryError) {
            ReaderContent.Failed("This document is too large to open on this device.")
        } catch (e: IOException) {
            ReaderContent.Failed("Couldn't read this file: ${e.message ?: "read error"}")
        } catch (e: Exception) {
            ReaderContent.Failed("Couldn't open this document: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun openEpub(): ReaderContent {
        val dir = File(files.tmpDir, "view-" + UUID.randomUUID()).apply { mkdirs() }
        tempDir = dir
        val packed = File(dir, "book.epub")
        stream().use { input -> packed.outputStream().use { SafeZip.copyBounded(input, it, Limits.MAX_IMPORT_BYTES) } }
        val book = EpubBook.open(packed, dir)
        packed.delete()
        return if (book.chapters.isNotEmpty()) ReaderContent.Epub(book) else ReaderContent.Failed("This EPUB has no readable chapters.")
    }

    fun report(p: Int) { position.value = p }
    fun requestJump(p: Int) { jump.value = p }
    fun consumeJump() { jump.value = null }

    fun setPdfBookMode(v: Boolean) = viewModelScope.launch { prefsRepo.setPdfBookMode(v) }
    fun cycleTheme() = viewModelScope.launch {
        val all = ReaderTheme.entries
        prefsRepo.setReaderTheme(all[(prefs.value.readerTheme.ordinal + 1) % all.size])
    }

    override fun onCleared() {
        tempDir?.let { d -> Thread { runCatching { d.deleteRecursively() } }.start() }
        tempDir = null
    }
}
