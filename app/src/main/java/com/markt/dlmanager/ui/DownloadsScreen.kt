package com.markt.dlmanager.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.markt.dlmanager.engine.DownloadInfo
import com.markt.dlmanager.engine.Downloads
import com.markt.dlmanager.engine.Status
import com.markt.dlmanager.util.Fmt
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(items: List<DownloadInfo>) {
    val context = LocalContext.current
    var showSettings by remember { mutableStateOf(false) }
    var toDelete by remember { mutableStateOf<DownloadInfo?>(null) }
    var batteryOk by remember { mutableStateOf(isBatteryUnrestricted(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { batteryOk = isBatteryUnrestricted(context) }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Downloads") },
            windowInsets = WindowInsets(0, 0, 0, 0),
            actions = {
                IconButton(onClick = { Downloads.resumeAll() }) { Icon(Icons.Filled.PlayArrow, "Resume all") }
                IconButton(onClick = { Downloads.pauseAll() }) { Icon(Icons.Filled.Pause, "Pause all") }
                IconButton(onClick = { showSettings = true }) { Icon(Icons.Filled.Settings, "Settings") }
            },
        )

        if (!batteryOk) {
            Card(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.BatteryAlert, null)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "Allow unrestricted battery so Android doesn't stop downloads in the background.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    FilledTonalButton(onClick = { requestBatteryUnrestricted(context) }) { Text("Allow") }
                }
            }
        }

        if (items.isEmpty()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(Icons.Outlined.Inbox, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.outline)
                Spacer(Modifier.height(8.dp))
                Text("No downloads yet", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Finished files are saved in Downloads/DLManager.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(items, key = { it.id }) { d ->
                    DownloadCard(d, onDelete = { toDelete = d })
                }
            }
        }
    }

    if (showSettings) SettingsDialog(onDismiss = { showSettings = false })
    toDelete?.let { d -> DeleteDialog(d, onDismiss = { toDelete = null }) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DownloadCard(d: DownloadInfo, onDelete: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var menu by remember { mutableStateOf(false) }

    Card(
        onClick = { if (d.status == Status.COMPLETED) openFile(context, d) },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    fileIcon(d.fileName),
                    contentDescription = null,
                    tint = if (d.status == Status.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(30.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        d.fileName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        statusLine(d),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                when (d.status) {
                    Status.QUEUED, Status.CONNECTING, Status.DOWNLOADING ->
                        IconButton(onClick = { Downloads.pause(d.id) }) { Icon(Icons.Filled.Pause, "Pause") }
                    Status.PAUSED ->
                        IconButton(onClick = { Downloads.resume(d.id) }) { Icon(Icons.Filled.PlayArrow, "Resume") }
                    Status.FAILED ->
                        IconButton(onClick = { Downloads.resume(d.id) }) { Icon(Icons.Filled.Refresh, "Retry") }
                    Status.COMPLETED ->
                        IconButton(onClick = { shareFile(context, d) }) { Icon(Icons.Filled.Share, "Share") }
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Copy link") },
                            leadingIcon = { Icon(Icons.Filled.ContentCopy, null) },
                            onClick = { menu = false; clipboard.setText(AnnotatedString(d.url)) },
                        )
                        if (d.status != Status.COMPLETED) {
                            DropdownMenuItem(
                                text = { Text("Restart from 0") },
                                leadingIcon = { Icon(Icons.Filled.RestartAlt, null) },
                                onClick = { menu = false; Downloads.restart(d.id) },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Delete") },
                            leadingIcon = { Icon(Icons.Filled.Delete, null) },
                            onClick = { menu = false; onDelete() },
                        )
                    }
                }
            }

            if (d.status != Status.COMPLETED) {
                Spacer(Modifier.height(8.dp))
                Box(Modifier.padding(end = 8.dp)) { SegmentBar(d) }
            }
            val error = d.error
            if (error != null && d.status != Status.COMPLETED) {
                Spacer(Modifier.height(4.dp))
                Text(
                    error,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (d.status == Status.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
        }
    }
}

/** IDM-style bar: one block per thread, showing how far each part has got. */
@Composable
private fun SegmentBar(d: DownloadInfo) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    val fill = when (d.status) {
        Status.FAILED -> MaterialTheme.colorScheme.error
        Status.PAUSED -> MaterialTheme.colorScheme.outline
        else -> MaterialTheme.colorScheme.primary
    }
    val divider = MaterialTheme.colorScheme.surface
    when {
        d.totalBytes > 0 && d.segments.isNotEmpty() -> Canvas(
            Modifier
                .fillMaxWidth()
                .height(10.dp)
                .clip(RoundedCornerShape(5.dp))
        ) {
            drawRect(track)
            val total = d.totalBytes.toFloat()
            val gap = 1.dp.toPx()
            d.segments.forEach { s ->
                val x = s.start.toFloat() / total * size.width
                val w = s.downloaded.toFloat() / total * size.width
                drawRect(fill, topLeft = Offset(x, 0f), size = Size(w, size.height))
                if (s.start > 0) drawRect(divider, topLeft = Offset(x, 0f), size = Size(gap, size.height))
            }
        }
        d.totalBytes > 0 -> LinearProgressIndicator(
            progress = { d.progress },
            modifier = Modifier.fillMaxWidth(),
        )
        d.status.isRunning -> LinearProgressIndicator(Modifier.fillMaxWidth())
        else -> LinearProgressIndicator(progress = { 0f }, modifier = Modifier.fillMaxWidth())
    }
}

private fun statusLine(d: DownloadInfo): String {
    val total = if (d.totalBytes > 0) Fmt.bytes(d.totalBytes) else "?"
    val percent = if (d.totalBytes > 0) " (${(d.progress * 100).toInt()}%)" else ""
    return when (d.status) {
        Status.QUEUED -> "Queued · ${Fmt.bytes(d.downloadedBytes)} / $total"
        Status.CONNECTING -> "Connecting…"
        Status.DOWNLOADING -> buildList {
            add("${Fmt.bytes(d.downloadedBytes)} / $total$percent")
            if (d.speed > 0) add("${Fmt.bytes(d.speed)}/s")
            if (d.speed > 0 && d.totalBytes > 0) {
                Fmt.duration((d.totalBytes - d.downloadedBytes) / d.speed).takeIf { it.isNotEmpty() }?.let { add("$it left") }
            }
            add(if (d.resumable) "${d.activeThreads} threads" else "1 thread (server limit)")
        }.joinToString(" · ")
        Status.PAUSED -> "Paused · ${Fmt.bytes(d.downloadedBytes)} / $total$percent"
        Status.FAILED -> "Failed · ${Fmt.bytes(d.downloadedBytes)} / $total"
        Status.COMPLETED -> {
            val date = d.completedAt?.let { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it)) }
            listOfNotNull(Fmt.bytes(d.totalBytes), date).joinToString(" · ")
        }
    }
}

