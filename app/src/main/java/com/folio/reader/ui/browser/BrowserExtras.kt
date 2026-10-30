package com.folio.reader.ui.browser

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.folio.reader.core.security.UrlSafety
import com.folio.reader.data.db.BookmarkEntity
import com.folio.reader.data.db.DownloadStatus
import com.folio.reader.data.prefs.PrefsRepository
import com.folio.reader.data.prefs.UserPrefs
import com.folio.reader.data.repo.BookmarkRepository
import com.folio.reader.data.repo.BrowserRepository
import com.folio.reader.ui.components.*
import com.folio.reader.ui.nav.Routes
import com.folio.reader.ui.theme.readerColors
import com.folio.reader.ui.web.LocalHtmlView
import com.folio.reader.ui.web.SecureWeb
import com.folio.reader.ui.web.readerCss
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject

@HiltViewModel
class BrowserDataViewModel @Inject constructor(private val repo: BrowserRepository) : ViewModel() {
    val history = repo.observeHistory(1000).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val downloads = repo.observeDownloads().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    fun deleteHistory(id: Long) = viewModelScope.launch { repo.deleteHistory(id) }
    fun clearHistory() = viewModelScope.launch { repo.clearHistory() }
    fun clearDownloads() = viewModelScope.launch { repo.clearDownloads() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(nav: (String) -> Unit, back: () -> Unit, vm: BrowserDataViewModel = hiltViewModel()) {
    val items by vm.history.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf(false) }
    val df = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    Scaffold(topBar = {
        TopAppBar(navigationIcon = { IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }, title = { Text("History") },
            actions = { if (items.isNotEmpty()) TextButton(onClick = { confirm = true }) { Text("Clear") } })
    }) { pad ->
        if (items.isEmpty()) EmptyState(Icons.Outlined.History, "No history", "Pages you visit in Folio's browser appear here. You can turn history off in Settings.", Modifier.padding(pad))
        else LazyColumn(Modifier.padding(pad)) {
            items(items, key = { it.id }) { h ->
                ListItem(modifier = Modifier.clickable { nav(Routes.browser(h.url)) },
                    headlineContent = { Text(h.title.ifBlank { h.url }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text("${UrlSafety.hostOf(h.url)} · ${df.format(Date(h.visitedAt))}", maxLines = 1) },
                    trailingContent = { IconButton(onClick = { vm.deleteHistory(h.id) }) { Icon(Icons.Default.Close, "Remove") } })
            }
        }
    }
    if (confirm) ConfirmDialog("Clear browsing history?", "Your bookmarks and library are not affected.", "Clear", onDismiss = { confirm = false }) { vm.clearHistory() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(nav: (String) -> Unit, back: () -> Unit, vm: BrowserDataViewModel = hiltViewModel()) {
    val items by vm.downloads.collectAsStateWithLifecycle()
    Scaffold(topBar = {
        TopAppBar(navigationIcon = { IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }, title = { Text("Downloads") },
            actions = { if (items.isNotEmpty()) TextButton(onClick = { vm.clearDownloads() }) { Text("Clear list") } })
    }) { pad ->
        if (items.isEmpty()) EmptyState(Icons.Outlined.Download, "No downloads", "Files you download in the browser are saved straight into your library.", Modifier.padding(pad))
        else LazyColumn(Modifier.padding(pad)) {
            items(items, key = { it.id }) { d ->
                val status = DownloadStatus.entries.firstOrNull { it.name == d.status }
                ListItem(
                    modifier = Modifier.clickable(enabled = d.documentId != null) { d.documentId?.let { nav(Routes.reader(it)) } },
                    leadingContent = {
                        when (status) {
                            DownloadStatus.RUNNING -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                            DownloadStatus.DONE -> Icon(Icons.Outlined.CheckCircle, null, tint = MaterialTheme.colorScheme.tertiary)
                            DownloadStatus.BLOCKED -> Icon(Icons.Outlined.Block, null, tint = MaterialTheme.colorScheme.error)
                            else -> Icon(Icons.Outlined.ErrorOutline, null, tint = MaterialTheme.colorScheme.error)
                        }
                    },
                    headlineContent = { Text(d.fileName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = {
                        Text(when (status) {
                            DownloadStatus.RUNNING -> "Downloading…"
                            DownloadStatus.DONE -> "In library · ${formatBytes(d.sizeBytes)} · tap to open"
                            DownloadStatus.BLOCKED -> "Not saved: ${d.error}"
                            else -> "Failed: ${d.error}"
                        } + "\n" + UrlSafety.hostOf(d.url), maxLines = 3)
                    },
                )
            }
        }
    }
}

@HiltViewModel
class OfflinePageViewModel @Inject constructor(handle: SavedStateHandle, private val repo: BookmarkRepository, prefsRepo: PrefsRepository) : ViewModel() {
    private val id: Long = handle["id"] ?: 0L
    val prefs = prefsRepo.prefs.stateIn(viewModelScope, SharingStarted.Eagerly, UserPrefs())
    var bookmark by mutableStateOf<BookmarkEntity?>(null); private set
    var html by mutableStateOf<String?>(null); private set
    init { viewModelScope.launch { repo.get(id)?.let { b -> bookmark = b; html = repo.offlineHtml(b) ?: "" } } }
}

/** Shows a page saved for offline reading. No network access: links ask before opening in the browser. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OfflinePageScreen(id: Long, nav: (String) -> Unit, back: () -> Unit, vm: OfflinePageViewModel = hiltViewModel()) {
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val b = vm.bookmark
    val colors = readerColors(prefs.readerTheme)
    Scaffold(containerColor = colors.background, topBar = {
        TopAppBar(
            navigationIcon = { IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            title = { Column { Text(b?.title ?: "Saved page", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                Text("Offline copy · " + (b?.let { UrlSafety.hostOf(it.url) } ?: ""), style = MaterialTheme.typography.labelSmall) } },
            actions = { b?.let { IconButton(onClick = { nav(Routes.browser(it.url)) }) { Icon(Icons.Outlined.Public, "Open live page") } } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.background, titleContentColor = colors.text, navigationIconContentColor = colors.text, actionIconContentColor = colors.text),
        )
    }) { pad ->
        val html = vm.html
        when {
            html == null -> Box(Modifier.padding(pad).fillMaxSize()) { CircularProgressIndicator(Modifier.padding(32.dp)) }
            html.isEmpty() -> EmptyState(Icons.Outlined.CloudOff, "No offline copy", "Open the page in the browser and choose Save for offline reading.", Modifier.padding(pad))
            else -> LocalHtmlView("<h1>${org.jsoup.nodes.Entities.escape(b?.title ?: "")}</h1>$html", readerCss(colors, prefs.readerFontScale), Modifier.padding(pad).fillMaxSize(),
                onLink = { u -> if (u.scheme == "https" && u.host != SecureWeb.LOCAL_HOST) nav(Routes.browser(u.toString())) })
        }
    }
}
