package com.folio.reader.core.format

/** How a format is displayed. */
enum class ReaderKind { PDF, EPUB, TEXT, MARKDOWN, HTML, TABLE, IMAGE, OFFICE }

/**
 * Every format Folio knows about. [supported] = false means the format is
 * recognised so the user gets a clear message, but no reader exists yet.
 * See docs/FORMAT_COMPATIBILITY.md.
 */
enum class DocFormat(
    val label: String,
    val extensions: Set<String>,
    val mime: String,
    val kind: ReaderKind?,
    val supported: Boolean = kind != null,
) {
    PDF("PDF", setOf("pdf"), "application/pdf", ReaderKind.PDF),
    EPUB("EPUB", setOf("epub"), "application/epub+zip", ReaderKind.EPUB),
    TXT("Text", setOf("txt", "text"), "text/plain", ReaderKind.TEXT),
    LOG("Log", setOf("log"), "text/plain", ReaderKind.TEXT),
    JSON("JSON", setOf("json"), "application/json", ReaderKind.TEXT),
    XML("XML", setOf("xml"), "application/xml", ReaderKind.TEXT),
    YAML("YAML", setOf("yaml", "yml"), "application/yaml", ReaderKind.TEXT),
    RTF("RTF", setOf("rtf"), "application/rtf", ReaderKind.TEXT),
    MARKDOWN("Markdown", setOf("md", "markdown"), "text/markdown", ReaderKind.MARKDOWN),
    HTML("HTML", setOf("html", "htm", "xhtml"), "text/html", ReaderKind.HTML),
    CSV("CSV", setOf("csv"), "text/csv", ReaderKind.TABLE),
    TSV("TSV", setOf("tsv", "tab"), "text/tab-separated-values", ReaderKind.TABLE),
    JPEG("JPEG", setOf("jpg", "jpeg"), "image/jpeg", ReaderKind.IMAGE),
    PNG("PNG", setOf("png"), "image/png", ReaderKind.IMAGE),
    WEBP("WebP", setOf("webp"), "image/webp", ReaderKind.IMAGE),
    GIF("GIF", setOf("gif"), "image/gif", ReaderKind.IMAGE),
    BMP("BMP", setOf("bmp"), "image/bmp", ReaderKind.IMAGE),

    // Office documents are laid out by the bundled LibreOffice engine and shown as pages.
    // (OOXML also has a built-in text view used when the engine isn't available.)
    DOCX("Word", setOf("docx"), "application/vnd.openxmlformats-officedocument.wordprocessingml.document", ReaderKind.OFFICE),
    XLSX("Excel", setOf("xlsx", "xlsm"), "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", ReaderKind.OFFICE),
    PPTX("PowerPoint", setOf("pptx"), "application/vnd.openxmlformats-officedocument.presentationml.presentation", ReaderKind.OFFICE),

    DOC("Word 97", setOf("doc"), "application/msword", ReaderKind.OFFICE),
    XLS("Excel 97", setOf("xls"), "application/vnd.ms-excel", ReaderKind.OFFICE),
    PPT("PowerPoint 97", setOf("ppt"), "application/vnd.ms-powerpoint", ReaderKind.OFFICE),
    ODF("OpenDocument", setOf("odt", "ods", "odp"), "application/vnd.oasis.opendocument.text", ReaderKind.OFFICE),

    // Recognised but not yet readable in this version.
    MOBI("Kindle", setOf("mobi", "azw", "azw3"), "application/x-mobipocket-ebook", null),
    FB2("FictionBook", setOf("fb2"), "application/x-fictionbook+xml", null),
    DJVU("DjVu", setOf("djvu", "djv"), "image/vnd.djvu", null),
    TIFF("TIFF", setOf("tif", "tiff"), "image/tiff", null);

    val isTextual: Boolean get() = kind == ReaderKind.TEXT || kind == ReaderKind.MARKDOWN || kind == ReaderKind.HTML || kind == ReaderKind.TABLE

    companion object {
        fun fromExtension(ext: String?): DocFormat? {
            val e = ext?.lowercase()?.trim() ?: return null
            return entries.firstOrNull { e in it.extensions }
        }

        fun fromFileName(name: String?): DocFormat? = fromExtension(name?.substringAfterLast('.', ""))

        fun fromMime(mime: String?): DocFormat? {
            val m = mime?.lowercase()?.substringBefore(';')?.trim() ?: return null
            return entries.firstOrNull { it.mime == m } ?: when {
                m == "image/jpg" -> JPEG
                m == "text/xml" -> XML
                m == "text/x-markdown" -> MARKDOWN
                m == "application/x-yaml" || m == "text/yaml" -> YAML
                m == "application/xhtml+xml" -> HTML
                m == "application/vnd.oasis.opendocument.spreadsheet" || m == "application/vnd.oasis.opendocument.presentation" -> ODF
                m == "text/rtf" -> RTF
                else -> null
            }
        }

        val supportedMimeTypes: Array<String> =
            arrayOf("application/pdf", "application/epub+zip", "text/*", "image/*", "application/json",
                "application/xml", "application/rtf", "application/yaml", "application/xhtml+xml",
                DOCX.mime, XLSX.mime, PPTX.mime, DOC.mime, XLS.mime, PPT.mime,
                "application/vnd.oasis.opendocument.text", "application/vnd.oasis.opendocument.spreadsheet",
                "application/vnd.oasis.opendocument.presentation", "application/octet-stream")
    }
}
