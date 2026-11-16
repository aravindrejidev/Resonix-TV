package com.resonix.tv.data

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.storage.StorageManager
import androidx.media3.common.MimeTypes
import com.resonix.tv.net.SmbShareStore
import com.resonix.tv.net.SmbUri
import com.resonix.tv.player.PlayRequest
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

/** A storage device with its capacity, for the Home cards. */
data class StorageInfo(val root: StorageRoot, val totalBytes: Long, val freeBytes: Long)

fun StorageRoots.listWithUsage(context: Context): List<StorageInfo> =
    list(context).map { StorageInfo(it, it.path.totalSpace, it.path.freeSpace) }

/** One line of the file browser. [path] is the folder / file name inside its [FileSource]. */
data class BrowserEntry(val name: String, val path: String, val isDir: Boolean, val sizeBytes: Long)

/** Where a stored movie address points to: its source, its folder and its file name. */
private class Located(val source: FileSource, val dir: String, val name: String)

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

    /** Folders that only hold system leftovers (thumbnails, recycle bins): never shown. */
    private val HIDDEN_FOLDERS = setOf("@eaDir", "#recycle", "\$RECYCLE.BIN", "System Volume Information", "lost+found")

    private fun extensionOf(name: String): String = name.substringAfterLast('.', "").lowercase()

    /** Folders first, then media files, both sorted by name. Null = the folder cannot be read. */
    fun list(source: FileSource, dir: String): List<BrowserEntry>? {
        val items = source.listRaw(dir) ?: return null
        val result = ArrayList<BrowserEntry>(items.size)
        for (item in items) {
            if (item.name.startsWith(".") || item.name in HIDDEN_FOLDERS) continue
            if (item.isDir) {
                result.add(BrowserEntry(item.name, source.join(dir, item.name), true, 0L))
            } else if (extensionOf(item.name) in MEDIA_EXTENSIONS) {
                result.add(BrowserEntry(item.name, source.join(dir, item.name), false, item.sizeBytes))
            }
        }
        result.sortWith(compareBy<BrowserEntry>({ !it.isDir }, { it.name.lowercase() }))
        return result
    }

    /**
     * The file that comes after [name] in its folder (sorted by name, like the browser shows
     * it), ready to play, or null when it was the last one. For "play the next episode".
     */
    fun findNext(source: FileSource, dir: String, name: String): PlayRequest? {
        val files = list(source, dir)?.filter { !it.isDir } ?: return null
        val index = files.indexOfFirst { it.name == name }
        if (index < 0) return null
        val next = files.getOrNull(index + 1) ?: return null
        return PlayRequest(
            source.uriOf(dir, next.name),
            next.name.substringBeforeLast('.'),
            findSubtitles(source, dir, next.name),
        )
    }

    /** Subtitle files next to the video whose name starts like the video ("Film.srt", "Film.en.srt"). */
    fun findSubtitles(source: FileSource, dir: String, videoName: String): List<SubtitleSource> {
        val base = videoName.substringBeforeLast('.')
        val items = runCatching { source.listRaw(dir) }.getOrNull() ?: return emptyList()
        val matches = items.filter { item ->
            !item.isDir && SUBTITLE_TYPES.containsKey(extensionOf(item.name)) &&
                item.name.substringBeforeLast('.').startsWith(base, ignoreCase = true)
        }
        return matches.sortedBy { it.name.lowercase() }.map { item ->
            val withoutExtension = item.name.substringBeforeLast('.')
            val rest = if (withoutExtension.length >= base.length) withoutExtension.substring(base.length) else ""
            val label = rest.trim('.', ' ', '_', '-')
            SubtitleSource(
                uri = source.uriOf(dir, item.name),
                mimeType = SUBTITLE_TYPES.getValue(extensionOf(item.name)),
                label = label.ifEmpty { "Subtitles" },
            )
        }
    }

    /** Works out where a stored address (file:// or smb://) lives. Other kinds have no neighbours. */
    private fun locate(context: Context, uri: Uri): Located? {
        if (uri.scheme == "file") {
            val file = File(uri.path ?: return null)
            val parent = file.parent ?: return null
            return Located(LocalFileSource, parent, file.name)
        }
        if (SmbUri.isSmb(uri)) {
            val parsed = SmbUri.parse(uri) ?: return null
            if (parsed.path.isEmpty()) return null
            val credentials = SmbShareStore.get(context).credentialsFor(parsed.host, parsed.port, parsed.share) ?: return null
            return Located(
                SmbFileSource(credentials),
                parsed.path.substringBeforeLast('/', ""),
                parsed.path.substringAfterLast('/'),
            )
        }
        return null
    }

    /** [findNext] for a stored address. Network shares are asked, so call it from a background dispatcher. */
    fun findNextFor(context: Context, uri: Uri): PlayRequest? =
        runCatching { locate(context, uri)?.let { findNext(it.source, it.dir, it.name) } }.getOrNull()

    /** [findSubtitles] for a stored address. */
    fun findSubtitlesFor(context: Context, uri: Uri): List<SubtitleSource> =
        runCatching { locate(context, uri)?.let { findSubtitles(it.source, it.dir, it.name) } }
            .getOrNull() ?: emptyList()
}
