package com.resonix.tv.data

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.storage.StorageManager
import androidx.media3.common.MimeTypes
import com.resonix.tv.player.SubtitleSource
import java.io.File

/** One storage device: internal storage, an SD card or a USB drive. */
data class StorageRoot(val label: String, val path: File)

object StorageRoots {

    @Suppress("DEPRECATION")
    fun list(context: Context): List<StorageRoot> {
        val roots = LinkedHashMap<String, StorageRoot>()

        val primary = Environment.getExternalStorageDirectory()
        roots[primary.absolutePath] = StorageRoot("Internal storage", primary)

        // Every mounted volume shows up in getExternalFilesDirs(); cut the app-specific part off.
        val marker = "/Android/data/"
        for (dir in context.getExternalFilesDirs(null)) {
            if (dir == null) continue
            val index = dir.absolutePath.indexOf(marker)
            if (index <= 0) continue
            val root = File(dir.absolutePath.substring(0, index))
            if (root.exists() && !roots.containsKey(root.absolutePath)) {
                roots[root.absolutePath] = StorageRoot(describe(context, root), root)
            }
        }

        // USB drives that were not reported above.
        File("/storage").listFiles()?.forEach { f ->
            if (f.isDirectory && f.name != "self" && f.name != "emulated" && f.canRead() &&
                !roots.containsKey(f.absolutePath)
            ) {
                roots[f.absolutePath] = StorageRoot(describe(context, f), f)
            }
        }
        return roots.values.toList()
    }

    private fun describe(context: Context, root: File): String {
        val manager = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
        val name = runCatching { manager?.getStorageVolume(root)?.getDescription(context) }.getOrNull()
        return if (name.isNullOrBlank()) "Storage ${root.name}" else name
    }

    /** "USB drive / Movies / Action" instead of "/storage/1234-ABCD/Movies/Action". */
    fun prettyPath(roots: List<StorageRoot>, dir: File): String {
        val root = roots.firstOrNull { dir.path.startsWith(it.path.path) } ?: return dir.path
        val rest = dir.path.removePrefix(root.path.path).trim('/')
        return if (rest.isEmpty()) root.label else root.label + " / " + rest.replace("/", " / ")
    }
}

data class BrowserEntry(val file: File, val isDir: Boolean, val sizeBytes: Long)

object FileBrowser {
    private val MEDIA_EXTENSIONS = setOf(
        "mkv", "mp4", "m4v", "avi", "mov", "ts", "m2ts", "mts", "webm", "wmv", "flv", "mpg", "mpeg",
        "vob", "3gp", "ogv", "mp3", "flac", "wav", "aac", "m4a", "ogg", "opus", "wma", "dts", "ac3",
        "eac3", "mka", "alac", "ape",
    )

    private val SUBTITLE_TYPES = mapOf(
        "srt" to MimeTypes.APPLICATION_SUBRIP,
        "vtt" to MimeTypes.TEXT_VTT,
        "ass" to MimeTypes.TEXT_SSA,
        "ssa" to MimeTypes.TEXT_SSA,
    )

    /** Folders first, then media files, both sorted by name. Null = the folder cannot be read. */
    fun list(dir: File): List<BrowserEntry>? {
        val children = dir.listFiles() ?: return null
        val result = ArrayList<BrowserEntry>(children.size)
        for (f in children) {
            if (f.name.startsWith(".")) continue
            if (f.isDirectory) {
                result.add(BrowserEntry(f, true, 0L))
            } else if (f.extension.lowercase() in MEDIA_EXTENSIONS) {
                result.add(BrowserEntry(f, false, f.length()))
            }
        }
        result.sortWith(compareBy<BrowserEntry>({ !it.isDir }, { it.file.name.lowercase() }))
        return result
    }

    /** Subtitle files next to the video whose name starts like the video ("Film.srt", "Film.en.srt"). */
    fun findSubtitles(video: File): List<SubtitleSource> {
        val dir = video.parentFile ?: return emptyList()
        val base = video.nameWithoutExtension
        val matches = dir.listFiles { f ->
            f.isFile && SUBTITLE_TYPES.containsKey(f.extension.lowercase()) &&
                f.name.startsWith(base, ignoreCase = true)
        } ?: return emptyList()
        return matches.sortedBy { it.name.lowercase() }.map { f ->
            val label = f.nameWithoutExtension.removePrefix(base).trim('.', ' ', '_', '-')
            SubtitleSource(
                uri = Uri.fromFile(f),
                mimeType = SUBTITLE_TYPES.getValue(f.extension.lowercase()),
                label = label.ifEmpty { "Subtitles" },
            )
        }
    }
}
