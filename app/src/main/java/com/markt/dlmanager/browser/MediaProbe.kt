package com.markt.dlmanager.browser

import android.webkit.CookieManager
import com.markt.dlmanager.engine.Http
import com.markt.dlmanager.engine.Probe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Asks the server about a found video (size, type, real name) the same way a download would. */
object MediaProbe {
    suspend fun probe(url: String, referer: String, userAgent: String): Probe? = withContext(Dispatchers.IO) {
        runCatching {
            val headers = buildMap {
                runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()
                    ?.takeIf { it.isNotBlank() }?.let { put("Cookie", it) }
                put("User-Agent", userAgent)
                referer.takeIf { it.startsWith("http") }?.let { put("Referer", it) }
            }
            Http.probe(url, headers)
        }.getOrNull()
    }
}
