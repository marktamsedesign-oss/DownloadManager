package com.markt.dlmanager.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.markt.dlmanager.engine.DownloadInfo
import com.markt.dlmanager.engine.Downloads
import com.markt.dlmanager.engine.PendingDownload
import com.markt.dlmanager.engine.Status
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

@Composable
fun SettingsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var threads by remember { mutableIntStateOf(Downloads.defaultThreads) }
    var concurrent by remember { mutableIntStateOf(Downloads.maxConcurrent) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Settings") },
        text = {
            Column {
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
                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
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
            }
        },
        confirmButton = {
            Button(onClick = {
                Downloads.defaultThreads = threads
                Downloads.maxConcurrent = concurrent
                onDismiss()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
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
