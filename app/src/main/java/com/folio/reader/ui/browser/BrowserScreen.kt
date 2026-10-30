package com.folio.reader.ui.browser

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.os.Message
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.folio.reader.core.security.UrlSafety
import com.folio.reader.data.prefs.ReaderTheme
import com.folio.reader.data.repo.Readability
import com.folio.reader.ui.home.Favicon
import com.folio.reader.ui.nav.Routes
import com.folio.reader.ui.theme.readerColors
import com.folio.reader.ui.web.LocalHtmlView
import com.folio.reader.ui.web.SecureWeb
import com.folio.reader.ui.web.readerCss
import kotlinx.serialization.json.Json

private const val START = ""

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(initialUrl: String?, nav: (String) -> Unit, back: () -> Unit, vm: BrowserViewModel = hiltViewModel()) {
    val ctx = LocalContext.current
    val focus = LocalFocusManager.current
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val tabs by vm.tabs.collectAsStateWithLifecycle()
    val currentId by vm.current.collectAsStateWithLifecycle()
    val bookmark by vm.currentBookmark.collectAsStateWithLifecycle()
    val pending by vm.pendingDownload.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val openDoc by vm.openDocument.collectAsStateWithLifecycle()

    var web by remember { mutableStateOf<WebView?>(null) }
    var webKey by remember { mutableIntStateOf(0) }
    var loadedTab by remember { mutableStateOf<Long?>(null) }
    var loadProgress by remember { mutableIntStateOf(100) }
    var pageUrl by remember { mutableStateOf("") }
    var pageTitle by remember { mutableStateOf("") }
    var canBack by remember { mutableStateOf(false) }
    var canForward by remember { mutableStateOf(false) }
    var address by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf(false) }
    var showTabs by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var finding by remember { mutableStateOf(false) }
    var findQuery by remember { mutableStateOf("") }
    var findResult by remember { mutableStateOf(0 to 0) }
    var external by remember { mutableStateOf<Intent?>(null) }
    var readerHtml by remember { mutableStateOf<Pair<String, String>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var editBookmark by remember { mutableStateOf<Long?>(null) }
    var textZoom by remember { mutableIntStateOf(100) }
    val snackbar = remember { SnackbarHostState() }
    val atStart = currentId == null || vm.currentTab()?.url == START

    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.message.value = null } }
    LaunchedEffect(openDoc) { openDoc?.let { vm.openDocument.value = null; nav(Routes.reader(it)) } }

    LaunchedEffect(Unit) {
        if (!vm.initialHandled) {
            vm.initialHandled = true
            if (initialUrl != null) vm.newTab(initialUrl) else if (tabs.isEmpty()) vm.newTab(START)
        }
    }

    fun go(input: String) {
        val url = UrlSafety.toBrowsableUrl(input, prefs.searchEngine.template)
        if (url == null) { vm.message.value = "That kind of address can't be opened here."; return }
        editing = false; focus.clearFocus(); error = null
        if (vm.currentTab() == null) { vm.newTab(url); return }
        vm.onPage(url, "")
        web?.loadUrl(url)
    }

    // Switch the single WebView between tabs, saving and restoring each tab's history.
    LaunchedEffect(currentId, web) {
        val w = web ?: return@LaunchedEffect
        val prev = loadedTab
        if (prev != null && prev != currentId) vm.saveState(prev, Bundle().also { w.saveState(it) })
        val tab = vm.currentTab() ?: return@LaunchedEffect
        if (loadedTab == tab.id) return@LaunchedEffect
        loadedTab = tab.id
        error = null
        when {
            tab.state != null -> { w.restoreState(tab.state); pageUrl = tab.url; pageTitle = tab.title }
            tab.url != START -> w.loadUrl(tab.url)
            else -> { w.loadUrl("about:blank"); pageUrl = ""; pageTitle = "" }
        }
    }

    LaunchedEffect(prefs.browserJavaScript, web) { web?.settings?.javaScriptEnabled = prefs.browserJavaScript }
    LaunchedEffect(textZoom, web) { web?.settings?.textZoom = textZoom }

    DisposableEffect(Unit) {
        onDispose {
            loadedTab?.let { id -> web?.let { w -> vm.saveState(id, Bundle().also { w.saveState(it) }) } }
            if (vm.prefs.value.browserClearOnExit) {
                SecureWeb.clearBrowsingData(ctx, web)
                vm.closeAll()
            }
        }
    }

    BackHandler {
        when {
            readerHtml != null -> readerHtml = null
            finding -> { finding = false; web?.clearMatches() }
            editing -> { editing = false; focus.clearFocus() }
            canBack && !atStart -> web?.goBack()
            else -> back()
        }
    }

    fun captureHtml(then: (String) -> Unit) {
        val w = web ?: return
        if (!prefs.browserJavaScript) { vm.message.value = "Turn on JavaScript in the menu to use reader mode and offline saving."; return }
        w.evaluateJavascript("(function(){return document.documentElement.outerHTML;})()") { raw ->
            val html = runCatching { Json.decodeFromString<String>(raw ?: "null") }.getOrNull()
            if (html.isNullOrEmpty()) vm.message.value = "Couldn't read this page." else then(html)
        }
    }

    fun snapshot(): Bitmap? = web?.takeIf { it.width > 0 && it.height > 0 }?.let { w ->
        runCatching {
            val scale = 360f / w.width
            val bmp = Bitmap.createBitmap(360, (w.height * scale).toInt().coerceIn(1, 640), Bitmap.Config.RGB_565)
            val c = Canvas(bmp); c.scale(scale, scale); w.draw(c); bmp
        }.getOrNull()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            Column(Modifier.background(MaterialTheme.colorScheme.surfaceContainer)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = back) { Icon(Icons.Default.Close, "Close browser") }
                    OutlinedTextField(
                        value = if (editing) address else (if (atStart) "" else pageUrl),
                        onValueChange = { address = it },
                        placeholder = { Text("Search or type address") },
                        leadingIcon = {
                            Icon(if (pageUrl.startsWith("https://") && !editing) Icons.Filled.Lock else Icons.Outlined.Search, null, Modifier.size(18.dp),
                                tint = if (pageUrl.startsWith("https://") && !editing) MaterialTheme.colorScheme.tertiary else LocalContentColor.current)
                        },
                        trailingIcon = { if (editing && address.isNotEmpty()) IconButton(onClick = { address = "" }) { Icon(Icons.Default.Close, "Clear") } },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium,
                        shape = RoundedCornerShape(24.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { go(address) }),
                        colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = MaterialTheme.colorScheme.surface, focusedContainerColor = MaterialTheme.colorScheme.surface),
                        modifier = Modifier.weight(1f).height(52.dp).onFocusChanged { f ->
                            if (f.isFocused && !editing) { editing = true; address = if (atStart) "" else pageUrl }
                        },
                    )
                    TabsButton(tabs.size) { showTabs = true }
                }
                if (loadProgress in 1..99) LinearProgressIndicator(progress = { loadProgress / 100f }, modifier = Modifier.fillMaxWidth().height(2.dp))
                AnimatedVisibility(finding) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(findQuery, { findQuery = it; web?.findAllAsync(it) }, placeholder = { Text("Find in page") }, singleLine = true,
                            modifier = Modifier.weight(1f), textStyle = MaterialTheme.typography.bodyMedium)
                        Text(if (findResult.second > 0) "${findResult.first + 1}/${findResult.second}" else "0/0", Modifier.padding(horizontal = 8.dp),
                            style = MaterialTheme.typography.labelMedium)
                        IconButton(onClick = { web?.findNext(false) }) { Icon(Icons.Default.KeyboardArrowUp, "Previous") }
                        IconButton(onClick = { web?.findNext(true) }) { Icon(Icons.Default.KeyboardArrowDown, "Next") }
                        IconButton(onClick = { finding = false; findQuery = ""; web?.clearMatches() }) { Icon(Icons.Default.Close, "Close find") }
                    }
                }
            }
        },
        bottomBar = {
            BottomAppBar(contentPadding = PaddingValues(horizontal = 8.dp)) {
                IconButton(enabled = canBack && !atStart, onClick = { web?.goBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                IconButton(enabled = canForward, onClick = { web?.goForward() }) { Icon(Icons.AutoMirrored.Filled.ArrowForward, "Forward") }
                IconButton(enabled = !atStart, onClick = { if (loadProgress in 1..99) web?.stopLoading() else web?.reload() }) {
                    Icon(if (loadProgress in 1..99) Icons.Default.Close else Icons.Default.Refresh, "Reload")
                }
                IconButton(enabled = !atStart, onClick = {
                    if (bookmark != null) editBookmark = bookmark!!.id
                    else vm.toggleBookmark(pageUrl, pageTitle) { id -> id?.let { bid -> snapshot()?.let { vm.thumbnail(bid, it) } } }
                }) {
                    Icon(if (bookmark != null) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder, "Bookmark",
                        tint = if (bookmark != null) MaterialTheme.colorScheme.secondary else LocalContentColor.current)
                }
                Spacer(Modifier.weight(1f))
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Menu") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text("New tab") }, leadingIcon = { Icon(Icons.Outlined.Add, null) }, onClick = { menu = false; vm.newTab(START) })
                        if (!atStart) {
                            DropdownMenuItem(text = { Text("Reader mode") }, leadingIcon = { Icon(Icons.Outlined.ChromeReaderMode, null) }, onClick = {
                                menu = false; captureHtml { html -> val a = Readability.extract(html, pageUrl); readerHtml = a.title to a.html }
                            })
                            DropdownMenuItem(text = { Text("Save for offline reading") }, leadingIcon = { Icon(Icons.Outlined.OfflinePin, null) }, onClick = {
                                menu = false; captureHtml { html -> vm.saveForOffline(pageUrl, pageTitle, html) }
                            })
                            DropdownMenuItem(text = { Text("Add to collection / edit bookmark") }, leadingIcon = { Icon(Icons.Outlined.FolderSpecial, null) }, onClick = {
                                menu = false; vm.toggleBookmark(pageUrl, pageTitle) { id -> editBookmark = id }
                            })
                            DropdownMenuItem(text = { Text("Find in page") }, leadingIcon = { Icon(Icons.Outlined.FindInPage, null) }, onClick = { menu = false; finding = true })
                            DropdownMenuItem(text = { Text("Text size: $textZoom%") }, leadingIcon = { Icon(Icons.Outlined.FormatSize, null) }, onClick = {},
                                trailingIcon = {
                                    Row {
                                        IconButton(onClick = { textZoom = (textZoom - 10).coerceAtLeast(50) }) { Icon(Icons.Default.Remove, "Smaller") }
                                        IconButton(onClick = { textZoom = (textZoom + 10).coerceAtMost(250) }) { Icon(Icons.Default.Add, "Larger") }
                                    }
                                })
                            DropdownMenuItem(text = { Text("Share link") }, leadingIcon = { Icon(Icons.Outlined.Share, null) }, onClick = {
                                menu = false
                                ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, pageUrl), "Share link"))
                            })
                            DropdownMenuItem(text = { Text("Open in another browser") }, leadingIcon = { Icon(Icons.Outlined.OpenInBrowser, null) }, onClick = {
                                menu = false
                                runCatching { ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW, Uri.parse(pageUrl)).addCategory(Intent.CATEGORY_BROWSABLE), "Open with")) }
                            })
                        }
                        DropdownMenuItem(text = { Text(if (prefs.browserJavaScript) "JavaScript: on" else "JavaScript: off") }, leadingIcon = { Icon(Icons.Outlined.Code, null) },
                            onClick = { menu = false; vm.setJs(!prefs.browserJavaScript); web?.reload() })
                        HorizontalDivider()
                        DropdownMenuItem(text = { Text("Downloads") }, leadingIcon = { Icon(Icons.Outlined.Download, null) }, onClick = { menu = false; nav(Routes.DOWNLOADS) })
                        DropdownMenuItem(text = { Text("History") }, leadingIcon = { Icon(Icons.Outlined.History, null) }, onClick = { menu = false; nav(Routes.HISTORY) })
                        DropdownMenuItem(text = { Text("Bookmarks") }, leadingIcon = { Icon(Icons.Outlined.Bookmarks, null) }, onClick = { menu = false; nav(Routes.BOOKMARKS) })
                    }
                }
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            key(webKey) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { c ->
                        WebView(c).apply {
                            SecureWeb.configureBrowser(this, prefs.browserJavaScript, prefs.browserBlockThirdPartyCookies)
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                    val uri = request.url
                                    return when (uri.scheme?.lowercase()) {
                                        "https" -> false
                                        "http" -> { view.loadUrl(uri.buildUpon().scheme("https").build().toString()); true }
                                        "intent" -> {
                                            val intent = runCatching { Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME) }.getOrNull()
                                            val fallback = intent?.getStringExtra("browser_fallback_url")
                                            if (fallback != null && fallback.startsWith("https://")) view.loadUrl(fallback)
                                            else if (intent != null && request.hasGesture()) external = intent.apply {
                                                component = null; selector = null; addCategory(Intent.CATEGORY_BROWSABLE)
                                            }
                                            true
                                        }
                                        "javascript", "file", "content", "data", "blob", "about" -> true
                                        else -> {
                                            // mailto:, tel:, market: … only after an explicit tap, and only with confirmation.
                                            if (request.hasGesture()) external = Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)
                                            true
                                        }
                                    }
                                }

                                override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                                    error = null
                                    if (url != null && url != "about:blank") pageUrl = url
                                }

                                override fun onPageFinished(view: WebView, url: String?) {
                                    canBack = view.canGoBack(); canForward = view.canGoForward()
                                    if (url != null && url != "about:blank") {
                                        pageUrl = url
                                        pageTitle = view.title.orEmpty()
                                        vm.onPage(url, pageTitle)
                                        vm.recordVisit(url, pageTitle)
                                    }
                                }

                                override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                                    canBack = view.canGoBack(); canForward = view.canGoForward()
                                }

                                override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, e: SslError) {
                                    // Never proceed past certificate errors.
                                    handler.cancel()
                                    error = "This site's security certificate isn't trusted, so Folio stopped loading it."
                                }

                                override fun onReceivedError(view: WebView, request: WebResourceRequest, e: WebResourceError) {
                                    if (request.isForMainFrame) error = "Couldn't load the page (${e.description}). Check your connection."
                                }

                                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                                    // The page crashed the renderer; rebuild the WebView instead of crashing the app.
                                    if (web === view) web = null
                                    loadedTab = null
                                    webKey++
                                    return true
                                }
                            }
                            webChromeClient = object : WebChromeClient() {
                                override fun onProgressChanged(view: WebView, p: Int) { loadProgress = p }
                                override fun onReceivedTitle(view: WebView, title: String?) { pageTitle = title.orEmpty() }
                                override fun onReceivedIcon(view: WebView, icon: Bitmap?) { if (icon != null && view.url != null) vm.favicon(view.url!!, icon) }
                                override fun onPermissionRequest(request: PermissionRequest) { request.deny() }
                                override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback) { callback.invoke(origin, false, false) }
                                override fun onShowFileChooser(view: WebView?, cb: ValueCallback<Array<Uri>>?, params: WebChromeClient.FileChooserParams?): Boolean {
                                    cb?.onReceiveValue(null)
                                    vm.message.value = "File uploads are turned off in Folio's browser."
                                    return true
                                }
                                override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?): Boolean = false
                            }
                            setDownloadListener { url, ua, disposition, mime, _ ->
                                val name = UrlSafety.safeFileName(URLUtil.guessFileName(url, disposition, mime), "download")
                                val isPdf = mime?.contains("pdf", true) == true || name.endsWith(".pdf", true)
                                if (!url.startsWith("https://")) vm.message.value = "Only secure (HTTPS) downloads are allowed."
                                else vm.pendingDownload.value = PendingDownload(url, ua, disposition, mime, name, isPdf)
                            }
                            setFindListener { active, count, _ -> findResult = active to count }
                            web = this
                        }
                    },
                    onRelease = { w -> w.stopLoading(); w.webChromeClient = null; w.destroy(); if (web === w) web = null },
                )
            }

            if (atStart) StartPage(vm, onOpen = { go(it) }, nav = nav)

            error?.let { msg ->
                Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Outlined.CloudOff, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    Text(msg, style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(16.dp))
                    OutlinedButton(onClick = { error = null; web?.reload() }) { Text("Try again") }
                }
            }

            if (editing) Suggestions(vm, address) { go(it) }

            readerHtml?.let { (title, html) ->
                val colors = readerColors(prefs.readerTheme)
                Surface(Modifier.fillMaxSize(), color = colors.background) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(4.dp)) {
                            IconButton(onClick = { readerHtml = null }) { Icon(Icons.Default.Close, "Close reader mode", tint = colors.text) }
                            Text(title, color = colors.text, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        }
                        LocalHtmlView("<h1>${org.jsoup.nodes.Entities.escape(title)}</h1>$html", readerCss(colors, 1f), Modifier.fillMaxSize(),
                            onLink = { u -> readerHtml = null; if (u.scheme == "https" && u.host != SecureWeb.LOCAL_HOST) web?.loadUrl(u.toString()) })
                    }
                }
            }
        }
    }

    if (showTabs) TabsSheet(tabs, currentId, onSelect = { vm.select(it); showTabs = false }, onClose = vm::close,
        onNew = { vm.newTab(START); showTabs = false }, onCloseAll = { vm.closeAll(); vm.newTab(START); showTabs = false }, onDismiss = { showTabs = false })

    pending?.let { d ->
        AlertDialog(
            onDismissRequest = { vm.pendingDownload.value = null },
            icon = { Icon(if (d.isPdf) Icons.Outlined.PictureAsPdf else Icons.Outlined.Download, null) },
            title = { Text(if (d.isPdf) "PDF document" else "Download file?") },
            text = {
                Text("${d.fileName}\nfrom ${UrlSafety.hostOf(d.url)}\n\n" +
                    if (d.isPdf) "Open it in Folio's PDF reader? It will be saved to your library with its source link, so it stays available offline."
                    else "Folio saves documents and images to your library. Other file types, such as apps and archives, are refused for your safety.")
            },
            confirmButton = { TextButton(onClick = { vm.startDownload(d, openWhenDone = true) }) { Text(if (d.isPdf) "Open in reader" else "Download & open") } },
            dismissButton = {
                Row {
                    TextButton(onClick = { vm.startDownload(d, openWhenDone = false) }) { Text("Save only") }
                    TextButton(onClick = { vm.pendingDownload.value = null }) { Text("Cancel") }
                }
            },
        )
    }

    external?.let { intent ->
        AlertDialog(
            onDismissRequest = { external = null },
            title = { Text("Open in another app?") },
            text = { Text("This page wants to open: ${intent.data?.scheme ?: intent.`package` ?: "another app"}") },
            confirmButton = {
                TextButton(onClick = {
                    try { ctx.startActivity(intent) } catch (e: ActivityNotFoundException) { vm.message.value = "No app can open this link." }
                    catch (e: SecurityException) { vm.message.value = "This link can't be opened." }
                    external = null
                }) { Text("Open") }
            },
            dismissButton = { TextButton(onClick = { external = null }) { Text("Stay here") } },
        )
    }

    editBookmark?.let { id ->
        val collections by hiltViewModel<com.folio.reader.ui.collections.CollectionsViewModel>().collections.collectAsStateWithLifecycle()
        com.folio.reader.ui.bookmarks.BookmarkEditSheet(id, collections, nav) { editBookmark = null; vm.onPage(pageUrl, pageTitle) }
    }
}

