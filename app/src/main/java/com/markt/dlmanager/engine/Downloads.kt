package com.markt.dlmanager.engine

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.StatFs
import android.os.SystemClock
import android.webkit.CookieManager
import android.webkit.WebSettings
import com.markt.dlmanager.Notifications
import com.markt.dlmanager.service.DownloadService
import com.markt.dlmanager.util.Fmt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.ByteBuffer
import java.util.UUID
import kotlin.math.min

/**
 * Multi-thread download engine.
 *
 * - The file is split into N byte ranges, each fetched by its own connection (HTTP Range requests).
 * - When a thread finishes early it takes half of the biggest remaining part ("dynamic splitting"),
 *   so all threads stay busy until the end — the same trick IDM uses.
 * - Progress of every part is saved, so pause/resume, app restarts and network drops continue
 *   where they stopped.
 * - Servers that don't support ranges fall back to a normal single-connection download.
 */
object Downloads {
    const val MAX_THREADS = 32
    private const val MIN_SPLIT = 1L shl 20          // never create a part smaller than 1 MiB
    private const val BUFFER = 64 * 1024
    private const val MAX_RETRIES = 12               // failures while online before giving up

    private lateinit var app: Context
    private lateinit var prefs: SharedPreferences
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val tasks = LinkedHashMap<String, Task>()
    private val _state = MutableStateFlow<List<DownloadInfo>>(emptyList())
    val state: StateFlow<List<DownloadInfo>> = _state.asStateFlow()
    private var ticker: Job? = null
    private val stateFile by lazy { File(app.filesDir, "downloads.json") }
    private val saveLock = Any()

    var defaultThreads: Int
        get() = prefs.getInt("threads", 8)
        set(v) = prefs.edit().putInt("threads", v.coerceIn(1, MAX_THREADS)).apply()

    var maxConcurrent: Int
        get() = prefs.getInt("concurrent", 3)
        set(v) {
            prefs.edit().putInt("concurrent", v.coerceIn(1, 10)).apply()
            schedule()
        }

