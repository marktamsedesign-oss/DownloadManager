package com.markt.dlmanager.ui

import android.content.Intent
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FindInPage
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.GppBad
import androidx.compose.material.icons.outlined.GppGood
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.markt.dlmanager.browser.Bookmark
import com.markt.dlmanager.browser.Browser
import com.markt.dlmanager.browser.BrowserPrefs
import com.markt.dlmanager.browser.BrowserTab
import com.markt.dlmanager.browser.DetectedMedia
import com.markt.dlmanager.browser.FoundLink
import com.markt.dlmanager.browser.MediaProbe
import com.markt.dlmanager.browser.Shields
import com.markt.dlmanager.engine.PendingDownload
import com.markt.dlmanager.engine.Probe
import com.markt.dlmanager.util.Fmt
import kotlinx.coroutines.delay

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BrowserScreen(
    browser: Browser,
    activeDownloads: Int,
    onDownload: (PendingDownload) -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    if (browser.showTabs) {
        TabsScreen(browser)
        return
    }
    val tab = browser.current
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val clipboard = LocalClipboardManager.current
    val addressFocus = remember { FocusRequester() }
    var editing by remember { mutableStateOf(false) }
    var field by remember { mutableStateOf(TextFieldValue("")) }
    var finding by remember { mutableStateOf(false) }
    var findQuery by remember { mutableStateOf("") }
    var foundLinks by remember { mutableStateOf<List<FoundLink>?>(null) }
    var showMedia by remember { mutableStateOf(false) }
    val imeVisible = WindowInsets.isImeVisible

    // The address field shows the site while browsing and the full link (all selected) while editing.
    LaunchedEffect(tab.id, tab.url, tab.hasPage, editing) {
        if (!editing) field = TextFieldValue(if (tab.hasPage) displayUrl(tab.url) else "")
    }
    LaunchedEffect(editing) {
        if (!editing) return@LaunchedEffect
        val full = if (tab.hasPage) tab.url else ""
        field = TextFieldValue(full, TextRange(0, full.length))
        delay(80)   // the tap that focused the field moves the cursor; select everything again after it
        if (field.text == full) field = field.copy(selection = TextRange(0, full.length))
    }

    fun go(text: String) {
        focusManager.clearFocus()
        tab.load(text)
    }

    fun downloadLink(text: String) {
        focusManager.clearFocus()
        val url = Browser.toUrl(text)
        if (Browser.isLink(text) && url != null) onDownload(PendingDownload.fromUrl(url, tab.userAgent))
        else browser.notice("That doesn't look like a link")
    }

    fun clip(): String? = clipboard.getText()?.text?.trim()?.takeIf { it.isNotEmpty() }
        .also { if (it == null) browser.notice("The clipboard is empty") }

    fun findLinks() {
        tab.findDownloadLinks { foundLinks = it }
    }

    fun back() {
        if (tab.canGoBack) tab.goBack() else if (tab.isPopup) browser.closeTab(tab)
    }

    // Back: the most specific handler is registered last and wins.
    BackHandler(enabled = tab.canGoBack || tab.isPopup) { back() }
    BackHandler(enabled = finding) { finding = false; tab.clearFind() }
    BackHandler(enabled = editing) { focusManager.clearFocus() }

    Column(Modifier.fillMaxSize()) {
        if (finding) {
            FindBar(
                query = findQuery,
                result = tab.findResult,
                onQuery = { findQuery = it; tab.find(it) },
                onNext = { tab.findNext(it) },
                onClose = { finding = false; tab.clearFind() },
            )
        } else {
            AddressBar(
                tab = tab,
                field = field,
                onFieldChange = { field = it },
                editing = editing,
                focusRequester = addressFocus,
                onFocusChange = { editing = it },
                onGo = { go(field.text) },
                onClear = { field = TextFieldValue("") },
                onFindLinks = ::findLinks,
            )
        }

        Box(Modifier.fillMaxWidth().height(2.dp)) {
            if (tab.isLoading) LinearProgressIndicator(progress = { tab.progress / 100f }, modifier = Modifier.fillMaxSize())
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            key(tab.id) { WebPage(tab) }
            if (!tab.hasPage) {
                StartPage(
                    onSearch = { runCatching { addressFocus.requestFocus() } },
                    onPasteGo = { clip()?.let(::go) },
                    onPasteDownload = { clip()?.let(::downloadLink) },
                    onOpen = { tab.load(it) },
                )
            }
            val savable = tab.media.filter { !it.streamed }
            if (!editing && tab.hasPage && Shields.looksLikeFile(tab.url)) {
                ExtendedFloatingActionButton(
                    onClick = { tab.pageDownload()?.let(onDownload) },
                    icon = { Icon(Icons.Filled.FileDownload, null) },
                    text = { Text("Download") },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                )
            } else if (!editing && tab.hasPage && savable.isNotEmpty()) {
                // a video (or audio) file was found on this page
                val allAudio = savable.all { it.audio }
                ExtendedFloatingActionButton(
                    onClick = { showMedia = true },
                    icon = { Icon(if (allAudio) Icons.Filled.MusicNote else Icons.Filled.Movie, null) },
                    text = {
                        Text(
                            (if (allAudio) "Audio" else "Video") + if (savable.size > 1) " (${savable.size})" else "",
                        )
                    },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                )
            }
            if (editing) {
                OmniboxPanel(
                    text = field.text,
                    hasClip = remember { clipboard.hasText() },
                    onGo = { go(field.text) },
                    onDownload = { downloadLink(field.text) },
                    onPasteGo = { clip()?.let(::go) },
                    onPasteDownload = { clip()?.let(::downloadLink) },
                    onOpen = { go(it) },
                )
            }
        }

        if (!imeVisible) {
            BottomBar(
                browser = browser,
                tab = tab,
                activeDownloads = activeDownloads,
                onBack = ::back,
                onOpenDownloads = onOpenDownloads,
                onFind = { findQuery = ""; finding = true },
                onFindLinks = ::findLinks,
                onShowMedia = { showMedia = true },
                onDownloadPage = { tab.pageDownload()?.let(onDownload) },
                onShare = {
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, tab.url)
                        .putExtra(Intent.EXTRA_SUBJECT, tab.title)
                    context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                },
                onCopyLink = {
                    clipboard.setText(AnnotatedString(tab.url))
                    browser.notice("Link copied")
                },
                onOpenSettings = onOpenSettings,
            )
        }
    }

    if (showMedia) {
        MediaDialog(
            tab = tab,
            onDismiss = { showMedia = false },
            onDownload = { showMedia = false; onDownload(it) },
        )
    }

    foundLinks?.let { links ->
        FoundLinksDialog(
            links = links,
            onDismiss = { foundLinks = null },
            onDownload = { l ->
                foundLinks = null
                onDownload(PendingDownload.fromUrl(l.url, tab.userAgent).copy(referer = tab.url.takeIf { it.startsWith("http") }))
            },
            onOpen = { l ->
                foundLinks = null
                tab.load(l.url)
            },
        )
    }

    browser.longPress?.let { lp ->
        LongPressDialog(
            url = lp.url,
            isImage = lp.isImage,
            onDismiss = { browser.longPress = null },
            onDownload = {
                browser.longPress = null
                onDownload(PendingDownload.fromUrl(lp.url, lp.tab.userAgent).copy(referer = lp.tab.url.takeIf { it.startsWith("http") }))
            },
            onNewTab = { browser.longPress = null; browser.newTab(lp.url) },
            onOpen = { browser.longPress = null; lp.tab.load(lp.url) },
            onCopy = {
                browser.longPress = null
                clipboard.setText(AnnotatedString(lp.url))
                browser.notice("Link copied")
            },
        )
    }
}

