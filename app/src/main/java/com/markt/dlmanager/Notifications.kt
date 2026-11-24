package com.markt.dlmanager

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.markt.dlmanager.engine.DownloadInfo
import com.markt.dlmanager.engine.Status
import com.markt.dlmanager.service.DownloadService
import com.markt.dlmanager.ui.MainActivity
import com.markt.dlmanager.util.Fmt

object Notifications {
    const val CHANNEL_PROGRESS = "progress"
    const val CHANNEL_DONE = "done"
    const val SERVICE_ID = 1

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_PROGRESS, "Download progress", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_DONE, "Finished downloads", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    private fun openDownloads(ctx: Context): PendingIntent {
        val intent = Intent(ctx, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_SHOW_DOWNLOADS, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            ctx, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun progress(ctx: Context, list: List<DownloadInfo>): Notification {
        val running = list.filter { it.status.isRunning }
        val queued = list.count { it.status == Status.QUEUED }
        val total = running.sumOf { it.totalBytes.coerceAtLeast(0) }
        val done = running.sumOf { it.downloadedBytes }
        val speed = running.sumOf { it.speed }
        val threads = running.sumOf { it.activeThreads }

        val title = when {
            running.size == 1 -> running[0].fileName
            running.isNotEmpty() -> "Downloading ${running.size} files"
            queued > 0 -> "$queued download(s) waiting"
            else -> "DL Manager"
        }
        val text = buildString {
            if (running.isNotEmpty()) {
                append(Fmt.bytes(done))
                if (total > 0) append(" / ").append(Fmt.bytes(total))
                append(" · ").append(Fmt.bytes(speed)).append("/s")
                if (threads > 0) append(" · ").append(threads).append(" threads")
            }
            if (queued > 0 && running.isNotEmpty()) append(" · $queued queued")
        }
        val percent = if (total > 0) ((done * 100) / total).toInt() else 0

        val pauseIntent = PendingIntent.getService(
            ctx, 1,
            Intent(ctx, DownloadService::class.java).setAction(DownloadService.ACTION_PAUSE_ALL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(ctx, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setProgress(100, percent, running.isNotEmpty() && total <= 0)
            .setContentIntent(openDownloads(ctx))
            .addAction(0, "Pause all", pauseIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    fun completed(ctx: Context, id: String, fileName: String) {
        post(ctx, id.hashCode(), NotificationCompat.Builder(ctx, CHANNEL_DONE)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle("Download complete")
            .setContentText(fileName)
            .setAutoCancel(true)
            .setContentIntent(openDownloads(ctx))
            .build())
    }

    fun failed(ctx: Context, id: String, fileName: String, reason: String?) {
        post(ctx, id.hashCode(), NotificationCompat.Builder(ctx, CHANNEL_DONE)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle("Download failed")
            .setContentText(listOfNotNull(fileName, reason).joinToString(" — "))
            .setAutoCancel(true)
            .setContentIntent(openDownloads(ctx))
            .build())
    }

    fun post(ctx: Context, id: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        NotificationManagerCompat.from(ctx).notify(id, notification)
    }
}
