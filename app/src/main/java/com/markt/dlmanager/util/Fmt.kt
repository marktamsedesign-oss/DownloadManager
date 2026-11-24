package com.markt.dlmanager.util

import android.net.Uri
import java.util.Locale

object Fmt {
    fun bytes(value: Long): String {
        val b = value.coerceAtLeast(0)
        if (b < 1024) return "$b B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var v = b / 1024.0
        var i = 0
        while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
        val pattern = when {
            v >= 100 -> "%.0f %s"
            v >= 10 -> "%.1f %s"
            else -> "%.2f %s"
        }
        return String.format(Locale.US, pattern, v, units[i])
    }

    fun duration(seconds: Long): String {
        if (seconds <= 0) return ""
        return when {
            seconds < 60 -> "${seconds}s"
            seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s"
            else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
        }
    }

    fun guessName(url: String): String {
        val uri = Uri.parse(url)
        return uri.lastPathSegment?.takeIf { it.isNotBlank() } ?: uri.host ?: "download"
    }
}