/** "https://www.example.com/a/b?c" → "example.com/a/b" (shortened), like a browser's address bar. */
private fun displayUrl(url: String): String {
    if (!url.startsWith("http")) return url
    val noScheme = url.substringAfter("://").removePrefix("www.")
    val host = noScheme.substringBefore('/')
    val path = noScheme.removePrefix(host).substringBefore('?').substringBefore('#').trimEnd('/')
    return if (path.isEmpty()) host else host + path
}

@Composable
private fun WebPage(tab: BrowserTab) {
    AndroidView(
        factory = {
            tab.webView.also { (it.parent as? ViewGroup)?.removeView(it) }
        },
        modifier = Modifier.fillMaxSize(),
        onRelease = { (it.parent as? ViewGroup)?.removeView(it) },
    )
}

@Composable
private fun AddressBar(
    tab: BrowserTab,
    field: TextFieldValue,
    onFieldChange: (TextFieldValue) -> Unit,
    editing: Boolean,
    focusRequester: FocusRequester,
    onFocusChange: (Boolean) -> Unit,
    onGo: () -> Unit,
    onClear: () -> Unit,
    onFindLinks: () -> Unit,
) {
    val secure = tab.url.startsWith("https://")
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 2.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier
                .weight(1f)
                .height(46.dp),
        ) {
            Row(Modifier.padding(start = 14.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                val (icon, tint) = when {
                    editing || !tab.hasPage -> Icons.Filled.Search to MaterialTheme.colorScheme.onSurfaceVariant
                    secure -> Icons.Filled.Lock to MaterialTheme.colorScheme.onSurfaceVariant
                    else -> Icons.Filled.Info to MaterialTheme.colorScheme.error
                }
                Icon(icon, contentDescription = if (!editing && tab.hasPage && !secure) "Not secure" else null, tint = tint, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (field.text.isEmpty()) {
                        Text(
                            "Search or type a link",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                    BasicTextField(
                        value = field,
                        onValueChange = onFieldChange,
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { onGo() }),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester)
                            .onFocusChanged { onFocusChange(it.isFocused) },
                    )
                }
                when {
                    editing && field.text.isNotEmpty() ->
                        IconButton(onClick = onClear) { Icon(Icons.Filled.Close, "Clear") }
                    !editing && tab.hasPage ->
                        IconButton(onClick = { tab.reloadOrStop() }) {
                            Icon(if (tab.isLoading) Icons.Filled.Close else Icons.Filled.Refresh, if (tab.isLoading) "Stop" else "Reload")
                        }
                }
            }
        }
        ShieldButton(tab, onFindLinks)
    }
}

