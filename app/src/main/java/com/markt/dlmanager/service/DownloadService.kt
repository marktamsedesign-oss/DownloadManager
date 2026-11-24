package com.markt.dlmanager.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.markt.dlmanager.Notifications
import com.markt.dlmanager.engine.DownloadInfo
import com.markt.dlmanager.engine.Downloads
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the app process alive while downloads run, so they continue
 * with the screen off, in the background, and after the app is swiped away from Recents.
 */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastUpdate = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        goForeground(Downloads.state.value)
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DLManager:downloads")
            .apply {
                setReferenceCounted(false)
                acquire(12 * 60 * 60 * 1000L)
            }
        scope.launch { Downloads.state.collect { onState(it) } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground(Downloads.state.value)
        if (intent?.action == ACTION_PAUSE_ALL) Downloads.pauseAll()
        return START_NOT_STICKY
    }

    private fun goForeground(list: List<DownloadInfo>) {
        ServiceCompat.startForeground(
            this,
            Notifications.SERVICE_ID,
            Notifications.progress(this, list),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
    }

    private fun onState(list: List<DownloadInfo>) {
        if (Downloads.releaseServiceIfIdle()) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastUpdate < 1_000) return
        lastUpdate = now
        Notifications.post(this, Notifications.SERVICE_ID, Notifications.progress(this, list))
    }

    /** Android 15+: the system's daily limit for background data-sync work was reached. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Downloads.onServiceTimeout()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_PAUSE_ALL = "com.markt.dlmanager.PAUSE_ALL"

        @Volatile
        var running = false

        fun start(ctx: Context) {
            try {
                ContextCompat.startForegroundService(ctx, Intent(ctx, DownloadService::class.java))
            } catch (e: Exception) {
                // Android blocks starting it from the background; downloads still run while the app is alive
                running = false
            }
        }
    }
}
