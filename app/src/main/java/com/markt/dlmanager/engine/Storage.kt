package com.markt.dlmanager.engine

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import java.io.IOException

/**
 * Files are written straight into the public Downloads/DLManager folder through MediaStore.
 * While downloading, the file is "pending" (hidden from other apps); it becomes visible when finished.
 * Writing in place means no extra copy at the end and no double storage use.
 */
object Storage {
    const val FOLDER = "DLManager"

    fun createPending(ctx: Context, name: String, mime: String?): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            if (mime != null) put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        return ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Couldn't create the file in Downloads")
    }

    fun publish(ctx: Context, uri: Uri) {
        val values = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
        ctx.contentResolver.update(uri, values, null, null)
    }

    fun displayName(ctx: Context, uri: Uri): String? = runCatching {
        ctx.contentResolver.query(uri, arrayOf(MediaStore.Downloads.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    fun exists(ctx: Context, uri: Uri): Boolean = runCatching {
        ctx.contentResolver.openFileDescriptor(uri, "r")?.close() != null
    }.getOrDefault(false)

    fun delete(ctx: Context, uri: Uri) {
        runCatching { ctx.contentResolver.delete(uri, null, null) }
    }

    /** MIME type from the file extension first (so Android doesn't rename the file), else the server's. */
    fun mimeFor(fileName: String, serverMime: String?): String? {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        val fromExt = if (ext.isNotEmpty()) MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) else null
        return fromExt ?: if (ext.isEmpty()) serverMime else null
    }

    fun withExtension(fileName: String, mime: String?): String {
        if (fileName.contains('.') || mime == null) return fileName
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: return fileName
        return "$fileName.$ext"
    }
}
