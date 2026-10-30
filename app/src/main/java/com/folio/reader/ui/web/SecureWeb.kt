package com.folio.reader.ui.web

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream

object SecureWeb {

    /** Settings shared by every WebView in the app. No JavaScript bridge is ever added. */
    @SuppressLint("SetJavaScriptEnabled")
    fun harden(web: WebView, javaScript: Boolean) {
        web.settings.apply {
            allowFileAccess = false
            allowContentAccess = false
            @Suppress("DEPRECATION") run {
                allowFileAccessFromFileURLs = false
                allowUniversalAccessFromFileURLs = false
                saveFormData = false
            }
            javaScriptEnabled = javaScript
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            setGeolocationEnabled(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            mediaPlaybackRequiresUserGesture = true
            builtInZoomControls = true
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true
            safeBrowsingEnabled = true
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.SAFE_BROWSING_ENABLE)) {
            WebSettingsCompat.setSafeBrowsingEnabled(web.settings, true)
        }
        // Legacy bridges some old OEM WebViews inject.
        web.removeJavascriptInterface("searchBoxJavaBridge_")
        web.removeJavascriptInterface("accessibility")
        web.removeJavascriptInterface("accessibilityTraversal")
    }

    /** Configuration for the browser: DOM storage on (sites need it), third-party cookies per user setting. */
    fun configureBrowser(web: WebView, javaScript: Boolean, blockThirdPartyCookies: Boolean) {
        harden(web, javaScript)
        web.settings.domStorageEnabled = true
        @Suppress("DEPRECATION") run { web.settings.databaseEnabled = false }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, !blockThirdPartyCookies)
    }

    /**
     * Configuration for rendering local, untrusted documents (EPUB chapters,
     * HTML/Markdown files, saved pages): JavaScript off, no storage, no cookies,
     * and every network request answered locally (see [LocalOnlyClient]).
     */
    fun configureDocument(web: WebView) {
        harden(web, javaScript = false)
        web.settings.domStorageEnabled = false
        @Suppress("DEPRECATION") run { web.settings.databaseEnabled = false }
        web.settings.cacheMode = WebSettings.LOAD_NO_CACHE
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false)
    }

    fun clearBrowsingData(context: Context, web: WebView?) {
        web?.clearCache(true)
        web?.clearHistory()
        web?.clearFormData()
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        android.webkit.WebStorage.getInstance().deleteAllData()
        runCatching { context.deleteDatabase("webview.db"); context.deleteDatabase("webviewCache.db") }
    }

    const val LOCAL_HOST = "folio.local"
    const val LOCAL_ORIGIN = "https://$LOCAL_HOST/"

    fun blocked(): WebResourceResponse = WebResourceResponse("text/plain", "utf-8", 403, "Blocked", mapOf("Cache-Control" to "no-store"), ByteArrayInputStream(ByteArray(0)))

    fun mimeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "xhtml", "xht" -> "application/xhtml+xml"
        "html", "htm" -> "text/html"
        "css" -> "text/css"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "svg" -> "image/svg+xml"
        "ttf" -> "font/ttf"
        "otf" -> "font/otf"
        "woff" -> "font/woff"
        "woff2" -> "font/woff2"
        else -> "application/octet-stream"
    }
}

/**
 * WebViewClient for local documents. Requests to https://folio.local/ are
 * served by [resolve]; every other request — including tracking pixels,
 * remote fonts and scripts embedded in a book — is refused, so opening a
 * document never touches the network. Links are handed to [onLink] instead
 * of navigating, and navigation inside the WebView is blocked.
 */
open class LocalOnlyClient(
    private val resolve: (path: String) -> File? = { null },
    private val injectCss: () -> String? = { null },
    private val onLink: (Uri) -> Unit = {},
    private val onLoaded: (WebView) -> Unit = {},
) : WebViewClient() {

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        val url = request.url
        if (url.scheme != "https" || url.host != SecureWeb.LOCAL_HOST) return SecureWeb.blocked()
        val path = url.path?.trimStart('/') ?: return SecureWeb.blocked()
        if (path == "__folio.css") {
            val css = injectCss() ?: ""
            return WebResourceResponse("text/css", "utf-8", ByteArrayInputStream(css.toByteArray()))
        }
        val file = resolve(path) ?: return SecureWeb.blocked()
        val mime = SecureWeb.mimeFor(file.name)
        if (mime == "image/svg+xml") {
            // SVG may contain script; JS is off, but serve it as an image only when referenced, never as a page.
            if (request.isForMainFrame) return SecureWeb.blocked()
        }
        val stream = if ((mime == "application/xhtml+xml" || mime == "text/html") && request.isForMainFrame) {
            // Inject the reader stylesheet into chapter documents.
            val text = file.readText()
            val link = "<link rel=\"stylesheet\" href=\"${SecureWeb.LOCAL_ORIGIN}__folio.css\"/>"
            val idx = text.indexOf("</head>", ignoreCase = true)
            val patched = if (idx >= 0) text.substring(0, idx) + link + text.substring(idx) else link + text
            ByteArrayInputStream(patched.toByteArray())
        } else FileInputStream(file)
        return WebResourceResponse(mime, if (mime.startsWith("text") || mime.contains("xml")) "utf-8" else null, 200, "OK",
            mapOf("Content-Security-Policy" to "default-src 'none'; img-src https://${SecureWeb.LOCAL_HOST} data:; style-src https://${SecureWeb.LOCAL_HOST} 'unsafe-inline'; font-src https://${SecureWeb.LOCAL_HOST}",
                "Cache-Control" to "no-store"), stream)
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val url = request.url
        if (url.host == SecureWeb.LOCAL_HOST && url.scheme == "https") {
            onLink(url)
            return true
        }
        if (url.scheme == "http" || url.scheme == "https" || url.scheme == "mailto") onLink(url)
        return true
    }

    override fun onPageFinished(view: WebView, url: String?) {
        super.onPageFinished(view, url)
        onLoaded(view)
    }
}
