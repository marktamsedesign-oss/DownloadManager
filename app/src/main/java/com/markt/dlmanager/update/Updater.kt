package com.markt.dlmanager.update

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import com.markt.dlmanager.BuildConfig
import com.markt.dlmanager.engine.DownloadInfo
import com.markt.dlmanager.engine.Downloads
import com.markt.dlmanager.engine.HttpStatusException
import com.markt.dlmanager.engine.Http
import com.markt.dlmanager.engine.PendingDownload
import com.markt.dlmanager.engine.Status
import com.markt.dlmanager.ui.openFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.IOException
import java.net.UnknownHostException
import java.security.MessageDigest

/** What update.json (published with every GitHub release by the build workflow) says. */
data class UpdateInfo(
    val versionCode: Long,
    val versionName: String,
    val apkUrl: String,
    val sha256: String?,
    val notes: String,
    val size: Long,
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val info: UpdateInfo) : UpdateState
    data class Downloading(val info: UpdateInfo, val downloadId: String) : UpdateState
    data class Ready(val info: UpdateInfo, val downloadId: String) : UpdateState
    data class Failed(val message: String) : UpdateState
}

/**
 * Over-the-air updates: reads update.json from the latest GitHub release, downloads the new APK
 * with the app's own multi-thread engine, checks its SHA-256 and opens Android's installer.
 * Every build is signed with the same key, so the new version installs over the old one.
 */
object Updater {
    private const val APK_MIME = "application/vnd.android.package-archive"
    private const val CHECK_EVERY_MS = 6 * 60 * 60 * 1000L

    private lateinit var app: Context
    private lateinit var prefs: SharedPreferences
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    val versionName: String get() = BuildConfig.VERSION_NAME
    val versionCode: Long get() = BuildConfig.VERSION_CODE.toLong()

    var autoCheck: Boolean
        get() = prefs.getBoolean("autoCheck", true)
        set(v) = prefs.edit().putBoolean("autoCheck", v).apply()

    fun init(context: Context) {
        app = context.applicationContext
        prefs = app.getSharedPreferences("updates", Context.MODE_PRIVATE)
        scope.launch { Downloads.state.collect { onDownloads(it) } }
    }

    /** Called when the app opens: checks at most every few hours, quietly. */
    fun checkIfDue() {
        if (!::prefs.isInitialized || !autoCheck) return
        if (System.currentTimeMillis() - prefs.getLong("lastCheck", 0) < CHECK_EVERY_MS) return
        check(manual = false)
    }

    fun check(manual: Boolean) {
        val s = _state.value
        if (s is UpdateState.Checking || s is UpdateState.Downloading || s is UpdateState.Ready) return
        _state.value = UpdateState.Checking
        scope.launch {
            _state.value = try {
                val info = fetch()
                prefs.edit().putLong("lastCheck", System.currentTimeMillis()).apply()
                if (info.versionCode > versionCode) UpdateState.Available(info) else UpdateState.UpToDate
            } catch (e: Exception) {
                if (manual) UpdateState.Failed(message(e)) else UpdateState.Idle
            }
        }
    }

    fun download(info: UpdateInfo) {
        val name = "DLManager-${info.versionName.ifBlank { info.versionCode.toString() }}.apk"
        val id = Downloads.add(
            PendingDownload(
                url = info.apkUrl,
                fileName = name,
                nameKnown = true,
                mimeType = APK_MIME,
                size = info.size,
                referer = null,
                userAgent = "DLManager/$versionName",
            ),
            name,
            threads = 4,
        )
        _state.value = UpdateState.Downloading(info, id)
    }

    fun install(context: Context, ready: UpdateState.Ready) {
        if (!context.packageManager.canRequestPackageInstalls()) {
            Toast.makeText(context, "Allow DL Manager to install apps, then tap Install again", Toast.LENGTH_LONG).show()
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return
        }
        val d = Downloads.state.value.firstOrNull { it.id == ready.downloadId }
        if (d == null) {
            _state.value = UpdateState.Available(ready.info)
            return
        }
        openFile(context, d)
    }

    private fun fetch(): UpdateInfo {
        val c = Http.connect(
            BuildConfig.UPDATE_URL,
            mapOf("User-Agent" to "DLManager/$versionName", "Accept" to "application/json"),
            null,
        )
        try {
            val code = c.responseCode
            if (code == 404) throw IOException("No update has been published yet")
            if (code !in 200..299) throw HttpStatusException(code)
            val o = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            val apk = o.getString("apk")
            if (!apk.startsWith("https://")) throw IOException("Invalid update link")
            return UpdateInfo(
                versionCode = o.getLong("versionCode"),
                versionName = o.optString("versionName"),
                apkUrl = apk,
                sha256 = o.optString("sha256").lowercase().takeIf { it.length == 64 },
                notes = o.optString("notes"),
                size = o.optLong("size", -1),
            )
        } finally {
            c.disconnect()
        }
    }

    private fun onDownloads(list: List<DownloadInfo>) {
        val s = _state.value as? UpdateState.Downloading ?: return
        val d = list.firstOrNull { it.id == s.downloadId }
        if (d == null) {
            _state.value = UpdateState.Available(s.info)       // the user deleted the download
            return
        }
        when (d.status) {
            Status.COMPLETED -> _state.value = verify(s.info, d)
            Status.FAILED -> _state.value = UpdateState.Failed("Update download failed: ${d.error ?: "unknown error"}")
            else -> Unit
        }
    }

    private fun verify(info: UpdateInfo, d: DownloadInfo): UpdateState {
        val expected = info.sha256 ?: return UpdateState.Ready(info, d.id)
        val actual = runCatching {
            val digest = MessageDigest.getInstance("SHA-256")
            app.contentResolver.openInputStream(Uri.parse(d.contentUri))!!.use { input ->
                val buf = ByteArray(256 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    digest.update(buf, 0, n)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }.getOrNull()
        if (actual == expected) return UpdateState.Ready(info, d.id)
        Downloads.remove(d.id, deleteFile = true)
        return UpdateState.Failed("The downloaded update was damaged — tap Check now to try again")
    }

    private fun message(e: Exception): String = when (e) {
        is UnknownHostException -> "No internet connection"
        is HttpStatusException -> "Update server error (HTTP ${e.code})"
        else -> e.message ?: e.javaClass.simpleName
    }
}
