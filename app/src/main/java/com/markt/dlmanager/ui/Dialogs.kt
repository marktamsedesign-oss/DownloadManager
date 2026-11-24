package com.markt.dlmanager.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.markt.dlmanager.browser.Browser
import com.markt.dlmanager.browser.BrowserPrefs
import com.markt.dlmanager.browser.SearchEngine
import com.markt.dlmanager.engine.DownloadInfo
import com.markt.dlmanager.engine.Downloads
import com.markt.dlmanager.engine.PendingDownload
import com.markt.dlmanager.engine.Status
import com.markt.dlmanager.update.UpdateState
import com.markt.dlmanager.update.Updater
import com.markt.dlmanager.util.Fmt
import kotlin.math.roundToInt

/** "Download file?" — rename, pick the number of threads, then start. */
@Composable
fun DownloadDialog(
    pending: PendingDownload,
    onDismiss: () -> Unit,
    onOpenInBrowser: () -> Unit,
    onConfirm: (fileName: String, threads: Int) -> Unit,
) {
    var name by remember(pending) { mutableStateOf(pending.fileName) }
    var threads by remember(pending) { mutableIntStateOf(Downloads.defaultThreads) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Download file") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Save as") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                InfoRow("Size", if (pending.size > 0) Fmt.bytes(pending.size) else "Checked when it starts")
                pending.mimeType?.let { InfoRow("Type", it) }
                Spacer(Modifier.height(10.dp))
                Text("Threads: $threads", fontWeight = FontWeight.Medium)
                Slider(
                    value = threads.toFloat(),
                    onValueChange = { threads = it.roundToInt() },
                    valueRange = 1f..Downloads.MAX_THREADS.toFloat(),
                    steps = Downloads.MAX_THREADS - 2,
                )
                Text(
                    "More threads = faster on most servers. If a site blocks you or errors, use fewer (4–8).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    pending.url,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                TextButton(onClick = onOpenInBrowser) { Text("Open in browser instead") }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(name.trim().ifEmpty { pending.fileName }, threads) }) {
                Text("Download")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.Medium)
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun SettingsDialog(browser: Browser, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var threads by remember { mutableIntStateOf(Downloads.defaultThreads) }
    var concurrent by remember { mutableIntStateOf(Downloads.maxConcurrent) }
    var engine by remember { mutableStateOf(BrowserPrefs.searchEngine) }
    var blockPopups by remember { mutableStateOf(BrowserPrefs.blockPopups) }
    var blockAds by remember { mutableStateOf(BrowserPrefs.blockAdHosts) }
    var autoUpdate by remember { mutableStateOf(Updater.autoCheck) }
    var confirmClear by remember { mutableStateOf(false) }
    val update by Updater.state.collectAsStateWithLifecycle()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Settings") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                SectionTitle("Downloads")
                Text("Default threads per download: $threads", fontWeight = FontWeight.Medium)
                Slider(
                    value = threads.toFloat(),
                    onValueChange = { threads = it.roundToInt() },
                    valueRange = 1f..Downloads.MAX_THREADS.toFloat(),
                    steps = Downloads.MAX_THREADS - 2,
                )
                Spacer(Modifier.height(8.dp))
                Text("Downloads at the same time: $concurrent", fontWeight = FontWeight.Medium)
                Slider(
                    value = concurrent.toFloat(),
                    onValueChange = { concurrent = it.roundToInt() },
                    valueRange = 1f..5f,
                    steps = 3,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (isBatteryUnrestricted(context)) "Battery: unrestricted ✓" else "Battery: optimized (may stop downloads)",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(onClick = { requestBatteryUnrestricted(context) }) { Text("Change") }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "Files are saved in Downloads/DLManager.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                SectionDivider()
                SectionTitle("Browser")
                Text("Search engine", fontWeight = FontWeight.Medium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SearchEngine.entries.forEach { e ->
                        FilterChip(selected = engine == e, onClick = { engine = e }, label = { Text(e.label) })
                    }
                }
                SwitchRow(
                    title = "Block pop-ups",
                    subtitle = "New windows are blocked unless they start a download",
                    checked = blockPopups,
                    onChange = { blockPopups = it },
                )
                SwitchRow(
                    title = "Block pop-up ad networks",
                    subtitle = "Stops known pop-under and redirect ad servers",
                    checked = blockAds,
                    onChange = { blockAds = it },
                )
                if (BrowserPrefs.popupSites.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text("Pop-ups allowed on", fontWeight = FontWeight.Medium)
                    BrowserPrefs.popupSites.toList().forEach { site ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(site, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            IconButton(onClick = { BrowserPrefs.setPopupsAllowed(site, false) }) {
                                Icon(Icons.Filled.Close, "Block pop-ups on $site again")
                            }
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                OutlinedButton(onClick = { confirmClear = true }) { Text("Clear cookies & site data") }

                SectionDivider()
                SectionTitle("Updates")
                Text(
                    "Version ${Updater.versionName} (${Updater.versionCode})",
                    style = MaterialTheme.typography.bodyMedium,
                )
                SwitchRow(
                    title = "Check for updates automatically",
                    subtitle = "New versions are published on GitHub by the build workflow",
                    checked = autoUpdate,
                    onChange = { autoUpdate = it },
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        onClick = { Updater.check(manual = true) },
                        enabled = update !is UpdateState.Checking && update !is UpdateState.Downloading,
                    ) { Text("Check now") }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        when (val u = update) {
                            UpdateState.Idle -> ""
                            UpdateState.Checking -> "Checking…"
                            UpdateState.UpToDate -> "You have the latest version"
                            is UpdateState.Available -> "Version ${u.info.versionName} is available"
                            is UpdateState.Downloading -> "Downloading ${u.info.versionName}…"
                            is UpdateState.Ready -> "Version ${u.info.versionName} is ready to install"
                            is UpdateState.Failed -> u.message
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (update is UpdateState.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                Downloads.defaultThreads = threads
                Downloads.maxConcurrent = concurrent
                BrowserPrefs.searchEngine = engine
                BrowserPrefs.blockPopups = blockPopups
                BrowserPrefs.blockAdHosts = blockAds
                Updater.autoCheck = autoUpdate
                onDismiss()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear cookies & site data?") },
            text = { Text("You'll be signed out of websites. Downloads already in the list keep working until they need a new login.") },
            confirmButton = {
                Button(onClick = { confirmClear = false; browser.clearBrowsingData() }) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

@Composable
private fun SectionDivider() {
    Spacer(Modifier.height(12.dp))
    HorizontalDivider()
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** Paste a link to download it without opening the browser. */
@Composable
fun AddLinkDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    val clipboard = LocalClipboardManager.current
    var text by remember {
        val clip = clipboard.getText()?.text?.trim().orEmpty()
        mutableStateOf(if (Browser.isLink(clip)) clip else "")
    }
    val url = Browser.toUrl(text).takeIf { Browser.isLink(text) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Download a link") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Link") },
                placeholder = { Text("https://…") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { url?.let(onConfirm) }),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            Button(onClick = { url?.let(onConfirm) }, enabled = url != null) { Text("Next") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Shows "update available" / download progress / "install" for OTA updates. */
@Composable
fun UpdatePrompt(downloads: List<DownloadInfo>) {
    val context = LocalContext.current
    val state by Updater.state.collectAsStateWithLifecycle()
    var closed by rememberSaveable { mutableStateOf("") }
    val key = when (val s = state) {
        is UpdateState.Available -> "available-${s.info.versionCode}"
        is UpdateState.Downloading -> "downloading-${s.info.versionCode}"
        is UpdateState.Ready -> "ready-${s.info.versionCode}"
        else -> return
    }
    if (key == closed) return
    val hide = { closed = key }

    when (val s = state) {
        is UpdateState.Available -> AlertDialog(
            onDismissRequest = hide,
            title = { Text("Update available") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("DL Manager ${s.info.versionName} (you have ${Updater.versionName})", fontWeight = FontWeight.Medium)
                    if (s.info.size > 0) Text(Fmt.bytes(s.info.size), style = MaterialTheme.typography.bodySmall)
                    if (s.info.notes.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(s.info.notes, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            },
            confirmButton = { Button(onClick = { Updater.download(s.info) }) { Text("Update") } },
            dismissButton = { TextButton(onClick = hide) { Text("Later") } },
        )
        is UpdateState.Downloading -> {
            val d = downloads.firstOrNull { it.id == s.downloadId }
            AlertDialog(
                onDismissRequest = hide,
                title = { Text("Downloading update") },
                text = {
                    Column {
                        Text("DL Manager ${s.info.versionName}")
                        Spacer(Modifier.height(10.dp))
                        if (d != null && d.totalBytes > 0) {
                            LinearProgressIndicator(progress = { d.progress }, modifier = Modifier.fillMaxWidth())
                        } else {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                    }
                },
                confirmButton = { TextButton(onClick = hide) { Text("Hide") } },
            )
        }
        is UpdateState.Ready -> AlertDialog(
            onDismissRequest = hide,
            title = { Text("Update ready") },
            text = { Text("DL Manager ${s.info.versionName} is downloaded and checked. Tap Install, then confirm in Android's installer.") },
            confirmButton = { Button(onClick = { Updater.install(context, s) }) { Text("Install") } },
            dismissButton = { TextButton(onClick = hide) { Text("Later") } },
        )
        else -> Unit
    }
}

@Composable
fun DeleteDialog(d: DownloadInfo, onDismiss: () -> Unit) {
    val completed = d.status == Status.COMPLETED
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (completed) "Delete download?" else "Cancel download?") },
        text = { Text(d.fileName, maxLines = 3, overflow = TextOverflow.Ellipsis) },
        confirmButton = {
            Button(onClick = { Downloads.remove(d.id, deleteFile = true); onDismiss() }) {
                Text(if (completed) "Delete file" else "Cancel download")
            }
        },
        dismissButton = {
            if (completed) {
                TextButton(onClick = { Downloads.remove(d.id, deleteFile = false); onDismiss() }) {
                    Text("Remove from list only")
                }
            } else {
                TextButton(onClick = onDismiss) { Text("Keep") }
            }
        },
    )
}
