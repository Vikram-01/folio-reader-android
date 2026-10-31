package com.folio.reader.ui.settings

import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.folio.reader.BuildConfig
import com.folio.reader.core.format.DocFormat
import com.folio.reader.data.db.AppDatabase
import com.folio.reader.data.files.FileStore
import com.folio.reader.data.prefs.*
import com.folio.reader.data.repo.BackupRepository
import com.folio.reader.data.repo.BrowserRepository
import com.folio.reader.ui.components.ConfirmDialog
import com.folio.reader.ui.components.formatBytes
import com.folio.reader.ui.nav.Routes
import com.folio.reader.ui.web.SecureWeb
import com.folio.reader.work.IndexScheduler
import com.folio.reader.work.IndexState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

data class StorageInfo(val library: Long, val pages: Long, val cache: Long, val docs: Int)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefsRepo: PrefsRepository,
    private val indexer: IndexScheduler,
    private val backup: BackupRepository,
    private val browser: BrowserRepository,
    private val db: AppDatabase,
    private val files: FileStore,
) : ViewModel() {
    val prefs = prefsRepo.prefs.stateIn(viewModelScope, SharingStarted.Eagerly, UserPrefs())
    val index = indexer.state.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), IndexState(false, 0, 0, null))
    val indexRows = db.search().observeRowCount().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)
    val busy = MutableStateFlow<String?>(null)
    val message = MutableStateFlow<String?>(null)
    val storage = MutableStateFlow<StorageInfo?>(null)

    init { refreshStorage() }

    fun refreshStorage() = viewModelScope.launch(Dispatchers.IO) {
        storage.value = StorageInfo(files.dirSize(files.libraryDir), files.dirSize(files.pagesDir),
            files.dirSize(files.epubCacheDir) + files.dirSize(files.tmpDir), db.documents().all().size)
    }

    fun set(block: suspend PrefsRepository.() -> Unit) = viewModelScope.launch { prefsRepo.block() }
    fun rebuildIndex() { indexer.rebuild(); message.value = "Rebuilding the search index in the background." }
    fun cancelIndex() { indexer.cancel(); message.value = "Indexing stopped. It resumes the next time you import or rebuild." }
    fun resumeIndex() = indexer.indexPending()

    fun exportBackup(uri: Uri, password: String) = viewModelScope.launch {
        busy.value = "Creating backup…"
        message.value = runCatching { backup.export(uri, password.takeIf { it.isNotEmpty() }?.toCharArray()) }
            .fold({ "Backup saved ($it items${if (password.isNotEmpty()) ", encrypted" else ""})." }, { "Backup failed: ${it.message}" })
        busy.value = null
    }

    suspend fun needsPassword(uri: Uri) = runCatching { backup.isEncrypted(uri) }.getOrDefault(false)

    fun restore(uri: Uri, password: String?) = viewModelScope.launch {
        busy.value = "Restoring…"
        message.value = runCatching { backup.restore(uri, password?.toCharArray()) }
            .fold({ "Restored ${it.documents} documents and ${it.bookmarks} bookmarks (${it.skipped} already present)." }, { "Restore failed: ${it.message}" })
        busy.value = null
        refreshStorage()
    }

    fun clearBrowsing() = viewModelScope.launch {
        browser.clearHistory(); browser.clearDownloads()
        withContext(Dispatchers.Main) { SecureWeb.clearBrowsingData(context, null) }
        message.value = "Browsing history, cookies and site data cleared."
    }

    fun clearCache() = viewModelScope.launch(Dispatchers.IO) {
        files.clearEpubCache(); files.clearTemp()
        refreshStorage(); message.value = "Temporary files cleared. Your library is untouched."
    }

    fun checkIntegrity() = viewModelScope.launch(Dispatchers.IO) {
        busy.value = "Checking database…"
        val dbResult = runCatching { db.integrityCheck() }.getOrElse { "error: ${it.message}" }
        val missing = db.documents().all().count { !files.libraryFile(it.storedName).isFile }
        busy.value = null
        message.value = (if (dbResult == "ok") "Database is healthy." else "Database check reported: $dbResult") +
            (if (missing == 0) " All library files are present." else " $missing document file(s) are missing.")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(nav: (String) -> Unit, back: () -> Unit, vm: SettingsViewModel = hiltViewModel()) {
    val p by vm.prefs.collectAsStateWithLifecycle()
    val idx by vm.index.collectAsStateWithLifecycle()
    val rows by vm.indexRows.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val storage by vm.storage.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var exportPassword by remember { mutableStateOf<Boolean>(false) }
    var pendingExport by remember { mutableStateOf("") }
    var restoreUri by remember { mutableStateOf<Uri?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    var showFormats by remember { mutableStateOf(false) }
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.message.value = null } }

    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) vm.exportBackup(uri, pendingExport)
        pendingExport = ""
    }
    val restorer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch { if (vm.needsPassword(uri)) restoreUri = uri else vm.restore(uri, null) }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = { TopAppBar(navigationIcon = { IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }, title = { Text("Settings") }) },
    ) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(bottom = 40.dp)) {
            busy?.let { b -> item { Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(12.dp)); Text(b) } } }

            item { Header("Appearance") }
            item {
                Choice("Theme", Icons.Outlined.DarkMode, AppTheme.entries.map { it.name.lowercase().replaceFirstChar { c -> c.uppercase() } }, p.theme.ordinal) { i ->
                    vm.set { setTheme(AppTheme.entries[i]) }
                }
            }
            if (Build.VERSION.SDK_INT >= 31) item { Toggle("Wallpaper colours", "Use Material You colours from your wallpaper", Icons.Outlined.Palette, p.dynamicColor) { vm.set { setDynamicColor(it) } } }

            item { Header("Reading") }
            item {
                Choice("Reader theme", Icons.Outlined.ChromeReaderMode, ReaderTheme.entries.map { it.label }, p.readerTheme.ordinal) { i -> vm.set { setReaderTheme(ReaderTheme.entries[i]) } }
            }
            item { Toggle("Keep screen on", "While a document is open", Icons.Outlined.LightMode, p.keepScreenOn) { vm.set { setKeepScreenOn(it) } } }

            item { Header("Browser") }
            item {
                Text("The browser is the only part of ReadStash that connects to the internet. It allows HTTPS only and never runs downloaded files.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
            }
            item { Choice("Search engine", Icons.Outlined.TravelExplore, SearchEngine.entries.map { it.label }, p.searchEngine.ordinal) { i -> vm.set { setSearchEngine(SearchEngine.entries[i]) } } }
            item { Toggle("JavaScript", "Needed by many sites. Turn off for maximum safety.", Icons.Outlined.Code, p.browserJavaScript) { vm.set { setBrowserJavaScript(it) } } }
            item { Toggle("Block third-party cookies", "Stops most cross-site tracking", Icons.Outlined.Cookie, p.browserBlockThirdPartyCookies) { vm.set { setBlockThirdPartyCookies(it) } } }
            item { Toggle("Save browsing history", "Stored only on this device", Icons.Outlined.History, p.browserSaveHistory) { vm.set { setBrowserSaveHistory(it) } } }
            item { Toggle("Clear cookies when leaving the browser", "Signs you out of sites each time", Icons.Outlined.CleaningServices, p.browserClearOnExit) { vm.set { setClearOnExit(it) } } }
            item { Action("Clear browsing data", "History, cookies, cache and site storage", Icons.Outlined.DeleteSweep) { confirmClear = true } }
            item { Action("Downloads", null, Icons.Outlined.Download) { nav(Routes.DOWNLOADS) } }

            item { Header("Search index") }
            item {
                val status = if (idx.running) "Indexing ${idx.done}/${idx.total}" + (idx.current?.let { " · $it" } ?: "") else "Up to date · $rows text blocks indexed"
                ListItem(leadingContent = { Icon(Icons.Outlined.ManageSearch, null) }, headlineContent = { Text("Full-text search") }, supportingContent = { Text(status) },
                    trailingContent = { if (idx.running) TextButton(onClick = vm::cancelIndex) { Text("Stop") } })
                if (idx.running && idx.total > 0) LinearProgressIndicator(progress = { idx.done.toFloat() / idx.total }, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp))
            }
            item { Action("Rebuild index", "Re-reads every document. Runs in the background.", Icons.Outlined.Refresh) { vm.rebuildIndex() } }

            item { Header("Backup & restore") }
            item {
                Text("Backups contain your documents, bookmarks, collections, tags, notes and reading progress. Restoring merges into your library and never deletes anything.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
            }
            item { Action("Create backup", "Optionally protected with a password (AES-256)", Icons.Outlined.Backup) { exportPassword = true } }
            item { Action("Restore from backup", null, Icons.Outlined.SettingsBackupRestore) { restorer.launch(arrayOf("*/*")) } }

            item { Header("Storage") }
            item {
                val s = storage
                ListItem(leadingContent = { Icon(Icons.Outlined.Storage, null) }, headlineContent = { Text("On this device") },
                    supportingContent = { Text(if (s == null) "Calculating…" else "Library ${formatBytes(s.library)} (${s.docs} documents) · saved pages ${formatBytes(s.pages)} · temporary ${formatBytes(s.cache)}") })
            }
            item { Action("Clear temporary files", "Unpacked books and leftovers. Your library is not affected.", Icons.Outlined.CleaningServices) { vm.clearCache() } }
            item { Action("Check data integrity", "Verifies the database and that every library file is present", Icons.Outlined.FactCheck) { vm.checkIntegrity() } }

            item { Header("Privacy & security") }
            item {
                Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        "Works offline. No account, analytics, ads or cloud sync.",
                        "Only permission requested: Internet, used by the in-app browser.",
                        "Files are stored in ReadStash's private app storage and excluded from cloud backup.",
                        "Documents open with JavaScript off and all network requests blocked.",
                        "Archives are checked for path traversal and zip bombs; size limits on every file.",
                        "Executables and app packages are refused; nothing downloaded is ever run.",
                    ).forEach { Row { Text("•  "); Text(it, style = MaterialTheme.typography.bodyMedium) } }
                }
            }

            item { Header("About") }
            item { Action("Supported formats", "What ReadStash can open in this version", Icons.Outlined.Description) { showFormats = true } }
            item { ListItem(leadingContent = { Icon(Icons.Outlined.Info, null) }, headlineContent = { Text("ReadStash ${BuildConfig.VERSION_NAME}") }, supportingContent = { Text("Offline-first reader & library") }) }
        }
    }

    if (exportPassword) {
        var pw by remember { mutableStateOf("") }
        var pw2 by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { exportPassword = false }, title = { Text("Create backup") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Add a password to encrypt the backup file. Without it, anyone with the file can read your library. If you forget the password the backup can't be recovered.",
                        style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(pw, { pw = it }, label = { Text("Password (optional)") }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                    if (pw.isNotEmpty()) OutlinedTextField(pw2, { pw2 = it }, label = { Text("Repeat password") }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
                        isError = pw2.isNotEmpty() && pw2 != pw, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                }
            },
            confirmButton = {
                TextButton(enabled = pw.isEmpty() || (pw == pw2 && pw.length >= 8), onClick = {
                    pendingExport = pw; exportPassword = false
                    val stamp = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                    exporter.launch("folio-backup-$stamp.folio")
                }) { Text(if (pw.isEmpty()) "Continue without password" else "Continue") }
            },
            dismissButton = { TextButton(onClick = { exportPassword = false }) { Text("Cancel") } })
    }
    restoreUri?.let { uri ->
        var pw by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { restoreUri = null }, title = { Text("Encrypted backup") },
            text = { OutlinedTextField(pw, { pw = it }, label = { Text("Password") }, singleLine = true, visualTransformation = PasswordVisualTransformation()) },
            confirmButton = { TextButton(enabled = pw.isNotEmpty(), onClick = { vm.restore(uri, pw); restoreUri = null }) { Text("Restore") } },
            dismissButton = { TextButton(onClick = { restoreUri = null }) { Text("Cancel") } })
    }
    if (confirmClear) ConfirmDialog("Clear browsing data?", "You'll be signed out of websites. Bookmarks and saved pages are kept.", "Clear",
        onDismiss = { confirmClear = false }) { vm.clearBrowsing() }
    if (showFormats) AlertDialog(onDismissRequest = { showFormats = false }, title = { Text("Supported formats") },
        text = {
            LazyColumn(Modifier.heightIn(max = 460.dp)) {
                item { Text("Opens in ReadStash", style = MaterialTheme.typography.titleSmall) }
                DocFormat.entries.filter { it.supported }.forEach { f -> item { Text("•  ${f.label} (${f.extensions.joinToString { ".$it" }})", style = MaterialTheme.typography.bodySmall) } }
                item { Text("Stored, no viewer yet", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp)) }
                DocFormat.entries.filter { !it.supported }.forEach { f -> item { Text("•  ${f.label} (${f.extensions.joinToString { ".$it" }})", style = MaterialTheme.typography.bodySmall) } }
            }
        },
        confirmButton = { TextButton(onClick = { showFormats = false }) { Text("Close") } })
}

