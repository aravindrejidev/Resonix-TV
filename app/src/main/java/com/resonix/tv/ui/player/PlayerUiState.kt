package com.resonix.tv.ui.player

import android.util.Log
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import com.resonix.tv.diag.AppLog

/** Compose-observable snapshot of an ExoPlayer instance. Call [poll] a few times per second. */
@Stable
class PlayerUiState(private val player: Player) {
    var isPlaying by mutableStateOf(false)
        private set
    var playbackState by mutableIntStateOf(Player.STATE_IDLE)
        private set
    var positionMs by mutableLongStateOf(0L)
        private set
    var durationMs by mutableLongStateOf(0L)
        private set
    var bufferedMs by mutableLongStateOf(0L)
        private set
    var tracks by mutableStateOf(Tracks.EMPTY)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    /** True when trying again can help (a USB drive that woke up late, a decoder that hiccuped). */
    var errorRetryable by mutableStateOf(false)
        private set

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            this@PlayerUiState.isPlaying = isPlaying
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            this@PlayerUiState.playbackState = playbackState
        }

        override fun onTracksChanged(tracks: Tracks) {
            this@PlayerUiState.tracks = tracks
        }

        override fun onPlayerError(error: PlaybackException) {
            AppLog.log(
                "PLAYER_ERROR",
                "${error.errorCodeName} | ${error.message} | at ${player.currentPosition / 1000} s\n" +
                    Log.getStackTraceString(error).take(1500),
            )
            this@PlayerUiState.errorRetryable = isRetryable(error.errorCode)
            this@PlayerUiState.error = friendlyMessage(error)
        }
    }

    fun attach() {
        player.addListener(listener)
        isPlaying = player.isPlaying
        playbackState = player.playbackState
        tracks = player.currentTracks
    }

    fun detach() {
        player.removeListener(listener)
    }

    /** Hides the error message (used before trying to play again). */
    fun clearError() {
        error = null
    }

    fun poll() {
        positionMs = player.currentPosition
        val duration = player.duration
        durationMs = if (duration == C.TIME_UNSET || duration < 0) 0L else duration
        bufferedMs = player.bufferedPosition
    }
}

/** A message people can understand, with the technical code underneath for the developer. */
private fun friendlyMessage(error: PlaybackException): String {
    val text = when (error.errorCode) {
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
        PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
        PlaybackException.ERROR_CODE_IO_UNSPECIFIED -> "The file can't be read. Was the USB drive unplugged?"
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED -> "This TV could not start a decoder for this video or audio."
        PlaybackException.ERROR_CODE_DECODING_FAILED -> "A decoder stopped working while playing."
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES -> "This TV can't play this video or audio format."
        PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
        PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED -> "The sound output failed."
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED -> "The file looks damaged, or it is not supported."
        else -> "Playback stopped because of an error."
    }
    return text + "\n" + error.errorCodeName
}

/** Errors that can go away by themselves; "format not supported" and "file damaged" never do. */
private fun isRetryable(code: Int): Boolean = when (code) {
    PlaybackException.ERROR_CODE_UNSPECIFIED,
    PlaybackException.ERROR_CODE_TIMEOUT,
    PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
    PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
    PlaybackException.ERROR_CODE_DECODING_FAILED,
    PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
    PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED -> true
    else -> false
}
