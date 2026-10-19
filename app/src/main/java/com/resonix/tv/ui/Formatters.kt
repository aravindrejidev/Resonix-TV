package com.resonix.tv.ui

import java.util.Locale

fun formatTime(ms: Long): String {
    val total = ms.coerceAtLeast(0L) / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.US, "%d:%02d", m, s)
}

fun formatSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format(Locale.US, "%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0))
    bytes >= 1L shl 20 -> String.format(Locale.US, "%.0f MB", bytes / (1024.0 * 1024.0))
    else -> String.format(Locale.US, "%d KB", bytes / 1024)
}

fun formatDb(db: Float): String = String.format(Locale.US, "%+.1f", db)

fun formatFreq(hz: Float): String = when {
    hz >= 1000f -> {
        val k = hz / 1000f
        if (k == k.toInt().toFloat()) "${k.toInt()}k" else String.format(Locale.US, "%.1fk", k)
    }
    hz == hz.toInt().toFloat() -> "${hz.toInt()}"
    else -> String.format(Locale.US, "%.1f", hz)
}

fun channelLayoutName(count: Int): String = when (count) {
    1 -> "1.0"
    2 -> "2.0"
    6 -> "5.1"
    7 -> "6.1"
    8 -> "7.1"
    else -> if (count > 0) "${count} ch" else "?"
}
