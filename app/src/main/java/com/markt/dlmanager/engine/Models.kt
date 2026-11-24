package com.markt.dlmanager.engine

import com.markt.dlmanager.util.Fmt
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Job

enum class Status {
    QUEUED, CONNECTING, DOWNLOADING, PAUSED, COMPLETED, FAILED;

    val isRunning: Boolean get() = this == CONNECTING || this == DOWNLOADING
    val isActive: Boolean get() = isRunning || this == QUEUED
}

/** One part of a multi-thread download, as shown in the UI. */
data class SegmentInfo(val start: Long, val end: Long, val downloaded: Long)

/** Immutable snapshot of a download for the UI. */
data class DownloadInfo(
    val id: String,
    val url: String,
    val fileName: String,
    val mimeType: String?,
    val totalBytes: Long,
    val downloadedBytes: Long,
    val status: Status,
    val error: String?,
    val speed: Long,
    val threads: Int,
    val activeThreads: Int,
    val resumable: Boolean,
    val segments: List<SegmentInfo>,
    val contentUri: String?,
    val createdAt: Long,
    val completedAt: Long?,
) {
    val progress: Float
        get() = if (totalBytes > 0) (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
}

/** A file the browser (or a shared link) wants to download — shown in the confirm dialog. */
data class PendingDownload(
    val url: String,
    val fileName: String,
    val nameKnown: Boolean,
    val mimeType: String?,
    val size: Long,
    val referer: String?,
    val userAgent: String?,
) {
    companion object {
        fun fromUrl(url: String, userAgent: String? = null) = PendingDownload(
            url = url,
            fileName = Fmt.guessName(url),
            nameKnown = false,
            mimeType = null,
            size = -1,
            referer = null,
            userAgent = userAgent,
        )
    }
}

/** A byte range [start, end] downloaded by one thread. `end` can shrink when the part is split. */
internal class Segment(val start: Long, end: Long, done: Long) {
    @Volatile var end: Long = end
    val done = AtomicLong(done)
    @Volatile var active = false

    val position: Long get() = start + done.get()
    fun remaining(): Long = end - position + 1
}

/** Mutable engine-side state of one download. */
internal class Task(
    val id: String,
    var url: String,
    var fileName: String,
    var nameIsFinal: Boolean,
    var headers: Map<String, String>,
    var threads: Int,
    val createdAt: Long,
) {
    var finalUrl: String? = null
    var mimeType: String? = null
    var totalBytes = -1L
    var resumable = false
    @Volatile var status = Status.QUEUED
    var error: String? = null
    var contentUri: String? = null
    var completedAt: Long? = null

    val lock = Any()
    val segments = mutableListOf<Segment>()        // guarded by lock
    val singleDone = AtomicLong(0)                 // progress when the server doesn't allow multi-thread

    @Volatile var generation = 0                   // bumps on every start/stop so stale workers are ignored
    var job: Job? = null
    val connections: MutableSet<HttpURLConnection> = ConcurrentHashMap.newKeySet()

    @Volatile var speed = 0L
    var sampleTime = 0L
    var sampleBytes = 0L

    fun downloaded(): Long = synchronized(lock) {
        if (segments.isEmpty()) singleDone.get() else segments.sumOf { it.done.get() }
    }

    fun snapshot(): DownloadInfo {
        val (segs, activeCount) = synchronized(lock) {
            segments.sortedBy { it.start }.map { SegmentInfo(it.start, it.end, it.done.get()) } to
                segments.count { it.active }
        }
        val done = if (segs.isEmpty()) singleDone.get() else segs.sumOf { it.downloaded }
        return DownloadInfo(
            id = id, url = url, fileName = fileName, mimeType = mimeType,
            totalBytes = totalBytes, downloadedBytes = done, status = status, error = error,
            speed = speed, threads = threads,
            activeThreads = if (status == Status.DOWNLOADING) (if (segs.isEmpty()) 1 else activeCount) else 0,
            resumable = resumable, segments = segs, contentUri = contentUri,
            createdAt = createdAt, completedAt = completedAt,
        )
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("url", url); put("fileName", fileName); put("nameIsFinal", nameIsFinal)
        put("threads", threads); put("createdAt", createdAt)
        put("finalUrl", finalUrl ?: JSONObject.NULL)
        put("mimeType", mimeType ?: JSONObject.NULL)
        put("totalBytes", totalBytes); put("resumable", resumable)
        put("status", status.name); put("error", error ?: JSONObject.NULL)
        put("contentUri", contentUri ?: JSONObject.NULL)
        put("completedAt", completedAt ?: JSONObject.NULL)
        put("singleDone", singleDone.get())
        put("headers", JSONObject(headers))
        put("segments", JSONArray().apply {
            synchronized(lock) {
                segments.forEach { put(JSONArray().put(it.start).put(it.end).put(it.done.get())) }
            }
        })
    }

    companion object {
        fun fromJson(o: JSONObject): Task {
            val headers = mutableMapOf<String, String>()
            o.optJSONObject("headers")?.let { h -> h.keys().forEach { k -> headers[k] = h.getString(k) } }
            return Task(
                id = o.getString("id"),
                url = o.getString("url"),
                fileName = o.getString("fileName"),
                nameIsFinal = o.optBoolean("nameIsFinal"),
                headers = headers,
                threads = o.optInt("threads", 8),
                createdAt = o.optLong("createdAt"),
            ).apply {
                finalUrl = o.optStringOrNull("finalUrl")
                mimeType = o.optStringOrNull("mimeType")
                totalBytes = o.optLong("totalBytes", -1)
                resumable = o.optBoolean("resumable")
                status = runCatching { Status.valueOf(o.getString("status")) }.getOrDefault(Status.PAUSED)
                error = o.optStringOrNull("error")
                contentUri = o.optStringOrNull("contentUri")
                completedAt = if (o.isNull("completedAt")) null else o.optLong("completedAt")
                singleDone.set(o.optLong("singleDone"))
                o.optJSONArray("segments")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val s = arr.getJSONArray(i)
                        segments.add(Segment(s.getLong(0), s.getLong(1), s.getLong(2)))
                    }
                }
            }
        }

        private fun JSONObject.optStringOrNull(key: String): String? =
            if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }
    }
}
