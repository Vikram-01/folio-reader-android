package com.folio.reader.core.format

/**
 * Decides a file's format from its leading bytes plus name/MIME hints.
 * Binary formats must carry the right signature, so a renamed executable or a
 * mislabelled file is never handed to a renderer that expects something else.
 */
object FormatDetector {

    sealed interface Result {
        data class Ok(val format: DocFormat) : Result
        data class Unsupported(val format: DocFormat) : Result
        data class Rejected(val reason: String) : Result
    }

    fun detect(header: ByteArray, fileName: String?, mime: String?): Result {
        val sniffed = sniff(header)
        val byName = DocFormat.fromFileName(fileName)
        val byMime = DocFormat.fromMime(mime)
        val claimed = byName ?: byMime

        val format: DocFormat = when {
            sniffed == Sniff.PDF -> DocFormat.PDF
            sniffed == Sniff.PNG -> DocFormat.PNG
            sniffed == Sniff.JPEG -> DocFormat.JPEG
            sniffed == Sniff.GIF -> DocFormat.GIF
            sniffed == Sniff.WEBP -> DocFormat.WEBP
            sniffed == Sniff.BMP -> DocFormat.BMP
            sniffed == Sniff.TIFF -> DocFormat.TIFF
            sniffed == Sniff.EXECUTABLE -> return Result.Rejected("Executable files can't be added to the library.")
            sniffed == Sniff.ZIP -> when {
                isEpub(header) -> DocFormat.EPUB
                claimed != null && claimed in ZIP_CONTAINERS -> claimed
                else -> return Result.Rejected("This archive isn't a supported document.")
            }
            sniffed == Sniff.OLE -> if (claimed == DocFormat.XLS || claimed == DocFormat.PPT) claimed ?: DocFormat.DOC else DocFormat.DOC
            claimed == null -> if (looksLikeText(header)) DocFormat.TXT
                else return Result.Rejected("Unknown file type.")
            // Name says binary format but bytes don't match its signature.
            claimed in SIGNED_FORMATS -> return Result.Rejected("The file content doesn't match its ${claimed.label} extension.")
            claimed.isTextual || claimed == DocFormat.RTF || claimed == DocFormat.FB2 ->
                if (looksLikeText(header)) claimed else return Result.Rejected("This file isn't valid text.")
            else -> claimed
        }
        return if (format.supported) Result.Ok(format) else Result.Unsupported(format)
    }

    private enum class Sniff { PDF, PNG, JPEG, GIF, WEBP, BMP, TIFF, ZIP, OLE, EXECUTABLE, NONE }

    private val ZIP_CONTAINERS = setOf(DocFormat.EPUB, DocFormat.DOCX, DocFormat.XLSX, DocFormat.PPTX, DocFormat.ODF)
    private val SIGNED_FORMATS = setOf(
        DocFormat.PDF, DocFormat.EPUB, DocFormat.PNG, DocFormat.JPEG, DocFormat.GIF,
        DocFormat.WEBP, DocFormat.BMP, DocFormat.DOCX, DocFormat.XLSX, DocFormat.PPTX, DocFormat.ODF,
    )

    private fun ByteArray.startsWith(vararg b: Int): Boolean =
        size >= b.size && b.indices.all { this[it] == b[it].toByte() }

    private fun sniff(h: ByteArray): Sniff = when {
        // PDF header may be preceded by a little junk; spec allows it within the first 1024 bytes.
        indexOf(h, "%PDF-".toByteArray(), 1024) >= 0 -> Sniff.PDF
        h.startsWith(0x89, 0x50, 0x4E, 0x47) -> Sniff.PNG
        h.startsWith(0xFF, 0xD8, 0xFF) -> Sniff.JPEG
        h.startsWith(0x47, 0x49, 0x46, 0x38) -> Sniff.GIF
        h.size >= 12 && h.startsWith(0x52, 0x49, 0x46, 0x46) &&
            h[8] == 'W'.code.toByte() && h[9] == 'E'.code.toByte() && h[10] == 'B'.code.toByte() && h[11] == 'P'.code.toByte() -> Sniff.WEBP
        h.startsWith(0x42, 0x4D) && h.size > 14 -> Sniff.BMP
        h.startsWith(0x49, 0x49, 0x2A, 0x00) || h.startsWith(0x4D, 0x4D, 0x00, 0x2A) -> Sniff.TIFF
        h.startsWith(0x50, 0x4B, 0x03, 0x04) -> Sniff.ZIP
        h.startsWith(0xD0, 0xCF, 0x11, 0xE0) -> Sniff.OLE
        h.startsWith(0x4D, 0x5A) || h.startsWith(0x7F, 0x45, 0x4C, 0x46) ||
            h.startsWith(0x64, 0x65, 0x78, 0x0A) || h.startsWith(0xCA, 0xFE, 0xBA, 0xBE) -> Sniff.EXECUTABLE
        else -> Sniff.NONE
    }

    /** EPUB OCF: first entry is an uncompressed "mimetype" file containing application/epub+zip. */
    private fun isEpub(h: ByteArray): Boolean = indexOf(h, "application/epub+zip".toByteArray(), 200) >= 0

    fun looksLikeText(h: ByteArray): Boolean {
        if (h.isEmpty()) return true
        var control = 0
        for (b in h) {
            val v = b.toInt() and 0xFF
            if (v == 0) return false
            if (v < 0x09 || (v in 0x0E..0x1F)) control++
        }
        return control * 100 / h.size < 2
    }

    private fun indexOf(h: ByteArray, needle: ByteArray, limit: Int): Int {
        val max = minOf(h.size - needle.size, limit)
        outer@ for (i in 0..max) {
            for (j in needle.indices) if (h[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }
}
