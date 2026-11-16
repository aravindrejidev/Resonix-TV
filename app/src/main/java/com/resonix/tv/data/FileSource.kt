package com.resonix.tv.data

import android.net.Uri
import com.resonix.tv.net.SmbConnections
import com.resonix.tv.net.SmbCredentials
import com.resonix.tv.net.SmbUri
import java.io.File

/** One item of a folder as the source reports it (before filtering and sorting). */
data class SourceItem(val name: String, val isDir: Boolean, val sizeBytes: Long)

/**
 * A place the file browser can read from: this TV's own storage, or a network share.
 * A folder is named by a [String] path: a full path for local storage, a path inside the share
 * ("Movies/Action", "" = the top) for a network share. Everything here blocks, so call it from a
 * background dispatcher. A network source throws an SmbIoException when the server can not be reached.
 */
interface FileSource {
    /** Everything in [dir]. Null = the folder cannot be read. */
    fun listRaw(dir: String): List<SourceItem>?

    fun join(dir: String, name: String): String

    /** The folder above [dir], or null when [dir] is already the top. */
    fun parent(dir: String): String?

    /** The address used to play the file (also the key of the watch history). */
    fun uriOf(dir: String, name: String): Uri
}

/** Internal storage, SD cards and USB drives. */
object LocalFileSource : FileSource {
    override fun listRaw(dir: String): List<SourceItem>? {
        val children = File(dir).listFiles() ?: return null
        return children.map { SourceItem(it.name, it.isDirectory, if (it.isDirectory) 0L else it.length()) }
    }

    override fun join(dir: String, name: String): String = File(dir, name).path

    override fun parent(dir: String): String? = File(dir).parent

    override fun uriOf(dir: String, name: String): Uri = Uri.fromFile(File(dir, name))
}

/** One share on a network server (SMB). */
class SmbFileSource(private val credentials: SmbCredentials) : FileSource {
    override fun listRaw(dir: String): List<SourceItem> =
        SmbConnections.listFolder(credentials, dir).map { SourceItem(it.name, it.isDir, it.sizeBytes) }

    override fun join(dir: String, name: String): String = if (dir.isEmpty()) name else "$dir/$name"

    override fun parent(dir: String): String? = if (dir.isEmpty()) null else dir.substringBeforeLast('/', "")

    override fun uriOf(dir: String, name: String): Uri =
        SmbUri.build(credentials.host, credentials.port, credentials.shareName, join(dir, name))
}
