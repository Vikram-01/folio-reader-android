package com.folio.reader.ui.collections

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.folio.reader.data.db.AppDatabase
import com.folio.reader.data.db.BookmarkEntity
import com.folio.reader.data.db.CollectionEntity
import com.folio.reader.data.db.DocumentEntity
import com.folio.reader.data.db.TagCount
import com.folio.reader.data.files.FileStore
import com.folio.reader.data.repo.BookmarkRepository
import com.folio.reader.ui.bookmarks.BookmarkRow
import com.folio.reader.ui.bookmarks.flattenCollections
import com.folio.reader.ui.components.*
import com.folio.reader.ui.home.DocRow
import com.folio.reader.ui.nav.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

val COLLECTION_COLORS = listOf(0xFFE07A4FL, 0xFF1F4E5FL, 0xFF6E8B74L, 0xFFB8860BL, 0xFF8A5A9EL, 0xFFC8553DL, 0xFF3D6C9EL, 0xFF5E6770L)

@HiltViewModel
class CollectionsViewModel @Inject constructor(private val repo: BookmarkRepository, db: AppDatabase) : ViewModel() {
    val collections = repo.observeCollections().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val counts = combine(db.collections().observeBookmarkCounts(), db.collections().observeDocumentCounts()) { b, d ->
        val m = HashMap<Long, Int>()
        b.forEach { m[it.id] = (m[it.id] ?: 0) + it.count }
        d.forEach { m[it.id] = (m[it.id] ?: 0) + it.count }
        m.toMap()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())
    val tags = db.tags().observeTagCounts().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList<TagCount>())

    fun create(name: String, parent: Long?, color: Long) = viewModelScope.launch { repo.createCollection(name, parent, color) }
    fun update(c: CollectionEntity) = viewModelScope.launch { repo.updateCollection(c) }
    fun delete(c: CollectionEntity) = viewModelScope.launch { repo.deleteCollection(c) }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CollectionsScreen(nav: (String) -> Unit, vm: CollectionsViewModel = hiltViewModel()) {
    val all by vm.collections.collectAsStateWithLifecycle()
    val counts by vm.counts.collectAsStateWithLifecycle()
    val tags by vm.tags.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<CollectionEntity?>(null) }
    var creating by remember { mutableStateOf<Long?>(-1) }
    var showCreate by remember { mutableStateOf(false) }
    val flat = remember(all) { flattenCollections(all) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Collections") }) },
        floatingActionButton = { ExtendedFloatingActionButton(onClick = { creating = null; showCreate = true }, icon = { Icon(Icons.Outlined.CreateNewFolder, null) }, text = { Text("New collection") }) },
    ) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
            item {
                Text("Group bookmarks and documents together. Collections can be nested.", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
            }
            if (flat.isEmpty()) item { EmptyState(Icons.Outlined.FolderSpecial, "No collections yet", "Create one for a project, a topic or a reading list.") }
            items(flat, key = { it.first.id }) { (c, depth) ->
                ListItem(
                    modifier = Modifier.clickable { nav(Routes.collection(c.id)) }.padding(start = (depth * 20).dp),
                    leadingContent = {
                        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(Color(c.color).copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                            Icon(if (depth == 0) Icons.Filled.FolderSpecial else Icons.Outlined.SubdirectoryArrowRight, null, tint = Color(c.color))
                        }
                    },
                    headlineContent = { Text(c.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text("${counts[c.id] ?: 0} items") },
                    trailingContent = { IconButton(onClick = { editing = c }) { Icon(Icons.Default.MoreVert, "Edit") } },
                )
            }
            if (tags.isNotEmpty()) {
                item { SectionHeader("Tags") }
                item {
                    FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        tags.forEach { t -> AssistChip(onClick = { nav(Routes.tag(t.id)) }, label = { Text("#${t.name}  ${t.count}") }) }
                    }
                }
            }
        }
    }

    if (showCreate) CollectionDialog(null, all, creating, onDismiss = { showCreate = false }) { name, parent, color -> vm.create(name, parent, color) }
    editing?.let { c ->
        var confirm by remember { mutableStateOf(false) }
        CollectionDialog(c, all, c.parentId, onDismiss = { editing = null }, onDelete = { confirm = true }) { name, parent, color ->
            vm.update(c.copy(name = name, parentId = parent?.takeIf { it != c.id }, color = color))
        }
        if (confirm) ConfirmDialog("Delete “${c.name}”?", "Nested collections are deleted too. Bookmarks and documents are kept — bookmarks become unsorted.", "Delete",
            onDismiss = { confirm = false }) { vm.delete(c); editing = null }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CollectionDialog(existing: CollectionEntity?, all: List<CollectionEntity>, initialParent: Long?, onDismiss: () -> Unit, onDelete: (() -> Unit)? = null,
                     onSave: (String, Long?, Long) -> Unit) {
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var parent by remember { mutableStateOf(initialParent?.takeIf { it > 0 }) }
    var color by remember { mutableStateOf(existing?.color ?: COLLECTION_COLORS.first()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "New collection" else "Edit collection") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                com.folio.reader.ui.bookmarks.CollectionPicker(all.filter { it.id != existing?.id }, parent, { parent = it }, label = "Inside")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    COLLECTION_COLORS.forEach { c ->
                        Box(Modifier.size(32.dp).clip(CircleShape).background(Color(c)).clickable { color = c }, contentAlignment = Alignment.Center) {
                            if (c == color) Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(18.dp))
                        }
                    }
                }
                if (onDelete != null) TextButton(onClick = onDelete, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Delete collection") }
            }
        },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onSave(name.trim(), parent, color); onDismiss() }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@HiltViewModel
