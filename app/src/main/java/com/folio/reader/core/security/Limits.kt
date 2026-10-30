package com.folio.reader.core.security

/** Hard ceilings applied to all untrusted input. */
object Limits {
    const val MAX_IMPORT_BYTES: Long = 1L shl 30            // 1 GiB per document
    const val MAX_DOWNLOAD_BYTES: Long = 1L shl 30          // 1 GiB per browser download
    const val MAX_TEXT_LOAD_BYTES: Long = 32L shl 20        // text readers load at most 32 MiB
    const val MAX_TABLE_ROWS: Int = 200_000
    const val MAX_ZIP_ENTRIES: Int = 20_000
    const val MAX_ZIP_TOTAL_BYTES: Long = 1L shl 30         // total uncompressed EPUB / backup size
    const val MAX_ZIP_ENTRY_BYTES: Long = 256L shl 20
    const val MAX_ZIP_RATIO: Long = 200                     // uncompressed / compressed
    const val MAX_INDEX_CHARS_PER_DOC: Int = 8_000_000      // full-text index cap per document
    const val MAX_IMAGE_PIXELS: Long = 80_000_000L          // ~ 9000 x 9000
    const val MAX_SAVED_PAGE_BYTES: Int = 8 shl 20          // offline web page HTML
    const val MAX_BOOKMARK_IMPORT_BYTES: Long = 50L shl 20
    const val MAX_TABS: Int = 200
}
