package com.folio.reader.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.folio.reader.data.db.BookmarkEntity
import com.folio.reader.data.db.CollectionEntity
import com.folio.reader.data.db.DocumentEntity
import com.folio.reader.data.prefs.PrefsRepository
import com.folio.reader.data.prefs.UserPrefs
import com.folio.reader.data.repo.BookmarkRepository
import com.folio.reader.data.repo.BrowserRepository
import com.folio.reader.data.repo.DownloadEvent
import com.folio.reader.data.repo.ImportResult
import com.folio.reader.data.repo.LibraryRepository
import com.folio.reader.data.repo.SaveResult
import com.folio.reader.ui.nav.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface UiEvent {
    data class Navigate(val route: String) : UiEvent
    data class Message(val text: String, val actionLabel: String? = null, val actionRoute: String? = null) : UiEvent
}

data class LinkDraft(val url: String, val title: String = "", val note: String = "", val collectionId: Long? = null,
                     val tags: List<String> = emptyList(), val duplicateOf: BookmarkEntity? = null)

@HiltViewModel
class MainViewModel @Inject constructor(
    prefsRepo: PrefsRepository,
    private val library: LibraryRepository,
    private val bookmarks: BookmarkRepository,
    browser: BrowserRepository,
) : ViewModel() {

    val prefs: StateFlow<UserPrefs?> = prefsRepo.prefs.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val collections: StateFlow<List<CollectionEntity>> = bookmarks.observeCollections().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // A channel buffers events until the UI collects them (e.g. share intents handled before first frame).
    private val _events = Channel<UiEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private val _linkDraft = MutableStateFlow<LinkDraft?>(null)
    val linkDraft = _linkDraft.asStateFlow()

    private val _duplicate = MutableStateFlow<Pair<Uri, DocumentEntity>?>(null)
    val duplicate = _duplicate.asStateFlow()

    private val _importing = MutableStateFlow(0)
    val importing = _importing.asStateFlow()

    init {
        viewModelScope.launch {
            browser.events.collect { e ->
                when (e) {
                    is DownloadEvent.Finished -> _events.send(UiEvent.Message(
                        if (e.duplicate) "Already in your library: ${e.title}" else "Saved to library: ${e.title}", "Open", Routes.reader(e.documentId)))
                    is DownloadEvent.Failed -> _events.send(UiEvent.Message("Not downloaded — ${e.reason}"))
                }
            }
        }
    }

    fun message(text: String) { _events.trySend(UiEvent.Message(text)) }

    /** Imports picked or shared files. A single file opens straight away. */
    fun importUris(uris: List<Uri>, openSingle: Boolean = true) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _importing.value += uris.size
            var ok = 0
            val failures = ArrayList<String>()
            var last: DocumentEntity? = null
            for (u in uris) {
                when (val r = library.importUri(u)) {
                    is ImportResult.Imported -> { ok++; last = r.doc }
                    is ImportResult.Duplicate -> if (uris.size == 1) _duplicate.value = u to r.existing else failures += "${r.existing.title} (already in library)"
                    is ImportResult.Failed -> failures += "${r.name}: ${r.reason}"
                }
                _importing.value -= 1
            }
            if (uris.size == 1 && last != null && openSingle) _events.send(UiEvent.Navigate(Routes.reader(last.id)))
            else if (ok > 0) _events.send(UiEvent.Message("Added $ok document${if (ok == 1) "" else "s"} to your library"))
            if (failures.isNotEmpty()) _events.send(UiEvent.Message(failures.take(3).joinToString("\n") + if (failures.size > 3) "\n+${failures.size - 3} more" else ""))
        }
    }

    fun resolveDuplicate(importAnyway: Boolean) {
        val (uri, existing) = _duplicate.value ?: return
        _duplicate.value = null
        viewModelScope.launch {
            if (!importAnyway) { _events.send(UiEvent.Navigate(Routes.reader(existing.id))); return@launch }
            when (val r = library.importUri(uri, allowDuplicate = true)) {
                is ImportResult.Imported -> _events.send(UiEvent.Navigate(Routes.reader(r.doc.id)))
                is ImportResult.Failed -> _events.send(UiEvent.Message(r.reason))
                is ImportResult.Duplicate -> Unit
            }
        }
    }

    fun dismissDuplicate() { _duplicate.value = null }

    fun startLinkDraft(url: String = "", title: String = "") { _linkDraft.value = LinkDraft(url, title) }
    fun updateLinkDraft(d: LinkDraft) { _linkDraft.value = d }
    fun cancelLinkDraft() { _linkDraft.value = null }

    fun saveLinkDraft(allowDuplicate: Boolean = false) {
        val d = _linkDraft.value ?: return
        viewModelScope.launch {
            val url = com.folio.reader.core.security.UrlSafety.extractUrl(d.url) ?: d.url.trim().let {
                if (it.isNotEmpty() && !it.contains("://") && it.contains('.')) "https://$it" else it
            }
            when (val r = bookmarks.save(url, d.title, d.note, d.collectionId, d.tags, allowDuplicate = allowDuplicate)) {
                is SaveResult.Saved -> { _linkDraft.value = null; _events.send(UiEvent.Message("Bookmark saved", "Open", Routes.browser(url))) }
                is SaveResult.Duplicate -> _linkDraft.value = d.copy(duplicateOf = r.existing)
                is SaveResult.Invalid -> _events.send(UiEvent.Message(r.reason))
            }
        }
    }

    fun createCollection(name: String, then: (Long) -> Unit) {
        viewModelScope.launch { then(bookmarks.createCollection(name, null)) }
    }
}