    fun init(context: Context) {
        app = context.applicationContext
        prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)
        load()
        publish()
    }

    // region Public controls

    fun add(p: PendingDownload, fileName: String, threads: Int): String {
        val headers = buildMap {
            runCatching { CookieManager.getInstance().getCookie(p.url) }.getOrNull()
                ?.takeIf { it.isNotBlank() }?.let { put("Cookie", it) }
            put("User-Agent", p.userAgent ?: WebSettings.getDefaultUserAgent(app))
            p.referer?.takeIf { it.startsWith("http") && it != p.url }?.let { put("Referer", it) }
        }
        val name = sanitize(fileName)
        val t = Task(
            id = UUID.randomUUID().toString(),
            url = p.url,
            fileName = name,
            nameIsFinal = p.nameKnown || name != sanitize(p.fileName),
            headers = headers,
            threads = threads.coerceIn(1, MAX_THREADS),
            createdAt = System.currentTimeMillis(),
        )
        t.mimeType = p.mimeType
        synchronized(this) { tasks[t.id] = t }
        save(); publish(); schedule()
        return t.id
    }

    @Synchronized
    fun schedule() {
        if (!::app.isInitialized) return
        val running = tasks.values.count { it.status.isRunning }
        val free = maxConcurrent - running
        if (free > 0) {
            tasks.values.filter { it.status == Status.QUEUED }
                .sortedBy { it.createdAt }
                .take(free)
                .forEach { start(it) }
        }
        if (tasks.values.any { it.status.isActive } && !DownloadService.running) {
            DownloadService.running = true
            DownloadService.start(app)
        }
    }

    @Synchronized
    fun pause(id: String) {
        val t = tasks[id] ?: return
        if (!t.status.isActive) return
        t.status = Status.PAUSED
        t.speed = 0
        stopTask(t)
        save(); publish(); schedule()
    }

    @Synchronized
    fun resume(id: String) {
        val t = tasks[id] ?: return
        if (t.status != Status.PAUSED && t.status != Status.FAILED) return
        t.status = Status.QUEUED
        t.error = null
        save(); publish(); schedule()
    }

    @Synchronized
    fun pauseAll() {
        tasks.values.filter { it.status.isActive }.forEach {
            it.status = Status.PAUSED
            it.speed = 0
            stopTask(it)
        }
        save(); publish(); schedule()
    }

    @Synchronized
    fun resumeAll() {
        tasks.values.filter { it.status == Status.PAUSED || it.status == Status.FAILED }.forEach {
            it.status = Status.QUEUED
            it.error = null
        }
        save(); publish(); schedule()
    }

    /** Throw away what was downloaded and start again from zero. */
    @Synchronized
    fun restart(id: String) {
        val t = tasks[id] ?: return
        stopTask(t)
        val old = t.contentUri
        t.contentUri = null
        t.totalBytes = -1
        t.resumable = false
        t.singleDone.set(0)
        synchronized(t.lock) { t.segments.clear() }
        t.status = Status.QUEUED
        t.error = null
        if (old != null) scope.launch { Storage.delete(app, Uri.parse(old)) }
        save(); publish(); schedule()
    }

    @Synchronized
    fun remove(id: String, deleteFile: Boolean) {
        val t = tasks.remove(id) ?: return
        stopTask(t)
        val uri = t.contentUri
        if (uri != null && (deleteFile || t.status != Status.COMPLETED)) {
            scope.launch { Storage.delete(app, Uri.parse(uri)) }
        }
        save(); publish(); schedule()
    }

    @Synchronized
    fun clearCompleted() {
        tasks.values.removeAll { it.status == Status.COMPLETED }
        save(); publish()
    }

    /** Android 15+ limits background data-sync services to ~6 h/day; pause cleanly when that happens. */
    @Synchronized
    fun onServiceTimeout() {
        tasks.values.filter { it.status.isActive }.forEach {
            it.status = Status.PAUSED
            it.speed = 0
            it.error = "Android's background time limit was reached — open the app and tap Resume"
            stopTask(it)
        }
        DownloadService.running = false
        save(); publish()
    }

    /** Called by the service; returns true (and marks it stopped) when nothing is left to do. */
    @Synchronized
    fun releaseServiceIfIdle(): Boolean {
        if (tasks.values.any { it.status.isActive }) return false
        DownloadService.running = false
        return true
    }

    // endregion

    // region Running a download

    private fun start(t: Task) {
        t.status = Status.CONNECTING
        t.error = null
        t.speed = 0
        t.sampleTime = 0
        val run = ++t.generation
        t.job = scope.launch { runTask(t, run) }
        startTicker()
    }

    private fun stopTask(t: Task) {
        t.generation++
        t.job?.cancel()
        t.job = null
        val open = t.connections.toList()
        t.connections.clear()
        if (open.isNotEmpty()) scope.launch { open.forEach { runCatching { it.disconnect() } } }
    }

    private suspend fun runTask(t: Task, run: Int) {
        fun current() = t.generation == run
        try {
            if (!isPrepared(t)) prepare(t)
            currentCoroutineContext().ensureActive()
            if (!current()) return
            t.status = Status.DOWNLOADING
            publish()

            if (t.resumable) {
                try {
                    runSegmented(t)
                } catch (e: NotResumableException) {
                    // server ignored the Range header after all → plain download
                    t.resumable = false
                    synchronized(t.lock) { t.segments.clear() }
                    runSingle(t)
                }
            } else {
                runSingle(t)
            }

            currentCoroutineContext().ensureActive()
            if (!current()) return
            val uri = Uri.parse(t.contentUri)
            Storage.publish(app, uri)
            Storage.displayName(app, uri)?.let { t.fileName = it }
            if (t.totalBytes <= 0) t.totalBytes = t.downloaded()
            t.status = Status.COMPLETED
            t.completedAt = System.currentTimeMillis()
            t.speed = 0
            t.error = null
            Notifications.completed(app, t.id, t.fileName)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            if (current() && currentCoroutineContext().isActive) {
                t.status = Status.FAILED
                t.error = friendly(e)
                t.speed = 0
                Notifications.failed(app, t.id, t.fileName, t.error)
            }
        } finally {
            withContext(NonCancellable) {
                save()
                publish()
                schedule()
            }
        }
    }

    private fun isPrepared(t: Task): Boolean {
        val uri = t.contentUri ?: return false
        if (!Storage.exists(app, Uri.parse(uri))) return false
        if (!t.resumable) return false    // single-stream downloads always restart from 0
        return t.totalBytes > 0 && synchronized(t.lock) { t.segments.isNotEmpty() }
    }

    /** Learn size/name/range support, create the file in Downloads and split it into parts. */
    private fun prepare(t: Task) {
        val probe = Http.probe(t.url, t.headers)
        t.finalUrl = probe.finalUrl
        t.totalBytes = probe.totalBytes
        t.resumable = probe.acceptsRanges && probe.totalBytes > 0
        if (!t.nameIsFinal) probe.fileName?.let { t.fileName = sanitize(it) }
        t.fileName = Storage.withExtension(t.fileName, probe.mimeType ?: t.mimeType)
        t.mimeType = Storage.mimeFor(t.fileName, probe.mimeType ?: t.mimeType)

        if (t.totalBytes > 0) {
            @Suppress("DEPRECATION")
            val free = StatFs(Environment.getExternalStorageDirectory().path).availableBytes
            if (free < t.totalBytes + (50L shl 20)) {
                throw IOException("Not enough storage: needs ${Fmt.bytes(t.totalBytes)}, ${Fmt.bytes(free)} free")
            }
        }

        t.contentUri?.let { Storage.delete(app, Uri.parse(it)) }
        val uri = Storage.createPending(app, t.fileName, t.mimeType)
        t.contentUri = uri.toString()
        Storage.displayName(app, uri)?.let { t.fileName = it }   // Android may add " (1)" if the name exists
        t.singleDone.set(0)

        synchronized(t.lock) {
            t.segments.clear()
            if (t.resumable) {
                val parts = min(t.threads.toLong(), maxOf(1L, t.totalBytes / MIN_SPLIT)).toInt()
                val size = t.totalBytes / parts
                for (i in 0 until parts) {
                    val start = i * size
                    val end = if (i == parts - 1) t.totalBytes - 1 else start + size - 1
                    t.segments.add(Segment(start, end, 0))
                }
            }
        }
        save()
        publish()
    }

    private suspend fun runSegmented(t: Task) {
        coroutineScope {
            repeat(t.threads) {
                launch {
                    while (isActive) {
                        val seg = claim(t) ?: break
                        try {
                            downloadSegment(t, seg)
                        } finally {
                            seg.active = false
                        }
                    }
                }
            }
        }
        if (t.downloaded() < t.totalBytes) throw IOException("Download incomplete — tap Retry")
    }

    /** Give a thread an unfinished part, or split the biggest part that another thread is working on. */
    private fun claim(t: Task): Segment? {
        synchronized(t.lock) {
            val waiting = t.segments.firstOrNull { !it.active && it.position <= it.end }
            if (waiting != null) {
                waiting.active = true
                return waiting
            }
            val victim = t.segments
                .filter { it.active && it.remaining() >= 2 * MIN_SPLIT }
                .maxByOrNull { it.remaining() } ?: return null
            val pos = victim.position
            val mid = pos + (victim.end - pos) / 2
            val piece = Segment(mid + 1, victim.end, 0)
            piece.active = true
            victim.end = mid
            t.segments.add(piece)
            return piece
        }
    }

    private suspend fun downloadSegment(t: Task, seg: Segment) {
        var failures = 0
        while (seg.position <= seg.end) {
            currentCoroutineContext().ensureActive()
            val before = seg.position
            try {
                fetchRange(t, seg)
            } catch (e: CancellationException) {
                throw e
            } catch (e: NotResumableException) {
                throw e
            } catch (e: HttpStatusException) {
                if (!e.retryable || ++failures > MAX_RETRIES) throw e
                delay(backoff(failures))
            } catch (e: IOException) {
                currentCoroutineContext().ensureActive()
                if (seg.position > before) failures = 0      // it was making progress
                waitForNetwork()
                if (++failures > MAX_RETRIES) throw e
                delay(backoff(failures))
            }
        }
    }

    private fun fetchRange(t: Task, seg: Segment) {
        val from = seg.position
        val to = seg.end
        if (from > to) return
        val conn = Http.connect(t.finalUrl ?: t.url, t.headers, "bytes=$from-$to") { t.connections.add(it) }
        try {
            val code = conn.responseCode
            if (code == 200) throw NotResumableException()
            if (code != 206) throw HttpStatusException(code)
            val pfd = app.contentResolver.openFileDescriptor(Uri.parse(t.contentUri), "rw")
                ?: throw IOException("Can't open the file for writing")
            ParcelFileDescriptor.AutoCloseOutputStream(pfd).use { out ->
                val channel = out.channel
                conn.inputStream.use { input ->
                    val buf = ByteArray(BUFFER)
                    while (true) {
                        val pos = seg.position
                        val want = min(BUFFER.toLong(), seg.end - pos + 1)
                        if (want <= 0) break                  // part finished (or was split)
                        val n = input.read(buf, 0, want.toInt())
                        if (n < 0) throw IOException("Connection closed early")
                        val allowed = min(n.toLong(), seg.end - pos + 1).toInt()
                        if (allowed <= 0) break
                        val bb = ByteBuffer.wrap(buf, 0, allowed)
                        var p = pos
                        while (bb.hasRemaining()) p += channel.write(bb, p)
                        seg.done.addAndGet(allowed.toLong())
                    }
                }
            }
        } finally {
            t.connections.remove(conn)
            conn.disconnect()
        }
    }

    private suspend fun runSingle(t: Task) {
        var failures = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            try {
                fetchWhole(t)
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: HttpStatusException) {
                if (!e.retryable || ++failures > MAX_RETRIES) throw e
                delay(backoff(failures))
            } catch (e: IOException) {
                currentCoroutineContext().ensureActive()
                waitForNetwork()
                if (++failures > MAX_RETRIES) throw e
                delay(backoff(failures))
            }
        }
    }

    private fun fetchWhole(t: Task) {
        t.singleDone.set(0)
        val conn = Http.connect(t.finalUrl ?: t.url, t.headers, null) { t.connections.add(it) }
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw HttpStatusException(code)
            val length = conn.contentLengthLong
            if (length > 0) t.totalBytes = length
            val pfd = app.contentResolver.openFileDescriptor(Uri.parse(t.contentUri), "rwt")
                ?: throw IOException("Can't open the file for writing")
            ParcelFileDescriptor.AutoCloseOutputStream(pfd).use { out ->
                conn.inputStream.use { input ->
                    val buf = ByteArray(BUFFER)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        t.singleDone.addAndGet(n.toLong())
                    }
                }
            }
            if (t.totalBytes > 0 && t.singleDone.get() < t.totalBytes) throw IOException("Connection closed early")
        } finally {
            t.connections.remove(conn)
            conn.disconnect()
        }
    }

    private suspend fun waitForNetwork() {
        val cm = app.getSystemService(ConnectivityManager::class.java)
        while (true) {
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            if (caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true) return
            delay(3_000)
        }
    }

    private fun backoff(attempt: Int): Long = min(30_000L, 1_000L * (1L shl min(attempt, 5)))

    private fun friendly(e: Throwable): String = when (e) {
        is HttpStatusException -> when (e.code) {
            401, 403 -> "Access denied (HTTP ${e.code}) — the link may have expired or needs a login"
            404 -> "File not found (HTTP 404)"
            416 -> "The file changed on the server — use Restart"
            else -> e.message ?: "Server error"
        }
        is UnknownHostException -> "No internet or the site can't be found"
        is SocketTimeoutException -> "Connection timed out"
        else -> e.message ?: e.javaClass.simpleName
    }

    // endregion

    // region Speed ticker, state, persistence

    @Synchronized
    private fun startTicker() {
        if (ticker != null) return
        ticker = scope.launch {
            var ticks = 0
            while (isActive) {
                delay(500)
                val now = SystemClock.elapsedRealtime()
                val list = synchronized(this@Downloads) { tasks.values.toList() }
                for (t in list) {
                    if (t.status == Status.DOWNLOADING) {
                        val bytes = t.downloaded()
                        if (t.sampleTime == 0L) {
                            t.sampleTime = now; t.sampleBytes = bytes
                        } else if (now - t.sampleTime >= 1_000) {
                            val inst = (bytes - t.sampleBytes) * 1_000 / (now - t.sampleTime)
                            t.speed = if (t.speed == 0L) inst else (t.speed * 6 + inst * 4) / 10
                            t.sampleTime = now; t.sampleBytes = bytes
                        }
                    } else if (!t.status.isRunning) {
                        t.speed = 0
                        t.sampleTime = 0
                    }
                }
                publish()
                if (++ticks % 6 == 0) save()
                val stop = synchronized(this@Downloads) {
                    if (tasks.values.none { it.status.isRunning }) { ticker = null; true } else false
                }
                if (stop) break
            }
        }
    }

    fun publish() {
        val list = synchronized(this) { tasks.values.map { it.snapshot() } }
        _state.value = list.sortedByDescending { it.createdAt }
    }

    private fun save() {
        if (!::app.isInitialized) return
        val json = synchronized(this) {
            JSONArray().apply { tasks.values.forEach { put(it.toJson()) } }.toString()
        }
        synchronized(saveLock) {
            runCatching {
                val tmp = File(stateFile.path + ".tmp")
                tmp.writeText(json)
                if (!tmp.renameTo(stateFile)) stateFile.writeText(json)
            }
        }
    }

    private fun load() {
        val text = runCatching { stateFile.readText() }.getOrNull() ?: return
        val arr = runCatching { JSONArray(text) }.getOrNull() ?: return
        synchronized(this) {
            for (i in 0 until arr.length()) {
                val t = runCatching { Task.fromJson(arr.getJSONObject(i)) }.getOrNull() ?: continue
                // downloads that were running when the app was closed start again automatically
                if (t.status.isRunning) t.status = Status.QUEUED
                tasks[t.id] = t
            }
        }
    }

    fun sanitize(name: String): String {
        val cleaned = name.replace(Regex("""[\\/:*?"<>|\u0000-\u001F]"""), "_").trim().trim('.')
        return cleaned.take(200).ifEmpty { "download" }
    }

    // endregion
}
