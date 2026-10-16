package com.resonix.tv.input

import android.view.KeyEvent as AndroidKeyEvent
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
        /** Maps a raw Android key code (Activity level - works whatever Compose has focused). */
        fun fromKeyCode(keyCode: Int): RemoteKey? = when (keyCode) {
            AndroidKeyEvent.KEYCODE_DPAD_CENTER,
            AndroidKeyEvent.KEYCODE_ENTER,
            AndroidKeyEvent.KEYCODE_NUMPAD_ENTER -> SELECT
            AndroidKeyEvent.KEYCODE_DPAD_UP -> UP
            AndroidKeyEvent.KEYCODE_DPAD_DOWN -> DOWN
            AndroidKeyEvent.KEYCODE_DPAD_LEFT -> LEFT
            AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> RIGHT
            AndroidKeyEvent.KEYCODE_BACK, AndroidKeyEvent.KEYCODE_ESCAPE -> BACK
            AndroidKeyEvent.KEYCODE_MENU -> MENU
            AndroidKeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> PLAY_PAUSE
            AndroidKeyEvent.KEYCODE_MEDIA_PLAY -> PLAY
            AndroidKeyEvent.KEYCODE_MEDIA_PAUSE -> PAUSE
            AndroidKeyEvent.KEYCODE_MEDIA_STOP -> STOP
            AndroidKeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> FAST_FORWARD
            AndroidKeyEvent.KEYCODE_MEDIA_REWIND -> REWIND
            AndroidKeyEvent.KEYCODE_MEDIA_NEXT -> NEXT
            AndroidKeyEvent.KEYCODE_MEDIA_PREVIOUS -> PREVIOUS
            AndroidKeyEvent.KEYCODE_CHANNEL_UP -> CHANNEL_UP
            AndroidKeyEvent.KEYCODE_CHANNEL_DOWN -> CHANNEL_DOWN
            else -> null
        }

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
