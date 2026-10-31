package com.folio.reader.ui.web

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.WebView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import com.folio.reader.ui.theme.ReaderColors

/** Builds the reading stylesheet used for books, HTML/Markdown documents and saved pages. */
fun readerCss(colors: ReaderColors, fontScale: Float, extra: String = ""): String {
    fun hex(c: androidx.compose.ui.graphics.Color) = String.format("#%06X", 0xFFFFFF and c.toArgb())
    return """
        html, body { ${colors.css}; }
        body { margin: 0 auto; padding: 18px 20px 64px; max-width: 46em; font-family: Georgia, 'Noto Serif', serif;
               font-size: ${(110 * fontScale).toInt()}%; line-height: 1.65; word-wrap: break-word; overflow-wrap: anywhere; }
        img, svg, video { max-width: 100% !important; height: auto !important; }
        pre, code { white-space: pre-wrap; font-size: 0.9em; }
        pre { background: rgba(127,127,127,0.12); padding: 12px; border-radius: 8px; overflow-x: auto; }
        a { color: ${hex(colors.text)}; text-decoration-color: rgba(127,127,127,0.6); }
        table { border-collapse: collapse; max-width: 100%; display: block; overflow-x: auto; }
        td, th { border: 1px solid rgba(127,127,127,0.35); padding: 4px 8px; }
        blockquote { border-left: 3px solid rgba(127,127,127,0.5); margin-left: 0; padding-left: 14px; color: ${hex(colors.muted)}; }
        h1, h2, h3 { line-height: 1.25; }
        $extra
    """.trimIndent()
}

/**
 * Displays untrusted HTML that is already on the device. JavaScript is off,
 * all network requests are refused, and links go to [onLink].
 */
@SuppressLint("ClickableViewAccessibility")
@Composable
fun LocalHtmlView(
    html: String,
    css: String,
    modifier: Modifier = Modifier,
    onLink: (Uri) -> Unit = {},
    onWebView: (WebView) -> Unit = {},
    onLoaded: (WebView) -> Unit = {},
) {
    val linkHandler by rememberUpdatedState(onLink)
    val loadedHandler by rememberUpdatedState(onLoaded)
    val currentCss by rememberUpdatedState(css)
    var view by remember { mutableStateOf<WebView?>(null) }
    val pageRef = remember { java.util.concurrent.atomic.AtomicReference<String?>(null) }
    var version by remember { mutableStateOf(0) }
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                SecureWeb.configureDocument(this)
                webViewClient = LocalOnlyClient(injectCss = { currentCss }, onLink = { linkHandler(it) }, onLoaded = { loadedHandler(it) },
                    page = { pageRef.get() })
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                view = this
                onWebView(this)
            }
        },
        onRelease = { it.stopLoading(); it.destroy() },
    )
    LaunchedEffect(view, html, css) {
        val v = view ?: return@LaunchedEffect
        val doc = "<!DOCTYPE html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">" +
            "<style>$css</style></head><body>$html</body></html>"
        pageRef.set(doc)
        version++
        // Served by LocalOnlyClient from the local origin; the version defeats any caching when content changes.
        v.loadUrl("${SecureWeb.LOCAL_ORIGIN}${LocalOnlyClient.DOC_PATH}?v=$version")
    }
}
