package com.folio.reader.data.files

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * All app files live in the private, per-app sandbox. Nothing is written to
 * shared storage; leaving the library is always an explicit user export.
 *
 *  files/library    imported and downloaded documents (permanent)
 *  files/thumbs     generated previews (regenerable)
 *  files/favicons   site icons captured by the browser
 *  files/pages      sanitised offline copies of saved web pages
 *  cache/epub       unpacked EPUB books (regenerable)
 *  cache/tmp        short-lived working files, wiped on start
 */
@Singleton
class FileStore @Inject constructor(@ApplicationContext private val context: Context) {

    val libraryDir: File get() = dir(context.filesDir, "library")
    val thumbsDir: File get() = dir(context.filesDir, "thumbs")
    val faviconDir: File get() = dir(context.filesDir, "favicons")
    val pagesDir: File get() = dir(context.filesDir, "pages")
    val epubCacheDir: File get() = dir(context.cacheDir, "epub")
    val tmpDir: File get() = dir(context.cacheDir, "tmp")

    private fun dir(parent: File, name: String) = File(parent, name).apply { if (!exists()) mkdirs() }

    fun libraryFile(storedName: String): File = childOf(libraryDir, storedName)
    fun thumbFile(name: String): File = childOf(thumbsDir, name)
    fun faviconFile(name: String): File = childOf(faviconDir, name)
    fun pageFile(name: String): File = childOf(pagesDir, name)

    /** Resolves a stored file name, refusing anything that would leave [parent]. */
    private fun childOf(parent: File, name: String): File {
        require(name.isNotBlank() && !name.contains('/') && !name.contains('\\') && name != "." && name != "..") { "Bad file name" }
        return File(parent, name)
    }

    fun newStoredName(extension: String): String {
        val ext = extension.lowercase().filter { it.isLetterOrDigit() }.take(8)
        return UUID.randomUUID().toString() + if (ext.isNotEmpty()) ".$ext" else ""
    }

    /** Private temp file; caller deletes it. */
    fun newTempFile(suffix: String = ".tmp"): File = File.createTempFile("w", suffix, tmpDir)

    fun clearTemp() {
        tmpDir.listFiles()?.forEach { it.deleteRecursively() }
    }

    fun clearEpubCache() {
        epubCacheDir.listFiles()?.forEach { it.deleteRecursively() }
    }

    fun dirSize(dir: File): Long = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
}
