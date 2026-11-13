package com.resonix.tv.diag

import android.content.Context
import android.os.Build
import android.util.Log
import com.resonix.tv.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A small log file for problems that only show up on the TV: crashes, player errors, decoders,
 * dropped frames. The last lines are shown in Settings > Diagnostics, and the file can be copied
 * from  Android/data/com.resonix.tv/files/resonix-log.txt  and sent to the developer.
 * It never leaves the TV by itself, and it holds file names only (no folders, no account data).
 */
object AppLog {
    private const val TAG = "Resonix"
    private const val MAX_BYTES = 200_000L

    private val lock = Any()
    private var file: File? = null
    private val clock = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

    /** Call once at start-up. Also records crashes before the app closes. */
    fun init(context: Context) {
        synchronized(lock) {
            if (file != null) return
            val dir = context.getExternalFilesDir(null) ?: context.filesDir
            val logFile = File(dir, "resonix-log.txt")
            try {
                // Keep the file small: when it grows too big, only the newest half stays.
                if (logFile.length() > MAX_BYTES) {
                    logFile.writeText(logFile.readText().takeLast((MAX_BYTES / 2).toInt()))
                }
            } catch (ignored: Exception) {
            }
            file = logFile
        }
        log(
            "START",
            "ResonixTV ${BuildConfig.VERSION_NAME} | ${Build.MANUFACTURER} ${Build.MODEL} | " +
                "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) | ${Build.SUPPORTED_ABIS.firstOrNull() ?: "?"}",
        )
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            log("CRASH", "thread ${thread.name}\n${Log.getStackTraceString(error)}")
            previous?.uncaughtException(thread, error)
        }
    }

    fun log(tag: String, message: String) {
        Log.i(TAG, "$tag  $message")
        synchronized(lock) {
            val logFile = file ?: return
            try {
                logFile.appendText("${clock.format(Date())}  $tag  $message\n")
            } catch (ignored: Exception) {
            }
        }
    }

    /** The newest [maxLines] lines (oldest first). */
    fun tail(maxLines: Int): List<String> = synchronized(lock) {
        try {
            file?.readLines()?.takeLast(maxLines) ?: emptyList()
        } catch (ignored: Exception) {
            emptyList()
        }
    }

    fun path(): String = file?.absolutePath ?: "(log not started)"
}
