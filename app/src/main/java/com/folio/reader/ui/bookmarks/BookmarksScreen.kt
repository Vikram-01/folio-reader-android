package com.folio.reader.ui.bookmarks

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.folio.reader.core.security.UrlSafety
import com.folio.reader.data.db.BookmarkEntity
import com.folio.reader.data.db.BookmarkType
import com.folio.reader.data.db.CollectionEntity
import com.folio.reader.data.db.TagEntity
import com.folio.reader.data.files.FileStore
import com.folio.reader.data.repo.BookmarkRepository
import com.folio.reader.data.repo.LibraryRepository
import com.folio.reader.ui.components.*
import com.folio.reader.ui.home.Favicon
import com.folio.reader.ui.nav.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

enum class BmFilter(val label: String) { ALL("All"), FAVORITES("Favorites"), OFFLINE("Offline"), UNSORTED("Unsorted") }

@HiltViewModel
class BookmarksViewModel @Inject constructor(
    private val repo: BookmarkRepository,
    library: LibraryRepository,
    val files: FileStore,
) : ViewModel() {
    val filter = MutableStateFlow(BmFilter.ALL)
    val type = MutableStateFlow<BookmarkType?>(null)
    val collection = MutableStateFlow<Long?>(null)
    val tag = MutableStateFlow<Long?>(null)
    val query = MutableStateFlow("")
    val selection = MutableStateFlow<Set<Long>>(emptySet())
    val collections = repo.observeCollections().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val tags = library.observeTags().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val message = MutableStateFlow<String?>(null)

    private val tagRefs = repo.observeAllTagRefs()
    val tagsByBookmark = combine(tagRefs, library.observeTags()) { refs, tags ->
        val byId = tags.associateBy { it.id }
        refs.groupBy { it.bookmarkId }.mapValues { (_, r) -> r.mapNotNull { byId[it.tagId]?.name } }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    private val criteria = combine(filter, type, collection, tag, query) { f, t, c, tg, q -> Criteria(f, t, c, tg, q) }
    private data class Criteria(val f: BmFilter, val t: BookmarkType?, val c: Long?, val tag: Long?, val q: String)

    val items = combine(repo.observeAll(), criteria, tagRefs) { all, cr, refs ->
        val tagged = cr.tag?.let { t -> refs.filter { it.tagId == t }.map { it.bookmarkId }.toSet() }
        val q = cr.q.trim().lowercase()
        all.filter { b ->
            when (cr.f) {
                BmFilter.ALL -> true
                BmFilter.FAVORITES -> b.isFavorite
                BmFilter.OFFLINE -> b.offlineName != null
                BmFilter.UNSORTED -> b.collectionId == null
            } && (cr.t == null || b.type == cr.t.name) && (cr.c == null || b.collectionId == cr.c) &&
                (tagged == null || b.id in tagged) &&
                (q.isEmpty() || b.title.lowercase().contains(q) || b.url.lowercase().contains(q) || b.note.lowercase().contains(q))
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun toggle(id: Long) { selection.value = selection.value.let { if (id in it) it - id else it + id } }
    fun clearSelection() { selection.value = emptySet() }
    fun selectAll() { selection.value = items.value.map { it.id }.toSet() }
    fun moveSelection(to: Long?) = viewModelScope.launch { repo.move(selection.value.toList(), to); clearSelection() }
    fun favoriteSelection(fav: Boolean) = viewModelScope.launch { repo.setFavorite(selection.value.toList(), fav); clearSelection() }
    fun deleteSelection() = viewModelScope.launch { repo.delete(selection.value.toList()); clearSelection() }
    fun importHtml(uri: Uri) = viewModelScope.launch {
        message.value = runCatching { repo.importHtml(uri) }.fold(
            { "Imported ${it.added} bookmarks" + (if (it.skippedDuplicates > 0) ", skipped ${it.skippedDuplicates} duplicates" else "") + (if (it.invalid > 0) ", ignored ${it.invalid} non-web links" else "") },
            { "Import failed: ${it.message}" })
    }
    fun exportHtml(uri: Uri) = viewModelScope.launch {
        message.value = runCatching { repo.exportHtml(uri) }.fold({ "Exported $it bookmarks" }, { "Export failed: ${it.message}" })
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun BookmarksScreen(nav: (String) -> Unit, onAdd: () -> Unit, vm: BookmarksViewModel = hiltViewModel()) {
    val items by vm.items.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    val type by vm.type.collectAsStateWithLifecycle()
    val collection by vm.collection.collectAsStateWithLifecycle()
    val collections by vm.collections.collectAsStateWithLifecycle()
    val tags by vm.tags.collectAsStateWithLifecycle()
    val tag by vm.tag.collectAsStateWithLifecycle()
    val tagNames by vm.tagsByBookmark.collectAsStateWithLifecycle()
    val selection by vm.selection.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    var edit by remember { mutableStateOf<Long?>(null) }
    var menu by remember { mutableStateOf(false) }
    var moveSel by remember { mutableStateOf(false) }
    var deleteSel by remember { mutableStateOf(false) }
    val selecting = selection.isNotEmpty()
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::importHtml) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/html")) { it?.let(vm::exportHtml) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.message.value = null } }
    BackHandler(selecting) { vm.clearSelection() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (selecting) TopAppBar(
                navigationIcon = { IconButton(onClick = vm::clearSelection) { Icon(Icons.Default.Close, "Clear") } },
                title = { Text("${selection.size} selected") },
                actions = {
                    IconButton(onClick = vm::selectAll) { Icon(Icons.Outlined.SelectAll, "Select all") }
                    IconButton(onClick = { vm.favoriteSelection(true) }) { Icon(Icons.Outlined.StarBorder, "Favorite") }
                    IconButton(onClick = { moveSel = true }) { Icon(Icons.Outlined.DriveFileMove, "Move") }
                    IconButton(onClick = { deleteSel = true }) { Icon(Icons.Outlined.DeleteOutline, "Delete") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
            ) else TopAppBar(
                title = { Text("Bookmarks") },
                actions = {
                    IconButton(onClick = { nav(Routes.browser()) }) { Icon(Icons.Outlined.Public, "Browser") }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More") }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem(text = { Text("Import bookmarks (HTML)") }, leadingIcon = { Icon(Icons.Outlined.FileDownload, null) },
                                onClick = { menu = false; importer.launch(arrayOf("text/html", "application/xhtml+xml", "application/octet-stream")) })
                            DropdownMenuItem(text = { Text("Export bookmarks (HTML)") }, leadingIcon = { Icon(Icons.Outlined.FileUpload, null) },
                                onClick = { menu = false; exporter.launch("folio-bookmarks.html") })
                            DropdownMenuItem(text = { Text("Browsing history") }, leadingIcon = { Icon(Icons.Outlined.History, null) },
                                onClick = { menu = false; nav(Routes.HISTORY) })
                            DropdownMenuItem(text = { Text("Downloads") }, leadingIcon = { Icon(Icons.Outlined.Download, null) },
                                onClick = { menu = false; nav(Routes.DOWNLOADS) })
                        }
                    }
                },
            )
        },
        floatingActionButton = { if (!selecting) ExtendedFloatingActionButton(onClick = onAdd, icon = { Icon(Icons.Filled.BookmarkAdd, null) }, text = { Text("Save link") }) },
    ) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
            item {
                OutlinedTextField(query, { vm.query.value = it }, placeholder = { Text("Filter bookmarks") }, singleLine = true,
                    leadingIcon = { Icon(Icons.Outlined.Search, null) },
                    trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { vm.query.value = "" }) { Icon(Icons.Default.Close, "Clear") } },
                    shape = RoundedCornerShape(28.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp))
            }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(BmFilter.entries.toList()) { f -> FilterChip(filter == f, { vm.filter.value = f }, { Text(f.label) }) }
                    items(BookmarkType.entries.toList()) { t -> FilterChip(type == t, { vm.type.value = if (type == t) null else t }, { Text(t.label) }) }
                }
                if (collections.isNotEmpty() || tags.isNotEmpty()) LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(collections, key = { "c${it.id}" }) { c ->
                        FilterChip(collection == c.id, { vm.collection.value = if (collection == c.id) null else c.id }, { Text(c.name) },
                            leadingIcon = { Box(Modifier.size(8.dp).clip(CircleShape).background(Color(c.color))) })
                    }
                    items(tags, key = { "t${it.id}" }) { t -> FilterChip(tag == t.id, { vm.tag.value = if (tag == t.id) null else t.id }, { Text("#${t.name}") }) }
                }
            }
            if (items.isEmpty()) item {
                EmptyState(Icons.Outlined.BookmarkBorder, "No bookmarks here",
                    "Save links from any app with Share → Save link to Folio, or tap Save link. Pages open in Folio's private browser.",
                    Modifier.padding(top = 24.dp))
            }
            items(items, key = { it.id }) { b ->
                BookmarkRow(
                    b, tagNames[b.id].orEmpty(), collections.firstOrNull { it.id == b.collectionId }, vm.files.faviconDir, b.id in selection,
                    onClick = { if (selecting) vm.toggle(b.id) else nav(Routes.browser(b.url)) },
                    onLong = { vm.toggle(b.id) },
                    onMore = { edit = b.id },
                    onOffline = { nav(Routes.offline(b.id)) },
                )
            }
        }
    }
    edit?.let { id -> BookmarkEditSheet(id, collections, nav) { edit = null } }
    if (moveSel) {
        var target by remember { mutableStateOf<Long?>(null) }
        AlertDialog(onDismissRequest = { moveSel = false }, title = { Text("Move ${selection.size} bookmark(s)") },
            text = { CollectionPicker(collections, target, { target = it }) },
            confirmButton = { TextButton(onClick = { vm.moveSelection(target); moveSel = false }) { Text("Move") } },
            dismissButton = { TextButton(onClick = { moveSel = false }) { Text("Cancel") } })
    }
    if (deleteSel) ConfirmDialog("Delete ${selection.size} bookmark(s)?", "Saved offline copies are deleted too.", "Delete", onDismiss = { deleteSel = false }) { vm.deleteSelection() }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun BookmarkRow(
    b: BookmarkEntity, tags: List<String>, collection: CollectionEntity?, favicons: File, selected: Boolean,
    onClick: () -> Unit, onLong: () -> Unit, onMore: () -> Unit, onOffline: () -> Unit,
) {
    Surface(
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLong),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            if (selected) Icon(Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(36.dp))
            else Favicon(b.faviconName?.let { File(favicons, it) }, b.title)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(b.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(UrlSafety.hostOf(b.url), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    Text(" · ${BookmarkType.entries.firstOrNull { it.name == b.type }?.label ?: ""}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    collection?.let { Text(" · ${it.name}", style = MaterialTheme.typography.bodySmall, color = Color(it.color), maxLines = 1) }
                }
                if (b.note.isNotBlank()) Text(b.note, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                if (tags.isNotEmpty()) FlowRow(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    tags.forEach { Text("#$it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) }
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                IconButton(onClick = onMore, modifier = Modifier.size(36.dp)) { Icon(Icons.Default.MoreVert, "Edit") }
                if (b.offlineName != null) IconButton(onClick = onOffline, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Outlined.OfflinePin, "Read offline", tint = MaterialTheme.colorScheme.tertiary)
                }
                if (b.isFavorite) Icon(Icons.Filled.Star, null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(16.dp))
            }
        }
    }
}

@HiltViewModel
class BookmarkEditViewModel @Inject constructor(private val repo: BookmarkRepository) : ViewModel() {
    suspend fun load(id: Long): Pair<BookmarkEntity, List<String>>? {
        val b = repo.get(id) ?: return null
        val tags = repo.observeTags(id).first().map(TagEntity::name)
        return b to tags
    }
    fun save(b: BookmarkEntity, tags: List<String>) = viewModelScope.launch { repo.update(b, tags) }
    fun delete(id: Long) = viewModelScope.launch { repo.delete(listOf(id)) }
    fun removeOffline(b: BookmarkEntity) = viewModelScope.launch { repo.removeOffline(b) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookmarkEditSheet(id: Long, collections: List<CollectionEntity>, nav: (String) -> Unit, onDismiss: () -> Unit) {
    val vm: BookmarkEditViewModel = hiltViewModel(key = "bmEdit$id")
    var draft by remember { mutableStateOf<BookmarkEntity?>(null) }
    var tags by remember { mutableStateOf(emptyList<String>()) }
    var confirmDelete by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    LaunchedEffect(id) { vm.load(id)?.let { (b, t) -> draft = b; tags = t } }
    val b = draft ?: return
    ModalBottomSheet(onDismissRequest = { vm.save(b, tags); onDismiss() }) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp).imePadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Edit bookmark", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                IconToggleButton(b.isFavorite, { draft = b.copy(isFavorite = it) }) {
                    Icon(if (b.isFavorite) Icons.Filled.Star else Icons.Outlined.StarBorder, "Favorite", tint = if (b.isFavorite) MaterialTheme.colorScheme.secondary else LocalContentColor.current)
                }
            }
            OutlinedTextField(b.title, { draft = b.copy(title = it) }, label = { Text("Title") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(b.url, { draft = b.copy(url = it) }, label = { Text("URL") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                isError = !UrlSafety.isWebUrl(b.url))
            OutlinedTextField(b.note, { draft = b.copy(note = it) }, label = { Text("Note") }, minLines = 3, modifier = Modifier.fillMaxWidth())
            var typeOpen by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(typeOpen, { typeOpen = it }) {
                OutlinedTextField(BookmarkType.entries.firstOrNull { it.name == b.type }?.label ?: "Link", {}, readOnly = true, label = { Text("Type") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(typeOpen) }, modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable))
                ExposedDropdownMenu(typeOpen, { typeOpen = false }) {
                    BookmarkType.entries.forEach { t -> DropdownMenuItem(text = { Text(t.label) }, onClick = { draft = b.copy(type = t.name); typeOpen = false }) }
                }
            }
            CollectionPicker(collections, b.collectionId, { draft = b.copy(collectionId = it) })
            TagEditor(tags, { tags = it })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.save(b, tags); onDismiss(); nav(Routes.browser(b.url)) }) { Text("Open") }
                OutlinedButton(onClick = {
                    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, b.url).putExtra(Intent.EXTRA_SUBJECT, b.title), "Share link"))
                }) { Text("Share") }
                if (b.offlineName != null) OutlinedButton(onClick = { onDismiss(); nav(Routes.offline(b.id)) }) { Text("Offline copy") }
            }
            if (b.offlineName != null) TextButton(onClick = { vm.removeOffline(b); draft = b.copy(offlineName = null) }) { Text("Remove offline copy") }
            TextButton(onClick = { confirmDelete = true }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                Icon(Icons.Outlined.DeleteOutline, null); Spacer(Modifier.width(6.dp)); Text("Delete bookmark")
            }
        }
    }
    if (confirmDelete) ConfirmDialog("Delete bookmark?", b.title, "Delete", onDismiss = { confirmDelete = false }) { vm.delete(b.id); onDismiss() }
}
