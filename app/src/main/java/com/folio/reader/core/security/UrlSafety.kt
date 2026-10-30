package com.folio.reader.core.security

import java.net.URI

object UrlSafety {

    private val TRACKING_PARAMS = setOf(
        "utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content", "utm_id",
        "fbclid", "gclid", "dclid", "mc_cid", "mc_eid", "igshid", "si", "ref_src",
    )

    /**
     * Turns user input into a navigable https URL. Plain words become a search
     * query; http is upgraded to https. Returns null for schemes the browser
     * must never load (file:, content:, javascript:, data:, intent: ...).
     */
    fun toBrowsableUrl(input: String, searchTemplate: String): String? {
        val text = input.trim()
        if (text.isEmpty()) return null
        val lower = text.lowercase()
        if (lower.startsWith("https://")) return text
        if (lower.startsWith("http://")) return "https://" + text.substring(7)
        if (Regex("^[a-z][a-z0-9+.-]*:").containsMatchIn(lower) && !lower.matches(Regex("^[^/\\s]+:\\d+.*"))) {
            return null
        }
        val looksLikeHost = !text.contains(' ') && text.contains('.') &&
            Regex("^[\\w.-]+\\.[a-zA-Z]{2,}(:\\d+)?(/.*)?$").matches(text)
        if (looksLikeHost) return "https://$text"
        return searchTemplate.replace("%s", java.net.URLEncoder.encode(text, "UTF-8"))
    }

    fun isWebUrl(url: String?): Boolean {
        val l = url?.trim()?.lowercase() ?: return false
        return l.startsWith("https://") || l.startsWith("http://")
    }

    /** Finds the first http(s) URL in shared text such as "Great read https://...". */
    fun extractUrl(text: String?): String? {
        if (text == null) return null
        return Regex("https?://[^\\s<>\"']+").find(text)?.value?.trimEnd('.', ',', ')', ']', '!', '?')
    }

    /** Canonical form used for duplicate detection. */
    fun normalize(url: String): String {
        return try {
            val uri = URI(url.trim())
            val scheme = (uri.scheme ?: "https").lowercase().let { if (it == "http") "https" else it }
            val host = (uri.host ?: return url.trim().lowercase()).lowercase().removePrefix("www.")
            val port = if (uri.port == -1 || uri.port == 443 || uri.port == 80) "" else ":${uri.port}"
            var path = uri.rawPath ?: ""
            if (path.endsWith("/")) path = path.dropLast(1)
            val query = uri.rawQuery?.split('&')
                ?.filter { it.isNotBlank() && it.substringBefore('=').lowercase() !in TRACKING_PARAMS }
                ?.sorted()
                ?.joinToString("&")
                ?.takeIf { it.isNotEmpty() }
            "$scheme://$host$port$path" + (query?.let { "?$it" } ?: "")
        } catch (e: Exception) {
            url.trim().lowercase()
        }
    }

    fun hostOf(url: String): String = try {
        URI(url).host?.removePrefix("www.") ?: url
    } catch (e: Exception) { url }

    /** Strips path separators and control characters from a name we will write to disk. */
    fun safeFileName(name: String?, fallback: String = "file"): String {
        val cleaned = (name ?: "")
            .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
            .trim().trim('.')
            .take(150)
        return cleaned.ifBlank { fallback }
    }
}
