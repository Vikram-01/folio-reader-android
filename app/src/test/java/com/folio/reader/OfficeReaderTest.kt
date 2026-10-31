package com.folio.reader

import com.folio.reader.core.format.DocFormat
import com.folio.reader.data.files.OfficeReader
import com.folio.reader.data.files.OfficeReader.Parsed
import com.folio.reader.ui.reader.ContentBuilder
import com.folio.reader.ui.reader.ReaderContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class OfficeReaderTest {

    private fun zip(vararg parts: Pair<String, String>): ByteArrayInputStream {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            z.putNextEntry(ZipEntry("[Content_Types].xml")); z.write("<Types/>".toByteArray()); z.closeEntry()
            for ((name, body) in parts) { z.putNextEntry(ZipEntry(name)); z.write(body.toByteArray()); z.closeEntry() }
        }
        return ByteArrayInputStream(out.toByteArray())
    }

    private val w = "xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\""

    @Test fun docxHeadingsFormattingListsAndTables() {
        val doc = """<w:document $w><w:body>
            <w:p><w:pPr><w:pStyle w:val="Heading1"/><w:tabs><w:tab w:val="left" w:pos="720"/></w:tabs></w:pPr><w:r><w:t>Chapter One</w:t></w:r></w:p>
            <w:p><w:r><w:rPr><w:b/></w:rPr><w:t>Bold</w:t></w:r><w:r><w:t xml:space="preserve"> and </w:t></w:r><w:r><w:rPr><w:i/></w:rPr><w:t>&lt;italic&gt;</w:t></w:r></w:p>
            <w:p><w:pPr><w:numPr><w:ilvl w:val="0"/><w:numId w:val="1"/></w:numPr></w:pPr><w:r><w:t>Item</w:t></w:r></w:p>
            <w:tbl><w:tr><w:tc><w:p><w:r><w:t>A1</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>B1</w:t></w:r></w:p></w:tc></w:tr></w:tbl>
            </w:body></w:document>"""
        val p = OfficeReader.parse(DocFormat.DOCX, zip("word/document.xml" to doc)) as Parsed.Html
        assertTrue(p.html, p.html.contains("<h1>Chapter One</h1>"))
        assertTrue(p.html.contains("<strong>Bold</strong>"))
        assertTrue(p.html.contains("<em>&lt;italic&gt;</em>"))
        assertTrue(p.html.contains("&bull;&nbsp;Item"))
        assertTrue(p.html.contains("<table><tr><td>A1</td><td>B1</td></tr></table>"))
        assertFalse("tab stops are not tabs", p.html.contains("Chapter One&emsp;") || p.html.contains("&emsp;Chapter"))
        assertTrue(OfficeReader.plainBlocks(p).joinToString { it.second }.contains("Chapter One"))
    }

    @Test fun xlsxSharedStringsNumbersAndSheetOrder() {
        val wb = """<workbook xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets>
            <sheet name="Budget" sheetId="1" r:id="rId2"/><sheet name="Notes" sheetId="2" r:id="rId1"/></sheets></workbook>"""
        val rels = """<Relationships><Relationship Id="rId1" Target="worksheets/sheet2.xml"/><Relationship Id="rId2" Target="worksheets/sheet1.xml"/></Relationships>"""
        val shared = """<sst><si><t>Item</t></si><si><t>Cost</t></si><si><r><t>Rent</t></r></si></sst>"""
        val s1 = """<worksheet><sheetData>
            <row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1" t="s"><v>1</v></c></row>
            <row r="2"><c r="A2" t="s"><v>2</v></c><c r="C2"><v>1200.0</v></c></row>
            <row r="3"><c r="A3" t="inlineStr"><is><t>Tax</t></is></c><c r="B3"><v>0.30000000000000004</v></c></row>
            </sheetData></worksheet>"""
        val s2 = """<worksheet><sheetData><row r="1"><c r="A1" t="b"><v>1</v></c></row></sheetData></worksheet>"""
        val p = OfficeReader.parse(DocFormat.XLSX, zip(
            "xl/workbook.xml" to wb, "xl/_rels/workbook.xml.rels" to rels, "xl/sharedStrings.xml" to shared,
            "xl/worksheets/sheet1.xml" to s1, "xl/worksheets/sheet2.xml" to s2,
        )) as Parsed.Sheets
        assertEquals(listOf("Budget", "Notes"), p.sheets.map { it.name })
        val rows = p.sheets[0].rows
        assertEquals(listOf("Item", "Cost"), rows[0])
        assertEquals(listOf("Rent", "", "1200"), rows[1])
        assertEquals(listOf("Tax", "0.3"), rows[2])
        assertEquals(listOf("TRUE"), p.sheets[1].rows[0])
    }

    @Test fun pptxSlidesInNumericOrder() {
        fun slide(vararg texts: String) = "<p:sld xmlns:p=\"p\" xmlns:a=\"a\"><p:cSld><p:spTree>" +
            texts.joinToString("") { "<p:sp><p:txBody><a:p><a:r><a:t>$it</a:t></a:r></a:p></p:txBody></p:sp>" } + "</p:spTree></p:cSld></p:sld>"
        val p = OfficeReader.parse(DocFormat.PPTX, zip(
            "ppt/slides/slide10.xml" to slide("Ten"),
            "ppt/slides/slide2.xml" to slide("Two", "Detail"),
            "ppt/slides/slide1.xml" to slide("One"),
        )) as Parsed.Html
        val one = p.html.indexOf("One"); val two = p.html.indexOf("Two"); val ten = p.html.indexOf("Ten")
        assertTrue(one in 0 until two && two < ten)
        assertTrue(p.html.contains("<h3>Two</h3><p>Detail</p>"))
        assertEquals(3, OfficeReader.plainBlocks(p).size)
    }

    @Test fun contentBuilderSanitisesAndPicksTableForSingleSheet() {
        val shared = """<sst><si><t>&lt;script&gt;x&lt;/script&gt;</t></si></sst>"""
        val s1 = """<worksheet><sheetData><row r="1"><c r="A1" t="s"><v>0</v></c></row><row r="2"><c r="A2"><v>5</v></c></row></sheetData></worksheet>"""
        val c = ContentBuilder.fromOffice(DocFormat.XLSX, zip("xl/sharedStrings.xml" to shared, "xl/worksheets/sheet1.xml" to s1))
        assertTrue(c is ReaderContent.Table)
        assertEquals("<script>x</script>", (c as ReaderContent.Table).rows[0][0]) // shown as text, never as HTML

        val doc = """<w:document $w><w:body><w:p><w:r><w:t>Hello</w:t></w:r></w:p></w:body></w:document>"""
        val h = ContentBuilder.fromOffice(DocFormat.DOCX, zip("word/document.xml" to doc)) as ReaderContent.Html
        assertTrue(h.html.contains("Hello"))
    }

    @Test(expected = IllegalStateException::class)
    fun emptyPackageFails() {
        OfficeReader.parse(DocFormat.DOCX, zip("docProps/app.xml" to "<x/>"))
    }
}
