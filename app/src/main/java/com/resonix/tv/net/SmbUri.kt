package com.resonix.tv.net

import android.net.Uri

/**
 * smb://host/share/folder/file.mkv  - the address used for playback and for the watch history.
 * It never holds a user name or password: the login is looked up from the saved share.
 */
object SmbUri {
    const val SCHEME = "smb"
    const val DEFAULT_PORT = 445

    class Parsed(val host: String, val port: Int, val share: String, val path: String)

    fun isSmb(uri: Uri?): Boolean = uri != null && SCHEME.equals(uri.scheme, ignoreCase = true)

    /** [path] uses "/" between folders and may be empty (= top of the share). */
    fun build(host: String, port: Int, share: String, path: String): Uri {
        val builder = Uri.Builder().scheme(SCHEME).authority(if (port == DEFAULT_PORT) host else "$host:$port")
        builder.appendPath(share)
        for (part in path.split('/')) {
            if (part.isNotEmpty()) builder.appendPath(part)
        }
        return builder.build()
    }

    fun parse(uri: Uri): Parsed? {
        val host = uri.host ?: return null
        val segments = uri.pathSegments
        if (segments.isEmpty()) return null
        val port = if (uri.port > 0) uri.port else DEFAULT_PORT
        return Parsed(host, port, segments[0], segments.drop(1).joinToString("/"))
    }
}

/** Forgiving reading of what a person types into the "Server" and "User name" boxes. */
object SmbInput {
    class HostParts(val host: String, val port: Int, val impliedShare: String)

    /** Accepts "nas", "192.168.1.20", "192.168.1.20:445", "smb://nas/Movies" and "\\nas\Movies". */
    fun parseHost(raw: String): HostParts {
        var text = raw.trim()
        text = text.removePrefix("smb://").removePrefix("SMB://")
        text = text.trimStart('\\', '/')
        text = text.replace('\\', '/')
        val hostPart = text.substringBefore('/')
        val share = text.substringAfter('/', "").substringBefore('/')
        var host = hostPart
        var port = SmbUri.DEFAULT_PORT
        val colon = hostPart.lastIndexOf(':')
        if (colon > 0 && hostPart.indexOf(':') == colon) {
            val number = hostPart.substring(colon + 1).toIntOrNull()
            if (number != null && number in 1..65535) {
                host = hostPart.substring(0, colon)
                port = number
            }
        }
        return HostParts(host.trim(), port, share.trim())
    }

    /** "WORKGROUP\\anna" or "anna@WORKGROUP" typed into the user box -> (user, domain). */
    fun splitUser(user: String, domain: String): Pair<String, String> {
        val name = user.trim()
        if (domain.isNotBlank()) return Pair(name, domain.trim())
        val backslash = name.indexOf('\\')
        if (backslash > 0) return Pair(name.substring(backslash + 1), name.substring(0, backslash))
        val at = name.indexOf('@')
        if (at > 0 && at < name.length - 1) return Pair(name.substring(0, at), name.substring(at + 1))
        return Pair(name, "")
    }
}
