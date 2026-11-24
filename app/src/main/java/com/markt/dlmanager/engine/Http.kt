package com.markt.dlmanager.engine

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder

class HttpStatusException(val code: Int) : IOException("Server error (HTTP $code)") {
    val retryable: Boolean get() = code == 408 || code == 429 || code >= 500
}

class NotResumableException : IOException("Server doesn't support split downloads")

data class Probe(
    val finalUrl: String,
    val totalBytes: Long,
    val acceptsRanges: Boolean,
    val fileName: String?,
    val mimeType: String?,
)

object Http {

    /** Opens a connection and follows redirects (including http → https, which Java won't do itself). */
    fun connect(
        url: String,
        headers: Map<String, String>,
        range: String?,
        track: (HttpURLConnection) -> Unit = {},
    ): HttpURLConnection {
        val originalHost = URL(url).host
        var current = URL(url)
        var hops = 0
        while (true) {
            val c = current.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 20_000
            c.readTimeout = 30_000
            c.useCaches = false
            val sameHost = current.host.equals(originalHost, ignoreCase = true)
            headers.forEach { (k, v) ->
                // never leak the site's cookies to a different host after a redirect
                if (sameHost || !k.equals("Cookie", ignoreCase = true)) c.setRequestProperty(k, v)
            }
            c.setRequestProperty("Accept-Encoding", "identity")   // byte ranges must be on the raw file
            if (range != null) c.setRequestProperty("Range", range)
            track(c)

            val code = c.responseCode
            if (code in 300..399 && code != 304) {
                val location = c.getHeaderField("Location")
                if (location != null) {
                    c.disconnect()
                    if (++hops > 10) throw IOException("Too many redirects")
                    current = URL(current, location)
                    continue
                }
            }
            return c
        }
    }

    /** Asks for the first byte only, to learn the size, the real name and whether split download works. */
    fun probe(url: String, headers: Map<String, String>): Probe {
        val c = connect(url, headers, "bytes=0-0")
        try {
            val code = c.responseCode
            val name = parseFileName(c.getHeaderField("Content-Disposition"))
            val type = c.contentType?.substringBefore(';')?.trim()?.lowercase()
            return when {
                code == 206 -> {
                    val total = c.getHeaderField("Content-Range")
                        ?.substringAfter('/', "")?.trim()?.toLongOrNull() ?: -1L
                    Probe(c.url.toString(), total, total > 0, name, type)
                }
                code in 200..299 -> Probe(c.url.toString(), c.contentLengthLong, false, name, type)
                else -> throw HttpStatusException(code)
            }
        } finally {
            c.disconnect()
        }
    }

    fun parseFileName(contentDisposition: String?): String? {
        val cd = contentDisposition ?: return null
        Regex("""filename\*\s*=\s*([^']*)'[^']*'([^;]+)""", RegexOption.IGNORE_CASE).find(cd)?.let { m ->
            val charset = m.groupValues[1].ifBlank { "UTF-8" }
            val raw = m.groupValues[2].trim().trim('"').replace("+", "%2B")
            runCatching { URLDecoder.decode(raw, charset) }.getOrNull()?.takeIf { it.isNotBlank() }?.let { return it }
        }
        Regex("""filename\s*=\s*"([^"]+)"""", RegexOption.IGNORE_CASE).find(cd)?.let {
            return it.groupValues[1].trim()
        }
        Regex("""filename\s*=\s*([^;]+)""", RegexOption.IGNORE_CASE).find(cd)?.let {
            return it.groupValues[1].trim().trim('"')
        }
        return null
    }
}
