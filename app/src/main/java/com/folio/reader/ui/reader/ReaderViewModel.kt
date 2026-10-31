package com.folio.reader.ui.reader

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.folio.reader.core.format.DocFormat
import com.folio.reader.core.format.ReaderKind
import com.folio.reader.data.db.AnnotationDao
import com.folio.reader.data.db.AnnotationEntity
import com.folio.reader.data.db.AnnotationKind
import com.folio.reader.data.db.DocumentEntity
import com.folio.reader.data.db.SearchHit
import com.folio.reader.data.epub.EpubBook
import com.folio.reader.data.files.FileStore
import com.folio.reader.data.files.TextExtractor
import com.folio.reader.data.files.TextFormats
import com.folio.reader.data.prefs.PrefsRepository
import com.folio.reader.data.prefs.ReaderTheme
import com.folio.reader.data.prefs.UserPrefs
import com.folio.reader.data.repo.LibraryRepository
import com.folio.reader.data.repo.SearchRepository
import com.folio.reader.work.IndexScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.io.File
import java.util.Locale
import javax.inject.Inject

/** Everything a format-specific reader needs, loaded off the main thread. */
sealed interface ReaderContent {
    data object Loading : ReaderContent
    data class Failed(val message: String) : ReaderContent
    data class Unsupported(val format: DocFormat) : ReaderContent
    data class Pdf(val source: PdfSource, val pageCount: Int) : ReaderContent
    data class Epub(val book: EpubBook) : ReaderContent
    data class Text(val lines: List<String>, val mono: Boolean, val truncated: Boolean) : ReaderContent
    data class Html(val html: String) : ReaderContent
    data class Table(val rows: List<List<String>>, val truncated: Boolean) : ReaderContent
    /** A File (library) or a content:// Uri (viewed in place). */
    data class Image(val model: Any) : ReaderContent
}

data class Position(val position: Int, val offset: Float, val fraction: Float)

