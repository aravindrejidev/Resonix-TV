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

/** Frequency for the parameter list: "250 Hz", "1.06 kHz", "16.0 kHz". */
fun formatFreqLong(hz: Float): String = when {
    hz >= 10000f -> String.format(Locale.US, "%.1f kHz", hz / 1000f)
    hz >= 1000f -> String.format(Locale.US, "%.2f kHz", hz / 1000f)
    hz >= 100f -> String.format(Locale.US, "%.0f Hz", hz)
    else -> String.format(Locale.US, "%.1f Hz", hz)
}

/** Q factor: "0.71", "1.4", "12". */
fun formatQ(q: Float): String = when {
    q >= 10f -> String.format(Locale.US, "%.0f", q)
    q >= 1f -> String.format(Locale.US, "%.1f", q)
    else -> String.format(Locale.US, "%.2f", q)
}
