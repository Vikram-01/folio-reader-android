package com.folio.reader.ui.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.folio.reader.data.db.AnnotationKind
import com.folio.reader.data.prefs.ReaderTheme
import com.folio.reader.ui.components.EmptyState
import com.folio.reader.ui.components.highlightSnippet
import com.folio.reader.ui.documents.DocumentDetailsSheet
import com.folio.reader.ui.nav.Routes
import com.folio.reader.ui.theme.readerColors
import com.folio.reader.ui.web.LocalHtmlView
import com.folio.reader.ui.web.SecureWeb
import com.folio.reader.ui.web.readerCss

private enum class Sheet { NONE, CONTENTS, SEARCH, NOTES, APPEARANCE, DETAILS }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(nav: (String) -> Unit, back: () -> Unit, vm: ReaderViewModel = hiltViewModel()) {
    val doc by vm.doc.collectAsStateWithLifecycle()
    val content by vm.content.collectAsStateWithLifecycle()
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val initial by vm.initial.collectAsStateWithLifecycle()
    val pos by vm.position.collectAsStateWithLifecycle()
    val jump by vm.jump.collectAsStateWithLifecycle()
    val annotations by vm.annotations.collectAsStateWithLifecycle()
    val speaking by vm.speaking.collectAsStateWithLifecycle()
    val searchMsg by vm.searchMessage.collectAsStateWithLifecycle()
    var chrome by remember { mutableStateOf(true) }
    var sheet by remember { mutableStateOf(Sheet.NONE) }
    var find by remember { mutableStateOf("") }
    var noteDialog by remember { mutableStateOf(false) }
    val colors = readerColors(prefs.readerTheme)
    val view = LocalView.current
    val snackbar = remember { SnackbarHostState() }

    DisposableEffect(prefs.keepScreenOn) { view.keepScreenOn = prefs.keepScreenOn; onDispose { view.keepScreenOn = false } }
    LaunchedEffect(searchMsg) { if (searchMsg != null && sheet != Sheet.SEARCH) { snackbar.showSnackbar(searchMsg!!); vm.searchMessage.value = null } }

