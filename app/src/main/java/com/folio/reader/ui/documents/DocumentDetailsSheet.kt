package com.folio.reader.ui.documents

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.folio.reader.core.format.DocFormat
import com.folio.reader.data.db.CollectionEntity
import com.folio.reader.data.db.DocumentEntity
import com.folio.reader.data.db.FolderEntity
import com.folio.reader.data.files.FileStore
import com.folio.reader.data.repo.BookmarkRepository
import com.folio.reader.data.repo.LibraryRepository
import com.folio.reader.ui.components.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class DocumentActionsViewModel @Inject constructor(
    private val library: LibraryRepository,
    bookmarks: BookmarkRepository,
    val files: FileStore,
) : ViewModel() {
    private val id = MutableStateFlow<Long?>(null)
    fun bind(docId: Long) { id.value = docId }

    val doc = id.flatMapLatest { it?.let(library::observe) ?: flowOf(null) }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val tags = id.flatMapLatest { it?.let(library::observeDocTags) ?: flowOf(emptyList()) }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val memberOf = id.flatMapLatest { it?.let(library::observeCollectionsOf) ?: flowOf(emptyList()) }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val collections = bookmarks.observeCollections().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val folders = library.observeFolders().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun rename(t: String) = id.value?.let { viewModelScope.launch { library.rename(it, t) } }
    fun favorite(f: Boolean) = id.value?.let { viewModelScope.launch { library.setFavorite(it, f) } }
    fun setTags(t: List<String>) = id.value?.let { viewModelScope.launch { library.setDocumentTags(it, t) } }
    fun moveTo(folder: Long?) = id.value?.let { viewModelScope.launch { library.moveToFolder(listOf(it), folder) } }
    fun toggleCollection(c: Long, member: Boolean) = id.value?.let {
        viewModelScope.launch { if (member) library.removeFromCollection(it, c) else library.addToCollection(it, c) }
    }
    fun export(uri: Uri, done: (Boolean) -> Unit) = id.value?.let { viewModelScope.launch { done(library.export(it, uri)) } }
    fun delete(done: () -> Unit) = id.value?.let { viewModelScope.launch { library.delete(it); done() } }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DocumentDetailsSheet(docId: Long, onDismiss: () -> Unit, onOpen: (() -> Unit)? = null, onDeleted: () -> Unit = {}) {
    val vm: DocumentActionsViewModel = hiltViewModel(key = "docActions$docId")
    LaunchedEffect(docId) { vm.bind(docId) }
    val doc by vm.doc.collectAsStateWithLifecycle()
    val tags by vm.tags.collectAsStateWithLifecycle()
    val memberOf by vm.memberOf.collectAsStateWithLifecycle()
    val collections by vm.collections.collectAsStateWithLifecycle()
    val folders by vm.folders.collectAsStateWithLifecycle()
    var rename by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var exportResult by remember { mutableStateOf<String?>(null) }
    val d = doc ?: return
    val format = DocFormat.entries.firstOrNull { it.name == d.format }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(d.mime)) { uri ->
        if (uri != null) vm.export(uri) { ok -> exportResult = if (ok) "Exported a copy." else "Export failed." }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                DocCover(d.thumbnailName?.let { File(vm.files.thumbsDir, it) }, format, d.title, Modifier.size(72.dp, 100.dp))
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(d.title, style = MaterialTheme.typography.titleLarge, maxLines = 3)
                    d.author?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        FormatBadge(format)
                        Text(formatBytes(d.sizeBytes) + (d.pageCount?.let { " · $it ${if (format == DocFormat.EPUB) "sections" else "pages"}" } ?: ""),
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
                IconToggleButton(checked = d.isFavorite, onCheckedChange = { vm.favorite(it) }) {
                    Icon(if (d.isFavorite) Icons.Filled.Star else Icons.Outlined.StarBorder, "Favorite",
                        tint = if (d.isFavorite) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (format?.supported == false) {
                Spacer(Modifier.height(12.dp))
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Text("${format.label} files are stored safely in your library, but this version of Folio can't display them yet. You can export a copy any time.",
                        Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (onOpen != null && format?.supported == true) Button(onClick = { onDismiss(); onOpen() }) { Icon(Icons.Outlined.MenuBook, null); Spacer(Modifier.width(6.dp)); Text("Open") }
                OutlinedButton(onClick = { rename = true }) { Text("Rename") }
                OutlinedButton(onClick = { exporter.launch(d.originalName) }) { Text("Export") }
            }
            exportResult?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp)) }

            Text("Folder", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 20.dp, bottom = 6.dp))
            FolderPicker(folders, d.folderId, vm::moveTo)

            Text("Collections", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 20.dp, bottom = 6.dp))
            if (collections.isEmpty()) Text("Create collections from the Collections tab.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                collections.forEach { c ->
                    val member = c.id in memberOf
                    FilterChip(selected = member, onClick = { vm.toggleCollection(c.id, member) }, label = { Text(c.name) })
                }
            }

            Text("Tags", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 20.dp, bottom = 6.dp))
            TagEditor(tags.map { it.name }, { vm.setTags(it) })

            Text("Details", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 20.dp, bottom = 6.dp))
            val df = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
            InfoLine("File name", d.originalName)
            InfoLine("Added", df.format(Date(d.addedAt)))
            InfoLine("Last opened", d.lastOpenedAt?.let { df.format(Date(it)) } ?: "Never")
            d.sourceUrl?.let { InfoLine("Source", it) }
            InfoLine("Search index", when { d.indexError != null -> "Couldn't index: ${d.indexError}"; d.indexedAt != null -> "Indexed"; format?.supported == true -> "Pending"; else -> "Not searchable" })
            InfoLine("SHA-256", d.sha256.take(16) + "…")

            Spacer(Modifier.height(20.dp))
            TextButton(onClick = { confirmDelete = true }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                Icon(Icons.Outlined.DeleteOutline, null); Spacer(Modifier.width(6.dp)); Text("Delete from library")
            }
        }
    }
    if (rename) TextInputDialog("Rename", d.title, "Title", onDismiss = { rename = false }) { vm.rename(it) }
    if (confirmDelete) ConfirmDialog("Delete “${d.title}”?",
        "The file, its notes, bookmarks and reading progress will be removed from this device. This can't be undone.", "Delete",
        onDismiss = { confirmDelete = false }) { vm.delete { onDismiss(); onDeleted() } }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(110.dp))
        Text(value, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderPicker(folders: List<FolderEntity>, selected: Long?, onSelect: (Long?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val ordered = remember(folders) { flattenFolders(folders) }
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }) {
        OutlinedTextField(
            value = folders.firstOrNull { it.id == selected }?.name ?: "Library (no folder)", onValueChange = {}, readOnly = true,
            leadingIcon = { Icon(Icons.Outlined.Folder, null) }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Library (no folder)") }, onClick = { onSelect(null); open = false })
            ordered.forEach { (f, depth) -> DropdownMenuItem(text = { Text("  ".repeat(depth) + f.name) }, onClick = { onSelect(f.id); open = false }) }
        }
    }
}

fun flattenFolders(all: List<FolderEntity>): List<Pair<FolderEntity, Int>> {
    val out = ArrayList<Pair<FolderEntity, Int>>()
    val byParent = all.groupBy { it.parentId }
    fun walk(parent: Long?, depth: Int, guard: Set<Long>) {
        byParent[parent].orEmpty().sortedBy { it.name.lowercase() }.forEach { f ->
            if (f.id in guard) return@forEach
            out += f to depth; walk(f.id, depth + 1, guard + f.id)
        }
    }
    walk(null, 0, emptySet())
    return out
}

