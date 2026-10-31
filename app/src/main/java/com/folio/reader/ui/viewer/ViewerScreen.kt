package com.folio.reader.ui.viewer

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.ViewDay
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.folio.reader.ui.components.EmptyState
import com.folio.reader.ui.nav.Routes
import com.folio.reader.ui.reader.EpubReader
import com.folio.reader.ui.reader.ImageReader
import com.folio.reader.ui.reader.PdfReader
import com.folio.reader.ui.reader.PdfThumbnails
import com.folio.reader.ui.reader.ReaderContent
import com.folio.reader.ui.reader.TableReader
import com.folio.reader.ui.reader.TextReader
import com.folio.reader.ui.theme.readerColors
import com.folio.reader.ui.web.LocalHtmlView
import com.folio.reader.ui.web.SecureWeb
import com.folio.reader.ui.web.readerCss

/** Read-only viewer for files opened from other apps. See [ViewerViewModel]: nothing is stored. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(nav: (String) -> Unit, back: () -> Unit, vm: ViewerViewModel = hiltViewModel()) {
    val content by vm.content.collectAsStateWithLifecycle()
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val title by vm.title.collectAsStateWithLifecycle()
    val formatLabel by vm.formatLabel.collectAsStateWithLifecycle()
    val pos by vm.position.collectAsStateWithLifecycle()
    val jump by vm.jump.collectAsStateWithLifecycle()
    val loadingStatus by vm.loadingStatus.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(notice) { notice?.let { snackbar.showSnackbar(it); vm.notice.value = null } }
    var chrome by remember { mutableStateOf(true) }
    var pages by remember { mutableStateOf(false) }
    val colors = readerColors(prefs.readerTheme)
    val view = LocalView.current
    DisposableEffect(prefs.keepScreenOn) { view.keepScreenOn = prefs.keepScreenOn; onDispose { view.keepScreenOn = false } }

    val c = content
    val total = when (c) {
        is ReaderContent.Pdf -> c.pageCount
        is ReaderContent.Epub -> c.book.chapters.size
        is ReaderContent.Text -> c.lines.size
        is ReaderContent.Table -> c.rows.size
        else -> 1
    }
    val unit = when (c) {
        is ReaderContent.Pdf -> "Page"
        is ReaderContent.Epub -> "Section"
        is ReaderContent.Text -> "Line"
        is ReaderContent.Table -> "Row"
        else -> ""
    }
    val toggleChrome = { chrome = !chrome }

    Box(Modifier.fillMaxSize().background(colors.background)) {
        when (c) {
            is ReaderContent.Loading -> com.folio.reader.ui.reader.LoadingIndicator(loadingStatus, Modifier.align(Alignment.Center))
            is ReaderContent.Failed -> Box(Modifier.fillMaxSize().statusBarsPadding(), contentAlignment = Alignment.Center) {
                EmptyState(Icons.Outlined.ErrorOutline, "Can't open this file", c.message) { OutlinedButton(onClick = back) { Text("Go back") } }
            }
            is ReaderContent.Unsupported -> Box(Modifier.fillMaxSize().statusBarsPadding(), contentAlignment = Alignment.Center) {
                EmptyState(Icons.Outlined.Inventory2, "No viewer for ${c.format.label} yet", "This version of ReadStash can't display ${c.format.label} files.") {
                    OutlinedButton(onClick = back) { Text("Go back") }
                }
            }
            is ReaderContent.Pdf -> PdfReader(c.source, c.pageCount, 0, prefs.readerTheme, prefs.pdfBookMode, jump, vm::consumeJump,
                onPage = vm::report, onTap = toggleChrome)
            is ReaderContent.Epub -> Box(Modifier.fillMaxSize().systemBarsPadding()) {
                val chapter = pos.coerceIn(0, c.book.chapters.lastIndex)
                EpubReader(c.book, chapter, 0f, colors, prefs.readerFontScale,
                    onProgress = { ch, _ -> vm.report(ch) },
                    onNavigate = { vm.report(it) },
                    onExternalLink = { u -> if (u.scheme == "https" || u.scheme == "http") nav(Routes.browser(u.toString())) },
                    onTap = toggleChrome)
            }
            is ReaderContent.Text -> Box(Modifier.fillMaxSize().systemBarsPadding()) {
                TextReader(c.lines, c.mono, c.truncated, colors, prefs.readerFontScale, 0, jump, vm::consumeJump, "", onLine = vm::report, onTap = toggleChrome)
            }
            is ReaderContent.Table -> Box(Modifier.fillMaxSize().systemBarsPadding()) {
                TableReader(c.rows, c.truncated, colors, 0, jump, vm::consumeJump, "", onRow = vm::report, onTap = toggleChrome)
            }
            is ReaderContent.Html -> Box(Modifier.fillMaxSize().systemBarsPadding().padding(top = 56.dp)) {
                LocalHtmlView(c.html, readerCss(colors, prefs.readerFontScale, "section.slide { margin: 0 0 8px; } hr { border: 0; border-top: 1px solid rgba(127,127,127,0.35); margin: 24px 0; }"),
                    Modifier.fillMaxSize(),
                    onLink = { u -> if (u.scheme == "https" && u.host != SecureWeb.LOCAL_HOST) nav(Routes.browser(u.toString())) })
            }
            is ReaderContent.Image -> ImageReader(c.model, colors, toggleChrome)
        }

        AnimatedVisibility(chrome, enter = fadeIn() + slideInVertically(), exit = fadeOut() + slideOutVertically(), modifier = Modifier.align(Alignment.TopCenter)) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.97f), tonalElevation = 3.dp) {
                Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                    Column(Modifier.weight(1f)) {
                        Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val sub = listOfNotNull(
                            formatLabel,
                            if (total > 1) "$unit ${pos + 1} of $total" else null,
                            "Not saved to ReadStash",
                        ).joinToString(" · ")
                        Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (c is ReaderContent.Pdf) {
                        IconButton(onClick = { vm.setPdfBookMode(!prefs.pdfBookMode) }) {
                            Icon(if (prefs.pdfBookMode) Icons.Outlined.ViewDay else Icons.AutoMirrored.Outlined.MenuBook,
                                if (prefs.pdfBookMode) "Switch to scroll view" else "Switch to book view")
                        }
                        IconButton(onClick = { pages = true }) { Icon(Icons.Outlined.GridView, "Pages") }
                    }
                    if (c !is ReaderContent.Image) IconButton(onClick = { vm.cycleTheme() }) { Icon(Icons.Outlined.Palette, "Change reading theme (${prefs.readerTheme.label})") }
                }
            }
        }

        AnimatedVisibility(chrome && total > 1, enter = fadeIn() + slideInVertically { it }, exit = fadeOut() + slideOutVertically { it }, modifier = Modifier.align(Alignment.BottomCenter)) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.97f), tonalElevation = 3.dp) {
                Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    val isEpub = c is ReaderContent.Epub
                    if (isEpub) IconButton(enabled = pos > 0, onClick = { vm.report(pos - 1) }) { Icon(Icons.Default.ChevronLeft, "Previous section") }
                    var dragging by remember { mutableStateOf<Float?>(null) }
                    Slider(
                        value = dragging ?: (pos.toFloat() / (total - 1).coerceAtLeast(1)),
                        onValueChange = { dragging = it },
                        onValueChangeFinished = {
                            dragging?.let { val t = (it * (total - 1)).toInt(); if (isEpub) vm.report(t) else vm.requestJump(t) }
                            dragging = null
                        },
                        modifier = Modifier.weight(1f),
                    )
                    if (isEpub) IconButton(enabled = pos < total - 1, onClick = { vm.report(pos + 1) }) { Icon(Icons.Default.ChevronRight, "Next section") }
                }
            }
        }
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        SnackbarHost(snackbar, Modifier.navigationBarsPadding().padding(bottom = 72.dp))
    }

    if (pages && c is ReaderContent.Pdf) {
        ModalBottomSheet(onDismissRequest = { pages = false }) {
            PdfThumbnails(c.source, c.pageCount, pos) { vm.requestJump(it); pages = false }
        }
    }
}
