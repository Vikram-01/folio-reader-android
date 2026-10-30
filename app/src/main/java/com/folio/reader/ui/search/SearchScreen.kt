package com.folio.reader.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.folio.reader.core.format.DocFormat
import com.folio.reader.data.db.BookmarkType
import com.folio.reader.data.repo.BookmarkRepository
import com.folio.reader.data.repo.LibraryRepository
import com.folio.reader.data.repo.ResultKind
import com.folio.reader.data.repo.SearchFilters
import com.folio.reader.data.repo.SearchRepository
import com.folio.reader.data.repo.SearchResult
import com.folio.reader.ui.bookmarks.CollectionPicker
import com.folio.reader.ui.components.*
import com.folio.reader.ui.documents.FolderPicker
import com.folio.reader.ui.nav.Routes
import com.folio.reader.work.IndexScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

enum class DateRange(val label: String, val days: Int?) { ANY("Any time", null), DAY("Past day", 1), WEEK("Past week", 7), MONTH("Past month", 30), YEAR("Past year", 365) }

@OptIn(FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val repo: SearchRepository,
    library: LibraryRepository,
    bookmarks: BookmarkRepository,
    indexer: IndexScheduler,
) : ViewModel() {
    val query = MutableStateFlow("")
    val filters = MutableStateFlow(SearchFilters())
    val added = MutableStateFlow(DateRange.ANY)
    val opened = MutableStateFlow(DateRange.ANY)
    val indexState = indexer.state.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val collections = bookmarks.observeCollections().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val folders = library.observeFolders().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val tags = library.observeTags().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val searching = MutableStateFlow(false)

    val results = combine(query.debounce(250), filters, added, opened) { q, f, a, o ->
        val now = System.currentTimeMillis()
        Triple(q, f.copy(addedAfter = a.days?.let { now - it * 86_400_000L }, openedAfter = o.days?.let { now - it * 86_400_000L }), Unit)
    }.mapLatest { (q, f, _) ->
        searching.value = true
        try { repo.search(q, f) } finally { searching.value = false }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(nav: (String) -> Unit, vm: SearchViewModel = hiltViewModel()) {
    val q by vm.query.collectAsStateWithLifecycle()
    val f by vm.filters.collectAsStateWithLifecycle()
    val results by vm.results.collectAsStateWithLifecycle()
    val idx by vm.indexState.collectAsStateWithLifecycle()
    val busy by vm.searching.collectAsStateWithLifecycle()
    var showFilters by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val activeFilters = listOf(f.formats.isNotEmpty(), f.bookmarkTypes.isNotEmpty(), f.collectionId != null, f.folderId != null,
        f.tagIds.isNotEmpty(), f.favoritesOnly, f.offlineOnly, vm.added.value != DateRange.ANY, vm.opened.value != DateRange.ANY).count { it }

    Scaffold(topBar = { TopAppBar(title = { Text("Search") }) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            OutlinedTextField(
                q, { vm.query.value = it }, placeholder = { Text("Search titles, text inside documents, links, notes…") },
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                trailingIcon = {
                    Row {
                        if (q.isNotEmpty()) IconButton(onClick = { vm.query.value = "" }) { Icon(Icons.Default.Close, "Clear") }
                        BadgedBox(badge = { if (activeFilters > 0) Badge { Text("$activeFilters") } }) {
                            IconButton(onClick = { showFilters = true }) { Icon(Icons.Outlined.Tune, "Filters") }
                        }
                    }
                },
                singleLine = true, shape = RoundedCornerShape(28.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).focusRequester(focus),
            )
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(ResultKind.entries.toList()) { k ->
                    val sel = k in f.kinds
                    FilterChip(sel, { vm.filters.value = f.copy(kinds = if (sel) f.kinds - k else f.kinds + k) },
                        { Text(k.name.lowercase().replaceFirstChar { it.uppercase() } + "s") })
                }
            }
            idx?.takeIf { it.running }?.let { s ->
                Row(Modifier.padding(horizontal = 20.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Indexing ${s.done}/${s.total}" + (s.current?.let { " · $it" } ?: "") + " — results may be incomplete",
                        style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            when {
                q.isBlank() -> EmptyState(Icons.Outlined.ManageSearch, "Search everything",
                    "Find words inside PDFs, books and text files, plus bookmark titles, URLs, saved pages, notes, tags and collections. Search runs entirely on this device.")
                results.isEmpty() && !busy -> EmptyState(Icons.Outlined.SearchOff, "No matches", "Try fewer words or clear some filters.")
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
                    item { Text("${results.size} result${if (results.size == 1) "" else "s"}", style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) }
                    items(results, key = { "${it.kind}-${it.id}-${it.location}-${it.snippet.hashCode()}" }) { r -> ResultRow(r, nav) }
                }
            }
        }
    }
    if (showFilters) FilterSheet(vm) { showFilters = false }
}

@Composable
private fun ResultRow(r: SearchResult, nav: (String) -> Unit) {
    val icon = when (r.kind) {
        ResultKind.DOCUMENT -> formatIcon(r.format)
        ResultKind.BOOKMARK -> Icons.Outlined.Bookmark
        ResultKind.NOTE -> Icons.Outlined.StickyNote2
        ResultKind.COLLECTION -> Icons.Outlined.FolderSpecial
        ResultKind.TAG -> Icons.Outlined.Tag
    }
    val hl = MaterialTheme.colorScheme.secondaryContainer
    ListItem(
        modifier = Modifier.clickable {
            when (r.kind) {
                ResultKind.DOCUMENT, ResultKind.NOTE -> nav(Routes.reader(r.id, r.location ?: -1))
                ResultKind.BOOKMARK -> r.url?.let { nav(Routes.browser(it)) }
                ResultKind.COLLECTION -> nav(Routes.collection(r.id))
                ResultKind.TAG -> nav(Routes.tag(r.id))
            }
        },
        leadingContent = { Icon(icon, null, tint = MaterialTheme.colorScheme.primary) },
        headlineContent = { Text(r.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column {
                Text(r.subtitle, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                r.snippet?.let { Text(highlightSnippet(it, hl), style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis) }
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun FilterSheet(vm: SearchViewModel, onDismiss: () -> Unit) {
    val f by vm.filters.collectAsStateWithLifecycle()
    val added by vm.added.collectAsStateWithLifecycle()
    val opened by vm.opened.collectAsStateWithLifecycle()
    val collections by vm.collections.collectAsStateWithLifecycle()
    val folders by vm.folders.collectAsStateWithLifecycle()
    val tags by vm.tags.collectAsStateWithLifecycle()
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Filters", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = { vm.filters.value = SearchFilters(kinds = f.kinds); vm.added.value = DateRange.ANY; vm.opened.value = DateRange.ANY }) { Text("Reset") }
            }
            Text("File type", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DocFormat.entries.filter { it.supported }.forEach { fmt ->
                    val sel = fmt in f.formats
                    FilterChip(sel, { vm.filters.value = f.copy(formats = if (sel) f.formats - fmt else f.formats + fmt) }, { Text(fmt.label) })
                }
            }
            Text("Bookmark type", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                BookmarkType.entries.forEach { t ->
                    val sel = t.name in f.bookmarkTypes
                    FilterChip(sel, { vm.filters.value = f.copy(bookmarkTypes = if (sel) f.bookmarkTypes - t.name else f.bookmarkTypes + t.name) }, { Text(t.label) })
                }
            }
            Text("Collection", style = MaterialTheme.typography.titleSmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { CollectionPicker(collections, f.collectionId, { vm.filters.value = f.copy(collectionId = it) }, label = "Collection (Unsorted = any)") }
            }
            Text("Folder", style = MaterialTheme.typography.titleSmall)
            FolderPicker(folders, f.folderId) { vm.filters.value = f.copy(folderId = it) }
            if (tags.isNotEmpty()) {
                Text("Tags", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    tags.forEach { t ->
                        val sel = t.id in f.tagIds
                        FilterChip(sel, { vm.filters.value = f.copy(tagIds = if (sel) f.tagIds - t.id else f.tagIds + t.id) }, { Text("#${t.name}") })
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Favorites only", Modifier.weight(1f)); Switch(f.favoritesOnly, { vm.filters.value = f.copy(favoritesOnly = it) })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Available offline (bookmarks)", Modifier.weight(1f)); Switch(f.offlineOnly, { vm.filters.value = f.copy(offlineOnly = it) })
            }
            Text("Date added", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { DateRange.entries.forEach { d -> FilterChip(added == d, { vm.added.value = d }, { Text(d.label) }) } }
            Text("Last opened / recently accessed", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { DateRange.entries.forEach { d -> FilterChip(opened == d, { vm.opened.value = d }, { Text(d.label) }) } }
        }
    }
}
