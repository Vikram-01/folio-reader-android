package com.folio.reader.data.files

import android.util.Xml
import com.folio.reader.core.format.DocFormat
import com.folio.reader.core.security.Limits
import com.folio.reader.core.security.SafeZip
import com.folio.reader.core.security.UnsafeArchiveException
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Minimal, dependency-free readers for Office Open XML (DOCX, XLSX, PPTX).
 *
 * The package is streamed straight from the source [InputStream] (a library
 * file or another app's content:// stream) and only the XML parts needed for
 * text are kept, in memory, under hard size caps. Nothing is written to disk.
 * The pull parser does not resolve external entities, so XXE is not possible.
 *
 * Output is simple HTML (headings, paragraphs, bold/italic/underline, lists,
 * tables); callers sanitise it with Jsoup before display.
 */
object OfficeReader {

    /** One spreadsheet: its name and rows (first row shown as the header). */
    data class Sheet(val name: String, val rows: List<List<String>>, val truncated: Boolean)

    sealed interface Parsed {
        data class Html(val html: String, val plainBlocks: List<Pair<String, String>>) : Parsed
        data class Sheets(val sheets: List<Sheet>) : Parsed
    }

    private const val MAX_PART_BYTES: Long = 64L shl 20
    private const val MAX_TOTAL_BYTES: Long = 256L shl 20
    private const val MAX_ROWS_PER_SHEET = 50_000
    private const val MAX_COLS = 200

    fun parse(format: DocFormat, input: InputStream): Parsed = when (format) {
        DocFormat.DOCX -> docx(readParts(input) { it == "word/document.xml" })
        DocFormat.PPTX -> pptx(readParts(input) { it.startsWith("ppt/slides/slide") && it.endsWith(".xml") })
        DocFormat.XLSX -> xlsx(readParts(input) {
            it == "xl/workbook.xml" || it == "xl/sharedStrings.xml" || it == "xl/_rels/workbook.xml.rels" ||
                (it.startsWith("xl/worksheets/") && it.endsWith(".xml"))
        })
        else -> throw IllegalArgumentException("${format.label} isn't an Office Open XML format.")
    }

    /** Plain text of a parsed document as (label, text) blocks, for search indexing and read-aloud. */
    fun plainBlocks(p: Parsed): List<Pair<String, String>> = when (p) {
        is Parsed.Html -> p.plainBlocks
        is Parsed.Sheets -> p.sheets.flatMap { s ->
            s.rows.chunked(100).mapIndexed { i, chunk ->
                "${s.name} · rows ${i * 100 + 1}–${i * 100 + chunk.size}" to chunk.joinToString("\n") { it.joinToString(" · ") }
            }
        }
    }

    // ---------------------------------------------------------------- zip

    private fun readParts(input: InputStream, wanted: (String) -> Boolean): Map<String, ByteArray> {
        val parts = HashMap<String, ByteArray>()
        var total = 0L
        var entries = 0
        ZipInputStream(input).use { zin ->
            while (true) {
                val e = zin.nextEntry ?: break
                if (++entries > Limits.MAX_ZIP_ENTRIES) throw UnsafeArchiveException("The document has too many parts.")
                val name = e.name.trimStart('/')
                if (e.isDirectory || !SafeZip.isSafeEntryName(name) || !wanted(name)) continue
                val out = ByteArrayOutputStream()
                total += SafeZip.copyBounded(zin, out, minOf(MAX_PART_BYTES, MAX_TOTAL_BYTES - total))
                parts[name] = out.toByteArray()
            }
        }
        if (parts.isEmpty()) throw IllegalStateException("This file doesn't contain any readable content.")
        return parts
    }

    private fun parser(bytes: ByteArray): XmlPullParser = Xml.newPullParser().apply {
        setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        setInput(ByteArrayInputStream(bytes), null)
    }

    /** Element name without its namespace prefix ("w:p" -> "p"). */
    private val XmlPullParser.local: String get() = name.substringAfter(':')

    private fun XmlPullParser.attr(localName: String): String? {
        for (i in 0 until attributeCount) if (getAttributeName(i).substringAfter(':') == localName) return getAttributeValue(i)
        return null
    }

    private fun esc(s: String): String = buildString(s.length) {
        for (c in s) {
            when (c) {
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '&' -> append("&amp;")
                '"' -> append("&quot;")
                else -> append(c)
            }
        }
    }

    // ---------------------------------------------------------------- DOCX

    private fun docx(parts: Map<String, ByteArray>): Parsed {
        val xml = parts["word/document.xml"] ?: throw IllegalStateException("This Word file has no document body.")
        val p = parser(xml)
        val html = StringBuilder()
        val plain = ArrayList<Pair<String, String>>()
        val plainBuf = StringBuilder()
        var section = 1

        // Paragraph state
        val para = StringBuilder()
        val paraText = StringBuilder()
        var style = ""
        var list = false
        // Run state
        var bold = false; var italic = false; var underline = false
        var inRunProps = false
        var inTabStops = false
        // Table state
        var tableDepth = 0
        var cell: StringBuilder? = null
        var inText = false

        fun flushPlain(force: Boolean = false) {
            if (plainBuf.length > 4000 || (force && plainBuf.isNotBlank())) {
                plain += "Part $section" to plainBuf.toString()
                plainBuf.setLength(0); section++
            }
        }

        while (p.next() != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> when (p.local) {
                    "p" -> { para.setLength(0); paraText.setLength(0); style = ""; list = false }
                    "pStyle" -> style = p.attr("val").orEmpty()
                    "numPr" -> list = true
                    "r" -> { bold = false; italic = false; underline = false }
                    "rPr" -> inRunProps = true
                    "b" -> if (inRunProps) bold = p.attr("val").let { it == null || it == "1" || it == "true" }
                    "i" -> if (inRunProps) italic = p.attr("val").let { it == null || it == "1" || it == "true" }
                    "u" -> if (inRunProps) underline = p.attr("val").let { it != null && it != "none" }
                    "t" -> inText = true
                    "tabs" -> inTabStops = true
                    "tab" -> if (!inRunProps && !inTabStops) { para.append("&emsp;"); paraText.append('\t') }
                    "br", "cr" -> { para.append("<br>"); paraText.append('\n') }
                    "tbl" -> { if (tableDepth == 0) html.append("<table>"); tableDepth++ }
                    "tr" -> if (tableDepth == 1) html.append("<tr>")
                    "tc" -> if (tableDepth == 1) cell = StringBuilder()
                }
                XmlPullParser.TEXT -> if (inText) {
                    val t = p.text ?: ""
                    var s = esc(t)
                    if (underline) s = "<u>$s</u>"
                    if (italic) s = "<em>$s</em>"
                    if (bold) s = "<strong>$s</strong>"
                    para.append(s); paraText.append(t)
                }
                XmlPullParser.END_TAG -> when (p.local) {
                    "t" -> inText = false
                    "rPr" -> inRunProps = false
                    "tabs" -> inTabStops = false
                    "p" -> {
                        val body = para.toString()
                        val target = cell
                        if (target != null) {
                            if (target.isNotEmpty()) target.append("<br>")
                            target.append(body)
                        } else if (paraText.isNotBlank()) {
                            val h = headingLevel(style)
                            when {
                                h != null -> html.append("<h$h>").append(body).append("</h$h>")
                                list -> html.append("<p>&bull;&nbsp;").append(body).append("</p>")
                                else -> html.append("<p>").append(body).append("</p>")
                            }
                        }
                        if (paraText.isNotBlank()) { plainBuf.append(paraText).append('\n'); flushPlain() }
                    }
                    "tc" -> if (tableDepth == 1) { html.append("<td>").append(cell ?: "").append("</td>"); cell = null }
                    "tr" -> if (tableDepth == 1) html.append("</tr>")
                    "tbl" -> { tableDepth--; if (tableDepth == 0) html.append("</table>") }
                }
            }
        }
        flushPlain(force = true)
        if (html.isEmpty()) html.append("<p><em>This document has no text.</em></p>")
        return Parsed.Html(html.toString(), plain)
    }

    private fun headingLevel(style: String): Int? {
        val s = style.lowercase()
        if (s == "title") return 1
        if (s == "subtitle") return 2
        val m = Regex("^heading\\s*([1-6])$").find(s) ?: return null
        return m.groupValues[1].toInt()
    }

    // ---------------------------------------------------------------- PPTX

    private fun pptx(parts: Map<String, ByteArray>): Parsed {
        val slides = parts.keys
            .mapNotNull { k -> Regex("^ppt/slides/slide(\\d+)\\.xml$").find(k)?.groupValues?.get(1)?.toIntOrNull()?.let { it to k } }
            .sortedBy { it.first }
        if (slides.isEmpty()) throw IllegalStateException("This presentation has no slides.")
        val html = StringBuilder()
        val plain = ArrayList<Pair<String, String>>()
        slides.forEachIndexed { i, (_, key) ->
            val p = parser(parts.getValue(key))
            val paragraphs = ArrayList<String>()
            val cur = StringBuilder()
            var inText = false
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                when (p.eventType) {
                    XmlPullParser.START_TAG -> when (p.local) {
                        "p" -> cur.setLength(0)
                        "t" -> inText = true
                        "br" -> cur.append('\n')
                    }
                    XmlPullParser.TEXT -> if (inText) cur.append(p.text ?: "")
                    XmlPullParser.END_TAG -> when (p.local) {
                        "t" -> inText = false
                        "p" -> if (cur.isNotBlank()) paragraphs += cur.toString()
                    }
                }
            }
            html.append("<section class=\"slide\"><h2>Slide ${i + 1}</h2>")
            if (paragraphs.isEmpty()) html.append("<p><em>No text on this slide.</em></p>")
            paragraphs.forEachIndexed { j, t ->
                val body = esc(t).replace("\n", "<br>")
                if (j == 0) html.append("<h3>").append(body).append("</h3>") else html.append("<p>").append(body).append("</p>")
            }
            html.append("</section><hr>")
            plain += "Slide ${i + 1}" to paragraphs.joinToString("\n")
        }
        return Parsed.Html(html.toString(), plain)
    }

    // ---------------------------------------------------------------- XLSX

    private fun xlsx(parts: Map<String, ByteArray>): Parsed {
        val shared = parts["xl/sharedStrings.xml"]?.let { sharedStrings(it) } ?: emptyList()
        val order = workbookSheets(parts)
        val sheetParts = if (order.isNotEmpty()) order.mapNotNull { (name, path) -> parts[path]?.let { name to it } }
        else parts.keys.filter { it.startsWith("xl/worksheets/sheet") }.sortedBy { Regex("\\d+").find(it)?.value?.toIntOrNull() ?: 0 }
            .mapIndexed { i, k -> "Sheet ${i + 1}" to parts.getValue(k) }
        if (sheetParts.isEmpty()) throw IllegalStateException("This workbook has no sheets.")
        return Parsed.Sheets(sheetParts.map { (name, bytes) -> sheet(name, bytes, shared) })
    }

    private fun sharedStrings(bytes: ByteArray): List<String> {
        val p = parser(bytes)
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var inText = false
        var inPhonetic = false
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> when (p.local) {
                    "si" -> cur.setLength(0)
                    "rPh" -> inPhonetic = true
                    "t" -> inText = !inPhonetic
                }
                XmlPullParser.TEXT -> if (inText) cur.append(p.text ?: "")
                XmlPullParser.END_TAG -> when (p.local) {
                    "t" -> inText = false
                    "rPh" -> inPhonetic = false
                    "si" -> out += cur.toString()
                }
            }
        }
        return out
    }

    /** Sheet names in workbook order, mapped to their part paths through the workbook relationships. */
    private fun workbookSheets(parts: Map<String, ByteArray>): List<Pair<String, String>> {
        val wb = parts["xl/workbook.xml"] ?: return emptyList()
        val rels = HashMap<String, String>()
        parts["xl/_rels/workbook.xml.rels"]?.let { bytes ->
            val p = parser(bytes)
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                if (p.eventType == XmlPullParser.START_TAG && p.local == "Relationship") {
                    val id = p.attr("Id") ?: continue
                    val target = p.attr("Target") ?: continue
                    rels[id] = if (target.startsWith("/")) target.trimStart('/') else "xl/" + target.removePrefix("./")
                }
            }
        }
        val out = ArrayList<Pair<String, String>>()
        val p = parser(wb)
        var index = 0
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            if (p.eventType == XmlPullParser.START_TAG && p.local == "sheet") {
                index++
                val name = p.attr("name") ?: "Sheet $index"
                val path = p.attr("id")?.let { rels[it] } ?: "xl/worksheets/sheet$index.xml"
                out += name to path
            }
        }
        return out
    }

    private fun columnIndex(ref: String?): Int? {
        if (ref.isNullOrEmpty()) return null
        var n = 0
        for (c in ref) {
            if (c !in 'A'..'Z') break
            n = n * 26 + (c - 'A' + 1)
        }
        return if (n == 0) null else n - 1
    }

    private fun sheet(name: String, bytes: ByteArray, shared: List<String>): Sheet {
        val p = parser(bytes)
        val rows = ArrayList<List<String>>()
        var row: ArrayList<String>? = null
        var col = 0
        var type: String? = null
        val value = StringBuilder()
        var inValue = false
        var truncated = false
        loop@ while (p.next() != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> when (p.local) {
                    "row" -> { if (rows.size >= MAX_ROWS_PER_SHEET) { truncated = true; break@loop }; row = ArrayList(); col = 0 }
                    "c" -> { col = columnIndex(p.attr("r")) ?: (row?.size ?: 0); type = p.attr("t"); value.setLength(0) }
                    "v", "t" -> inValue = true
                }
                XmlPullParser.TEXT -> if (inValue) value.append(p.text ?: "")
                XmlPullParser.END_TAG -> when (p.local) {
                    "v", "t" -> inValue = false
                    "c" -> {
                        val r = row ?: continue@loop
                        if (col >= MAX_COLS) continue@loop
                        val raw = value.toString()
                        val text = when (type) {
                            "s" -> raw.trim().toIntOrNull()?.let { shared.getOrNull(it) } ?: ""
                            "b" -> if (raw.trim() == "1") "TRUE" else "FALSE"
                            else -> formatNumber(raw)
                        }
                        while (r.size < col) r += ""
                        if (r.size == col) r += text else r[col] = text
                    }
                    "row" -> {
                        val done = row
                        if (done != null && done.any { c -> c.isNotBlank() }) rows.add(done)
                        row = null
                    }
                }
            }
        }
        return Sheet(name, rows, truncated)
    }

    /** Shows "3.0" as "3" and trims float noise like 0.30000000000000004, leaving text untouched. */
    private fun formatNumber(raw: String): String {
        val d = raw.toDoubleOrNull() ?: return raw
        if (d.isNaN() || d.isInfinite()) return raw
        if (d == Math.rint(d) && kotlin.math.abs(d) < 1e15) return d.toLong().toString()
        return java.math.BigDecimal(d).round(java.math.MathContext(12)).stripTrailingZeros().toPlainString()
    }
}
