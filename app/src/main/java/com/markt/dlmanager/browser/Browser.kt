package com.markt.dlmanager.browser

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.View
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.markt.dlmanager.engine.Http
import com.markt.dlmanager.engine.PendingDownload
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.net.URLEncoder

/** A short message for the snackbar, optionally with one action. */
class Notice(val message: String, val actionLabel: String? = null, val action: (() -> Unit)? = null)

/** Tabs, pop-up decisions, full-screen video and file uploads for the built-in browser. */
class Browser(val context: Context) {
    val tabs = mutableStateListOf<BrowserTab>()
    var currentIndex by mutableIntStateOf(0)
        private set
    val current: BrowserTab get() = tabs[currentIndex.coerceIn(0, tabs.lastIndex)]

    /** Called with every file the browser wants to download. */
    var onDownload: (PendingDownload) -> Unit = {}

    /** Set by the activity: opens the system file picker for <input type=file>. */
    var fileChooser: ((ValueCallback<Array<Uri>>, WebChromeClient.FileChooserParams) -> Boolean)? = null

    var longPress by mutableStateOf<LongPress?>(null)
    var showTabs by mutableStateOf(false)

    /** A video playing full screen (WebChromeClient custom view). */
    var fullscreenView by mutableStateOf<View?>(null)
        private set
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null

    private val _notices = MutableSharedFlow<Notice>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val notices: SharedFlow<Notice> = _notices.asSharedFlow()

    private var nextId = 1L
    private val probes = mutableSetOf<PopupProbe>()

    init {
        val (saved, index) = BrowserPrefs.loadTabs()
        saved.forEach { tabs.add(BrowserTab(this, nextId++, it.url, it.title)) }
        if (tabs.isEmpty()) tabs.add(BrowserTab(this, nextId++, null, ""))
        currentIndex = index.coerceIn(0, tabs.lastIndex)
    }

    // region Tabs

    fun newTab(url: String? = null, select: Boolean = true): BrowserTab =
        addTab(BrowserTab(this, nextId++, null, ""), select).also { tab -> url?.let { tab.load(it) } }

    private fun addTab(tab: BrowserTab, select: Boolean): BrowserTab {
        val at = (currentIndex + 1).coerceAtMost(tabs.size)
        tabs.add(at, tab)
        if (select) select(tab) else if (at <= currentIndex) currentIndex++
        persist()
        return tab
    }

    fun select(tab: BrowserTab) {
        val index = tabs.indexOf(tab)
        if (index < 0) return
        if (index != currentIndex) tabs.getOrNull(currentIndex)?.pause()
        currentIndex = index
        tab.resume()
        showTabs = false
        persist()
    }

    fun closeTab(tab: BrowserTab) {
        val index = tabs.indexOf(tab)
        if (index < 0) return
        val wasCurrent = index == currentIndex
        tabs.removeAt(index)
        tab.destroy()
        if (tabs.isEmpty()) tabs.add(BrowserTab(this, nextId++, null, ""))
        currentIndex = when {
            wasCurrent -> index - 1                      // the tab on the left (the opener, for a pop-up)
            index < currentIndex -> currentIndex - 1
            else -> currentIndex
        }.coerceIn(0, tabs.lastIndex)
        if (wasCurrent) current.resume()
        persist()
    }

    fun closeAllTabs() {
        tabs.toList().forEach { it.destroy() }
        tabs.clear()
        tabs.add(BrowserTab(this, nextId++, null, ""))
        currentIndex = 0
        showTabs = false
        persist()
    }

    fun load(text: String) = current.load(text)

    fun persist() {
        BrowserPrefs.saveTabs(tabs.map { SavedTab(if (it.hasPage) it.url else "", it.title) }, currentIndex)
    }

    // endregion

    // region Lifecycle

    fun onPause() {
        tabs.getOrNull(currentIndex)?.pause()
        CookieManager.getInstance().flush()
        persist()
    }

    fun onResume() {
        tabs.getOrNull(currentIndex)?.resume()
    }

    fun destroy() {
        probes.toList().forEach { it.finish() }
        tabs.forEach { it.destroy() }
    }

    fun clearBrowsingData() {
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        WebStorage.getInstance().deleteAllData()
        tabs.forEach { if (it.isCreated) it.webView.clearCache(true) }
        notice("Cookies, site data and cache cleared")
    }

    // endregion

    fun notice(message: String, actionLabel: String? = null, action: (() -> Unit)? = null) {
        _notices.tryEmit(Notice(message, actionLabel, action))
    }

    internal fun popupsBlockedFor(tab: BrowserTab): Boolean =
        BrowserPrefs.blockPopups && !BrowserPrefs.popupsAllowed(Shields.site(tab.url))

    // region Pop-ups

    /**
     * A page asked for a new window (window.open, target=_blank).
     * - Pop-ups allowed on this site → it becomes a new tab (keeps window.opener, so logins work).
     * - Otherwise a hidden "probe" follows it (scripts off, through redirects) just far enough to see
     *   what it is: a file goes to the downloader, a page on the same site opens as a new tab (download
     *   sites often do that for their "your download is ready" page), and a page on another site is
     *   blocked before it shows, with an "Open" button in case it was wanted.
     */
    internal fun onCreateWindow(opener: BrowserTab, isUserGesture: Boolean, resultMsg: Message): Boolean {
        val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
        if (!popupsBlockedFor(opener)) {
            val tab = addTab(BrowserTab(this, nextId++, null, "", openerUrl = opener.url), select = true)
            transport.webView = tab.webView
            resultMsg.sendToTarget()
            return true
        }
        opener.notePopupAttempt()
        if (!isUserGesture) {
            opener.noteBlocked()
            notice("Pop-up blocked")
            return false
        }
        val probe = PopupProbe(opener)
        probes += probe
        transport.webView = probe.webView
        resultMsg.sendToTarget()
        return true
    }

