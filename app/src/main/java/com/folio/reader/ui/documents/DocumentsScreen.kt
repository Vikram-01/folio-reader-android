package com.folio.reader.ui.documents

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.folio.reader.core.format.DocFormat
import com.folio.reader.core.format.ReaderKind
import com.folio.reader.data.db.DocumentEntity
import com.folio.reader.data.db.FolderEntity
import com.folio.reader.data.db.ReadingProgressEntity
import com.folio.reader.data.files.FileStore
import com.folio.reader.data.prefs.DocSort
import com.folio.reader.data.prefs.LayoutMode
import com.folio.reader.data.prefs.PrefsRepository
import com.folio.reader.data.prefs.UserPrefs
import com.folio.reader.data.repo.LibraryRepository
import com.folio.reader.ui.components.*
import com.folio.reader.ui.nav.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

enum class TypeFilter(val label: String) { ALL("All"), PDF("PDF"), BOOKS("Books"), TEXT("Text & data"), IMAGES("Images"), STORED("No viewer") }

data class DocsState(
    val folder: FolderEntity? = null,
    val path: List<FolderEntity> = emptyList(),
    val subfolders: List<FolderEntity> = emptyList(),
    val docs: List<DocumentEntity> = emptyList(),
    val progress: Map<Long, Float> = emptyMap(),
    val total: Int = 0,
)

