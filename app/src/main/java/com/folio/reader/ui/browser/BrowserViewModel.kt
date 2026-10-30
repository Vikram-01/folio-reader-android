package com.folio.reader.ui.browser

import android.graphics.Bitmap
import android.os.Bundle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.folio.reader.core.security.Limits
import com.folio.reader.core.security.UrlSafety
import com.folio.reader.data.db.BookmarkEntity
import com.folio.reader.data.prefs.PrefsRepository
import com.folio.reader.data.prefs.UserPrefs
import com.folio.reader.data.repo.BookmarkRepository
import com.folio.reader.data.repo.BrowserRepository
import com.folio.reader.data.repo.SaveResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject

data class BrowserTab(val id: Long, val url: String, val title: String, val state: Bundle? = null)

data class PendingDownload(val url: String, val userAgent: String?, val disposition: String?, val mime: String?, val fileName: String, val isPdf: Boolean)

/**
 * Tabs are kept as lightweight records (URL, title, saved WebView state) and
 * share one live WebView, so hundreds of tabs cost almost no memory.
 */
@HiltViewModel
class BrowserViewModel @Inject constructor(
    private val prefsRepo: PrefsRepository,
    private val bookmarks: BookmarkRepository,
    private val browser: BrowserRepository,
) : ViewModel() {
    private val ids = AtomicLong(1)
    val prefs = prefsRepo.prefs.stateIn(viewModelScope, SharingStarted.Eagerly, UserPrefs())

    private val _tabs = MutableStateFlow<List<BrowserTab>>(emptyList())
    val tabs = _tabs.asStateFlow()
    private val _current = MutableStateFlow<Long?>(null)
    val current = _current.asStateFlow()

    val currentBookmark = MutableStateFlow<BookmarkEntity?>(null)
    val pendingDownload = MutableStateFlow<PendingDownload?>(null)
    val message = MutableStateFlow<String?>(null)
    var initialHandled = false

    fun currentTab(): BrowserTab? = _tabs.value.firstOrNull { it.id == _current.value }

    fun newTab(url: String, title: String = ""): BrowserTab {
        if (_tabs.value.size >= Limits.MAX_TABS) _tabs.update { it.drop(1) }
        val t = BrowserTab(ids.getAndIncrement(), url, title)
        _tabs.update { it + t }
        _current.value = t.id
        refreshBookmark(url)
        return t
    }

    fun select(id: Long) { _current.value = id; currentTab()?.let { refreshBookmark(it.url) } }

    fun close(id: Long) {
        val list = _tabs.value
        val idx = list.indexOfFirst { it.id == id }
        _tabs.value = list.filter { it.id != id }
        if (_current.value == id) _current.value = _tabs.value.getOrNull((idx - 1).coerceAtLeast(0))?.id
    }

    fun closeAll() { _tabs.value = emptyList(); _current.value = null }

    fun saveState(id: Long, bundle: Bundle) { _tabs.update { l -> l.map { if (it.id == id) it.copy(state = bundle) else it } } }

    fun onPage(url: String, title: String) {
        val id = _current.value ?: return
        _tabs.update { l -> l.map { if (it.id == id) it.copy(url = url, title = title.ifBlank { it.title }) else it } }
        refreshBookmark(url)
    }

    fun recordVisit(url: String, title: String) {
        if (!prefs.value.browserSaveHistory) return
        viewModelScope.launch { browser.recordVisit(url, title) }
    }

    private fun refreshBookmark(url: String) {
        viewModelScope.launch { currentBookmark.value = if (UrlSafety.isWebUrl(url)) bookmarks.findDuplicate(url) else null }
    }

    fun toggleBookmark(url: String, title: String, then: (Long?) -> Unit = {}) = viewModelScope.launch {
        val existing = bookmarks.findDuplicate(url)
        if (existing != null) { then(existing.id); return@launch }
        when (val r = bookmarks.save(url, title)) {
            is SaveResult.Saved -> { message.value = "Bookmarked"; refreshBookmark(url); then(r.id) }
            is SaveResult.Duplicate -> then(r.existing.id)
            is SaveResult.Invalid -> message.value = r.reason
        }
    }

    fun saveForOffline(url: String, title: String, html: String) = viewModelScope.launch {
        val id = bookmarks.findDuplicate(url)?.id ?: when (val r = bookmarks.save(url, title)) {
            is SaveResult.Saved -> r.id
            is SaveResult.Duplicate -> r.existing.id
            is SaveResult.Invalid -> { message.value = r.reason; return@launch }
        }
        message.value = if (bookmarks.saveOffline(id, html, url)) "Saved for offline reading" else "This page is too large to save"
        refreshBookmark(url)
    }

    fun favicon(url: String, icon: Bitmap) = viewModelScope.launch { bookmarks.saveFavicon(url, icon) }
    fun thumbnail(id: Long, bmp: Bitmap) = viewModelScope.launch { bookmarks.saveThumbnail(id, bmp) }

    suspend fun suggestions(q: String) = if (q.length < 2) emptyList() else browser.suggest(q)

    /** Set when a download the user asked to open has finished; the screen navigates and clears it. */
    val openDocument = MutableStateFlow<Long?>(null)

    fun startDownload(d: PendingDownload, openWhenDone: Boolean) {
        pendingDownload.value = null
        message.value = "Downloading ${d.fileName}…"
        browser.download(d.url, d.userAgent, d.disposition, d.mime) { id -> if (id != null && openWhenDone) openDocument.value = id }
    }

    fun setJs(v: Boolean) = viewModelScope.launch { prefsRepo.setBrowserJavaScript(v) }
}
