package com.resonix.tv.net

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.resonix.tv.diag.AppLog
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.Provider
import java.security.Security
import java.util.EnumSet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import com.hierynomus.smbj.share.File as SmbFile

/** One item of a folder on a share. */
data class SmbEntry(val name: String, val isDir: Boolean, val sizeBytes: Long)

/** An open file on a share (kept by the player's data source until it is closed). */
class SmbOpenFile(val file: SmbFile, val sizeBytes: Long)

/** Result of "Test connection": [message] is shown as it is. */
class SmbTestOutcome(val ok: Boolean, val message: String)

/**
 * The only place that talks to the smbj library.
 *
 * - One logged-in connection per server + share + user is kept and shared (listing, playback and
 *   subtitles use the same one). If it breaks (TV slept, Wi-Fi dropped) it is dropped and made
 *   again once, automatically.
 * - Every call blocks: use it from a background dispatcher only.
 * - Every failure leaves as an [SmbIoException]. Passwords never appear in a log line or message.
 */
object SmbConnections {
    private const val OP_TIMEOUT_SEC = 20L
    private const val CONNECT_TIMEOUT_MS = 5_000

    /** After a wrong password, the same login is not tried again for this long (account lock-outs). */
    private const val AUTH_COOLDOWN_MS = 20_000L

    private const val ATTR_HIDDEN_OR_SYSTEM = 0x6L
    private const val ATTR_DIRECTORY = 0x10L

    /** Share names tried when the server's own list can not be asked (see [probeShares]). */
    private val COMMON_SHARES = listOf(
        "Public", "Share", "Shared", "Media", "Movies", "Films", "Video", "Videos", "TV", "Series",
        "Music", "Photo", "Photos", "Pictures", "Download", "Downloads", "Documents", "Data", "Storage",
        "Backup", "Home", "Homes", "NAS", "USB", "Disk", "Volume1", "Volume_1", "Multimedia",
        "Entertainment", "Plex", "Kodi", "Library", "Archive",
    )

    private class Entry(
        val client: SMBClient,
        val connection: Connection,
        val session: Session,
        val share: DiskShare,
    ) {
        fun closeQuietly() {
            runCatching { share.close() }
            runCatching { session.close() }
            runCatching { connection.close() }
            runCatching { client.close() }
        }
    }

    private val entries = ConcurrentHashMap<String, Entry>()
    private val locks = ConcurrentHashMap<String, Any>()
    private val authFailedAt = ConcurrentHashMap<String, Long>()
    private val providerReady = AtomicBoolean(false)

