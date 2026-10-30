package com.folio.reader

import com.folio.reader.core.security.BackupCrypto
import com.folio.reader.core.security.SafeZip
import com.folio.reader.core.security.UnsafeArchiveException
import com.folio.reader.core.security.UrlSafety
import com.folio.reader.core.security.WrongPasswordException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.random.Random

class SecurityTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun zip(file: File, vararg entries: Pair<String, ByteArray>) {
        ZipOutputStream(file.outputStream()).use { z ->
            entries.forEach { (n, b) -> z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() }
        }
    }

    @Test fun entryNamesRejectTraversal() {
        assertFalse(SafeZip.isSafeEntryName("../evil"))
        assertFalse(SafeZip.isSafeEntryName("a/../../evil"))
        assertFalse(SafeZip.isSafeEntryName("/etc/passwd"))
        assertFalse(SafeZip.isSafeEntryName("C:\\windows"))
        assertFalse(SafeZip.isSafeEntryName("a\\..\\b"))
        assertTrue(SafeZip.isSafeEntryName("OEBPS/chapter1.xhtml"))
        assertTrue(SafeZip.isSafeEntryName("a/..b/c"))
    }

    @Test fun zipSlipIsBlocked() {
        val f = tmp.newFile("slip.zip")
        zip(f, "ok.txt" to "fine".toByteArray(), "../../escaped.txt" to "bad".toByteArray())
        val dest = tmp.newFolder("out")
        try { SafeZip.extractAll(f, dest); fail("expected rejection") } catch (e: UnsafeArchiveException) { }
        assertFalse(File(tmp.root, "escaped.txt").exists())
    }

    @Test fun zipBombIsBlocked() {
        val f = tmp.newFile("bomb.zip")
        zip(f, "zeros.bin" to ByteArray(64 shl 20))   // 64 MiB of zeros compresses ~1000:1
        try { SafeZip.extractAll(f, tmp.newFolder("bomb")); fail("expected rejection") } catch (e: UnsafeArchiveException) { }
    }

    @Test fun boundedCopyStopsAtLimit() {
        try {
            SafeZip.copyBounded(ByteArrayInputStream(ByteArray(2000)), ByteArrayOutputStream(), 1000)
            fail("expected rejection")
        } catch (e: UnsafeArchiveException) { }
        assertEquals(1000L, SafeZip.copyBounded(ByteArrayInputStream(ByteArray(1000)), ByteArrayOutputStream(), 1000))
    }

    @Test fun browserUrlRules() {
        val t = "https://duckduckgo.com/?q=%s"
        assertEquals("https://example.com", UrlSafety.toBrowsableUrl("example.com", t))
        assertEquals("https://example.com/x", UrlSafety.toBrowsableUrl("http://example.com/x", t))
        assertNull(UrlSafety.toBrowsableUrl("javascript:alert(1)", t))
        assertNull(UrlSafety.toBrowsableUrl("file:///data/data/x", t))
        assertNull(UrlSafety.toBrowsableUrl("content://foo/bar", t))
        assertNull(UrlSafety.toBrowsableUrl("intent://scan#Intent;end", t))
        assertTrue(UrlSafety.toBrowsableUrl("hello world", t)!!.startsWith("https://duckduckgo.com/?q=hello"))
    }

    @Test fun duplicateUrlNormalisation() {
        assertEquals(UrlSafety.normalize("https://www.Example.com/a/?utm_source=x&b=2&a=1#frag"), UrlSafety.normalize("http://example.com/a?a=1&b=2"))
        assertFalse(UrlSafety.normalize("https://example.com/a") == UrlSafety.normalize("https://example.com/b"))
    }

    @Test fun sharedTextUrlExtraction() {
        assertEquals("https://ex.com/p?q=1", UrlSafety.extractUrl("Read this: https://ex.com/p?q=1."))
        assertNull(UrlSafety.extractUrl("no link here"))
    }

    @Test fun safeFileNames() {
        assertEquals("a_b_c.pdf", UrlSafety.safeFileName("a/b\\c.pdf"))
        assertEquals("file", UrlSafety.safeFileName("..."))
    }

    @Test fun backupEncryptionRoundTrip() {
        val data = Random(7).nextBytes(3 * (1 shl 20) + 123)   // spans several chunks
        val enc = ByteArrayOutputStream()
        BackupCrypto.encrypt(ByteArrayInputStream(data), enc, "correct horse".toCharArray())
        assertTrue(BackupCrypto.isEncrypted(enc.toByteArray()))
        val dec = ByteArrayOutputStream()
        BackupCrypto.decrypt(ByteArrayInputStream(enc.toByteArray()), dec, "correct horse".toCharArray())
        assertArrayEquals(data, dec.toByteArray())
    }

    @Test fun backupWrongPasswordAndTruncationFail() {
        val enc = ByteArrayOutputStream()
        BackupCrypto.encrypt(ByteArrayInputStream(ByteArray(3 shl 20)), enc, "pw-123456".toCharArray())
        try { BackupCrypto.decrypt(ByteArrayInputStream(enc.toByteArray()), ByteArrayOutputStream(), "nope".toCharArray()); fail() } catch (e: WrongPasswordException) { }
        val bytes = enc.toByteArray()
        // Drop the final chunk: must be detected.
        val chunkLen = (1 shl 20) + 16 + 4
        val truncated = bytes.copyOf(bytes.size - chunkLen)
        try { BackupCrypto.decrypt(ByteArrayInputStream(truncated), ByteArrayOutputStream(), "pw-123456".toCharArray()); fail() } catch (e: java.io.IOException) { }
    }
}