@Composable private fun Header(t: String) =
    Text(t, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 20.dp, top = 24.dp, bottom = 4.dp))

@Composable private fun Toggle(title: String, sub: String?, icon: ImageVector, checked: Boolean, onChange: (Boolean) -> Unit) =
    ListItem(modifier = Modifier.clickable { onChange(!checked) }, leadingContent = { Icon(icon, null) }, headlineContent = { Text(title) },
        supportingContent = if (sub != null) ({ Text(sub) }) else null, trailingContent = { Switch(checked, onChange) })

@Composable private fun Action(title: String, sub: String?, icon: ImageVector, onClick: () -> Unit) =
    ListItem(modifier = Modifier.clickable(onClick = onClick), leadingContent = { Icon(icon, null) }, headlineContent = { Text(title) }, supportingContent = if (sub != null) ({ Text(sub) }) else null)

@Composable private fun Choice(title: String, icon: ImageVector, options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        ListItem(modifier = Modifier.clickable { open = true }, leadingContent = { Icon(icon, null) }, headlineContent = { Text(title) },
            supportingContent = { Text(options.getOrElse(selected) { "" }) })
        DropdownMenu(open, { open = false }, offset = androidx.compose.ui.unit.DpOffset(56.dp, 0.dp)) {
            options.forEachIndexed { i, o -> DropdownMenuItem(text = { Text(o) }, onClick = { onSelect(i); open = false }, trailingIcon = { if (i == selected) Icon(Icons.Default.Check, null) }) }
        }
    }
}
