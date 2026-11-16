package com.resonix.tv.net

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceException
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeoutException

/** Turns the many exceptions of the network stack into a few plain problems. */
object SmbErrors {

    /** [handshake] = the failure happened while connecting / negotiating (before any login). */
    fun classify(error: Throwable, handshake: Boolean = false): SmbProblem {
        if (error is SmbIoException) return error.problem
        val chain = generateSequence(error) { it.cause }.take(8).toList()
        val text = chain.joinToString(" | ") { it.javaClass.name + " " + (it.message ?: "") }.lowercase()
        return when {
            chain.any { it is UnknownHostException } -> SmbProblem.HOST_NOT_FOUND
            chain.any { it is NoRouteToHostException } || text.contains("unreachable") ||
                text.contains("no route") -> SmbProblem.UNREACHABLE
            chain.any { it is SocketTimeoutException || it is TimeoutException } ||
                text.contains("timed out") || text.contains("timeout") -> SmbProblem.TIMEOUT
            text.contains("refused") -> SmbProblem.REFUSED
            chain.any { it is ConnectException } -> SmbProblem.UNREACHABLE
            text.contains("status_logon_failure") || text.contains("status_account_disabled") ||
                text.contains("status_account_locked_out") || text.contains("status_password_expired") ||
                text.contains("status_password_must_change") || text.contains("status_account_restriction") ||
                text.contains("status_no_logon_servers") || text.contains("status_logon_type_not_granted") ||
                text.contains("authentication failed") -> SmbProblem.WRONG_LOGIN
            text.contains("status_access_denied") || text.contains("status_sharing_violation") ->
                SmbProblem.ACCESS_DENIED
            text.contains("status_bad_network_name") -> SmbProblem.SHARE_NOT_FOUND
            text.contains("status_object_name_not_found") || text.contains("status_object_path_not_found") ||
                text.contains("status_no_such_file") || text.contains("status_not_a_directory") ||
                text.contains("status_object_name_invalid") -> SmbProblem.PATH_NOT_FOUND
            text.contains("negotiat") || text.contains("dialect") || text.contains("smb1") ||
                text.contains("status_not_supported") -> SmbProblem.SMB_VERSION
            handshake && (chain.any { it is EOFException || it is SocketException } ||
                text.contains("reset") || text.contains("closed") || text.contains("broken pipe")) ->
                SmbProblem.SMB_VERSION
            text.contains("transport") || text.contains("broken pipe") || text.contains("reset") ||
                text.contains("connection closed") || chain.any { it is EOFException || it is SocketException } ->
                SmbProblem.CONNECTION_LOST
            else -> SmbProblem.OTHER
        }
    }

    /** True for problems where the server gave a clear answer: asking again can not help. */
    fun isDefinite(problem: SmbProblem): Boolean = when (problem) {
        SmbProblem.WRONG_LOGIN, SmbProblem.ACCESS_DENIED, SmbProblem.SHARE_NOT_FOUND,
        SmbProblem.PATH_NOT_FOUND, SmbProblem.NOT_SAVED, SmbProblem.PASSWORD_UNREADABLE,
        SmbProblem.SMB_VERSION, SmbProblem.HOST_NOT_FOUND -> true
        else -> false
    }

    /** What leaves the network layer: our own exception, or ExoPlayer's position error, as it is. */
    @OptIn(UnstableApi::class)
    fun toIo(error: Throwable, handshake: Boolean = false): IOException {
        if (error is SmbIoException || error is DataSourceException) return error as IOException
        return SmbIoException(classify(error, handshake), error)
    }

    /** One sentence for the screen. */
    fun describe(error: Throwable): String {
        val problem = classify(error)
        if (problem != SmbProblem.OTHER) return problem.text
        val detail = generateSequence(error) { it.cause }.last()
        return problem.text + " (" + detail.javaClass.simpleName + ")"
    }

    /** A short line for the log: category and exception class names only, no texts from the server. */
    fun forLog(error: Throwable): String {
        val names = generateSequence(error) { it.cause }.take(4).joinToString(" < ") { it.javaClass.simpleName }
        return classify(error).name + " [" + names + "]"
    }
}