    /** Closing a dead connection can take a while: never do it on the caller's thread. */
    private val closer = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "resonix-smb-close").apply { isDaemon = true }
    }

    fun toSmbPath(path: String): String = path.replace('/', '\\')

    // ---------------------------------------------------------------- public actions

    fun listFolder(creds: SmbCredentials, path: String): List<SmbEntry> =
        withShare(creds) { share ->
            val result = ArrayList<SmbEntry>()
            for (info in share.list(toSmbPath(path))) {
                val name = info.getFileName()
                if (name == "." || name == "..") continue
                val attributes = info.getFileAttributes()
                if ((attributes and ATTR_HIDDEN_OR_SYSTEM) != 0L) continue
                result.add(SmbEntry(name, (attributes and ATTR_DIRECTORY) != 0L, info.getEndOfFile()))
            }
            result
        }

    /** Opens a file for reading. The caller must hand it back with [closeAsync]. */
    fun openFile(creds: SmbCredentials, path: String): SmbOpenFile =
        withShare(creds) { share ->
            val file = share.openFile(
                toSmbPath(path),
                EnumSet.of(AccessMask.GENERIC_READ),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN,
                null,
            )
            val size = try {
                file.getFileInformation().getStandardInformation().getEndOfFile()
            } catch (e: Exception) {
                runCatching { file.close() }
                throw e
            }
            SmbOpenFile(file, size)
        }

    fun closeAsync(open: SmbOpenFile) {
        closer.execute { runCatching { open.file.close() } }
    }

    /**
     * Share names that exist on the server. Asking a server for its full share list needs a
     * separate "remote procedure call" protocol that the SMB library does not include, so this
     * tries the usual names (and the user's own name, for home folders) and keeps those that open.
     */
    fun probeShares(creds: SmbCredentials): List<String> =
        withSession(creds) { session ->
            val candidates = LinkedHashSet<String>(COMMON_SHARES)
            if (!creds.guest && creds.username.isNotEmpty()) candidates.add(creds.username)
            val found = ArrayList<String>()
            for (name in candidates) {
                try {
                    val share = session.connectShare(name)
                    if (share is DiskShare) found.add(name)
                    runCatching { share.close() }
                } catch (e: Exception) {
                    val problem = SmbErrors.classify(e)
                    if (problem == SmbProblem.CONNECTION_LOST || problem == SmbProblem.TIMEOUT) throw SmbErrors.toIo(e)
                }
            }
            found
        }

    /** Logs in with a fresh connection (never the shared one) and checks the share opens. */
    fun test(creds: SmbCredentials): SmbTestOutcome {
        return try {
            if (creds.shareName.isEmpty()) {
                val names = probeShares(creds)
                if (names.isEmpty()) {
                    SmbTestOutcome(
                        true,
                        "Login works, but none of the usual share names was found. " +
                            "Type the share name yourself (the name you see in Windows or in the NAS settings).",
                    )
                } else {
                    SmbTestOutcome(true, "Connected. Shares found: ${names.joinToString(", ")}.")
                }
            } else {
                val count = withSession(creds) { session ->
                    val share = try {
                        session.connectShare(creds.shareName)
                    } catch (e: Exception) {
                        throw SmbErrors.toIo(e)
                    }
                    try {
                        if (share !is DiskShare) throw SmbIoException(SmbProblem.SHARE_NOT_FOUND)
                        share.list("").size
                    } finally {
                        runCatching { share.close() }
                    }
                }
                SmbTestOutcome(true, "Connected. The share opens and its top folder has $count items.")
            }
        } catch (e: Exception) {
            AppLog.log("SMB_TEST", "failed: ${SmbErrors.forLog(e)} for ${creds.host}")
            SmbTestOutcome(false, SmbErrors.describe(e))
        }
    }

    /** Forget every connection and login memory of a server (its share was edited or deleted). */
    fun forget(host: String) {
        val prefix = host.lowercase() + ":"
        for (key in entries.keys.toList()) {
            if (key.startsWith(prefix)) entries.remove(key)?.let { entry -> closer.execute { entry.closeQuietly() } }
        }
        for (key in authFailedAt.keys.toList()) {
            if (key.startsWith(prefix)) authFailedAt.remove(key)
        }
    }

    /** The connection broke while reading: throw it away so the next open starts clean. */
    fun dropConnection(creds: SmbCredentials) {
        val entry = entries.remove(creds.poolKey) ?: return
        closer.execute { entry.closeQuietly() }
    }

    // ---------------------------------------------------------------- shared connections

    private fun <T> withShare(creds: SmbCredentials, block: (DiskShare) -> T): T {
        var entry = acquire(creds)
        try {
            return block(entry.share)
        } catch (first: Exception) {
            val problem = SmbErrors.classify(first)
            if (SmbErrors.isDefinite(problem) || first is InterruptedException) throw SmbErrors.toIo(first)
            AppLog.log("SMB", "connection problem (${SmbErrors.forLog(first)}), trying once more")
            discard(creds.poolKey, entry)
            entry = acquire(creds)
            try {
                return block(entry.share)
            } catch (second: Exception) {
                discard(creds.poolKey, entry)
                throw SmbErrors.toIo(second)
            }
        }
    }

    private fun discard(key: String, entry: Entry) {
        entries.remove(key, entry)
        closer.execute { entry.closeQuietly() }
    }

    private fun acquire(creds: SmbCredentials): Entry {
        val key = creds.poolKey
        val ready = entries[key]
        if (ready != null) return ready
        val lock = locks.getOrPut(key) { Any() }
        return synchronized(lock) {
            // Another thread may have connected while this one waited for the lock.
            val again = entries[key]
            if (again != null) return@synchronized again
            val failedAt = authFailedAt[key]
            if (failedAt != null && System.currentTimeMillis() - failedAt < AUTH_COOLDOWN_MS) {
                throw SmbIoException(SmbProblem.WRONG_LOGIN)
            }
            val entry = try {
                connect(creds)
            } catch (e: Exception) {
                val io = SmbErrors.toIo(e)
                if (io is SmbIoException && io.problem == SmbProblem.WRONG_LOGIN) {
                    authFailedAt[key] = System.currentTimeMillis()
                }
                AppLog.log("SMB_CONNECT", "failed: ${SmbErrors.forLog(e)} for smb://${creds.host}/${creds.shareName}")
                throw io
            }
            authFailedAt.remove(key)
            entries[key] = entry
            AppLog.log("SMB_CONNECT", "ok: smb://${creds.host}/${creds.shareName}")
            entry
        }
    }

    // ---------------------------------------------------------------- connecting

    private fun config(): SmbConfig =
        SmbConfig.builder()
            .withTimeout(OP_TIMEOUT_SEC, TimeUnit.SECONDS)
            .build()

    /**
     * Android's own "BC" security provider is a cut-down copy; SMB logins need the full
     * BouncyCastle that comes with the library. Done once, by name, so nothing breaks if it is missing.
     */
    private fun ensureCryptoProvider() {
        if (!providerReady.compareAndSet(false, true)) return
        try {
            val provider = Class.forName("org.bouncycastle.jce.provider.BouncyCastleProvider")
                .getDeclaredConstructor()
                .newInstance() as Provider
            Security.removeProvider("BC")
            Security.addProvider(provider)
        } catch (e: Throwable) {
            AppLog.log("SMB", "BouncyCastle setup skipped: ${e.javaClass.simpleName}")
        }
    }

    /** A quick check of the TCP port first: unknown host, refused and timeout are then told apart clearly. */
    private fun precheck(host: String, port: Int) {
        val address = try {
            InetAddress.getByName(host)
        } catch (e: Exception) {
            throw SmbIoException(SmbProblem.HOST_NOT_FOUND, e)
        }
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(address, port), CONNECT_TIMEOUT_MS)
        } catch (e: Exception) {
            throw SmbErrors.toIo(e)
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun authenticate(connection: Connection, creds: SmbCredentials): Session {
        val users = if (creds.guest) listOf("Guest", "") else listOf(creds.username)
        var last: Exception? = null
        for (user in users) {
            try {
                val domain: String? = creds.domain.ifEmpty { null }
                val context = AuthenticationContext(user, creds.password.toCharArray(), domain)
                return connection.authenticate(context)
            } catch (e: Exception) {
                last = e
                val problem = SmbErrors.classify(e)
                if (problem != SmbProblem.WRONG_LOGIN && problem != SmbProblem.ACCESS_DENIED) break
            }
        }
        throw SmbErrors.toIo(last ?: IllegalStateException("login failed"))
    }

    /** Server + login + share, left open for sharing. */
    private fun connect(creds: SmbCredentials): Entry {
        precheck(creds.host, creds.port)
        ensureCryptoProvider()
        val client = SMBClient(config())
        try {
            val connection = try {
                client.connect(creds.host, creds.port)
            } catch (e: Exception) {
                throw SmbErrors.toIo(e, handshake = true)
            }
            try {
                val session = authenticate(connection, creds)
                try {
                    val share = try {
                        session.connectShare(creds.shareName)
                    } catch (e: Exception) {
                        throw SmbErrors.toIo(e)
                    }
                    if (share !is DiskShare) {
                        runCatching { share.close() }
                        throw SmbIoException(SmbProblem.SHARE_NOT_FOUND)
                    }
                    return Entry(client, connection, session, share)
                } catch (e: Exception) {
                    runCatching { session.close() }
                    throw e
                }
            } catch (e: Exception) {
                runCatching { connection.close() }
                throw e
            }
        } catch (e: Exception) {
            runCatching { client.close() }
            throw e
        }
    }

    /** Server + login only, closed again when [block] ends (used by the test and the share search). */
    private fun <T> withSession(creds: SmbCredentials, block: (Session) -> T): T {
        precheck(creds.host, creds.port)
        ensureCryptoProvider()
        val client = SMBClient(config())
        try {
            val connection = try {
                client.connect(creds.host, creds.port)
            } catch (e: Exception) {
                throw SmbErrors.toIo(e, handshake = true)
            }
            try {
                val session = authenticate(connection, creds)
                try {
                    return block(session)
                } finally {
                    runCatching { session.close() }
                }
            } finally {
                runCatching { connection.close() }
            }
        } finally {
            runCatching { client.close() }
        }
    }
}
