package com.folio.reader.ui.reader

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.WebView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.folio.reader.data.epub.EpubBook
import com.folio.reader.ui.theme.ReaderColors
import com.folio.reader.ui.web.LocalOnlyClient
import com.folio.reader.ui.web.SecureWeb
import com.folio.reader.ui.web.readerCss

/**
 * Renders one EPUB chapter at a time in a locked-down WebView. Book files are
 * served from the unpacked cache through https://folio.local/; JavaScript is
 * off and all other requests are refused, so a book can't phone home.
 */
@SuppressLint("ClickableViewAccessibility")
@Composable
fun EpubReader(
    book: EpubBook,
    chapter: Int,
    initialOffset: Float,
    colors: ReaderColors,
    fontScale: Float,
    onProgress: (chapter: Int, offset: Float) -> Unit,
    onNavigate: (chapter: Int) -> Unit,
    onExternalLink: (Uri) -> Unit,
    onTap: () -> Unit,
) {
    val css = readerCss(colors, fontScale, "body { padding-top: 56px; padding-bottom: 96px; }")
    val currentCss by rememberUpdatedState(css)
    val progressCb by rememberUpdatedState(onProgress)
    val navigate by rememberUpdatedState(onNavigate)
    val external by rememberUpdatedState(onExternalLink)
    val tap by rememberUpdatedState(onTap)
    var pendingOffset by remember { mutableFloatStateOf(initialOffset) }
    var loadedChapter by remember { mutableIntStateOf(-1) }
    var loadedCss by remember { mutableStateOf("") }
    var view by remember { mutableStateOf<WebView?>(null) }
    val currentChapter by rememberUpdatedState(chapter)

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            WebView(ctx).apply {
                SecureWeb.configureDocument(this)
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                webViewClient = LocalOnlyClient(
                    resolve = { path -> book.resolve(path) },
                    injectCss = { currentCss },
                    onLink = { uri ->
                        if (uri.host == SecureWeb.LOCAL_HOST) {
                            val target = EpubBook.normalize(uri.path?.trimStart('/') ?: "")
                            val idx = book.chapters.indexOfFirst { it.href == target }
                            if (idx >= 0 && idx != currentChapter) { pendingOffset = 0f; navigate(idx) }
                        } else external(uri)
                    },
                    onLoaded = { v ->
                        val off = pendingOffset
                        v.postDelayed({
                            val max = (v.contentHeight * v.resources.displayMetrics.density - v.height).toInt().coerceAtLeast(0)
                            v.scrollTo(0, (max * off).toInt())
                        }, 120)
                    },
                )
                setOnScrollChangeListener { v, _, y, _, _ ->
                    val w = v as WebView
                    val max = (w.contentHeight * w.resources.displayMetrics.density - w.height).coerceAtLeast(1f)
                    progressCb(currentChapter, (y / max).coerceIn(0f, 1f))
                }
                var downX = 0f; var downY = 0f
                setOnTouchListener { _, e ->
                    when (e.actionMasked) {
                        android.view.MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y }
                        android.view.MotionEvent.ACTION_UP -> if (kotlin.math.abs(e.x - downX) < 12 && kotlin.math.abs(e.y - downY) < 12 && e.eventTime - e.downTime < 250) tap()
                    }
                    false
                }
                view = this
            }
        },
        onRelease = { it.stopLoading(); it.destroy() },
    )

    LaunchedEffect(view, chapter, css) {
        val v = view ?: return@LaunchedEffect
        if (loadedChapter == chapter && loadedCss == css) return@LaunchedEffect
        if (loadedChapter == chapter) {
            // Theme / font change: keep the reader's place.
            val max = (v.contentHeight * v.resources.displayMetrics.density - v.height).coerceAtLeast(1f)
            pendingOffset = (v.scrollY / max).coerceIn(0f, 1f)
        } else if (loadedChapter != -1) pendingOffset = 0f
        loadedChapter = chapter
        loadedCss = css
        val href = book.chapters.getOrNull(chapter)?.href ?: return@LaunchedEffect
        v.loadUrl(SecureWeb.LOCAL_ORIGIN + href.split('/').joinToString("/") { Uri.encode(it) })
    }
}
