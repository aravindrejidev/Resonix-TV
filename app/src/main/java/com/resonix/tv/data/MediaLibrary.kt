package com.resonix.tv.data

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.core.content.ContextCompat

data class MediaEntry(
    val uri: Uri,
    val title: String,
    val durationMs: Long,
    val sizeBytes: Long,
)

/** Lists the videos Android has indexed (internal storage + USB drives) and resolves file names. */
object MediaLibrary {

    fun permissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 33) {
            arrayOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

    fun hasPermission(context: Context): Boolean = permissions().all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    /** Blocking: call from a background dispatcher. */
    fun loadVideos(context: Context, limit: Int = 400): List<MediaEntry> {
        val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
        )
        val result = ArrayList<MediaEntry>()
        runCatching {
            context.contentResolver.query(
                collection, projection, null, null, "${MediaStore.Video.Media.DATE_ADDED} DESC",
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val durCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                while (cursor.moveToNext() && result.size < limit) {
                    val name = cursor.getString(nameCol) ?: "Video"
                    result.add(
                        MediaEntry(
                            uri = ContentUris.withAppendedId(collection, cursor.getLong(idCol)),
                            title = name.substringBeforeLast('.'),
                            durationMs = cursor.getLong(durCol),
                            sizeBytes = cursor.getLong(sizeCol),
                        ),
                    )
                }
            }
        }
        return result
    }

    fun displayName(context: Context, uri: Uri): String {
        if (uri.scheme == "content") {
            runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val name = c.getString(0)
                        if (!name.isNullOrBlank()) return name.substringBeforeLast('.')
                    }
                }
            }
        }
        return (uri.lastPathSegment ?: "Video").substringAfterLast('/').substringBeforeLast('.')
    }
}
