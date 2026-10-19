package com.resonix.tv.ui.player

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
            this@PlayerUiState.error = "${error.errorCodeName}: ${error.message ?: "unknown error"}"
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

    fun poll() {
        positionMs = player.currentPosition
        val duration = player.duration
        durationMs = if (duration == C.TIME_UNSET || duration < 0) 0L else duration
        bufferedMs = player.bufferedPosition
    }
}
