package com.folio.reader.data.files

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.folio.reader.core.format.DocFormat
import com.folio.reader.core.format.ReaderKind
import com.folio.reader.core.security.Limits
import com.folio.reader.data.epub.EpubBook
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class DocInfo(val pageCount: Int?, val title: String?, val author: String?, val thumbnailName: String?)

@Singleton
class ThumbnailGenerator @Inject constructor(private val files: FileStore) {

    /** Reads page count / metadata and writes a JPEG preview. Failures yield nulls, never crashes. */
    fun inspect(docId: Long, file: File, format: DocFormat, cacheKey: String): DocInfo {
        val thumbName = "doc_$docId.jpg"
        return try {
            when (format.kind) {
                ReaderKind.PDF -> PdfLock.withLock {
                    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                        PdfRenderer(pfd).use { r ->
                            val saved = if (r.pageCount > 0) r.openPage(0).use { page ->
                                val w = 360
                                val h = (w.toFloat() * page.height / page.width.coerceAtLeast(1)).toInt().coerceIn(1, 1200)
                                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                                bmp.eraseColor(Color.WHITE)
                                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                save(bmp, thumbName)
                            } else false
                            DocInfo(r.pageCount, null, null, thumbName.takeIf { saved })
                        }
                    }
                }
                ReaderKind.IMAGE -> {
                    val bmp = decodeSampled(file, 480)
                    DocInfo(1, null, null, bmp?.let { if (save(it, thumbName)) thumbName else null })
                }
                ReaderKind.EPUB -> {
                    val book = EpubBook.open(file, File(files.epubCacheDir, cacheKey).apply { mkdirs() })
                    val cover = book.coverHref?.let { book.resolve(it) }
                    val bmp = cover?.let { decodeSampled(it, 480) }
                    DocInfo(book.chapters.size, book.title, book.author, bmp?.let { if (save(it, thumbName)) thumbName else null })
                }
                else -> DocInfo(null, null, null, null)
            }
        } catch (e: Throwable) {
            if (e is VirtualMachineError && e !is OutOfMemoryError) throw e
            DocInfo(null, null, null, null)
        }
    }

    private fun save(bmp: Bitmap, name: String): Boolean = try {
        files.thumbFile(name).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 82, it) }
    } finally { bmp.recycle() }

    companion object {
        /** Decodes an image no wider than [maxSide], refusing absurd dimensions (decompression bombs). */
        fun decodeSampled(file: File, maxSide: Int): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            val w = bounds.outWidth; val h = bounds.outHeight
            if (w <= 0 || h <= 0) return null
            if (w.toLong() * h > Limits.MAX_IMAGE_PIXELS * 4) return null
            var sample = 1
            while (w / (sample * 2) >= maxSide || h / (sample * 2) >= maxSide) sample *= 2
            return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    }
}

/** PdfRenderer is not thread-safe and allows one open page at a time; all users share this lock. */
object PdfLock {
    private val lock = Any()
    fun <T> withLock(block: () -> T): T = synchronized(lock) { block() }
}
