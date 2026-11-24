package com.markt.dlmanager.browser

import android.net.Uri
import android.webkit.WebResourceResponse
import org.json.JSONArray
import java.io.ByteArrayInputStream

/** A link found by "Find download links". */
data class FoundLink(val url: String, val label: String, val isFile: Boolean)

/** Helpers for the pop-up / ad blocker. */
object Shields {

    /**
     * Ad networks: first the ones whose main business is pop-ups, pop-unders and forced redirects,
     * then common banner / display / native ad networks. Some download sites refuse to work when
     * their ads are blocked; the shield menu can allow ads on that one site.
     */
    private val adHosts = setOf(
        // pop-up / redirect networks
        "popads.net", "popcash.net", "propellerads.com", "propellerclick.com", "onclickads.net",
        "onclkds.com", "adsterra.com", "exoclick.com", "exosrv.com", "realsrv.com", "juicyads.com",
        "hilltopads.net", "hilltopads.com", "adcash.com", "clickadu.com", "popmyads.com", "mgid.com",
        "adskeeper.com", "bidvertiser.com", "monetag.com", "a-ads.com", "galaksion.com", "zeropark.com",
        "evadav.com", "trafficstars.com", "tsyndicate.com", "trafficjunky.net", "popunder.net",
        "highperformanceformat.com", "profitablegatecpm.com",
        // banner / display / native / in-app ad networks
        "doubleclick.net", "googlesyndication.com", "googleadservices.com", "adservice.google.com",
        "googletagservices.com", "2mdn.net", "amazon-adsystem.com", "adnxs.com", "criteo.com", "criteo.net",
        "taboola.com", "outbrain.com", "rubiconproject.com", "pubmatic.com", "openx.net", "casalemedia.com",
        "smartadserver.com", "adform.net", "media.net", "33across.com", "sharethrough.com", "teads.tv",
        "adsrvr.org", "moatads.com", "adsafeprotected.com", "serving-sys.com", "zedo.com", "infolinks.com",
        "revcontent.com", "coinzilla.com", "bitmedia.io", "cointraffic.io", "adtelligent.com", "vidoomy.com",
        "yieldmo.com", "inmobi.com", "applovin.com", "unityads.unity3d.com", "adcolony.com", "chartboost.com",
        "vungle.com", "admob.com",
    )

    /** The ad host list as a JavaScript array literal, for the page script. */
    val adHostsJs: String = adHosts.joinToString(",", "[", "]") { "\"$it\"" }

    /** Two-letter country domains that are usually split one level deeper (example.co.uk). */
    private val secondLevel = setOf("co", "com", "net", "org", "gov", "edu", "ac", "or", "ne", "go")

    /** File types that are treated as a download when a pop-up or the page points at them. */
    private val fileExtensions = setOf(
        "apk", "apks", "xapk", "zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz", "iso", "img", "bin",
        "exe", "msi", "dmg", "deb", "rpm", "pdf", "epub", "mobi", "doc", "docx", "xls", "xlsx", "ppt",
        "pptx", "csv", "mp4", "mkv", "webm", "avi", "mov", "m4v", "3gp", "flv", "wmv", "mp3", "m4a",
        "aac", "flac", "wav", "ogg", "opus", "torrent", "jpg", "jpeg", "png", "gif", "webp",
    )

    fun isAdHost(host: String?): Boolean {
        var h = host?.lowercase()?.trimEnd('.') ?: return false
        while (true) {
            if (h in adHosts) return true
            val dot = h.indexOf('.')
            if (dot < 0) return false
            h = h.substring(dot + 1)
        }
    }

    /** "https://www.mail.example.co.uk/x" → "example.co.uk". Empty for non-web URLs. */
    fun site(url: String?): String {
        if (url.isNullOrEmpty()) return ""
        val host = runCatching { Uri.parse(url).host }.getOrNull()?.lowercase()?.trimEnd('.') ?: return ""
        if (host.isEmpty()) return ""
        if (host.all { it.isDigit() || it == '.' } || host.contains(':')) return host   // IP address
        val labels = host.split('.')
        if (labels.size <= 2) return host.removePrefix("www.")
        val n = labels.size
        val keep = if (labels[n - 1].length == 2 && labels[n - 2] in secondLevel) 3 else 2
        return labels.takeLast(keep).joinToString(".")
    }

    fun host(url: String?): String =
        runCatching { Uri.parse(url).host }.getOrNull()?.removePrefix("www.").orEmpty()

    /** True when the URL's path ends in a file type we download (…/file.apk?token=…). */
    fun looksLikeFile(url: String?): Boolean {
        if (url.isNullOrEmpty() || !url.startsWith("http")) return false
        val path = runCatching { Uri.parse(url).path }.getOrNull() ?: return false
        val ext = path.substringAfterLast('/').substringAfterLast('.', "").lowercase()
        return ext in fileExtensions
    }

    private val downloadWords =
        Regex("""download|descarg|baixar|telecharg|herunterlad|scarica|unduh|\bget\b|\bdl\b|mirror""", RegexOption.IGNORE_CASE)
    private val downloadPath = Regex("""download|/dl/|/get/|/file/|mirror""", RegexOption.IGNORE_CASE)

    /**
     * Turns the page script's [{u, t}] list into download candidates: links to files first, then
     * links whose text or path says "download" (or similar). Ads and links back to the same page are dropped.
     */
    fun rankLinks(json: String?, pageUrl: String): List<FoundLink> {
        val arr = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        val page = pageUrl.substringBefore('#')
        val found = LinkedHashMap<String, FoundLink>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val u = o.optString("u")
            val text = o.optString("t").trim()
            if (!u.startsWith("http") || u.substringBefore('#') == page) continue
            val parsed = runCatching { Uri.parse(u) }.getOrNull() ?: continue
            if (isAdHost(parsed.host)) continue
            val path = parsed.path.orEmpty()
            val isFile = looksLikeFile(u)
            if (!isFile && !downloadWords.containsMatchIn(text) && !downloadPath.containsMatchIn(path)) continue
            val name = path.substringAfterLast('/')
            val label = when {
                isFile && name.isNotBlank() -> name
                text.isNotBlank() -> text
                name.isNotBlank() -> name
                else -> parsed.host.orEmpty()
            }
            found.putIfAbsent(u, FoundLink(u, label, isFile))
        }
        return found.values.sortedByDescending { it.isFile }
    }

    /** An empty answer for blocked requests. */
    fun emptyResponse() = WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
}