@HiltViewModel
class ReaderViewModel @Inject constructor(
    handle: SavedStateHandle,
    @ApplicationContext private val context: Context,
    private val library: LibraryRepository,
    private val prefsRepo: PrefsRepository,
    private val annotationsDao: AnnotationDao,
    private val search: SearchRepository,
    private val extractor: TextExtractor,
    private val indexer: IndexScheduler,
    private val files: FileStore,
    private val officePdf: com.folio.reader.data.office.OfficePdfCache,
    @com.folio.reader.di.AppScope private val appScope: kotlinx.coroutines.CoroutineScope,
) : ViewModel() {
    val docId: Long = handle["id"] ?: 0L
    private val requestedPos: Int = handle["pos"] ?: -1

    val prefs = prefsRepo.prefs.stateIn(viewModelScope, SharingStarted.Eagerly, UserPrefs())
    val doc = MutableStateFlow<DocumentEntity?>(null)
    val content = MutableStateFlow<ReaderContent>(ReaderContent.Loading)
    /** Shown under the spinner while loading takes a while (e.g. laying out an Office document). */
    val loadingStatus = MutableStateFlow<String?>(null)
    /** Where the reader should open; null until known. */
    val initial = MutableStateFlow<Position?>(null)
    /** Current reading position as reported by the reader. */
    val position = MutableStateFlow(Position(0, 0f, 0f))
    /** One-shot jump requests (search results, TOC, annotations, slider). */
    val jump = MutableStateFlow<Int?>(null)

    val annotations = annotationsDao.observeForDocument(docId).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val hits = MutableStateFlow<List<SearchHit>>(emptyList())
    val searchMessage = MutableStateFlow<String?>(null)
    val speaking = MutableStateFlow(false)

    private var saveJob: Job? = null
    private var tts: TextToSpeech? = null

    init {
        viewModelScope.launch {
            val d = library.get(docId)
            if (d == null) { content.value = ReaderContent.Failed("This document is no longer in your library."); return@launch }
            doc.value = d
            library.markOpened(docId)
            val saved = library.progress(docId)
            initial.value = when {
                requestedPos >= 0 -> Position(requestedPos, 0f, 0f)
                saved != null -> Position(saved.position, saved.offset, saved.fraction)
                else -> Position(0, 0f, 0f)
            }
            position.value = initial.value!!
            content.value = withContext(Dispatchers.IO) { load(d) }
            (content.value as? ReaderContent.Pdf)?.let { if (d.pageCount != it.pageCount) library.setPageCount(docId, it.pageCount) }
        }
    }

    private suspend fun load(d: DocumentEntity): ReaderContent {
        val format = library.formatOf(d)
        val file = library.fileOf(d)
        if (!file.isFile) return ReaderContent.Failed("The stored file is missing. Restore it from a backup or import it again.")
        if (!format.supported) return ReaderContent.Unsupported(format)
        return try {
            when (format.kind) {
                ReaderKind.PDF -> PdfDocumentHolder.pageCount(file).let { n ->
                    if (n > 0) ReaderContent.Pdf(PdfSource.FromFile(file), n) else ReaderContent.Failed("This PDF has no pages.")
                }
                ReaderKind.EPUB -> EpubBook.open(file, File(files.epubCacheDir, d.id.toString()).apply { mkdirs() }).let { b ->
                    if (b.chapters.isNotEmpty()) ReaderContent.Epub(b) else ReaderContent.Failed("This EPUB has no readable chapters.")
                }
                ReaderKind.TEXT, ReaderKind.MARKDOWN, ReaderKind.HTML, ReaderKind.TABLE -> {
                    val (raw, truncated) = TextFormats.readText(file)
                    ContentBuilder.fromText(format, raw, truncated)
                }
                ReaderKind.OFFICE -> loadOffice(d, format, file)
                ReaderKind.IMAGE -> ReaderContent.Image(file)
                null -> ReaderContent.Unsupported(format)
            }
        } catch (e: SecurityException) {
            ReaderContent.Failed("This PDF is password-protected. Password-protected PDFs aren't supported yet.")
        } catch (e: OutOfMemoryError) {
            ReaderContent.Failed("This document is too large to open on this device.")
        } catch (e: Exception) {
            ReaderContent.Failed("Couldn't open this document: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** Office documents are laid out as pages by the document engine; the text view is the fallback. */
    private suspend fun loadOffice(d: DocumentEntity, format: DocFormat, file: File): ReaderContent {
        var reason: String? = null
        if (officePdf.available) {
            if (officePdf.cached(d.id.toString()) == null) loadingStatus.value = "Laying out pages… (the first time can take a little while)"
            try {
                val pdf = officePdf.ensure(file, d.id.toString())
                val n = PdfDocumentHolder.pageCount(pdf)
                if (n > 0) return ReaderContent.Pdf(PdfSource.FromFile(pdf), n)
                officePdf.evict(d.id.toString())
                reason = "the layout had no pages"
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                officePdf.evict(d.id.toString())
                reason = e.message
            } finally {
                loadingStatus.value = null
            }
        } else reason = "the document engine isn't available on this device"
        if (!com.folio.reader.data.office.OfficePdfCache.hasTextFallback(format)) {
            return ReaderContent.Failed("Couldn't lay out this ${format.label} document${reason?.let { ": $it" } ?: "."}")
        }
        searchMessage.value = "Showing text only — ${reason ?: "layout unavailable"}."
        return file.inputStream().buffered().use { ContentBuilder.fromOffice(format, it) }
    }

    fun report(p: Position) {
        position.value = p
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(600)
            library.saveProgress(docId, p.position, p.offset, p.fraction)
        }
    }

    fun requestJump(pos: Int) { jump.value = pos }
    fun consumeJump() { jump.value = null }

    fun runSearch(q: String) = viewModelScope.launch {
        val d = doc.value ?: return@launch
        if (q.isBlank()) { hits.value = emptyList(); searchMessage.value = null; return@launch }
        hits.value = search.searchInDocument(docId, q)
        searchMessage.value = when {
            hits.value.isNotEmpty() -> null
            d.indexError != null -> "Text search isn't available for this file (${d.indexError})."
            d.indexedAt == null -> { indexer.indexPending(); "This document is still being indexed for search. Try again in a moment." }
            library.formatOf(d).kind == ReaderKind.IMAGE -> "Images have no searchable text."
            else -> "No matches."
        }
    }

    fun toggleBookmark(pos: Int, label: String) = viewModelScope.launch {
        val existing = annotations.value.firstOrNull { it.kind == AnnotationKind.BOOKMARK.name && it.position == pos }
        if (existing != null) annotationsDao.delete(existing)
        else annotationsDao.insert(AnnotationEntity(documentId = docId, kind = AnnotationKind.BOOKMARK.name, position = pos, label = label, createdAt = System.currentTimeMillis()))
    }

    fun addNote(pos: Int, label: String, note: String, quote: String = "") = viewModelScope.launch {
        annotationsDao.insert(AnnotationEntity(documentId = docId, kind = AnnotationKind.NOTE.name, position = pos, label = label, note = note.take(20_000),
            quote = quote.take(2000), createdAt = System.currentTimeMillis()))
    }

    fun deleteAnnotation(a: AnnotationEntity) = viewModelScope.launch { annotationsDao.delete(a) }

    fun setTheme(t: ReaderTheme) = viewModelScope.launch { prefsRepo.setReaderTheme(t) }
    fun setFontScale(v: Float) = viewModelScope.launch { prefsRepo.setReaderFontScale(v) }
    fun setKeepScreenOn(v: Boolean) = viewModelScope.launch { prefsRepo.setKeepScreenOn(v) }
    fun setPdfBookMode(v: Boolean) = viewModelScope.launch { prefsRepo.setPdfBookMode(v) }

    // ---- Text to speech (Android's on-device engine; nothing is sent anywhere by Folio) ----

    fun speak(location: Int) {
        val d = doc.value ?: return
        viewModelScope.launch {
            val text = when (val c = content.value) {
                is ReaderContent.Text -> c.lines.drop(location).take(400).joinToString("\n")
                is ReaderContent.Html -> Jsoup.parse(c.html).text()
                is ReaderContent.Table -> c.rows.drop(location).take(50).joinToString(". ") { it.joinToString(", ") }
                else -> withContext(Dispatchers.IO) { runCatching { extractor.textAt(library.fileOf(d), library.formatOf(d), d.id.toString(), location) }.getOrDefault("") }
            }
            if (text.isBlank()) { searchMessage.value = "There's no readable text here."; return@launch }
            val engine = tts ?: TextToSpeech(context) { status ->
                if (status == TextToSpeech.SUCCESS) enqueue(text) else { speaking.value = false; searchMessage.value = "Text-to-speech isn't available on this device." }
            }.also { t ->
                tts = t
                t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) { speaking.value = true }
                    override fun onDone(id: String?) { if (id == "last") speaking.value = false }
                    @Deprecated("Deprecated in Java") override fun onError(id: String?) { speaking.value = false }
                })
                return@launch
            }
            enqueue(text, engine)
        }
    }

    private fun enqueue(text: String, engine: TextToSpeech? = tts) {
        val t = engine ?: return
        t.language = Locale.getDefault()
        t.stop()
        val chunks = text.replace(Regex("\\s+"), " ").chunked(3500)
        chunks.forEachIndexed { i, c -> t.speak(c, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, if (i == chunks.lastIndex) "last" else "c$i") }
        speaking.value = true
    }

    fun stopSpeaking() { tts?.stop(); speaking.value = false }

    override fun onCleared() {
        tts?.shutdown()
        tts = null
        saveJob?.cancel()
        // Save synchronously-ish on exit so the last position isn't lost.
        val p = position.value
        appScope.launch(Dispatchers.IO) { library.saveProgress(docId, p.position, p.offset, p.fraction) }
    }

}

