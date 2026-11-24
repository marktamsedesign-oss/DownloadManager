package com.markt.dlmanager.browser

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.markt.dlmanager.engine.Http
import com.markt.dlmanager.engine.PendingDownload

/** One browser tab: a WebView (created when first shown) plus the state the UI draws. */
@SuppressLint("SetJavaScriptEnabled")
class BrowserTab internal constructor(
    private val browser: Browser,
    val id: Long,
    restoreUrl: String?,
    restoreTitle: String,
    /** Set when a page opened this tab with window.open / target=_blank (pop-ups allowed there). */
    private val openerUrl: String? = null,
) {
    val isPopup: Boolean get() = openerUrl != null

    var url by mutableStateOf(restoreUrl.orEmpty())
        private set
    var title by mutableStateOf(restoreTitle)
        private set
    var progress by mutableIntStateOf(0)
        private set
    var canGoBack by mutableStateOf(false)
        private set
    var canGoForward by mutableStateOf(false)
        private set
    var hasPage by mutableStateOf(!restoreUrl.isNullOrEmpty())
        private set
    var favicon by mutableStateOf<Bitmap?>(null)
        private set
    var desktopMode by mutableStateOf(false)
        private set
    /** Pop-ups / redirects blocked since this page was opened. */
    var blockedCount by mutableIntStateOf(0)
    /** Find-in-page result: current match (1-based) and total. */
    var findResult by mutableStateOf<Pair<Int, Int>?>(null)

    /** Site of the open page, readable from WebView's background threads. */
    @Volatile private var siteNow: String = Shields.site(restoreUrl)
    private var pendingUrl: String? = restoreUrl?.takeIf { it.isNotEmpty() }
    private var lastPopupAttempt = 0L
    private var _webView: WebView? = null

    val isCreated: Boolean get() = _webView != null

    val webView: WebView
        get() = _webView ?: createWebView().also { wv ->
            _webView = wv
            pendingUrl?.let { wv.loadUrl(it) }
            pendingUrl = null
        }

    val userAgent: String
        get() = _webView?.settings?.userAgentString ?: WebSettings.getDefaultUserAgent(browser.context)

    val isLoading: Boolean get() = progress in 1..99

    private fun createWebView(): WebView = WebView(browser.context).apply {
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            setSupportZoom(true)
            // New windows go to onCreateWindow, where the pop-up blocker decides what happens.
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = false
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        webViewClient = Client()
        webChromeClient = Chrome()
        setDownloadListener { dlUrl, ua, contentDisposition, mimeType, length ->
            onPageDownload(dlUrl, ua, contentDisposition, mimeType, length)
        }
        setOnLongClickListener { onLongPress() }
        setFindListener { active, total, _ ->
            findResult = if (total > 0) (active + 1) to total else 0 to 0
        }
    }

    // region Navigation

    fun load(text: String) {
        Browser.toUrl(text)?.let { webView.loadUrl(it) }
    }

    fun goBack() = _webView?.goBack()
    fun reload() = _webView?.reload()
    fun goForward() = _webView?.goForward()

    fun reloadOrStop() {
        val wv = _webView ?: return
        if (isLoading) wv.stopLoading() else wv.reload()
    }

    /** Shows the start page in this tab. */
    fun goHome() {
        webView.loadUrl("about:blank")
        title = ""
    }

    fun setDesktop(on: Boolean) {
        desktopMode = on
        val wv = webView
        wv.settings.userAgentString = if (on) desktopUserAgent(WebSettings.getDefaultUserAgent(browser.context)) else null
        if (hasPage) wv.reload()
    }

    fun find(query: String) {
        if (query.isEmpty()) {
            _webView?.clearMatches(); findResult = null
        } else {
            _webView?.findAllAsync(query)
        }
    }

    fun findNext(forward: Boolean) = _webView?.findNext(forward)

    fun clearFind() {
        _webView?.clearMatches()
        findResult = null
    }

    /** Collects the file and download links on the page (ads left out) for "Find download links". */
    fun findDownloadLinks(onResult: (List<FoundLink>) -> Unit) {
        val wv = _webView
        if (wv == null || !hasPage) {
            onResult(emptyList())
            return
        }
        val page = url
        wv.evaluateJavascript(PageScripts.findLinks) { json -> onResult(Shields.rankLinks(json, page)) }
    }

    // endregion

    // region Lifecycle

    fun pause() = _webView?.onPause()
    fun resume() = _webView?.onResume()

    fun destroy() {
        _webView?.apply {
            stopLoading()
            (parent as? ViewGroup)?.removeView(this)
            destroy()
        }
        _webView = null
    }

    // endregion

    /** The current page itself (PDF, video, image…) as a download. */
    fun pageDownload(): PendingDownload? =
        url.takeIf { it.startsWith("http") }?.let { PendingDownload.fromUrl(it, userAgent) }

    internal fun noteBlocked() {
        blockedCount++
    }

    internal fun notePopupAttempt() {
        lastPopupAttempt = SystemClock.elapsedRealtime()
    }

    private fun sync() {
        val wv = _webView ?: return
        val newUrl = wv.url.orEmpty()
        url = newUrl
        siteNow = Shields.site(newUrl)
        canGoBack = wv.canGoBack()
        canGoForward = wv.canGoForward()
        hasPage = newUrl.isNotEmpty() && newUrl != "about:blank"
        if (!hasPage) title = ""
    }

    /**
     * "Tab-under": a script opens a pop-up and, in the same click, sends this tab to an ad.
     * Right after a blocked pop-up, refuse navigations of this tab to a different site.
     */
    private fun isTabUnder(target: String): Boolean {
        if (!browser.popupsBlockedFor(this)) return false
        if (SystemClock.elapsedRealtime() - lastPopupAttempt > 1_500) return false
        val from = Shields.site(url)
        return from.isNotEmpty() && Shields.site(target) != from
    }

    /**
     * On pages that already tried pop-ups or redirects (or on sites set to "always ask"), a tap that
     * leads to another site asks first: fake download buttons are usually links to ad sites.
     */
    private fun asksBeforeLeaving(target: String): Boolean {
        val from = Shields.site(url)
        if (from.isEmpty() || Shields.site(target) == from || Shields.looksLikeFile(target)) return false
        return BrowserPrefs.alwaysAsk(from) || (blockedCount > 0 && browser.popupsBlockedFor(this))
    }

    private fun injectShield(view: WebView) {
        if (hasPage && BrowserPrefs.blockAdsOn(siteNow)) view.evaluateJavascript(PageScripts.shield, null)
    }

    private fun onPageDownload(dlUrl: String, ua: String?, contentDisposition: String?, mimeType: String?, length: Long) {
        if (dlUrl.startsWith("blob:") || dlUrl.startsWith("data:")) {
            browser.notice("This site builds the file inside the page — it can't be downloaded here")
            return
        }
        val headerName = Http.parseFileName(contentDisposition)
        browser.onDownload(
            PendingDownload(
                url = dlUrl,
                fileName = headerName ?: URLUtil.guessFileName(dlUrl, contentDisposition, mimeType),
                nameKnown = headerName != null,
                mimeType = mimeType?.takeIf { it.isNotBlank() && it != "application/octet-stream" },
                size = length,
                referer = url.takeIf { it.startsWith("http") } ?: openerUrl?.takeIf { it.startsWith("http") },
                userAgent = ua,
            )
        )
        // a pop-up whose only job was to deliver a file: close it again
        // (posted: a WebView must not be destroyed from inside its own callback)
        if (isPopup && !hasPage) Handler(Looper.getMainLooper()).post { browser.closeTab(this) }
    }

    private fun onLongPress(): Boolean {
        val wv = _webView ?: return false
        val result = wv.hitTestResult
        var isImage = false
        val link = when (result.type) {
            WebView.HitTestResult.SRC_ANCHOR_TYPE -> result.extra
            WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> {
                val msg = Handler(Looper.getMainLooper()).obtainMessage()
                wv.requestFocusNodeHref(msg)
                msg.data.getString("url") ?: result.extra
            }
            WebView.HitTestResult.IMAGE_TYPE -> result.extra.also { isImage = true }
            else -> null
        }
        if (link != null && (link.startsWith("http://") || link.startsWith("https://"))) {
            browser.longPress = LongPress(link, isImage, this)
            return true
        }
        return false
    }

    private inner class Client : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val uri = request.url
            val scheme = uri.scheme?.lowercase() ?: return false
            if (scheme == "http" || scheme == "https") {
                if (!request.isForMainFrame) return false
                val target = uri.toString()
                if (BrowserPrefs.blockAdsOn(siteNow) && Shields.isAdHost(uri.host)) {
                    noteBlocked()
                    browser.notice("Blocked a link to an ad")
                    return true
                }
                if (!request.isRedirect && isTabUnder(target)) {
                    noteBlocked()
                    browser.notice("Blocked a redirect", "Open") { browser.newTab(target) }
                    return true
                }
                if (!request.isRedirect && asksBeforeLeaving(target)) {
                    browser.notice("That tap leads to ${Shields.host(target)}", "Open") { view.loadUrl(target) }
                    return true
                }
                return false
            }
            if (scheme in setOf("about", "data", "javascript", "blob", "file")) return false
            // tel:, mailto:, market:, intent:// … → another app
            browser.openExternal(this@BrowserTab, uri, request.hasGesture())
            return true
        }

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            if (BrowserPrefs.blockAdsOn(siteNow) && Shields.isAdHost(request.url.host)) return Shields.emptyResponse()
            return null
        }

        override fun onPageCommitVisible(view: WebView, url: String?) = injectShield(view)

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            // a new page: reset the per-page counters
            blockedCount = 0
            this@BrowserTab.favicon = null
            findResult = null
            sync()
        }

        override fun onPageFinished(view: WebView, url: String?) {
            sync()
            if (title.isEmpty() && hasPage) title = view.title.orEmpty()
            injectShield(view)
            browser.persist()
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) = sync()
    }

    private inner class Chrome : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) {
            progress = newProgress
            sync()
        }

        override fun onReceivedTitle(view: WebView, title: String?) {
            this@BrowserTab.title = title.orEmpty()
        }

        override fun onReceivedIcon(view: WebView, icon: Bitmap?) {
            favicon = icon
        }

        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean =
            browser.onCreateWindow(this@BrowserTab, isUserGesture, resultMsg)

        override fun onCloseWindow(window: WebView) {
            if (isPopup) Handler(Looper.getMainLooper()).post { browser.closeTab(this@BrowserTab) }
        }

        override fun onShowCustomView(view: View, callback: CustomViewCallback) =
            browser.enterFullscreen(view, callback)

        override fun onHideCustomView() = browser.fullscreenHidden()

        override fun onShowFileChooser(
            webView: WebView,
            filePathCallback: ValueCallback<Array<Uri>>,
            fileChooserParams: FileChooserParams,
        ): Boolean = browser.fileChooser?.invoke(filePathCallback, fileChooserParams) ?: false
    }

    companion object {
        /** Mobile WebView user agent → the matching desktop Chrome one. */
        fun desktopUserAgent(mobile: String): String =
            mobile.replace(Regex("""\([^)]*Android[^)]*\)"""), "(X11; Linux x86_64)")
                .replace(Regex("""\s*Version/\S+"""), "")
                .replace(" Mobile", "")
    }
}

/** A long-pressed link or image. */
data class LongPress(val url: String, val isImage: Boolean, val tab: BrowserTab)
