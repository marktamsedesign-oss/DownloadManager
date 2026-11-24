package com.markt.dlmanager.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.markt.dlmanager.engine.Http
import com.markt.dlmanager.engine.PendingDownload
import java.net.URLEncoder

/** Owns the WebView and turns anything the page tries to download into a PendingDownload. */
@SuppressLint("SetJavaScriptEnabled")
class BrowserController(
    private val context: Context,
    private val onDownload: (PendingDownload) -> Unit,
) {
    var url by mutableStateOf("")
        private set
    var progress by mutableIntStateOf(0)
        private set
    var canGoBack by mutableStateOf(false)
        private set
    var canGoForward by mutableStateOf(false)
        private set
    var hasPage by mutableStateOf(false)
        private set
    /** Set when the user long-presses a link or image. */
    var longPressUrl by mutableStateOf<String?>(null)

    val webView: WebView = WebView(context)

    val userAgent: String get() = webView.settings.userAgentString

    init {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            mediaPlaybackRequiresUserGesture = false
            setSupportMultipleWindows(false)       // target=_blank opens in the same view
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                val scheme = uri.scheme?.lowercase() ?: return false
                if (scheme in setOf("http", "https", "about", "data", "javascript", "blob")) return false
                // tel:, mailto:, market:, intent:// … → hand to Android
                try {
                    val intent = if (scheme == "intent") {
                        Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME).apply {
                            addCategory(Intent.CATEGORY_BROWSABLE)
                            component = null
                            selector = null
                        }
                    } else {
                        Intent(Intent.ACTION_VIEW, uri)
                    }
                    context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (e: Exception) {
                    if (scheme == "intent") {
                        runCatching { Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME) }
                            .getOrNull()?.getStringExtra("browser_fallback_url")
                            ?.let { view.loadUrl(it) }
                    }
                }
                return true
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) = sync()
            override fun onPageFinished(view: WebView, url: String?) = sync()
            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) = sync()
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progress = newProgress
                sync()
            }
        }

        // The page started a file download (attachment, zip, apk, video file…)
        webView.setDownloadListener { dlUrl, ua, contentDisposition, mimeType, length ->
            if (dlUrl.startsWith("blob:") || dlUrl.startsWith("data:")) {
                Toast.makeText(context, "This site builds the file inside the page — it can't be downloaded here", Toast.LENGTH_LONG).show()
                return@setDownloadListener
            }
            val headerName = Http.parseFileName(contentDisposition)
            onDownload(
                PendingDownload(
                    url = dlUrl,
                    fileName = headerName ?: URLUtil.guessFileName(dlUrl, contentDisposition, mimeType),
                    nameKnown = headerName != null,
                    mimeType = mimeType?.takeIf { it.isNotBlank() && it != "application/octet-stream" },
                    size = length,
                    referer = url.ifEmpty { null },
                    userAgent = ua,
                )
            )
        }

        // Long-press a link / image → our own menu with "Download"
        webView.setOnLongClickListener {
            val result = webView.hitTestResult
            val link = when (result.type) {
                WebView.HitTestResult.SRC_ANCHOR_TYPE -> result.extra
                WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> {
                    val msg = Handler(Looper.getMainLooper()).obtainMessage()
                    webView.requestFocusNodeHref(msg)
                    msg.data.getString("url") ?: result.extra
                }
                WebView.HitTestResult.IMAGE_TYPE -> result.extra
                else -> null
            }
            if (link != null && (link.startsWith("http://") || link.startsWith("https://"))) {
                longPressUrl = link
                true
            } else {
                false
            }
        }
    }

    private fun sync() {
        url = webView.url.orEmpty()
        canGoBack = webView.canGoBack()
        canGoForward = webView.canGoForward()
        hasPage = url.isNotEmpty() && url != "about:blank"
    }

    fun load(text: String) {
        toUrl(text)?.let { webView.loadUrl(it) }
    }

    fun goBack() = webView.goBack()
    fun goForward() = webView.goForward()
    fun reloadOrStop() = if (progress in 1..99) webView.stopLoading() else webView.reload()

    /** The current page itself (PDF, video, image…) as a download. */
    fun currentPageDownload(): PendingDownload? =
        url.takeIf { it.startsWith("http") }?.let { PendingDownload.fromUrl(it, userAgent).copy(referer = null) }

    companion object {
        fun toUrl(text: String): String? {
            val t = text.trim()
            if (t.isEmpty()) return null
            if (t.startsWith("http://", true) || t.startsWith("https://", true)) return t
            if (!t.contains(' ') && t.contains('.')) return "https://$t"
            return "https://www.google.com/search?q=" + URLEncoder.encode(t, "UTF-8")
        }

        fun isLink(text: String): Boolean {
            val t = text.trim()
            return t.startsWith("http://", true) || t.startsWith("https://", true) ||
                (!t.contains(' ') && t.contains('.'))
        }
    }
}