    val c = content
    val total = when (c) {
        is ReaderContent.Pdf -> c.pageCount
        is ReaderContent.Epub -> c.book.chapters.size
        is ReaderContent.Text -> c.lines.size
        is ReaderContent.Table -> c.rows.size
        else -> 1
    }
    fun label(p: Int): String = when (c) {
        is ReaderContent.Pdf -> "Page ${p + 1}"
        is ReaderContent.Epub -> c.book.chapters.getOrNull(p)?.title ?: "Section ${p + 1}"
        is ReaderContent.Text -> "Line ${p + 1}"
        is ReaderContent.Table -> "Row ${p + 1}"
        else -> ""
    }
    val bookmarked = annotations.any { it.kind == AnnotationKind.BOOKMARK.name && it.position == pos.position }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }, containerColor = colors.background, contentWindowInsets = WindowInsets(0)) { _ ->
        Box(Modifier.fillMaxSize().background(colors.background)) {
            val start = initial
            when {
                c is ReaderContent.Loading || start == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                c is ReaderContent.Failed -> Box(Modifier.fillMaxSize().statusBarsPadding(), contentAlignment = Alignment.Center) {
                    EmptyState(Icons.Outlined.ErrorOutline, "Can't open this document", c.message) { OutlinedButton(onClick = back) { Text("Go back") } }
                }
                c is ReaderContent.Unsupported -> Box(Modifier.fillMaxSize().statusBarsPadding(), contentAlignment = Alignment.Center) {
                    EmptyState(Icons.Outlined.Inventory2, "No viewer for ${c.format.label} yet",
                        "This file is stored safely in your library. This version of Folio can't display ${c.format.label} files; you can export a copy from the details sheet.") {
                        Button(onClick = { sheet = Sheet.DETAILS }) { Text("Details & export") }
                    }
                }
                c is ReaderContent.Pdf -> PdfReader(c.file, c.pageCount, start.position, prefs.readerTheme, jump, vm::consumeJump,
                    onPage = { p -> vm.report(Position(p, 0f, (p + 1f) / c.pageCount)) }, onTap = { chrome = !chrome })
                c is ReaderContent.Epub -> Box(Modifier.fillMaxSize().systemBarsPadding()) {
                    val chapter = jump ?: pos.position.coerceIn(0, c.book.chapters.lastIndex)
                    LaunchedEffect(jump) { if (jump != null) { vm.report(Position(jump!!, 0f, jump!!.toFloat() / total)); vm.consumeJump() } }
                    EpubReader(c.book, chapter, if (chapter == start.position) start.offset else 0f, colors, prefs.readerFontScale,
                        onProgress = { ch, off -> vm.report(Position(ch, off, (ch + off) / total)) },
                        onNavigate = { vm.requestJump(it) },
                        onExternalLink = { u -> if (u.scheme == "https" || u.scheme == "http") nav(Routes.browser(u.toString())) },
                        onTap = { chrome = !chrome })
                }
                c is ReaderContent.Text -> Box(Modifier.fillMaxSize().systemBarsPadding()) {
                    TextReader(c.lines, c.mono, c.truncated, colors, prefs.readerFontScale, start.position, jump, vm::consumeJump, find,
                        onLine = { l -> vm.report(Position(l, 0f, if (c.lines.size <= 1) 1f else l.toFloat() / (c.lines.size - 1))) }, onTap = { chrome = !chrome })
                }
                c is ReaderContent.Table -> Box(Modifier.fillMaxSize().systemBarsPadding()) {
                    TableReader(c.rows, c.truncated, colors, start.position, jump, vm::consumeJump, find,
                        onRow = { r -> vm.report(Position(r, 0f, if (c.rows.size <= 1) 1f else r.toFloat() / (c.rows.size - 1))) }, onTap = { chrome = !chrome })
                }
                c is ReaderContent.Html -> Box(Modifier.fillMaxSize().systemBarsPadding().padding(top = 56.dp)) {
                    LocalHtmlView(c.html, readerCss(colors, prefs.readerFontScale), Modifier.fillMaxSize(),
                        onLink = { u -> if (u.scheme == "https" && u.host != SecureWeb.LOCAL_HOST) nav(Routes.browser(u.toString())) })
                    LaunchedEffect(Unit) { vm.report(Position(0, 0f, 1f)) }
                }
                c is ReaderContent.Image -> {
                    ImageReader(c.file, colors) { chrome = !chrome }
                    LaunchedEffect(Unit) { vm.report(Position(0, 0f, 1f)) }
                }
            }

            // Top chrome
            AnimatedVisibility(chrome, enter = fadeIn() + slideInVertically(), exit = fadeOut() + slideOutVertically(), modifier = Modifier.align(Alignment.TopCenter)) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.97f), tonalElevation = 3.dp) {
                    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                        Column(Modifier.weight(1f)) {
                            Text(doc?.title ?: "", style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (total > 1) Text(label(pos.position) + " · ${(pos.fraction * 100).toInt()}%", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (c is ReaderContent.Pdf || c is ReaderContent.Epub || c is ReaderContent.Text || c is ReaderContent.Table) {
                            IconButton(onClick = { sheet = Sheet.SEARCH }) { Icon(Icons.Outlined.Search, "Search in document") }
                            IconButton(onClick = { vm.toggleBookmark(pos.position, label(pos.position)) }) {
                                Icon(if (bookmarked) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder, "Bookmark this place",
                                    tint = if (bookmarked) MaterialTheme.colorScheme.secondary else LocalContentColor.current)
                            }
                        }
                        var more by remember { mutableStateOf(false) }
                        Box {
                            IconButton(onClick = { more = true }) { Icon(Icons.Default.MoreVert, "More") }
                            DropdownMenu(more, { more = false }) {
                                if (c is ReaderContent.Pdf || c is ReaderContent.Epub) DropdownMenuItem(text = { Text(if (c is ReaderContent.Pdf) "Pages" else "Contents") },
                                    leadingIcon = { Icon(Icons.Outlined.Toc, null) }, onClick = { more = false; sheet = Sheet.CONTENTS })
                                DropdownMenuItem(text = { Text("Notes & bookmarks") }, leadingIcon = { Icon(Icons.Outlined.StickyNote2, null) }, onClick = { more = false; sheet = Sheet.NOTES })
                                DropdownMenuItem(text = { Text("Add note here") }, leadingIcon = { Icon(Icons.Outlined.EditNote, null) }, onClick = { more = false; noteDialog = true })
                                DropdownMenuItem(text = { Text("Appearance") }, leadingIcon = { Icon(Icons.Outlined.Palette, null) }, onClick = { more = false; sheet = Sheet.APPEARANCE })
                                if (c !is ReaderContent.Image) DropdownMenuItem(text = { Text(if (speaking) "Stop reading aloud" else "Read aloud") },
                                    leadingIcon = { Icon(if (speaking) Icons.Outlined.StopCircle else Icons.Outlined.RecordVoiceOver, null) },
                                    onClick = { more = false; if (speaking) vm.stopSpeaking() else vm.speak(pos.position) })
                                DropdownMenuItem(text = { Text("Details") }, leadingIcon = { Icon(Icons.Outlined.Info, null) }, onClick = { more = false; sheet = Sheet.DETAILS })
                                doc?.sourceUrl?.let { url -> DropdownMenuItem(text = { Text("Open source page") }, leadingIcon = { Icon(Icons.Outlined.Public, null) }, onClick = { more = false; nav(Routes.browser(url)) }) }
                            }
                        }
                    }
                }
            }

            // Bottom chrome: position slider and section navigation
            AnimatedVisibility(chrome && total > 1, enter = fadeIn() + slideInVertically { it }, exit = fadeOut() + slideOutVertically { it }, modifier = Modifier.align(Alignment.BottomCenter)) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.97f), tonalElevation = 3.dp) {
                    Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (c is ReaderContent.Epub) IconButton(enabled = pos.position > 0, onClick = { vm.requestJump(pos.position - 1) }) { Icon(Icons.Default.ChevronLeft, "Previous section") }
                        var dragging by remember { mutableStateOf<Float?>(null) }
                        Slider(
                            value = dragging ?: (pos.position.toFloat() / (total - 1).coerceAtLeast(1)),
                            onValueChange = { dragging = it },
                            onValueChangeFinished = { dragging?.let { vm.requestJump((it * (total - 1)).toInt()) }; dragging = null },
                            modifier = Modifier.weight(1f),
                        )
                        if (c is ReaderContent.Epub) IconButton(enabled = pos.position < total - 1, onClick = { vm.requestJump(pos.position + 1) }) { Icon(Icons.Default.ChevronRight, "Next section") }
                        if (speaking) IconButton(onClick = vm::stopSpeaking) { Icon(Icons.Outlined.StopCircle, "Stop reading aloud") }
                    }
                }
            }
        }
    }

    when (sheet) {
        Sheet.NONE -> Unit
        Sheet.DETAILS -> DocumentDetailsSheet(vm.docId, onDismiss = { sheet = Sheet.NONE }, onDeleted = back)
        else -> ModalBottomSheet(onDismissRequest = { sheet = Sheet.NONE }) {
            when (sheet) {
                Sheet.CONTENTS -> when (c) {
                    is ReaderContent.Pdf -> PdfThumbnails(c.file, c.pageCount, pos.position) { vm.requestJump(it); sheet = Sheet.NONE }
                    is ReaderContent.Epub -> LazyColumn(Modifier.heightIn(max = 560.dp)) {
                        items(c.book.toc) { t ->
                            ListItem(modifier = Modifier.clickable { vm.requestJump(t.chapterIndex); sheet = Sheet.NONE }.padding(start = (t.depth.coerceIn(0, 4) * 16).dp),
                                headlineContent = { Text(t.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                                trailingContent = { if (t.chapterIndex == pos.position) Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary) })
                        }
                    }
                    else -> Unit
                }
                Sheet.SEARCH -> InDocSearch(vm, find, { find = it }) { loc -> vm.requestJump(loc); sheet = Sheet.NONE }
                Sheet.NOTES -> Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
                    Text("Notes & bookmarks", style = MaterialTheme.typography.titleLarge)
                    if (annotations.isEmpty()) Text("Tap the bookmark icon to mark a place, or use “Add note here”.", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 16.dp))
                    LazyColumn(Modifier.heightIn(max = 520.dp)) {
                        items(annotations, key = { it.id }) { a ->
                            ListItem(
                                modifier = Modifier.clickable { vm.requestJump(a.position); sheet = Sheet.NONE },
                                leadingContent = { Icon(if (a.kind == AnnotationKind.BOOKMARK.name) Icons.Filled.Bookmark else Icons.Outlined.StickyNote2, null, tint = MaterialTheme.colorScheme.secondary) },
                                headlineContent = { Text(a.label) },
                                supportingContent = { if (a.note.isNotBlank()) Text(a.note, maxLines = 3, overflow = TextOverflow.Ellipsis) },
                                trailingContent = { IconButton(onClick = { vm.deleteAnnotation(a) }) { Icon(Icons.Outlined.DeleteOutline, "Delete") } },
                            )
                        }
                    }
                }
                Sheet.APPEARANCE -> Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("Appearance", style = MaterialTheme.typography.titleLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ReaderTheme.entries.forEach { t ->
                            FilterChip(prefs.readerTheme == t, { vm.setTheme(t) }, { Text(t.label) },
                                leadingIcon = { Box(Modifier.size(14.dp).background(readerColors(t).background, androidx.compose.foundation.shape.CircleShape)) })
                        }
                    }
                    if (c !is ReaderContent.Pdf && c !is ReaderContent.Image) {
                        Text("Text size ${(prefs.readerFontScale * 100).toInt()}%", style = MaterialTheme.typography.labelLarge)
                        Slider(prefs.readerFontScale, { vm.setFontScale(it) }, valueRange = 0.7f..2.0f, steps = 12)
                    } else Text("PDF pages are shown as printed; the theme tints pages for comfortable night reading.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Keep screen on while reading", Modifier.weight(1f)); Switch(prefs.keepScreenOn, { vm.setKeepScreenOn(it) })
                    }
                }
                else -> Unit
            }
        }
    }

    if (noteDialog) {
        var text by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { noteDialog = false }, title = { Text("Note · ${label(pos.position).ifBlank { "Document" }}") },
            text = { OutlinedTextField(text, { text = it }, minLines = 3, modifier = Modifier.fillMaxWidth(), placeholder = { Text("Your thoughts…") }) },
            confirmButton = { TextButton(enabled = text.isNotBlank(), onClick = { vm.addNote(pos.position, label(pos.position).ifBlank { "Document" }, text); noteDialog = false }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { noteDialog = false }) { Text("Cancel") } })
    }
}

@Composable
private fun InDocSearch(vm: ReaderViewModel, query: String, onQuery: (String) -> Unit, onPick: (Int) -> Unit) {
    val hits by vm.hits.collectAsStateWithLifecycle()
    val msg by vm.searchMessage.collectAsStateWithLifecycle()
    val hl = MaterialTheme.colorScheme.secondaryContainer
    Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
        OutlinedTextField(query, onQuery, placeholder = { Text("Search in this document") }, singleLine = true,
            leadingIcon = { Icon(Icons.Outlined.Search, null) }, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { vm.runSearch(query) }))
        LaunchedEffect(query) { kotlinx.coroutines.delay(300); vm.runSearch(query) }
        msg?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 10.dp)) }
        if (hits.isNotEmpty()) Text("${hits.size} match${if (hits.size == 1) "" else "es"}", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(vertical = 8.dp))
        LazyColumn(Modifier.heightIn(max = 480.dp)) {
            items(hits) { h ->
                ListItem(modifier = Modifier.clickable { onPick(h.location.toIntOrNull() ?: 0) },
                    overlineContent = { Text(h.locationLabel) },
                    headlineContent = { Text(highlightSnippet(h.snippet, hl), style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis) })
            }
        }
    }
}
