package com.resonix.tv.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.resonix.tv.BuildConfig
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class UpdateInfo(
    val version: String,
    val notes: String,
    val apkUrl: String,
    val sizeBytes: Long,
)

sealed interface UpdateStatus {
    data object Idle : UpdateStatus
    data object Checking : UpdateStatus
    data object UpToDate : UpdateStatus
    data object NotConfigured : UpdateStatus
    data class Available(val info: UpdateInfo) : UpdateStatus
    data class Downloading(val info: UpdateInfo) : UpdateStatus
    data class Ready(val info: UpdateInfo, val file: File) : UpdateStatus
    data class NeedsPermission(val info: UpdateInfo, val file: File) : UpdateStatus
    data class Failed(val message: String) : UpdateStatus
}

/**
 * Looks for the newest GitHub Release of the repository that built this APK
 * (BuildConfig.UPDATE_REPO), downloads its .apk and hands it to the system installer.
 * Works for public repositories (no token is stored in the app).
 */
object Updater {
    private const val USER_AGENT = "ResonixTV-Updater"

    val repo: String get() = BuildConfig.UPDATE_REPO
    val currentVersion: String get() = BuildConfig.VERSION_NAME

    /** Compares "1.10.0" with "1.9.2" numerically; suffixes such as "-dev" are ignored. */
    fun compareVersions(a: String, b: String): Int {
        fun parts(v: String): List<Int> =
            v.trim().removePrefix("v").removePrefix("V").substringBefore('-').split('.')
                .map { piece -> piece.filter { it.isDigit() }.toIntOrNull() ?: 0 }

        val pa = parts(a)
        val pb = parts(b)
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return x.compareTo(y)
        }
        return 0
    }

    suspend fun fetchLatest(): UpdateStatus = withContext(Dispatchers.IO) {
        if (repo.isBlank()) return@withContext UpdateStatus.NotConfigured
        try {
            val connection = URL("https://api.github.com/repos/$repo/releases/latest")
                .openConnection() as HttpURLConnection
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", USER_AGENT)
            try {
                when (val code = connection.responseCode) {
                    200 -> parseRelease(connection.inputStream.bufferedReader().use { it.readText() })
                    404 -> UpdateStatus.Failed("No release found. Is the repository public and has a release been published?")
                    403 -> UpdateStatus.Failed("GitHub is limiting requests right now. Try again in a while.")
                    else -> UpdateStatus.Failed("GitHub answered with code $code")
                }
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            UpdateStatus.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun parseRelease(body: String): UpdateStatus {
        val json = JSONObject(body)
        val version = json.optString("tag_name").removePrefix("v").removePrefix("V")
        if (version.isBlank()) return UpdateStatus.Failed("The latest release has no version tag")

        val assets = json.optJSONArray("assets")
        var apkUrl = ""
        var size = 0L
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                if (asset.optString("name").endsWith(".apk", ignoreCase = true)) {
                    apkUrl = asset.optString("browser_download_url")
                    size = asset.optLong("size")
                    break
                }
            }
        }
        if (compareVersions(version, currentVersion) <= 0) return UpdateStatus.UpToDate
        if (apkUrl.isBlank()) return UpdateStatus.Failed("Release $version has no APK attached")
        return UpdateStatus.Available(UpdateInfo(version, json.optString("body"), apkUrl, size))
    }

    /** Downloads the APK into cache/updates and checks that it really is a ResonixTV package. */
    suspend fun download(context: Context, info: UpdateInfo, onProgress: (Float) -> Unit): Result<File> =
        withContext(Dispatchers.IO) {
            runCatching {
                val dir = File(context.cacheDir, "updates")
                dir.mkdirs()
                dir.listFiles()?.forEach { it.delete() }
                val target = File(dir, "ResonixTV-${info.version}.apk")
                val partial = File(dir, target.name + ".part")

                val connection = URL(info.apkUrl).openConnection() as HttpURLConnection
                connection.connectTimeout = 15_000
                connection.readTimeout = 30_000
                connection.setRequestProperty("User-Agent", USER_AGENT)
                try {
                    check(connection.responseCode == 200) { "Download failed (HTTP ${connection.responseCode})" }
                    val total = if (connection.contentLengthLong > 0) connection.contentLengthLong else info.sizeBytes
                    connection.inputStream.use { input ->
                        partial.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var done = 0L
                            var lastReport = 0L
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                output.write(buffer, 0, n)
                                done += n
                                val now = System.currentTimeMillis()
                                if (total > 0 && now - lastReport > 150) {
                                    lastReport = now
                                    onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                                }
                            }
                        }
                    }
                } finally {
                    connection.disconnect()
                }
                check(partial.renameTo(target)) { "Could not save the downloaded update" }

                val archive = context.packageManager.getPackageArchiveInfo(target.absolutePath, 0)
                check(archive != null && archive.packageName == context.packageName) {
                    "The downloaded file is not a ResonixTV APK"
                }
                onProgress(1f)
                target
            }
        }

    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    /** Settings page where the user allows ResonixTV to install apps. */
    fun installPermissionIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun install(context: Context, apk: File): Result<Unit> = runCatching {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