class CollectionDetailViewModel @Inject constructor(handle: SavedStateHandle, db: AppDatabase, repo: BookmarkRepository, val files: FileStore) : ViewModel() {
    private val id: Long = handle["id"] ?: 0L
    val collection = repo.observeCollections().map { a -> a.firstOrNull { it.id == id } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val children = repo.observeCollections().map { a -> a.filter { it.parentId == id } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val bookmarks = repo.observeInCollection(id).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList<BookmarkEntity>())
    val documents = db.collections().observeDocuments(id).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList<DocumentEntity>())
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectionDetailScreen(id: Long, nav: (String) -> Unit, back: () -> Unit, vm: CollectionDetailViewModel = hiltViewModel()) {
    val c by vm.collection.collectAsStateWithLifecycle()
    val kids by vm.children.collectAsStateWithLifecycle()
    val bms by vm.bookmarks.collectAsStateWithLifecycle()
    val docs by vm.documents.collectAsStateWithLifecycle()
    var edit by remember { mutableStateOf<Long?>(null) }
    Scaffold(topBar = {
        TopAppBar(navigationIcon = { IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            title = { Text(c?.name ?: "Collection") })
    }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
            if (kids.isNotEmpty()) item {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(kids, key = { it.id }) { k -> AssistChip(onClick = { nav(Routes.collection(k.id)) }, label = { Text(k.name) }, leadingIcon = { Icon(Icons.Outlined.FolderSpecial, null) }) }
                }
            }
            if (docs.isNotEmpty()) {
                item { SectionHeader("Documents") }
                item { DocRow(docs, vm.files.thumbsDir) { nav(Routes.reader(it.id)) } }
            }
            if (bms.isNotEmpty()) item { SectionHeader("Bookmarks") }
            items(bms, key = { it.id }) { b ->
                BookmarkRow(b, emptyList(), null, vm.files.faviconDir, false, onClick = { nav(Routes.browser(b.url)) }, onLong = { edit = b.id },
                    onMore = { edit = b.id }, onOffline = { nav(Routes.offline(b.id)) })
            }
            if (docs.isEmpty() && bms.isEmpty() && kids.isEmpty()) item {
                EmptyState(Icons.Outlined.Inbox, "Empty collection", "Add bookmarks from their edit sheet, or documents from their details sheet.")
            }
        }
    }
    edit?.let { bid ->
        val all by hiltViewModel<CollectionsViewModel>().collections.collectAsStateWithLifecycle()
        com.folio.reader.ui.bookmarks.BookmarkEditSheet(bid, all, nav) { edit = null }
    }
}

@HiltViewModel
class TagViewModel @Inject constructor(handle: SavedStateHandle, db: AppDatabase, val files: FileStore) : ViewModel() {
    private val id: Long = handle["id"] ?: 0L
    val name = db.tags().observeAll().map { a -> a.firstOrNull { it.id == id }?.name ?: "" }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val docs = combine(db.documents().observeAll(), db.tags().observeAllDocTagRefs()) { d, r ->
        val ids = r.filter { it.tagId == id }.map { it.documentId }.toSet(); d.filter { it.id in ids }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val bookmarks = combine(db.bookmarks().observeAll(), db.tags().observeAllBookmarkTagRefs()) { b, r ->
        val ids = r.filter { it.tagId == id }.map { it.bookmarkId }.toSet(); b.filter { it.id in ids }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TagScreen(id: Long, nav: (String) -> Unit, back: () -> Unit, vm: TagViewModel = hiltViewModel()) {
    val name by vm.name.collectAsStateWithLifecycle()
    val docs by vm.docs.collectAsStateWithLifecycle()
    val bms by vm.bookmarks.collectAsStateWithLifecycle()
    Scaffold(topBar = {
        TopAppBar(navigationIcon = { IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }, title = { Text("#$name") })
    }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize()) {
            if (docs.isNotEmpty()) { item { SectionHeader("Documents") }; item { DocRow(docs, vm.files.thumbsDir) { nav(Routes.reader(it.id)) } } }
            if (bms.isNotEmpty()) item { SectionHeader("Bookmarks") }
            items(bms, key = { it.id }) { b ->
                BookmarkRow(b, emptyList(), null, vm.files.faviconDir, false, onClick = { nav(Routes.browser(b.url)) }, onLong = {}, onMore = {}, onOffline = { nav(Routes.offline(b.id)) })
            }
        }
    }
}
