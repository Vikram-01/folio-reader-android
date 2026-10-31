package com.folio.reader.data.files

import com.folio.reader.core.security.Limits
import java.io.File
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

object TextFormats {

    /** Reads text with a byte cap, sniffing a BOM and falling back from UTF-8 to Latin-1. */
    fun readText(file: File, limit: Long = Limits.MAX_TEXT_LOAD_BYTES): Pair<String, Boolean> {
        val truncated = file.length() > limit
        val bytes = file.inputStream().use { input ->
            val buf = ByteArray(minOf(file.length(), limit).toInt())
            var off = 0
            while (off < buf.size) {
                val n = input.read(buf, off, buf.size - off)
                if (n < 0) break
                off += n
            }
            if (off == buf.size) buf else buf.copyOf(off)
        }
        return decode(bytes) to truncated
    }

    /** Like [readText] but for a stream of unknown length (e.g. another app's content:// file). Reads at most [limit] bytes. */
    fun readStream(input: java.io.InputStream, limit: Long = Limits.MAX_TEXT_LOAD_BYTES): Pair<String, Boolean> {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        var total = 0L
        var truncated = false
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            val keep = minOf(n.toLong(), limit - total).toInt()
            if (keep > 0) out.write(buf, 0, keep)
            total += keep
            if (keep < n) { truncated = true; break }
        }
        return decode(out.toByteArray()) to truncated
    }

    fun decode(bytes: ByteArray): String {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        } catch (e: java.nio.charset.CharacterCodingException) {
            String(bytes, Charset.forName("windows-1252"))
        }
    }

    /**
     * Converts RTF to plain text: control words are dropped, \par becomes a
     * newline, \'hh and \uN escapes are decoded, and destination groups
     * (fonts, colours, pictures, metadata) are skipped.
     */
    fun rtfToPlain(rtf: String): String {
        val out = StringBuilder(rtf.length / 2)
        val skipStack = ArrayDeque<Boolean>()
        var skipping = false
        var ucSkip = 1
        var i = 0
        val skipDestinations = setOf("fonttbl", "colortbl", "stylesheet", "info", "pict", "object", "header", "footer",
            "themedata", "colorschememapping", "latentstyles", "datastore", "xmlnstbl", "listtable", "listoverridetable", "generator", "rsidtbl")
        while (i < rtf.length) {
            val c = rtf[i]
            when (c) {
                '{' -> { skipStack.addLast(skipping); i++ }
                '}' -> { skipping = if (skipStack.isEmpty()) false else skipStack.removeLast(); i++ }
                '\\' -> {
                    i++
                    if (i >= rtf.length) break
                    val n = rtf[i]
                    when {
                        n == '\\' || n == '{' || n == '}' -> { if (!skipping) out.append(n); i++ }
                        n == '*' -> { skipping = true; i++ }
                        n == '\'' -> {
                            val hex = rtf.substring(i + 1, minOf(i + 3, rtf.length))
                            hex.toIntOrNull(16)?.let { if (!skipping) out.append(String(byteArrayOf(it.toByte()), Charset.forName("windows-1252"))) }
                            i += 3
                        }
                        n.isLetter() -> {
                            val start = i
                            while (i < rtf.length && rtf[i].isLetter()) i++
                            val word = rtf.substring(start, i)
                            val numStart = i
                            if (i < rtf.length && (rtf[i] == '-' || rtf[i].isDigit())) {
                                i++
                                while (i < rtf.length && rtf[i].isDigit()) i++
                            }
                            val param = rtf.substring(numStart, i).toIntOrNull()
                            if (i < rtf.length && rtf[i] == ' ') i++
                            if (word in skipDestinations) skipping = true
                            if (!skipping) when (word) {
                                "par", "line", "sect", "page" -> out.append('\n')
                                "tab", "cell" -> out.append('\t')
                                "row" -> out.append('\n')
                                "uc" -> ucSkip = param ?: 1
                                "u" -> if (param != null) {
                                    out.append((if (param < 0) param + 65536 else param).toChar())
                                    i += ucSkip.coerceIn(0, 4)
                                }
                                "emdash" -> out.append('—')
                                "endash" -> out.append('–')
                                "bullet" -> out.append('•')
                                "lquote" -> out.append('‘')
                                "rquote" -> out.append('’')
                                "ldblquote" -> out.append('“')
                                "rdblquote" -> out.append('”')
                            }
                        }
                        else -> i++
                    }
                }
                '\r', '\n' -> i++
                else -> { if (!skipping) out.append(c); i++ }
            }
        }
        return out.toString().replace(Regex("\n{3,}"), "\n\n").trim()
    }

    /** RFC 4180 parser with quoted fields, escaped quotes and embedded newlines. */
    fun parseDelimited(text: String, delimiter: Char, maxRows: Int = Limits.MAX_TABLE_ROWS): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val field = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < text.length && rows.size < maxRows) {
            val c = text[i]
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') { field.append('"'); i++ } else inQuotes = false
                } else field.append(c)
            } else when (c) {
                '"' -> if (field.isEmpty()) inQuotes = true else field.append(c)
                delimiter -> { row.add(field.toString()); field.setLength(0) }
                '\r' -> {}
                '\n' -> { row.add(field.toString()); field.setLength(0); rows.add(row); row = ArrayList() }
                else -> field.append(c)
            }
            i++
        }
        if ((field.isNotEmpty() || row.isNotEmpty()) && rows.size < maxRows) { row.add(field.toString()); rows.add(row) }
        return rows
    }
}
