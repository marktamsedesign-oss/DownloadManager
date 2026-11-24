package com.markt.dlmanager.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.markt.dlmanager.browser.Browser
import com.markt.dlmanager.browser.BrowserTab
import com.markt.dlmanager.browser.Shields

/** Tab switcher: tap a tab to show it, × to close it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TabsScreen(browser: Browser) {
    var confirmCloseAll by remember { mutableStateOf(false) }
    BackHandler { browser.showTabs = false }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(if (browser.tabs.size == 1) "1 tab" else "${browser.tabs.size} tabs") },
            navigationIcon = {
                IconButton(onClick = { browser.showTabs = false }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            },
            actions = {
                TextButton(onClick = { confirmCloseAll = true }) { Text("Close all") }
                IconButton(onClick = { browser.newTab() }) { Icon(Icons.Filled.Add, "New tab") }
            },
            windowInsets = WindowInsets(0, 0, 0, 0),
        )
        LazyColumn(
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(browser.tabs, key = { _, t -> t.id }) { index, t ->
                TabCard(
                    tab = t,
                    selected = index == browser.currentIndex,
                    onClick = { browser.select(t) },
                    onClose = { browser.closeTab(t) },
                )
            }
        }
    }

    if (confirmCloseAll) {
        AlertDialog(
            onDismissRequest = { confirmCloseAll = false },
            title = { Text("Close all tabs?") },
            confirmButton = {
                Button(onClick = { confirmCloseAll = false; browser.closeAllTabs() }) { Text("Close all") }
            },
            dismissButton = { TextButton(onClick = { confirmCloseAll = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun TabCard(tab: BrowserTab, selected: Boolean, onClick: () -> Unit, onClose: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Row(Modifier.padding(start = 14.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            val icon = tab.favicon
            if (icon != null) {
                Image(icon.asImageBitmap(), contentDescription = null, modifier = Modifier.size(24.dp))
            } else {
                Icon(Icons.Filled.Public, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    tab.title.ifBlank { if (tab.hasPage) Shields.host(tab.url) else "New tab" },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (tab.hasPage) Shields.host(tab.url) else "Start page",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Close tab") }
        }
    }
}