@Composable
private fun TabsButton(count: Int, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Box(Modifier.size(24.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)), contentAlignment = Alignment.Center) {
            Text(if (count > 99) ":)" else count.toString(), style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun Suggestions(vm: BrowserViewModel, q: String, onPick: (String) -> Unit) {
    val items by produceState(initialValue = emptyList<com.folio.reader.data.db.BrowserHistoryEntity>(), q) { value = vm.suggestions(q) }
    if (items.isEmpty()) return
    Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp, shadowElevation = 6.dp) {
        LazyColumn {
            items(items, key = { it.id }) { h ->
                ListItem(modifier = Modifier.clickable { onPick(h.url) },
                    leadingContent = { Icon(Icons.Outlined.History, null) },
                    headlineContent = { Text(h.title.ifBlank { h.url }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text(h.url, maxLines = 1, overflow = TextOverflow.Ellipsis) })
            }
        }
    }
}

@Composable
private fun StartPage(vm: BrowserViewModel, onOpen: (String) -> Unit, nav: (String) -> Unit) {
    val history by produceState(initialValue = emptyList<com.folio.reader.data.db.BrowserHistoryEntity>()) { value = vm.suggestions("http") }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        LazyColumn(contentPadding = PaddingValues(20.dp)) {
            item {
                Text("Private browsing in Folio", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(6.dp))
                Text("HTTPS only · no trackers in documents · downloads go straight to your library. The browser is the only part of Folio that uses the internet.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(16.dp))
                OutlinedButton(onClick = { nav(Routes.BOOKMARKS) }) { Icon(Icons.Outlined.Bookmarks, null); Spacer(Modifier.width(8.dp)); Text("Your bookmarks") }
            }
            if (history.isNotEmpty()) {
                item { Text("Recently visited", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 24.dp, bottom = 8.dp)) }
                items(history, key = { it.id }) { h ->
                    ListItem(modifier = Modifier.clickable { onOpen(h.url) }, leadingContent = { Favicon(null, h.title.ifBlank { UrlSafety.hostOf(h.url) }) },
                        headlineContent = { Text(h.title.ifBlank { h.url }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text(UrlSafety.hostOf(h.url), maxLines = 1) })
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TabsSheet(tabs: List<BrowserTab>, current: Long?, onSelect: (Long) -> Unit, onClose: (Long) -> Unit, onNew: () -> Unit, onCloseAll: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${tabs.size} tab${if (tabs.size == 1) "" else "s"}", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = onCloseAll) { Text("Close all") }
            FilledTonalButton(onClick = onNew) { Icon(Icons.Default.Add, null); Text("New") }
        }
        LazyVerticalGrid(GridCells.Adaptive(160.dp), contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.heightIn(max = 520.dp)) {
            items(tabs, key = { it.id }) { t ->
                Card(onClick = { onSelect(t.id) }, colors = CardDefaults.cardColors(
                    containerColor = if (t.id == current) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh)) {
                    Row(Modifier.padding(start = 12.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(t.title.ifBlank { if (t.url == START) "New tab" else UrlSafety.hostOf(t.url) }, style = MaterialTheme.typography.labelLarge,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        IconButton(onClick = { onClose(t.id) }) { Icon(Icons.Default.Close, "Close tab", Modifier.size(18.dp)) }
                    }
                    Text(if (t.url == START) "Start page" else UrlSafety.hostOf(t.url), style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp), maxLines = 1)
                }
            }
        }
    }
}
