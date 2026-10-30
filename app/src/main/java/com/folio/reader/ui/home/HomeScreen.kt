package com.folio.reader.ui.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import com.folio.reader.core.format.DocFormat
import com.folio.reader.data.db.BookmarkEntity
import com.folio.reader.data.db.CollectionEntity
import com.folio.reader.data.db.ContinueReadingRow
import com.folio.reader.data.db.DocumentEntity
import com.folio.reader.data.files.FileStore
import com.folio.reader.data.repo.BookmarkRepository
import com.folio.reader.data.repo.LibraryRepository
import com.folio.reader.ui.components.DocCover
import com.folio.reader.ui.components.EmptyState
import com.folio.reader.ui.components.SectionHeader
import com.folio.reader.ui.components.relativeTime
import com.folio.reader.ui.nav.Routes
import com.folio.reader.core.security.UrlSafety
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import java.io.File
import java.util.Calendar
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(library: LibraryRepository, bookmarks: BookmarkRepository, val files: FileStore) : ViewModel() {
    private fun <T> StateFlow0(f: kotlinx.coroutines.flow.Flow<T>, init: T) = f.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), init)
    val continueReading = StateFlow0(library.observeContinueReading(), emptyList<ContinueReadingRow>())
    val recentlyOpened = StateFlow0(library.observeRecentlyOpened(), emptyList<DocumentEntity>())
    val recentlyAdded = StateFlow0(library.observeRecentlyAdded(), emptyList<DocumentEntity>())
    val favorites = StateFlow0(library.observeFavorites(), emptyList<DocumentEntity>())
    val recentBookmarks = StateFlow0(bookmarks.observeRecent(8), emptyList<BookmarkEntity>())
    val favoriteBookmarks = StateFlow0(bookmarks.observeFavorites(), emptyList<BookmarkEntity>())
    val collections = StateFlow0(bookmarks.observeCollections(), emptyList<CollectionEntity>())
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(nav: (String) -> Unit, onImport: () -> Unit, onSaveLink: () -> Unit, vm: HomeViewModel = hiltViewModel()) {
    val cont by vm.continueReading.collectAsStateWithLifecycle()
    val opened by vm.recentlyOpened.collectAsStateWithLifecycle()
    val added by vm.recentlyAdded.collectAsStateWithLifecycle()
    val favs by vm.favorites.collectAsStateWithLifecycle()
    val bms by vm.recentBookmarks.collectAsStateWithLifecycle()
    val favBms by vm.favoriteBookmarks.collectAsStateWithLifecycle()
    val cols by vm.collections.collectAsStateWithLifecycle()
    val thumbs = vm.files.thumbsDir
    val favicons = vm.files.faviconDir

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Folio", style = MaterialTheme.typography.headlineMedium) },
                actions = {
                    IconButton(onClick = { nav(Routes.browser()) }) { Icon(Icons.Outlined.Public, "Browser") }
                    IconButton(onClick = { nav(Routes.SETTINGS) }) { Icon(Icons.Outlined.Settings, "Settings") }
                },
            )
        },
    ) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(bottom = 32.dp)) {
            item {
                Text(greeting(), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp))
                Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    QuickAction("Import files", "PDF, EPUB, text, images", Icons.Outlined.FileUpload, MaterialTheme.colorScheme.primaryContainer, Modifier.weight(1f), onImport)
                    QuickAction("Save a link", "Bookmarks, offline", Icons.Outlined.AddLink, MaterialTheme.colorScheme.secondaryContainer, Modifier.weight(1f), onSaveLink)
                }
            }

            if (cont.isNotEmpty()) {
                item { SectionHeader("Continue reading") }
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(cont, key = { it.id }) { c -> ContinueCard(c, c.thumbnailName?.let { File(thumbs, it) }) { nav(Routes.reader(c.id)) } }
                    }
                }
            }

            if (opened.isEmpty() && added.isEmpty() && bms.isEmpty()) {
                item {
                    EmptyState(Icons.Outlined.AutoStories, "Your shelf is empty",
                        "Import documents from your phone or save links to read later. Everything stays on this device.")
                }
            }

            if (opened.isNotEmpty()) {
                item { SectionHeader("Recently opened") { TextButton(onClick = { nav(Routes.DOCUMENTS) }) { Text("Library") } } }
                item { DocRow(opened, thumbs) { nav(Routes.reader(it.id)) } }
            }
            if (added.isNotEmpty()) {
                item { SectionHeader("Recently added") }
                item { DocRow(added, thumbs) { nav(Routes.reader(it.id)) } }
            }
            if (favs.isNotEmpty()) {
                item { SectionHeader("Favorite documents") }
                item { DocRow(favs, thumbs) { nav(Routes.reader(it.id)) } }
            }
            if (bms.isNotEmpty()) {
                item { SectionHeader("Recent bookmarks") { TextButton(onClick = { nav(Routes.BOOKMARKS) }) { Text("All") } } }
                items(bms.take(6), key = { "bm${it.id}" }) { b -> BookmarkMini(b, favicons) { nav(Routes.browser(b.url)) } }
            }
            if (favBms.isNotEmpty()) {
                item { SectionHeader("Favorite links") }
                items(favBms.take(6), key = { "fb${it.id}" }) { b -> BookmarkMini(b, favicons) { nav(Routes.browser(b.url)) } }
            }
            if (cols.isNotEmpty()) {
                item { SectionHeader("Collections") { TextButton(onClick = { nav(Routes.COLLECTIONS) }) { Text("All") } } }
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(cols.filter { it.parentId == null }, key = { it.id }) { c ->
                            AssistChip(onClick = { nav(Routes.collection(c.id)) }, label = { Text(c.name) },
                                leadingIcon = { Box(Modifier.size(10.dp).clip(CircleShape).background(Color(c.color))) })
                        }
                    }
                }
            }
        }
    }
}

