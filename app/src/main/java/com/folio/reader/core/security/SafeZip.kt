package com.folio.reader.core.security

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipFile

class UnsafeArchiveException(message: String) : IOException(message)

/**
 * Archive helpers hardened against path traversal ("zip slip") and
 * decompression bombs. Sizes declared in the archive are not trusted:
 * bytes are counted while they are actually inflated.
 */
object SafeZip {

    /** Rejects absolute paths, drive letters, backslashes and any ".." segment. */
    fun isSafeEntryName(name: String): Boolean {
        if (name.isEmpty() || name.length > 1024) return false
        if (name.startsWith("/") || name.contains('\\') || name.contains('\u0000')) return false
        if (name.length >= 2 && name[1] == ':') return false
        return name.split('/').none { it == ".." }
    }

    /** Resolves [entryName] under [root], refusing anything that escapes it. */
    fun resolveInside(root: File, entryName: String): File {
        if (!isSafeEntryName(entryName)) throw UnsafeArchiveException("Unsafe path in archive: $entryName")
        val base = root.canonicalFile
        val target = File(base, entryName).canonicalFile
        if (target != base && !target.path.startsWith(base.path + File.separator)) {
            throw UnsafeArchiveException("Archive entry escapes target folder: $entryName")
        }
        return target
    }

    /** Validates entry count and declared sizes before anything is inflated. */
    fun preflight(
        zip: ZipFile,
        maxEntries: Int = Limits.MAX_ZIP_ENTRIES,
        maxTotal: Long = Limits.MAX_ZIP_TOTAL_BYTES,
        maxEntry: Long = Limits.MAX_ZIP_ENTRY_BYTES,
    ) {
        var count = 0
        var total = 0L
        for (e in zip.entries()) {
            count++
            if (count > maxEntries) throw UnsafeArchiveException("Archive has too many entries.")
            if (!isSafeEntryName(e.name)) throw UnsafeArchiveException("Unsafe path in archive: ${e.name}")
            if (e.size > maxEntry) throw UnsafeArchiveException("Archive entry is too large.")
            if (e.size > 0) total += e.size
            if (total > maxTotal) throw UnsafeArchiveException("Archive expands beyond the size limit.")
            if (e.compressedSize > 0 && e.size > (1L shl 20) && e.size / e.compressedSize > Limits.MAX_ZIP_RATIO) {
                throw UnsafeArchiveException("Archive compression ratio looks like a zip bomb.")
            }
        }
    }

    /** Copies at most [limit] bytes; throws if the stream holds more. */
    fun copyBounded(input: InputStream, output: OutputStream, limit: Long): Long {
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > limit) throw UnsafeArchiveException("Content exceeds the allowed size.")
            output.write(buf, 0, n)
        }
        return total
    }

    /** Reads a single entry into memory with a hard cap. */
    fun readEntry(zip: ZipFile, name: String, limit: Long = Limits.MAX_ZIP_ENTRY_BYTES): ByteArray? {
        if (!isSafeEntryName(name)) return null
        val entry = zip.getEntry(name) ?: return null
        val out = java.io.ByteArrayOutputStream()
        zip.getInputStream(entry).use { copyBounded(it, out, limit) }
        return out.toByteArray()
    }

    /** Extracts every entry under [dest] with traversal and size checks. */
    fun extractAll(zipFile: File, dest: File) {
        ZipFile(zipFile).use { zip ->
            preflight(zip)
            dest.mkdirs()
            var total = 0L
            for (e in zip.entries()) {
                val target = resolveInside(dest, e.name)
                if (e.isDirectory) { target.mkdirs(); continue }
                target.parentFile?.mkdirs()
                zip.getInputStream(e).use { input ->
                    target.outputStream().use { out ->
                        total += copyBounded(input, out, minOf(Limits.MAX_ZIP_ENTRY_BYTES, Limits.MAX_ZIP_TOTAL_BYTES - total))
                    }
                }
            }
        }
    }
}