/**
 * The blocker's shield: how much was blocked on this page, per-site switches for pop-ups, ads and
 * "ask before opening other sites", and "Find download links" for pages where ads cover the button.
 */
@Composable
private fun ShieldButton(tab: BrowserTab, onFindLinks: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    val site = Shields.site(tab.url)
    // read the observable lists so the switches update right away
    val popupsBlocked = BrowserPrefs.blockPopups && site !in BrowserPrefs.popupSites.sites
    val adsBlocked = BrowserPrefs.blockAdHosts && site !in BrowserPrefs.adSites.sites
    val alwaysAsk = site in BrowserPrefs.guardSites.sites
    val guarded = popupsBlocked || adsBlocked
    Box {
        IconButton(onClick = { open = true }) {
            BadgedBox(badge = {
                if (tab.blockedCount > 0) Badge { Text(if (tab.blockedCount > 99) "99+" else "${tab.blockedCount}") }
            }) {
                Icon(
                    if (guarded) Icons.Outlined.GppGood else Icons.Outlined.GppBad,
                    contentDescription = "Pop-up and ad blocker",
                    tint = if (guarded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).width(260.dp)) {
                Text("Pop-up & ad blocker", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                Text(
                    when {
                        !BrowserPrefs.blockPopups && !BrowserPrefs.blockAdHosts -> "Turned off in Settings."
                        tab.blockedCount == 1 -> "1 pop-up or redirect blocked on this page."
                        tab.blockedCount > 1 -> "${tab.blockedCount} pop-ups or redirects blocked on this page."
                        else -> "Nothing blocked on this page yet."
                    } + " Pop-ups that start a download still go through. If ads cover the download button, " +
                        "use Find download links.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (site.isNotEmpty()) {
                HorizontalDivider()
                Text(
                    "On $site",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 16.dp, top = 8.dp, end = 16.dp),
                )
                if (BrowserPrefs.blockPopups) {
                    SwitchItem("Block pop-ups", popupsBlocked) { BrowserPrefs.setPopupsAllowed(site, !it) }
                }
                if (BrowserPrefs.blockAdHosts) {
                    SwitchItem("Block ads", adsBlocked) {
                        BrowserPrefs.setAdsAllowed(site, !it)
                        tab.reload()
                    }
                }
                SwitchItem("Ask before opening other sites", alwaysAsk) { BrowserPrefs.setAlwaysAsk(site, it) }
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("Find download links") },
                    leadingIcon = { Icon(Icons.Filled.Link, null) },
                    enabled = tab.hasPage,
                    onClick = { open = false; onFindLinks() },
                )
            }
        }
    }
}

