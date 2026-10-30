package com.folio.reader.data.repo

import android.webkit.CookieManager
import android.webkit.URLUtil
import com.folio.reader.core.format.DocFormat
import com.folio.reader.core.security.Limits
import com.folio.reader.core.security.UrlSafety
import com.folio.reader.data.db.AppDatabase
import com.folio.reader.data.db.BrowserHistoryEntity
import com.folio.reader.data.db.DownloadEntity
import com.folio.reader.data.db.DownloadStatus
import com.folio.reader.data.files.FileStore
import com.folio.reader.di.AppScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.HttpsURLConnection

sealed interface DownloadEvent {
    data class Finished(val documentId: Long, val title: String, val duplicate: Boolean) : DownloadEvent
    data class Failed(val fileName: String, val reason: String) : DownloadEvent
}

@Singleton
class BrowserRepository @Inject constructor(
    private val db: AppDatabase,
    private val files: FileStore,
    private val library: LibraryRepository,
    @AppScope private val scope: CoroutineScope,
) {
    private val dao = db.browser()
    private val _events = MutableSharedFlow<DownloadEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<DownloadEvent> = _events

    fun observeHistory(limit: Int = 500) = dao.observeHistory(limit)
    fun observeDownloads() = dao.observeDownloads()

    suspend fun recordVisit(url: String, title: String) {
        if (!UrlSafety.isWebUrl(url)) return
        val last = dao.lastVisit(url)
        val now = System.currentTimeMillis()
        if (last != null && now - last.visitedAt < 30 * 60_000) {
            if (title.isNotBlank() && title != last.title) dao.setTitle(last.id, title.take(500))
        } else {
            dao.addHistory(BrowserHistoryEntity(url = url.take(4096), title = title.take(500), visitedAt = now))
        }
    }

    suspend fun suggest(q: String) = dao.suggest("%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%")
    suspend fun deleteHistory(id: Long) = dao.deleteHistory(id)
    suspend fun clearHistory() = dao.clearHistory()
    suspend fun clearDownloads() = dao.clearFinishedDownloads()

    /**
     * Downloads a file over HTTPS into private storage and adds it to the
     * library. Only recognised document and image types are kept; anything
     * else (APKs, scripts, archives, executables) is refused and nothing is
     * ever opened or run automatically.
     */
    fun download(url: String, userAgent: String?, contentDisposition: String?, mimeType: String?, onDone: (Long?) -> Unit = {}) {
        scope.launch(Dispatchers.IO) {
            val guessed = UrlSafety.safeFileName(URLUtil.guessFileName(url, contentDisposition, mimeType), "download")
            val rowId = dao.insertDownload(DownloadEntity(url = url, fileName = guessed, mime = mimeType, status = DownloadStatus.RUNNING.name, createdAt = System.currentTimeMillis()))
            val result = runCatching { fetch(url, userAgent, guessed, mimeType) }
            val row = dao.download(rowId) ?: return@launch
            result.fold(
                onSuccess = { r ->
                    when (r) {
                        is ImportResult.Imported -> {
                            dao.updateDownload(row.copy(status = DownloadStatus.DONE.name, documentId = r.doc.id, sizeBytes = r.doc.sizeBytes, fileName = r.doc.originalName))
                            _events.tryEmit(DownloadEvent.Finished(r.doc.id, r.doc.title, false)); onDone(r.doc.id)
                        }
                        is ImportResult.Duplicate -> {
                            dao.updateDownload(row.copy(status = DownloadStatus.DONE.name, documentId = r.existing.id, sizeBytes = r.existing.sizeBytes))
                            _events.tryEmit(DownloadEvent.Finished(r.existing.id, r.existing.title, true)); onDone(r.existing.id)
                        }
                        is ImportResult.Failed -> {
                            dao.updateDownload(row.copy(status = DownloadStatus.BLOCKED.name, error = r.reason))
                            _events.tryEmit(DownloadEvent.Failed(guessed, r.reason)); onDone(null)
                        }
                    }
                },
                onFailure = { e ->
                    dao.updateDownload(row.copy(status = DownloadStatus.FAILED.name, error = e.message?.take(200)))
                    _events.tryEmit(DownloadEvent.Failed(guessed, e.message ?: "Download failed")); onDone(null)
                },
            )
        }
    }

    private suspend fun fetch(url: String, userAgent: String?, fileName: String, mimeType: String?): ImportResult = withContext(Dispatchers.IO) {
        var current = URL(url)
        var redirects = 0
        while (true) {
            if (current.protocol != "https") throw IOException("Only secure (HTTPS) downloads are allowed.")
            val conn = current.openConnection() as HttpsURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 20_000
            conn.readTimeout = 30_000
            userAgent?.let { conn.setRequestProperty("User-Agent", it) }
            CookieManager.getInstance().getCookie(current.toString())?.let { conn.setRequestProperty("Cookie", it) }
            try {
                val code = conn.responseCode
                if (code in 300..399) {
                    val loc = conn.getHeaderField("Location") ?: throw IOException("Bad redirect")
                    current = URL(current, loc)
                    if (++redirects > 8) throw IOException("Too many redirects")
                    continue
                }
                if (code !in 200..299) throw IOException("Server returned HTTP $code")
                val length = conn.contentLengthLong
                if (length > Limits.MAX_DOWNLOAD_BYTES) throw IOException("File is larger than the 1 GB limit.")
                val type = conn.contentType ?: mimeType
                val disposition = conn.getHeaderField("Content-Disposition")
                val name = if (disposition != null) UrlSafety.safeFileName(URLUtil.guessFileName(current.toString(), disposition, type), fileName) else fileName
                // Fix up names like "download" when the server tells us the real type.
                val finalName = if (DocFormat.fromFileName(name) == null) DocFormat.fromMime(type)?.let { "$name.${it.extensions.first()}" } ?: name else name
                val tmp = files.newTempFile(".dl")
                conn.inputStream.use { input -> tmp.outputStream().use { out -> com.folio.reader.core.security.SafeZip.copyBounded(input, out, Limits.MAX_DOWNLOAD_BYTES) } }
                return@withContext library.importLocalFile(tmp, finalName, type, url)
            } finally {
                conn.disconnect()
            }
        }
        @Suppress("UNREACHABLE_CODE")
        throw IllegalStateException()
    }
}
