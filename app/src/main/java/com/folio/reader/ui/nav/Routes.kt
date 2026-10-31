package com.folio.reader.ui.nav

import android.net.Uri

object Routes {
    const val HOME = "home"
    const val DOCUMENTS = "documents"
    const val BOOKMARKS = "bookmarks"
    const val COLLECTIONS = "collections"
    const val SEARCH = "search"
    const val SETTINGS = "settings"
    const val COLLECTION = "collection/{id}"
    const val READER = "reader/{id}?pos={pos}"
    const val BROWSER = "browser?url={url}"
    const val OFFLINE = "offline/{id}"
    const val DOWNLOADS = "downloads"
    const val HISTORY = "history"
    const val TAG = "tag/{id}"
    /** Read-only viewer for a file from another app; nothing is copied into Folio. */
    const val VIEW = "view?uri={uri}"

    fun collection(id: Long) = "collection/$id"
    fun tag(id: Long) = "tag/$id"
    fun reader(id: Long, pos: Int = -1) = "reader/$id?pos=$pos"
    fun browser(url: String? = null) = if (url == null) "browser" else "browser?url=${Uri.encode(url)}"
    fun offline(id: Long) = "offline/$id"
    fun view(uri: Uri) = "view?uri=${Uri.encode(uri.toString())}"
}
