package com.folio.reader.ui.reader

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.folio.reader.core.format.DocFormat
import com.folio.reader.core.format.ReaderKind
import com.folio.reader.core.security.Limits
import com.folio.reader.data.files.OfficeReader
import com.folio.reader.data.files.TextFormats
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import org.jsoup.Jsoup
import org.jsoup.safety.Safelist
import java.io.File
import java.io.IOException
import java.io.InputStream

/** Where a PDF's bytes come from: a library file, or another app's content:// Uri (read in place, never copied). */
sealed interface PdfSource {
    data class FromFile(val file: File) : PdfSource
    data class FromUri(val uri: Uri) : PdfSource
}

fun PdfSource.open(context: Context): ParcelFileDescriptor = when (this) {
    is PdfSource.FromFile -> ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    is PdfSource.FromUri -> context.contentResolver.openFileDescriptor(uri, "r") ?: throw IOException("Couldn't open the file.")
}

/** Turns raw document data into [ReaderContent]. Shared by the library reader and the no-storage viewer. */
object ContentBuilder {

    private val prettyJson = Json { prettyPrint = true }
    private val safelist: Safelist = Safelist.relaxed().removeTags("img").addTags("section", "hr", "s", "mark")

    fun sanitize(html: String): String = Jsoup.clean(html, safelist)

    /** TEXT, MARKDOWN, HTML and TABLE formats from already-decoded text. */
    fun fromText(format: DocFormat, raw: String, truncated: Boolean): ReaderContent = when (format.kind) {
        ReaderKind.TEXT -> {
            var text = if (format == DocFormat.RTF) TextFormats.rtfToPlain(raw) else raw
            if (format == DocFormat.JSON && text.length < 4_000_000) {
                text = runCatching { prettyJson.encodeToString(JsonElement.serializer(), Json.parseToJsonElement(text)) }.getOrDefault(text)
            }
            ReaderContent.Text(text.lines(), format != DocFormat.TXT && format != DocFormat.RTF, truncated)
        }
        ReaderKind.MARKDOWN -> {
            val ext = listOf(TablesExtension.create())
            val html = HtmlRenderer.builder().extensions(ext).build().render(Parser.builder().extensions(ext).build().parse(raw))
            ReaderContent.Html(sanitize(html))
        }
        ReaderKind.HTML -> ReaderContent.Html(sanitize(Jsoup.parse(raw).body()?.html() ?: ""))
        ReaderKind.TABLE -> {
            val rows = TextFormats.parseDelimited(raw, if (format == DocFormat.TSV) '\t' else ',')
            ReaderContent.Table(rows, truncated || rows.size >= Limits.MAX_TABLE_ROWS)
        }
        else -> ReaderContent.Unsupported(format)
    }

    /** DOCX / XLSX / PPTX, streamed from [input]. */
    fun fromOffice(format: DocFormat, input: InputStream): ReaderContent = when (val p = OfficeReader.parse(format, input)) {
        is OfficeReader.Parsed.Html -> ReaderContent.Html(sanitize(p.html))
        is OfficeReader.Parsed.Sheets -> {
            val sheets = p.sheets.filter { it.rows.isNotEmpty() }
            when {
                sheets.isEmpty() -> ReaderContent.Html("<p><em>This workbook has no data.</em></p>")
                sheets.size == 1 -> ReaderContent.Table(sheets[0].rows, sheets[0].truncated)
                else -> ReaderContent.Html(sanitize(sheetsHtml(sheets)))
            }
        }
    }

    private fun sheetsHtml(sheets: List<OfficeReader.Sheet>): String = buildString {
        val maxRows = 5_000
        for (s in sheets) {
            append("<h2>").append(esc(s.name)).append("</h2><table>")
            s.rows.take(maxRows).forEachIndexed { i, r ->
                append("<tr>")
                val tag = if (i == 0) "th" else "td"
                r.forEach { c -> append('<').append(tag).append('>').append(esc(c)).append("</").append(tag).append('>') }
                append("</tr>")
            }
            append("</table>")
            if (s.truncated || s.rows.size > maxRows) append("<p><em>Only the first ${minOf(s.rows.size, maxRows)} rows of this sheet are shown.</em></p>")
        }
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