    @SuppressLint("SetJavaScriptEnabled")
    private inner class PopupProbe(private val opener: BrowserTab) {
        val webView = WebView(context)
        private var done = false
        private val handler = Handler(Looper.getMainLooper())
        private val timeout = Runnable { finish() }
        private val openerSite = Shields.site(opener.url)

        init {
            webView.settings.apply {
                javaScriptEnabled = false            // don't run the pop-up's scripts while we look at it
                userAgentString = opener.userAgent
                setSupportMultipleWindows(false)
            }
            webView.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    if (!request.isForMainFrame) return false
                    return !decide(request.url.toString(), committed = false)
                }

                override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                    if (url.isNullOrEmpty() || url == "about:blank") return
                    decide(url, committed = true)
                }

                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                    if (done || (BrowserPrefs.blockAdsOn(openerSite) && Shields.isAdHost(request.url.host))) Shields.emptyResponse() else null
            }
            webView.webChromeClient = WebChromeClient()
            webView.setDownloadListener { dlUrl, ua, contentDisposition, mimeType, length ->
                if (done) return@setDownloadListener
                finish()
                if (dlUrl.startsWith("http")) {
                    val headerName = Http.parseFileName(contentDisposition)
                    onDownload(
                        PendingDownload(
                            url = dlUrl,
                            fileName = headerName ?: URLUtil.guessFileName(dlUrl, contentDisposition, mimeType),
                            nameKnown = headerName != null,
                            mimeType = mimeType?.takeIf { it.isNotBlank() && it != "application/octet-stream" },
                            size = length,
                            referer = opener.url.takeIf { it.startsWith("http") },
                            userAgent = ua,
                        )
                    )
                }
            }
            handler.postDelayed(timeout, 15_000)
        }

        /** Returns true when the pop-up may keep loading (we still don't know what it is). */
        private fun decide(url: String, committed: Boolean): Boolean {
            if (done) return false
            val uri = Uri.parse(url)
            val scheme = uri.scheme?.lowercase()
            when {
                scheme == "about" -> return true
                scheme != "http" && scheme != "https" -> block(null)          // other apps, data:, javascript:
                BrowserPrefs.blockAdsOn(openerSite) && Shields.isAdHost(uri.host) -> block(null)
                Shields.looksLikeFile(url) -> {
                    finish()
                    onDownload(PendingDownload.fromUrl(url, opener.userAgent).copy(referer = opener.url.takeIf { it.startsWith("http") }))
                }
                !committed -> return true                                       // follow redirects first
                Shields.site(url).let { it.isNotEmpty() && it == Shields.site(opener.url) } -> {
                    finish()                                                    // same site → ordinary new tab
                    newTab(url)
                }
                else -> block(url)                                              // a page on another site
            }
            return false
        }

        private fun block(url: String?) {
            finish()
            opener.noteBlocked()
            if (url != null) notice("Pop-up blocked", "Open") { newTab(url) } else notice("Pop-up blocked")
        }

        fun finish() {
            if (done) return
            done = true
            handler.removeCallbacks(timeout)
            webView.stopLoading()
            // never destroy a WebView from inside its own callback
            handler.post {
                webView.destroy()
                probes.remove(this)
            }
        }
    }

    // endregion

    // region Other apps (tel:, mailto:, intent://, market://…)

    internal fun openExternal(tab: BrowserTab, uri: Uri, hasGesture: Boolean) {
        val scheme = uri.scheme?.lowercase().orEmpty()
        if (!hasGesture) {
            // pages that throw you into the Play Store or another app without a tap
            tab.noteBlocked()
            notice("Blocked a redirect to another app")
            return
        }
        if (scheme in setOf("mailto", "tel", "sms", "smsto", "geo")) {
            launchExternal(tab, uri)
        } else {
            notice("This page wants to open another app", "Open") { launchExternal(tab, uri) }
        }
    }

    private fun launchExternal(tab: BrowserTab, uri: Uri) {
        val scheme = uri.scheme?.lowercase()
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
            val fallback = if (scheme == "intent") {
                runCatching { Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME) }
                    .getOrNull()?.getStringExtra("browser_fallback_url")
            } else null
            if (fallback != null && fallback.startsWith("http")) tab.load(fallback)
            else notice("No app on this phone can open that link")
        }
    }

    // endregion

    // region Full-screen video

    internal fun enterFullscreen(view: View, callback: WebChromeClient.CustomViewCallback) {
        if (fullscreenView != null) {
            callback.onCustomViewHidden()
            return
        }
        fullscreenView = view
        fullscreenCallback = callback
    }

    /** The page left full screen by itself. */
    internal fun fullscreenHidden() {
        fullscreenView = null
        fullscreenCallback = null
    }

    /** The user pressed Back while full screen. */
    fun exitFullscreen() {
        val cb = fullscreenCallback
        fullscreenView = null
        fullscreenCallback = null
        cb?.onCustomViewHidden()
    }

    // endregion

    companion object {
        fun toUrl(text: String): String? {
            val t = text.trim()
            if (t.isEmpty()) return null
            if (t.startsWith("http://", true) || t.startsWith("https://", true)) return t
            if (t.startsWith("about:", true)) return t
            if (!t.contains(' ') && t.contains('.')) return "https://$t"
            return BrowserPrefs.searchEngine.template.replace("%s", URLEncoder.encode(t, "UTF-8"))
        }

        fun isLink(text: String): Boolean {
            val t = text.trim()
            return t.startsWith("http://", true) || t.startsWith("https://", true) ||
                (!t.contains(' ') && t.contains('.'))
        }
    }
}
