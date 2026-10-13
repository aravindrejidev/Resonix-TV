package com.resonix.tv.input

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type

/**
 * Logical remote-control actions, independent of the physical key codes that different
 * Android TV remotes (D-pad, media keys, keyboards) actually send.
 */
enum class RemoteKey {
    SELECT, UP, DOWN, LEFT, RIGHT, BACK, MENU,
    PLAY_PAUSE, PLAY, PAUSE, STOP, FAST_FORWARD, REWIND, NEXT, PREVIOUS,
    CHANNEL_UP, CHANNEL_DOWN;

    companion object {
        /** Maps a Compose key event to a [RemoteKey]. Only key-down events are mapped. */
        fun from(event: KeyEvent): RemoteKey? {
            if (event.type != KeyEventType.KeyDown) return null
            return when (event.key) {
                Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> SELECT
                Key.DirectionUp -> UP
                Key.DirectionDown -> DOWN
                Key.DirectionLeft -> LEFT
                Key.DirectionRight -> RIGHT
                Key.Back, Key.Escape -> BACK
                Key.Menu -> MENU
                Key.MediaPlayPause -> PLAY_PAUSE
                Key.MediaPlay -> PLAY
                Key.MediaPause -> PAUSE
                Key.MediaStop -> STOP
                Key.MediaFastForward -> FAST_FORWARD
                Key.MediaRewind -> REWIND
                Key.MediaNext -> NEXT
                Key.MediaPrevious -> PREVIOUS
                Key.ChannelUp -> CHANNEL_UP
                Key.ChannelDown -> CHANNEL_DOWN
                else -> null
            }
        }
    }
}
