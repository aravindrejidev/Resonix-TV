package com.resonix.tv.player

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener
import com.resonix.tv.diag.AppLog
import com.resonix.tv.net.SmbConnections
import com.resonix.tv.net.SmbCredentials
import com.resonix.tv.net.SmbErrors
import com.resonix.tv.net.SmbIoException
import com.resonix.tv.net.SmbOpenFile
import com.resonix.tv.net.SmbProblem
import com.resonix.tv.net.SmbShareStore
import com.resonix.tv.net.SmbUri
import java.io.InterruptedIOException

/**
 * Plays files from a network share (smb://host/share/path).
 *
 * - Random access: every open() starts at DataSpec.position, so seeking in MKV / MP4 works.
 * - Reads go to the server in big blocks (up to 512 KB), not in the player's small 64 KB pieces:
 *   on Wi-Fi the waiting time per request would otherwise cap the speed.
 * - The login comes from the saved share (see SmbShareStore); the address holds none.
 * - After a failure the same instance can be opened again (ExoPlayer retries that way).
 */
@OptIn(UnstableApi::class)
class SmbDataSource(context: Context) : BaseDataSource(/* isNetwork = */ true) {

    private val appContext = context.applicationContext
    private var currentUri: Uri? = null
    private var credentials: SmbCredentials? = null
    private var handle: SmbOpenFile? = null
    private var opened = false

    /** Block read from the server; the player takes its bytes from here. */
    private val block = ByteArray(MAX_BLOCK)
    private var blockLength = 0
    private var blockPos = 0
    private var blockSize = MAX_BLOCK

    private var filePosition = 0L
    private var bytesRemaining = 0L

    private var openedAtMs = 0L
    private var bytesDelivered = 0L

    override fun open(dataSpec: DataSpec): Long {
        currentUri = dataSpec.uri
        transferInitializing(dataSpec)
        try {
            val parsed = SmbUri.parse(dataSpec.uri) ?: throw SmbIoException(SmbProblem.PATH_NOT_FOUND)
            val creds = SmbShareStore.get(appContext).credentialsFor(parsed.host, parsed.port, parsed.share)
                ?: throw SmbIoException(SmbProblem.NOT_SAVED)
            credentials = creds
            val file = SmbConnections.openFile(creds, parsed.path)
            handle = file
            if (dataSpec.position > file.sizeBytes) {
                throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
            }
            filePosition = dataSpec.position
            val untilEnd = file.sizeBytes - dataSpec.position
            bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) minOf(dataSpec.length, untilEnd) else untilEnd
            blockLength = 0
            blockPos = 0
            AppLog.log("SMB_OPEN", "${fileName(parsed.path)} from ${dataSpec.position / 1_048_576} MB")
        } catch (e: Exception) {
            releaseHandle()
            throw SmbErrors.toIo(e)
        }
        opened = true
        openedAtMs = System.currentTimeMillis()
        bytesDelivered = 0L
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        if (blockPos >= blockLength && !fillBlock()) return C.RESULT_END_OF_INPUT
        val count = minOf(length.toLong(), bytesRemaining, (blockLength - blockPos).toLong()).toInt()
        System.arraycopy(block, blockPos, buffer, offset, count)
        blockPos += count
        filePosition += count
        bytesRemaining -= count
        bytesDelivered += count
        bytesTransferred(count)
        return count
    }

    /** Reads the next block from the server. False = the file ended earlier than the server said. */
    private fun fillBlock(): Boolean {
        val file = handle ?: throw SmbIoException(SmbProblem.CONNECTION_LOST)
        blockPos = 0
        blockLength = 0
        while (true) {
            val wanted = minOf(blockSize.toLong(), bytesRemaining).toInt()
            try {
                val got = file.file.read(block, filePosition, 0, wanted)
                if (got < 0) {
                    bytesRemaining = 0L
                    return false
                }
                if (got == 0) throw SmbIoException(SmbProblem.CONNECTION_LOST)
                blockLength = got
                return true
            } catch (e: Exception) {
                if (Thread.currentThread().isInterrupted) throw InterruptedIOException()
                // An old server may allow smaller blocks only: halve the size and ask again.
                val tooBig = e.message?.contains("INVALID_PARAMETER", ignoreCase = true) == true
                if (tooBig && blockSize > MIN_BLOCK) {
                    blockSize /= 2
                    AppLog.log("SMB", "server wants smaller reads, now ${blockSize / 1024} KB")
                    continue
                }
                val io = SmbErrors.toIo(e)
                // A broken connection must not be reused by the retry that ExoPlayer starts next.
                val problem = SmbErrors.classify(e)
                if (!SmbErrors.isDefinite(problem)) credentials?.let { SmbConnections.dropConnection(it) }
                throw io
            }
        }
    }

    override fun getUri(): Uri? = currentUri

    override fun close() {
        val wasOpen = opened
        opened = false
        releaseHandle()
        currentUri = null
        blockLength = 0
        blockPos = 0
        if (wasOpen) {
            val seconds = ((System.currentTimeMillis() - openedAtMs) / 1000.0).coerceAtLeast(0.1)
            if (bytesDelivered > 4_194_304L) {
                val mbPerSecond = bytesDelivered / 1_048_576.0 / seconds
                AppLog.log("SMB_CLOSE", String.format(java.util.Locale.US, "%d MB read, %.1f MB/s", bytesDelivered / 1_048_576L, mbPerSecond))
            }
            transferEnded()
        }
    }

    private fun releaseHandle() {
        val file = handle
        handle = null
        if (file != null) SmbConnections.closeAsync(file)
    }

    private fun fileName(path: String): String = path.substringAfterLast('/')

    private companion object {
        const val MAX_BLOCK = 512 * 1024
        const val MIN_BLOCK = 64 * 1024
    }
}

/**
 * smb:// goes to [SmbDataSource]; everything else (files, content, http, https ...) goes to
 * Media3's normal DefaultDataSource, exactly as before.
 */
@OptIn(UnstableApi::class)
internal class RoutingDataSource(
    private val smb: DataSource,
    private val other: DataSource,
) : DataSource {

    private var active: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        smb.addTransferListener(transferListener)
        other.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val target = if (SmbUri.isSmb(dataSpec.uri)) smb else other
        active = target
        return target.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val source = active ?: throw IllegalStateException("read before open")
        return source.read(buffer, offset, length)
    }

    override fun getUri(): Uri? = active?.getUri()

    override fun getResponseHeaders(): Map<String, List<String>> =
        active?.getResponseHeaders() ?: emptyMap()

    override fun close() {
        val source = active
        active = null
        source?.close()
    }
}

/** The data source factory of the player: see [RoutingDataSource]. */
@OptIn(UnstableApi::class)
class ResonixDataSourceFactory(context: Context) : DataSource.Factory {
    private val appContext = context.applicationContext
    private val defaultFactory = DefaultDataSource.Factory(appContext)

    override fun createDataSource(): DataSource =
        RoutingDataSource(SmbDataSource(appContext), defaultFactory.createDataSource())
}
