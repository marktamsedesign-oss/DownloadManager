package com.markt.dlmanager.ui

import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.markt.dlmanager.engine.PendingDownload

@Composable
fun BrowserScreen(browser: BrowserController, onDownload: (PendingDownload) -> Unit) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val clipboard = LocalClipboardManager.current
    var text by remember { mutableStateOf(browser.url) }
    var focused by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }

    LaunchedEffect(browser.url) { if (!focused) text = browser.url }
    BackHandler(enabled = browser.canGoBack) { browser.goBack() }

    fun go(value: String) {
        focusManager.clearFocus()
        browser.load(value)
    }

    fun downloadDirect(value: String) {
        focusManager.clearFocus()
        if (BrowserController.isLink(value)) {
            BrowserController.toUrl(value)?.let { onDownload(PendingDownload.fromUrl(it, browser.userAgent)) }
        } else {
            Toast.makeText(context, "That doesn't look like a link", Toast.LENGTH_SHORT).show()
        }
    }

    Column(Modifier.fillMaxSize()) {
        // Address bar
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 4.dp, top = 6.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier
                    .weight(1f)
                    .onFocusChanged { focused = it.isFocused },
                singleLine = true,
                placeholder = { Text("Paste a link or search") },
                leadingIcon = { Icon(Icons.Filled.Link, contentDescription = null) },
                trailingIcon = {
                    if (focused && text.isNotEmpty()) {
                        IconButton(onClick = { text = "" }) { Icon(Icons.Filled.Close, "Clear") }
                    }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { go(text) }),
                shape = RoundedCornerShape(28.dp),
                textStyle = MaterialTheme.typography.bodyMedium,
            )
            IconButton(onClick = {
                val pasted = clipboard.getText()?.text?.trim().orEmpty()
                if (pasted.isNotEmpty()) {
                    text = pasted
                    go(pasted)
                }
            }) { Icon(Icons.Filled.ContentPaste, "Paste and open") }
            Box {
                IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, "More") }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Open in browser") },
                        leadingIcon = { Icon(Icons.Filled.Language, null) },
                        onClick = { menuOpen = false; go(text) },
                    )
                    DropdownMenuItem(
                        text = { Text("Download link directly") },
                        leadingIcon = { Icon(Icons.Filled.FileDownload, null) },
                        onClick = { menuOpen = false; downloadDirect(text) },
                    )
                    DropdownMenuItem(
                        text = { Text("Paste & download") },
                        leadingIcon = { Icon(Icons.Filled.ContentPaste, null) },
                        onClick = {
                            menuOpen = false
                            val pasted = clipboard.getText()?.text?.trim().orEmpty()
                            text = pasted
                            downloadDirect(pasted)
                        },
                    )
                }
            }
        }

        if (browser.progress in 1..99) {
            LinearProgressIndicator(progress = { browser.progress / 100f }, modifier = Modifier.fillMaxWidth())
        } else {
            Spacer(Modifier.height(4.dp))
        }

        // Page
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            AndroidView(
                factory = {
                    (browser.webView.parent as? ViewGroup)?.removeView(browser.webView)
                    browser.webView
                },
                modifier = Modifier.fillMaxSize(),
            )
            if (!browser.hasPage) StartPage()
        }

        // Bottom toolbar
        HorizontalDivider()
        Surface(tonalElevation = 2.dp) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { browser.goBack() }, enabled = browser.canGoBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                }
                IconButton(onClick = { browser.goForward() }, enabled = browser.canGoForward) {
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, "Forward")
                }
                IconButton(onClick = { browser.reloadOrStop() }, enabled = browser.hasPage) {
                    Icon(if (browser.progress in 1..99) Icons.Filled.Close else Icons.Filled.Refresh, "Reload")
                }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = { browser.currentPageDownload()?.let(onDownload) },
                    enabled = browser.hasPage,
                ) {
                    Icon(Icons.Filled.FileDownload, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Download", fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.width(4.dp))
            }
        }
    }

    // Long-press menu
    browser.longPressUrl?.let { link ->
        AlertDialog(
            onDismissRequest = { browser.longPressUrl = null },
            title = { Text("Link") },
            text = { Text(link, maxLines = 4, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall) },
            confirmButton = {
                Button(onClick = {
                    browser.longPressUrl = null
                    onDownload(PendingDownload.fromUrl(link, browser.userAgent).copy(referer = browser.url))
                }) {
                    Icon(Icons.Filled.FileDownload, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Download")
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = {
                        clipboard.setText(AnnotatedString(link))
                        browser.longPressUrl = null
                    }) {
                        Icon(Icons.Filled.ContentCopy, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Copy")
                    }
                    TextButton(onClick = {
                        browser.longPressUrl = null
                        browser.load(link)
                    }) { Text("Open") }
                }
            },
        )
    }
}

@Composable
private fun StartPage() {
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Filled.FileDownload,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(72.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text("Paste a link to start", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            "Open any page and tap Download, or long-press a link. Files the site sends as " +
                "downloads are caught automatically. You can also share a link to DL Manager from any app.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