@Composable
private fun SwitchItem(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    DropdownMenuItem(
        text = { Text(text, maxLines = 2) },
        trailingIcon = { Switch(checked = checked, onCheckedChange = onChange) },
        onClick = { onChange(!checked) },
    )
}

/** Videos and audio found on the page, with size and type from the server; tap to download. */
@Composable
private fun MediaDialog(tab: BrowserTab, onDismiss: () -> Unit, onDownload: (PendingDownload) -> Unit) {
    var scanning by remember { mutableStateOf(true) }
    LaunchedEffect(tab.id) { tab.scanMedia { scanning = false } }
    val items = tab.media.toList()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Videos on this page") },
        text = {
            Column(
                Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                if (items.isEmpty()) {
                    Text(
                        if (scanning) "Looking…" else "No video found yet. Start playing the video, then open this again.",
                    )
                }
                items.forEachIndexed { i, m -> MediaRow(m, i, tab, onDownload) }
                if (items.any { it.streamed }) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Streamed videos come in many small pieces or are copy-protected (most video and TV " +
                            "sites), so they can't be saved as one file.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun MediaRow(m: DetectedMedia, index: Int, tab: BrowserTab, onDownload: (PendingDownload) -> Unit) {
    var probe by remember(m.url) { mutableStateOf<Probe?>(null) }
    var checking by remember(m.url) { mutableStateOf(!m.streamed) }
    if (!m.streamed) {
        LaunchedEffect(m.url) {
            probe = MediaProbe.probe(m.url, tab.url, tab.userAgent)
            checking = false
        }
    }
    val p = probe
    // the server answered with a web page, not a file: nothing to save here
    val notMedia = p?.mimeType?.startsWith("text/") == true
    val canSave = !m.streamed && !notMedia
    val name = p?.fileName ?: m.url.substringBefore('?').substringAfterLast('/').takeIf { it.contains('.') }
        ?: "${if (m.audio) "Audio" else "Video"} ${index + 1}"
    val details = buildList {
        Shields.quality(m.url, m.width, m.height)?.let(::add)
        when {
            m.streamed -> add("streamed, can't be saved")
            checking -> add("checking…")
            notMedia -> add("not a file")
            p == null -> add("size unknown")
            else -> {
                if (p.totalBytes > 0) add(Fmt.bytes(p.totalBytes))
                p.mimeType?.let(::add)
            }
        }
        if (m.url.startsWith("http")) add(Shields.host(m.url))
    }.joinToString(" · ")
    val save = {
        onDownload(
            PendingDownload(
                url = m.url,
                fileName = name,
                nameKnown = p?.fileName != null,
                mimeType = p?.mimeType?.takeIf { it.startsWith("video/") || it.startsWith("audio/") },
                size = p?.totalBytes ?: -1,
                referer = tab.url.takeIf { it.startsWith("http") },
                userAgent = tab.userAgent,
            )
        )
    }
    ListItem(
        headlineContent = { Text(name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(details, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        leadingContent = { Icon(if (m.audio) Icons.Filled.MusicNote else Icons.Filled.Movie, null) },
        trailingContent = if (canSave) {
            { IconButton(onClick = save) { Icon(Icons.Filled.FileDownload, "Download") } }
        } else null,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(enabled = canSave, onClick = save),
    )
}

/** "Find download links": the page's file and download links, with ads left out. */
@Composable
private fun FoundLinksDialog(
    links: List<FoundLink>,
    onDismiss: () -> Unit,
    onDownload: (FoundLink) -> Unit,
    onOpen: (FoundLink) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Download links") },
        text = {
            if (links.isEmpty()) {
                Text(
                    "No download links found on this page yet. If the site shows a countdown or a " +
                        "\"Generating link\" step, wait for it to finish and try again.",
                )
            } else {
                Column(
                    Modifier
                        .heightIn(max = 440.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        "Ads are left out. Tap a file to download it, or a page link to open it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    links.take(60).forEach { l ->
                        ListItem(
                            headlineContent = { Text(l.label, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                            supportingContent = {
                                Text(
                                    Shields.host(l.url) + if (l.isFile) " · file" else " · page",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            leadingContent = { Icon(if (l.isFile) Icons.Filled.FileDownload else Icons.Filled.Public, null) },
                            trailingContent = if (l.isFile) null else {
                                { IconButton(onClick = { onDownload(l) }) { Icon(Icons.Filled.FileDownload, "Download this link") } }
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.clickable { if (l.isFile) onDownload(l) else onOpen(l) },
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun FindBar(
    query: String,
    result: Pair<Int, Int>?,
    onQuery: (String) -> Unit,
    onNext: (Boolean) -> Unit,
    onClose: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 2.dp, end = 2.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Close find") }
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier
                .weight(1f)
                .height(46.dp),
        ) {
            Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) {
                        Text("Find in page", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    BasicTextField(
                        value = query,
                        onValueChange = onQuery,
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { onNext(true) }),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focus),
                    )
                }
                if (result != null && query.isNotEmpty()) {
                    Text(
                        "${result.first}/${result.second}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        IconButton(onClick = { onNext(false) }, enabled = query.isNotEmpty()) { Icon(Icons.Filled.KeyboardArrowUp, "Previous") }
        IconButton(onClick = { onNext(true) }, enabled = query.isNotEmpty()) { Icon(Icons.Filled.KeyboardArrowDown, "Next") }
    }
}

@Composable
private fun BottomBar(
    browser: Browser,
    tab: BrowserTab,
    activeDownloads: Int,
    onBack: () -> Unit,
    onOpenDownloads: () -> Unit,
    onFind: () -> Unit,
    onFindLinks: () -> Unit,
    onShowMedia: () -> Unit,
    onDownloadPage: () -> Unit,
    onShare: () -> Unit,
    onCopyLink: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    HorizontalDivider()
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(56.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack, enabled = tab.canGoBack || tab.isPopup) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
            }
            IconButton(onClick = { tab.goForward() }, enabled = tab.canGoForward) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, "Forward")
            }
            IconButton(onClick = { tab.goHome() }, enabled = tab.hasPage) {
                Icon(Icons.Filled.Home, "Home")
            }
            IconButton(onClick = { browser.showTabs = true }) {
                TabCount(browser.tabs.size)
            }
            IconButton(onClick = onOpenDownloads) {
                BadgedBox(badge = { if (activeDownloads > 0) Badge { Text("$activeDownloads") } }) {
                    Icon(Icons.Outlined.Download, "Downloads")
                }
            }
            Box {
                IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, "Menu") }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("New tab") },
                        leadingIcon = { Icon(Icons.Filled.Add, null) },
                        onClick = { menuOpen = false; browser.newTab() },
                    )
                    DropdownMenuItem(
                        text = { Text("Download this page") },
                        leadingIcon = { Icon(Icons.Filled.FileDownload, null) },
                        enabled = tab.hasPage,
                        onClick = { menuOpen = false; onDownloadPage() },
                    )
                    DropdownMenuItem(
                        text = { Text("Videos on this page") },
                        leadingIcon = { Icon(Icons.Filled.Movie, null) },
                        enabled = tab.hasPage,
                        onClick = { menuOpen = false; onShowMedia() },
                    )
                    DropdownMenuItem(
                        text = { Text("Find download links") },
                        leadingIcon = { Icon(Icons.Filled.Link, null) },
                        enabled = tab.hasPage,
                        onClick = { menuOpen = false; onFindLinks() },
                    )
                    DropdownMenuItem(
                        text = { Text("Find in page") },
                        leadingIcon = { Icon(Icons.Filled.FindInPage, null) },
                        enabled = tab.hasPage,
                        onClick = { menuOpen = false; onFind() },
                    )
                    val bookmarked = BrowserPrefs.isBookmarked(tab.url)
                    DropdownMenuItem(
                        text = { Text(if (bookmarked) "Remove bookmark" else "Add bookmark") },
                        leadingIcon = { Icon(if (bookmarked) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder, null) },
                        enabled = tab.hasPage,
                        onClick = { menuOpen = false; BrowserPrefs.toggleBookmark(tab.title, tab.url) },
                    )
                    DropdownMenuItem(
                        text = { Text("Share") },
                        leadingIcon = { Icon(Icons.Filled.Share, null) },
                        enabled = tab.hasPage,
                        onClick = { menuOpen = false; onShare() },
                    )
                    DropdownMenuItem(
                        text = { Text("Copy link") },
                        leadingIcon = { Icon(Icons.Filled.ContentCopy, null) },
                        enabled = tab.hasPage,
                        onClick = { menuOpen = false; onCopyLink() },
                    )
                    DropdownMenuItem(
                        text = { Text("Desktop site") },
                        leadingIcon = { Icon(Icons.Filled.DesktopWindows, null) },
                        trailingIcon = { Checkbox(checked = tab.desktopMode, onCheckedChange = null) },
                        onClick = { menuOpen = false; tab.setDesktop(!tab.desktopMode) },
                    )
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("Settings") },
                        leadingIcon = { Icon(Icons.Filled.Settings, null) },
                        onClick = { menuOpen = false; onOpenSettings() },
                    )
                }
            }
        }
    }
}

/** Chrome-style square with the number of open tabs. */
@Composable
private fun TabCount(count: Int) {
    Box(
        Modifier
            .size(22.dp)
            .border(2.dp, LocalContentColor.current, RoundedCornerShape(5.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (count > 99) "∞" else "$count",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
        )
    }
}

/** Shown under the address bar while typing: open/search, download directly, clipboard, bookmarks. */
@Composable
private fun OmniboxPanel(
    text: String,
    hasClip: Boolean,
    onGo: () -> Unit,
    onDownload: () -> Unit,
    onPasteGo: () -> Unit,
    onPasteDownload: () -> Unit,
    onOpen: (String) -> Unit,
) {
    val query = text.trim()
    val isLink = Browser.isLink(query)
    val matches = if (query.isEmpty()) BrowserPrefs.bookmarks.take(8) else BrowserPrefs.bookmarks.filter {
        it.title.contains(query, ignoreCase = true) || it.url.contains(query, ignoreCase = true)
    }.take(8)

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            if (query.isNotEmpty()) {
                Suggestion(
                    icon = if (isLink) Icons.Filled.Public else Icons.Filled.Search,
                    title = query,
                    subtitle = if (isLink) "Open" else "Search with ${BrowserPrefs.searchEngine.label}",
                    onClick = onGo,
                )
                if (isLink) {
                    Suggestion(Icons.Filled.FileDownload, query, "Download this link directly", onDownload)
                }
            }
            if (hasClip) {
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilledTonalButton(onClick = onPasteGo) {
                        Icon(Icons.Filled.ContentPaste, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Paste & go")
                    }
                    FilledTonalButton(onClick = onPasteDownload) {
                        Icon(Icons.Filled.FileDownload, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Paste & download")
                    }
                }
            }
            if (matches.isNotEmpty()) {
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                matches.forEach { b -> Suggestion(Icons.Filled.Bookmark, b.title, displayUrl(b.url)) { onOpen(b.url) } }
            }
        }
    }
}

@Composable
private fun Suggestion(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingContent = { Icon(icon, null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
private fun StartPage(
    onSearch: () -> Unit,
    onPasteGo: () -> Unit,
    onPasteDownload: () -> Unit,
    onOpen: (String) -> Unit,
) {
    var toRemove by remember { mutableStateOf<Bookmark?>(null) }
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Filled.FileDownload,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(56.dp),
        )
        Spacer(Modifier.height(6.dp))
        Text("DL Manager", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(20.dp))
        Surface(
            onClick = onSearch,
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
        ) {
            Row(Modifier.padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(12.dp))
                Text(
                    "Search with ${BrowserPrefs.searchEngine.label} or type a link",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = onPasteGo) {
                Icon(Icons.Filled.ContentPaste, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Paste & go")
            }
            FilledTonalButton(onClick = onPasteDownload) {
                Icon(Icons.Filled.FileDownload, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Paste & download")
            }
        }
        Spacer(Modifier.height(28.dp))
        if (BrowserPrefs.bookmarks.isNotEmpty()) {
            Text("Bookmarks", style = MaterialTheme.typography.titleSmall, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                BrowserPrefs.bookmarks.forEach { b ->
                    Column(
                        Modifier
                            .width(76.dp)
                            .combinedClickable(onClick = { onOpen(b.url) }, onLongClick = { toRemove = b }),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            Modifier
                                .size(48.dp)
                                .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                (Shields.host(b.url).firstOrNull() ?: '?').uppercase(),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            b.title,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        } else {
            Text(
                "Pages you bookmark from the ⋮ menu show up here.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.height(28.dp))
        Text(
            "Long-press a link to download it, or open a file and tap Download. Files a site sends " +
                "as downloads are caught automatically. Pop-ups are blocked unless they start a download.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }

    toRemove?.let { b ->
        AlertDialog(
            onDismissRequest = { toRemove = null },
            title = { Text("Remove bookmark?") },
            text = { Text(b.title, maxLines = 3, overflow = TextOverflow.Ellipsis) },
            confirmButton = {
                Button(onClick = { BrowserPrefs.removeBookmark(b); toRemove = null }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { toRemove = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun LongPressDialog(
    url: String,
    isImage: Boolean,
    onDismiss: () -> Unit,
    onDownload: () -> Unit,
    onNewTab: () -> Unit,
    onOpen: () -> Unit,
    onCopy: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isImage) "Image" else "Link") },
        text = {
            Column {
                Text(
                    url,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = onDownload, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.FileDownload, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (isImage) "Download image" else "Download")
                }
                Suggestion(Icons.AutoMirrored.Filled.OpenInNew, "Open in new tab", "Keeps this page open", onNewTab)
                Suggestion(Icons.Filled.Public, "Open here", "Replaces this page", onOpen)
                Suggestion(Icons.Filled.ContentCopy, "Copy link", "To paste somewhere else", onCopy)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
