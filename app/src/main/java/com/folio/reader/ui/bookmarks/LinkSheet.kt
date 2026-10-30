package com.folio.reader.ui.bookmarks

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.folio.reader.data.db.CollectionEntity
import com.folio.reader.ui.LinkDraft
import com.folio.reader.ui.components.TagEditor
import com.folio.reader.ui.components.TextInputDialog

/** Bottom sheet used for "Quick save link", share-sheet links and bookmark creation. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LinkSheet(
    draft: LinkDraft,
    collections: List<CollectionEntity>,
    onChange: (LinkDraft) -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
    onSaveAnyway: () -> Unit,
    onOpenExisting: () -> Unit,
    onCreateCollection: (String) -> Unit,
) {
    var newCollection by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp).imePadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Save link", style = MaterialTheme.typography.headlineSmall)
            Text("Saved on this device only. Folio doesn't fetch the page until you open it.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(
                draft.url, { onChange(draft.copy(url = it, duplicateOf = null)) }, label = { Text("URL") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                leadingIcon = { Icon(Icons.Outlined.Link, null) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
            )
            OutlinedTextField(draft.title, { onChange(draft.copy(title = it)) }, label = { Text("Title (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(draft.note, { onChange(draft.copy(note = it)) }, label = { Text("Note") }, minLines = 2, maxLines = 5, modifier = Modifier.fillMaxWidth())
            CollectionPicker(collections, draft.collectionId, { onChange(draft.copy(collectionId = it)) }, onNew = { newCollection = true })
            TagEditor(draft.tags, { onChange(draft.copy(tags = it)) })

            val dup = draft.duplicateOf
            if (dup != null) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Column(Modifier.padding(14.dp)) {
                        Text("You already saved this link", style = MaterialTheme.typography.titleSmall)
                        Text(dup.title, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = onOpenExisting) { Text("Open existing") }
                            TextButton(onClick = onSaveAnyway) { Text("Save again") }
                        }
                    }
                }
            } else {
                Button(onClick = onSave, enabled = draft.url.isNotBlank(), modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Icon(Icons.Filled.BookmarkAdd, null); Spacer(Modifier.width(8.dp)); Text("Save bookmark")
                }
            }
        }
    }
    if (newCollection) TextInputDialog("New collection", onDismiss = { newCollection = false }, onConfirm = onCreateCollection)
}

/** Dropdown of nested collections with indentation, plus "Unsorted" and "New…". */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectionPicker(collections: List<CollectionEntity>, selected: Long?, onSelect: (Long?) -> Unit, onNew: (() -> Unit)? = null, label: String = "Collection") {
    var open by remember { mutableStateOf(false) }
    val ordered = remember(collections) { flattenCollections(collections) }
    val current = collections.firstOrNull { it.id == selected }?.name ?: "Unsorted"
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }) {
        OutlinedTextField(
            value = current, onValueChange = {}, readOnly = true, label = { Text(label) },
            leadingIcon = { Icon(Icons.Outlined.FolderSpecial, null) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Unsorted") }, onClick = { onSelect(null); open = false })
            ordered.forEach { (c, depth) ->
                DropdownMenuItem(text = { Text("  ".repeat(depth) + c.name) }, onClick = { onSelect(c.id); open = false })
            }
            if (onNew != null) {
                HorizontalDivider()
                DropdownMenuItem(text = { Text("New collection…") }, leadingIcon = { Icon(Icons.Default.Add, null) }, onClick = { open = false; onNew() })
            }
        }
    }
}

fun flattenCollections(all: List<CollectionEntity>): List<Pair<CollectionEntity, Int>> {
    val out = ArrayList<Pair<CollectionEntity, Int>>()
    val byParent = all.groupBy { it.parentId }
    fun walk(parent: Long?, depth: Int, guard: Set<Long>) {
        byParent[parent].orEmpty().sortedBy { it.name.lowercase() }.forEach { c ->
            if (c.id in guard) return@forEach
            out += c to depth
            walk(c.id, depth + 1, guard + c.id)
        }
    }
    walk(null, 0, emptySet())
    return out
}