private fun greeting(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
    in 5..11 -> "Good morning. What are we reading today?"
    in 12..16 -> "Good afternoon. Pick up where you left off."
    in 17..21 -> "Good evening. Settle in with something good."
    else -> "Late night reading? Dark mode is in Settings."
}

@Composable
private fun QuickAction(title: String, sub: String, icon: androidx.compose.ui.graphics.vector.ImageVector, bg: Color, modifier: Modifier, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = modifier, colors = CardDefaults.cardColors(containerColor = bg), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(16.dp)) {
            Icon(icon, null)
            Spacer(Modifier.height(18.dp))
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(sub, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun ContinueCard(c: ContinueReadingRow, thumb: File?, onClick: () -> Unit) {
    val fmt = DocFormat.entries.firstOrNull { it.name == c.format }
    Card(onClick = onClick, modifier = Modifier.width(280.dp), shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            DocCover(thumb, fmt, c.title, Modifier.size(64.dp, 88.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(c.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                val where = when (fmt) {
                    DocFormat.PDF -> "Page ${c.position + 1}" + (c.pageCount?.let { " of $it" } ?: "")
                    DocFormat.EPUB -> "Section ${c.position + 1}" + (c.pageCount?.let { " of $it" } ?: "")
                    else -> "${(c.fraction * 100).toInt()}% read"
                }
                Text(where + " · " + relativeTime(c.updatedAt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(progress = { c.fraction }, modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)))
            }
        }
    }
}

@Composable
fun DocRow(docs: List<DocumentEntity>, thumbs: File, onClick: (DocumentEntity) -> Unit) {
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(docs, key = { it.id }) { d ->
            Column(Modifier.width(108.dp).clip(RoundedCornerShape(12.dp)).clickable { onClick(d) }) {
                DocCover(d.thumbnailName?.let { File(thumbs, it) }, DocFormat.entries.firstOrNull { it.name == d.format }, d.title, Modifier.fillMaxWidth().aspectRatio(0.72f))
                Spacer(Modifier.height(6.dp))
                Text(d.title, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
fun BookmarkMini(b: BookmarkEntity, favicons: File, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick).padding(horizontal = 4.dp),
        leadingContent = { Favicon(b.faviconName?.let { File(favicons, it) }, b.title) },
        headlineContent = { Text(b.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(UrlSafety.hostOf(b.url), maxLines = 1) },
        trailingContent = { if (b.offlineName != null) Icon(Icons.Outlined.OfflinePin, "Available offline", tint = MaterialTheme.colorScheme.tertiary) },
    )
}

@Composable
fun Favicon(file: File?, title: String, size: androidx.compose.ui.unit.Dp = 36.dp) {
    Box(Modifier.size(size).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
        if (file != null && file.exists()) AsyncImage(model = file, contentDescription = null, modifier = Modifier.size(size * 0.6f))
        else Text(title.trim().take(1).uppercase().ifEmpty { "•" }, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
    }
}
