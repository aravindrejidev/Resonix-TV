package com.resonix.tv.net

import androidx.annotation.OptIn
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceException

/** A saved network share. The password is NOT part of this class: it lives encrypted in [SmbShareStore]. */
data class SmbShare(
    val id: String,
    val displayName: String,
    val host: String,
    val port: Int,
    /** Name of the share on the server. Empty = "ask the server which shares it has". */
    val shareName: String,
    val username: String,
    val domain: String,
    val guest: Boolean,
)

/** Everything needed to log in. Never printed: [toString] shows the address only. */
class SmbCredentials(
    val host: String,
    val port: Int,
    val shareName: String,
    val username: String,
    val password: String,
    val domain: String,
    val guest: Boolean,
) {
    /** Connections are shared per server + share + user (the password is not part of the key). */
    val poolKey: String
        get() = "$host:$port|$shareName|${if (guest) "<guest>" else username}|$domain".lowercase()

    fun withShare(name: String): SmbCredentials =
        SmbCredentials(host, port, name, username, password, domain, guest)

    override fun toString(): String = "SmbCredentials(smb://$host/$shareName)"
}

/** Why a network action failed, in a few plain categories. [text] is what the viewer reads. */
enum class SmbProblem(val playbackCode: Int, val text: String) {
    HOST_NOT_FOUND(
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        "The server was not found. Check the name or IP address (an IP like 192.168.1.20 is the safest), " +
            "and that the TV and the server are on the same network.",
    ),
    TIMEOUT(
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        "The server did not answer in time. It may be switched off, asleep, or out of Wi-Fi range.",
    ),
    REFUSED(
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        "The server refused the connection. Is file sharing (SMB) switched on at the server?",
    ),
    UNREACHABLE(
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        "There is no way to reach the server. Is the TV connected to the same network?",
    ),
    SMB_VERSION(
        PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
        "The server and the TV could not agree on an SMB version. ResonixTV needs SMB 2 or SMB 3. " +
            "Very old servers only offer SMB 1: switch SMB 2 / SMB 3 on at the server.",
    ),
    WRONG_LOGIN(
        PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
        "Wrong user name or password (or the account is disabled or locked).",
    ),
    ACCESS_DENIED(
        PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
        "Access denied. This user may not open this share or folder (or guest login is not allowed).",
    ),
    SHARE_NOT_FOUND(
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
        "The server has no share with this name. Check the share name.",
    ),
    PATH_NOT_FOUND(
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
        "That folder or file was not found on the share. It may have been moved or deleted.",
    ),
    NOT_SAVED(
        PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
        "This network share is not saved any more. Add it again under Network.",
    ),
    PASSWORD_UNREADABLE(
        PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
        "The saved password cannot be read any more. Edit the share and type the password again.",
    ),
    CONNECTION_LOST(
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        "The connection to the server was lost. Check the network and try again.",
    ),
    OTHER(
        PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
        "The network share could not be read.",
    ),
}

/**
 * The one exception type that leaves the network layer. It is a DataSourceException, so ExoPlayer
 * gets a matching error code (for example "no permission" for a wrong password, which the player
 * does not retry on its own).
 */
@OptIn(UnstableApi::class)
class SmbIoException(val problem: SmbProblem, cause: Throwable? = null) :
    DataSourceException(cause, problem.playbackCode) {
    override val message: String
        get() = problem.text
}
