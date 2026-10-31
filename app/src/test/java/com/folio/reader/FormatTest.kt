package com.folio.reader

import com.folio.reader.core.format.DocFormat
import com.folio.reader.core.format.FormatDetector
import com.folio.reader.core.format.FormatDetector.Result
import com.folio.reader.data.epub.EpubBook
import com.folio.reader.data.files.TextFormats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FormatTest {
    private fun ok(r: Result) = (r as Result.Ok).format

    @Test fun detectsBySignature() {
        assertEquals(DocFormat.PDF, ok(FormatDetector.detect("%PDF-1.7\n".toByteArray(), "x.pdf", null)))
        assertEquals(DocFormat.PNG, ok(FormatDetector.detect(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A), "renamed.txt", null)))
        val epub = byteArrayOf(0x50, 0x4B, 0x03, 0x04) + ByteArray(26) + "mimetypeapplication/epub+zip".toByteArray()
        assertEquals(DocFormat.EPUB, ok(FormatDetector.detect(epub, "book.zip", null)))
    }

    @Test fun rejectsExecutablesAndMismatches() {
        assertTrue(FormatDetector.detect(byteArrayOf(0x4D, 0x5A, 0, 0), "invoice.pdf", null) is Result.Rejected)
        assertTrue(FormatDetector.detect("dex\n035\u0000".toByteArray(), "classes.txt", null) is Result.Rejected)
        assertTrue(FormatDetector.detect("hello".toByteArray(), "fake.pdf", null) is Result.Rejected)
        assertTrue(FormatDetector.detect(byteArrayOf(0, 1, 2, 3, 0, 0), "data.txt", null) is Result.Rejected)
    }

    @Test fun recognisesOffice() {
        val zip = byteArrayOf(0x50, 0x4B, 0x03, 0x04) + ByteArray(40)
        assertEquals(DocFormat.DOCX, ok(FormatDetector.detect(zip, "report.docx", null)))
        assertEquals(DocFormat.XLSX, ok(FormatDetector.detect(zip, "budget.xlsx", null)))
        assertEquals(DocFormat.PPTX, ok(FormatDetector.detect(zip, "talk.pptx", null)))
        // Unnamed Office package recognised from its part folders.
        val unnamed = byteArrayOf(0x50, 0x4B, 0x03, 0x04) + ByteArray(26) + "word/document.xml".toByteArray()
        assertEquals(DocFormat.DOCX, ok(FormatDetector.detect(unnamed, null, null)))
        // Legacy binary Office is laid out by the document engine.
        val ole = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte()) + ByteArray(40)
        assertEquals(DocFormat.DOC, ok(FormatDetector.detect(ole, "old.doc", null)))
        assertEquals(DocFormat.XLS, ok(FormatDetector.detect(ole, "old.xls", null)))
        // Recognised formats without a viewer stay "unsupported".
        assertTrue(FormatDetector.detect("BOOKMOBI".toByteArray() + ByteArray(40), "novel.mobi", null) is Result.Unsupported)
    }

    @Test fun textFormats() {
        assertEquals(DocFormat.JSON, ok(FormatDetector.detect("{\"a\":1}".toByteArray(), "a.json", null)))
        assertEquals(DocFormat.TXT, ok(FormatDetector.detect("plain".toByteArray(), "noext", null)))
        assertEquals(DocFormat.MARKDOWN, ok(FormatDetector.detect("# Title".toByteArray(), "readme.md", null)))
    }

    @Test fun csvParsing() {
        val rows = TextFormats.parseDelimited("a,b,c\n1,\"x, y\",\"he said \"\"hi\"\"\"\n2,\"multi\nline\",3\n", ',')
        assertEquals(3, rows.size)
        assertEquals("x, y", rows[1][1])
        assertEquals("he said \"hi\"", rows[1][2])
        assertEquals("multi\nline", rows[2][1])
    }

    @Test fun rtfToText() {
        val rtf = "{\\rtf1\\ansi{\\fonttbl{\\f0 Times;}}\\f0 Hello \\b world\\b0\\par Caf\\'e9 \\u8212? done}"
        val t = TextFormats.rtfToPlain(rtf)
        assertTrue(t, t.contains("Hello world"))
        assertTrue(t, t.contains("Café"))
        assertTrue(t, t.contains("—"))
        assertTrue(!t.contains("Times"))
    }

    @Test fun charsetFallback() {
        assertEquals("héllo", TextFormats.decode("héllo".toByteArray(Charsets.UTF_8)))
        assertEquals("héllo", TextFormats.decode(byteArrayOf(0x68, 0xE9.toByte(), 0x6C, 0x6C, 0x6F)))
    }

    @Test fun epubPathNormalisation() {
        assertEquals("OEBPS/images/a.png", EpubBook.normalize("OEBPS/text/../images/./a.png"))
        assertEquals("a.png", EpubBook.normalize("../../a.png"))
    }
}