private fun fileIcon(name: String): ImageVector = when (name.substringAfterLast('.', "").lowercase()) {
    "mp4", "mkv", "mov", "webm", "avi", "3gp", "m4v", "ts" -> Icons.Filled.Movie
    "mp3", "m4a", "wav", "flac", "aac", "ogg", "opus" -> Icons.Filled.MusicNote
    "jpg", "jpeg", "png", "gif", "webp", "heic", "bmp" -> Icons.Filled.Image
    "pdf" -> Icons.Filled.PictureAsPdf
    "zip", "rar", "7z", "tar", "gz", "tgz", "xz" -> Icons.Filled.Archive
    "apk", "apks", "xapk" -> Icons.Filled.Android
    "xls", "xlsx", "csv" -> Icons.Filled.TableChart
    "doc", "docx", "txt", "rtf", "ppt", "pptx" -> Icons.Filled.Description
    else -> Icons.Filled.InsertDriveFile
}

private fun mimeOf(context: Context, d: DownloadInfo, uri: Uri): String =
    d.mimeType ?: context.contentResolver.getType(uri) ?: "*/*"

fun openFile(context: Context, d: DownloadInfo) {
    val uri = d.contentUri?.let(Uri::parse) ?: return
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, mimeOf(context, d, uri))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "No app on this phone can open this file", Toast.LENGTH_SHORT).show()
    } catch (e: SecurityException) {
        Toast.makeText(context, "The file was moved or deleted", Toast.LENGTH_SHORT).show()
    }
}

fun shareFile(context: Context, d: DownloadInfo) {
    val uri = d.contentUri?.let(Uri::parse) ?: return
    val send = Intent(Intent.ACTION_SEND)
        .setType(mimeOf(context, d, uri))
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, d.fileName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

fun isBatteryUnrestricted(context: Context): Boolean =
    context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

fun requestBatteryUnrestricted(context: Context) {
    try {
        context.startActivity(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (e: Exception) {
        context.startActivity(
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