@HiltViewModel
class DocumentsViewModel @Inject constructor(
    private val library: LibraryRepository,
    private val prefsRepo: PrefsRepository,
    val files: FileStore,
) : ViewModel() {
    val folderId = MutableStateFlow<Long?>(null)
    val filter = MutableStateFlow(TypeFilter.ALL)
    val tagFilter = MutableStateFlow<Long?>(null)
    val query = MutableStateFlow("")
    val selection = MutableStateFlow<Set<Long>>(emptySet())
    val prefs = prefsRepo.prefs.stateIn(viewModelScope, SharingStarted.Eagerly, UserPrefs())
    val tags = library.observeTags().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val folders = library.observeFolders().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val filters = combine(folderId, filter, tagFilter, query) { f, t, tag, q -> listOf(f, t, tag, q) }

    val state = combine(
        library.observeAll(), library.observeFolders(), library.observeProgress(), library.observeAllDocTagRefs(),
        combine(filters, prefsRepo.prefs) { a, b -> a to b },
    ) { docs, folders, progress, tagRefs, (flt, prefs) ->
        val folderId = flt[0] as Long?
        val type = flt[1] as TypeFilter
        val tag = flt[2] as Long?
        val q = (flt[3] as String).trim().lowercase()
        val folder = folders.firstOrNull { it.id == folderId }
        val path = buildList<FolderEntity> {
            var f = folder; var guard = 0
            while (f != null && guard++ < 32) { val cur: FolderEntity = f; add(0, cur); f = folders.firstOrNull { it.id == cur.parentId } }
        }
        val tagged = tag?.let { t -> tagRefs.filter { it.tagId == t }.map { it.documentId }.toSet() }
        val inScope = docs.filter { d ->
            // Searching, filtering by type or tag looks across all folders.
            val scoped = if (q.isEmpty() && type == TypeFilter.ALL && tag == null) d.folderId == folderId else true
            val fmt = DocFormat.entries.firstOrNull { it.name == d.format }
            val typeOk = when (type) {
                TypeFilter.ALL -> true
                TypeFilter.PDF -> fmt == DocFormat.PDF
                TypeFilter.BOOKS -> fmt?.kind == ReaderKind.EPUB
                TypeFilter.TEXT -> fmt?.kind in setOf(ReaderKind.TEXT, ReaderKind.MARKDOWN, ReaderKind.HTML, ReaderKind.TABLE)
                TypeFilter.IMAGES -> fmt?.kind == ReaderKind.IMAGE
                TypeFilter.STORED -> fmt?.supported != true
            }
            scoped && typeOk && (tagged == null || d.id in tagged) &&
                (q.isEmpty() || d.title.lowercase().contains(q) || d.originalName.lowercase().contains(q))
        }
        val sorted = when (prefs.docSort) {
            DocSort.RECENT_ADDED -> inScope.sortedByDescending { it.addedAt }
            DocSort.RECENT_OPENED -> inScope.sortedByDescending { it.lastOpenedAt ?: 0 }
            DocSort.TITLE -> inScope.sortedBy { it.title.lowercase() }
            DocSort.SIZE -> inScope.sortedByDescending { it.sizeBytes }
            DocSort.TYPE -> inScope.sortedWith(compareBy({ it.format }, { it.title.lowercase() }))
        }
        DocsState(folder, path, if (q.isEmpty() && type == TypeFilter.ALL && tag == null) folders.filter { it.parentId == folderId }.sortedBy { it.name.lowercase() } else emptyList(),
            sorted, progress.associate { it.documentId to it.fraction }, docs.size)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DocsState())

    fun openFolder(id: Long?) { folderId.value = id; selection.value = emptySet() }
    fun toggleSelect(id: Long) { selection.value = selection.value.let { if (id in it) it - id else it + id } }
    fun clearSelection() { selection.value = emptySet() }
    fun setLayout(m: LayoutMode) = viewModelScope.launch { prefsRepo.setDocLayout(m) }
    fun setSort(s: DocSort) = viewModelScope.launch { prefsRepo.setDocSort(s) }
    fun createFolder(name: String) = viewModelScope.launch { library.createFolder(name, folderId.value) }
    fun renameFolder(f: FolderEntity, name: String) = viewModelScope.launch { library.renameFolder(f, name) }
    fun deleteFolder(f: FolderEntity) = viewModelScope.launch { library.deleteFolder(f) }
    fun moveSelection(to: Long?) = viewModelScope.launch { library.moveToFolder(selection.value.toList(), to); clearSelection() }
    fun favoriteSelection() = viewModelScope.launch { selection.value.forEach { library.setFavorite(it, true) }; clearSelection() }
    fun deleteSelection() = viewModelScope.launch { selection.value.forEach { library.delete(it) }; clearSelection() }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun DocumentsScreen(nav: (String) -> Unit, onImport: () -> Unit, vm: DocumentsViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    val tagFilter by vm.tagFilter.collectAsStateWithLifecycle()
    val tags by vm.tags.collectAsStateWithLifecycle()
    val folders by vm.folders.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val selection by vm.selection.collectAsStateWithLifecycle()
    var details by remember { mutableStateOf<Long?>(null) }
    var newFolder by remember { mutableStateOf(false) }
    var folderMenu by remember { mutableStateOf<FolderEntity?>(null) }
    var renameFolder by remember { mutableStateOf<FolderEntity?>(null) }
    var deleteFolder by remember { mutableStateOf<FolderEntity?>(null) }
    var moveSel by remember { mutableStateOf(false) }
    var deleteSel by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    val thumbs = vm.files.thumbsDir
    val selecting = selection.isNotEmpty()

    BackHandler(enabled = selecting || s.folder != null || searching) {
        when {
            selecting -> vm.clearSelection()
            searching -> { searching = false; vm.query.value = "" }
            else -> vm.openFolder(s.folder?.parentId)
        }
    }

    Scaffold(
        topBar = {
            if (selecting) {
                TopAppBar(
                    navigationIcon = { IconButton(onClick = vm::clearSelection) { Icon(Icons.Default.Close, "Clear selection") } },
                    title = { Text("${selection.size} selected") },
                    actions = {
                        IconButton(onClick = { vm.favoriteSelection() }) { Icon(Icons.Outlined.StarBorder, "Favorite") }
                        IconButton(onClick = { moveSel = true }) { Icon(Icons.Outlined.DriveFileMove, "Move") }
                        IconButton(onClick = { deleteSel = true }) { Icon(Icons.Outlined.DeleteOutline, "Delete") }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                )
            } else if (searching) {
                TopAppBar(
                    navigationIcon = { IconButton(onClick = { searching = false; vm.query.value = "" }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Close search") } },
                    title = {
                        TextField(query, { vm.query.value = it }, placeholder = { Text("Filter by name") }, singleLine = true,
                            colors = TextFieldDefaults.colors(focusedContainerColor = MaterialTheme.colorScheme.surface, unfocusedContainerColor = MaterialTheme.colorScheme.surface),
                            modifier = Modifier.fillMaxWidth())
                    },
                )
            } else {
                TopAppBar(
                    navigationIcon = { if (s.folder != null) IconButton(onClick = { vm.openFolder(s.folder?.parentId) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Up") } },
                    title = { Text(s.folder?.name ?: "Library", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    actions = {
                        IconButton(onClick = { searching = true }) { Icon(Icons.Outlined.FilterList, "Filter") }
                        IconButton(onClick = { vm.setLayout(if (prefs.docLayout == LayoutMode.GRID) LayoutMode.LIST else LayoutMode.GRID) }) {
                            Icon(if (prefs.docLayout == LayoutMode.GRID) Icons.Outlined.ViewList else Icons.Outlined.GridView, "Layout")
                        }
                        Box {
                            IconButton(onClick = { sortMenu = true }) { Icon(Icons.Outlined.Sort, "Sort") }
                            DropdownMenu(sortMenu, { sortMenu = false }) {
                                DocSort.entries.forEach { o ->
                                    DropdownMenuItem(text = { Text(o.label) }, onClick = { vm.setSort(o); sortMenu = false },
                                        trailingIcon = { if (prefs.docSort == o) Icon(Icons.Default.Check, null) })
                                }
                            }
                        }
                        IconButton(onClick = { newFolder = true }) { Icon(Icons.Outlined.CreateNewFolder, "New folder") }
                    },
                )
            }
        },
        floatingActionButton = {
            if (!selecting) ExtendedFloatingActionButton(onClick = onImport, icon = { Icon(Icons.Default.Add, null) }, text = { Text("Import") })
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(TypeFilter.entries.toList()) { t -> FilterChip(selected = filter == t, onClick = { vm.filter.value = t }, label = { Text(t.label) }) }
                items(tags, key = { "t${it.id}" }) { t ->
                    FilterChip(selected = tagFilter == t.id, onClick = { vm.tagFilter.value = if (tagFilter == t.id) null else t.id }, label = { Text("#${t.name}") })
                }
            }
            if (s.path.isNotEmpty()) {
                Row(Modifier.padding(horizontal = 20.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Library", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clip(RoundedCornerShape(6.dp)).combinedClickable(onClick = { vm.openFolder(null) }).padding(4.dp))
                    s.path.forEach { f ->
                        Text(" / ", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(f.name, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, maxLines = 1,
                            modifier = Modifier.clip(RoundedCornerShape(6.dp)).combinedClickable(onClick = { vm.openFolder(f.id) }).padding(4.dp))
                    }
                }
            }

            if (s.docs.isEmpty() && s.subfolders.isEmpty()) {
                EmptyState(
                    Icons.Outlined.LibraryBooks,
                    if (s.total == 0) "No documents yet" else "Nothing here",
                    if (s.total == 0) "Import PDFs, EPUB books, text, CSV and images. Imported files are copied into Folio, so they stay even if the original is moved or deleted."
                    else "Try another filter or folder.",
                    Modifier.padding(top = 32.dp),
                ) { if (s.total == 0) Button(onClick = onImport) { Text("Import documents") } }
                return@Scaffold
            }

            val grid = prefs.docLayout == LayoutMode.GRID
            LazyVerticalGrid(
                columns = if (grid) GridCells.Adaptive(120.dp) else GridCells.Fixed(1),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(if (grid) 16.dp else 4.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                if (s.subfolders.isNotEmpty()) {
                    items(s.subfolders, key = { "f${it.id}" }, span = { GridItemSpan(if (grid) 1 else maxLineSpan) }) { f ->
                        FolderTile(f, grid, onClick = { vm.openFolder(f.id) }, onLong = { folderMenu = f })
                    }
                }
                items(s.docs, key = { it.id }, span = { GridItemSpan(if (grid) 1 else maxLineSpan) }) { d ->
                    val fmt = DocFormat.entries.firstOrNull { it.name == d.format }
                    val selected = d.id in selection
                    val click: () -> Unit = {
                        if (selecting) vm.toggleSelect(d.id) else if (fmt?.supported == true) nav(Routes.reader(d.id)) else { details = d.id }
                        Unit
                    }
                    if (grid) {
                        Column(
                            Modifier.clip(RoundedCornerShape(14.dp))
                                .then(if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(14.dp)) else Modifier)
                                .combinedClickable(onClick = click, onLongClick = { vm.toggleSelect(d.id) })
                                .padding(4.dp),
                        ) {
                            Box {
                                DocCover(d.thumbnailName?.let { File(thumbs, it) }, fmt, d.title, Modifier.fillMaxWidth().aspectRatio(0.72f))
                                s.progress[d.id]?.let { p ->
                                    LinearProgressIndicator(progress = { p }, modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(6.dp).height(3.dp).clip(RoundedCornerShape(2.dp)))
                                }
                                if (d.isFavorite) Icon(Icons.Filled.Star, null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(18.dp))
                                if (selected) Icon(Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.align(Alignment.TopStart).padding(6.dp))
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(d.title, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                FormatBadge(fmt)
                                Spacer(Modifier.weight(1f))
                                IconButton(onClick = { details = d.id }, modifier = Modifier.size(28.dp)) { Icon(Icons.Default.MoreVert, "More", Modifier.size(18.dp)) }
                            }
                        }
                    } else {
                        ListItem(
                            modifier = Modifier.clip(RoundedCornerShape(12.dp)).combinedClickable(onClick = click, onLongClick = { vm.toggleSelect(d.id) }),
                            colors = ListItemDefaults.colors(containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface),
                            leadingContent = { DocCover(d.thumbnailName?.let { File(thumbs, it) }, fmt, d.title, Modifier.size(44.dp, 60.dp), 8.dp) },
                            headlineContent = { Text(d.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    FormatBadge(fmt)
                                    Text("${formatBytes(d.sizeBytes)} · ${relativeTime(d.lastOpenedAt ?: d.addedAt)}", maxLines = 1)
                                    s.progress[d.id]?.let { Text("· ${(it * 100).toInt()}%") }
                                }
                            },
                            trailingContent = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (d.isFavorite) Icon(Icons.Filled.Star, null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(18.dp))
                                    IconButton(onClick = { details = d.id }) { Icon(Icons.Default.MoreVert, "More") }
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    details?.let { id -> DocumentDetailsSheet(id, onDismiss = { details = null }, onOpen = { nav(Routes.reader(id)) }) }
    if (newFolder) TextInputDialog("New folder", confirm = "Create", onDismiss = { newFolder = false }) { vm.createFolder(it) }
    folderMenu?.let { f ->
        AlertDialog(onDismissRequest = { folderMenu = null }, title = { Text(f.name) },
            text = { Text("Rename or delete this folder. Deleting a folder keeps its documents — they move to the library root.") },
            confirmButton = { TextButton(onClick = { renameFolder = f; folderMenu = null }) { Text("Rename") } },
            dismissButton = { TextButton(onClick = { deleteFolder = f; folderMenu = null }) { Text("Delete", color = MaterialTheme.colorScheme.error) } })
    }
    renameFolder?.let { f -> TextInputDialog("Rename folder", f.name, onDismiss = { renameFolder = null }) { vm.renameFolder(f, it) } }
    deleteFolder?.let { f ->
        ConfirmDialog("Delete folder “${f.name}”?", "Sub-folders are removed too. Documents inside are kept and moved to the library root.", "Delete folder",
            onDismiss = { deleteFolder = null }) { vm.deleteFolder(f) }
    }
    if (moveSel) {
        var target by remember { mutableStateOf<Long?>(null) }
        AlertDialog(onDismissRequest = { moveSel = false }, title = { Text("Move ${selection.size} document(s)") },
            text = { FolderPicker(folders, target) { target = it } },
            confirmButton = { TextButton(onClick = { vm.moveSelection(target); moveSel = false }) { Text("Move") } },
            dismissButton = { TextButton(onClick = { moveSel = false }) { Text("Cancel") } })
    }
    if (deleteSel) ConfirmDialog("Delete ${selection.size} document(s)?", "The files and their notes will be removed from this device. This can't be undone.", "Delete",
        onDismiss = { deleteSel = false }) { vm.deleteSelection() }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderTile(f: FolderEntity, grid: Boolean, onClick: () -> Unit, onLong: () -> Unit) {
    if (grid) {
        Column(Modifier.clip(RoundedCornerShape(14.dp)).combinedClickable(onClick = onClick, onLongClick = onLong).padding(4.dp)) {
            Box(Modifier.fillMaxWidth().aspectRatio(0.72f).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Folder, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(40.dp))
            }
            Spacer(Modifier.height(6.dp))
            Text(f.name, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    } else {
        ListItem(modifier = Modifier.clip(RoundedCornerShape(12.dp)).combinedClickable(onClick = onClick, onLongClick = onLong),
            leadingContent = { Icon(Icons.Filled.Folder, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp)) },
            headlineContent = { Text(f.name) })
    }
}

