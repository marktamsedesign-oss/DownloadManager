package com.markt.dlmanager.browser

import android.net.Uri
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream

/** Helpers for the pop-up / ad blocker. */
object Shields {

    /**
     * Ad networks whose main business is pop-ups, pop-unders and forced redirects.
     * Ordinary display-ad networks are left alone on purpose: many download sites refuse to show
     * their download button when those are blocked.
     */
    private val adHosts = setOf(
        "popads.net", "popcash.net", "propellerads.com", "propellerclick.com", "onclickads.net",
        "onclkds.com", "adsterra.com", "exoclick.com", "exosrv.com", "realsrv.com", "juicyads.com",
        "hilltopads.net", "hilltopads.com", "adcash.com", "clickadu.com", "popmyads.com", "mgid.com",
        "adskeeper.com", "bidvertiser.com", "monetag.com", "a-ads.com", "galaksion.com", "zeropark.com",
        "evadav.com", "trafficstars.com", "tsyndicate.com", "trafficjunky.net", "popunder.net",
    )

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

    /** An empty answer for blocked requests. */
    fun emptyResponse() = WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
}
