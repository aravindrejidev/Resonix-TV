package com.resonix.tv.player

/**
 * Lets the activity tell the open player screen that the app left the screen
 * (HOME button, TV going to sleep, screen saver, another app in front).
 * The player screen sets [onAppStopped] while a movie is open and clears it when it closes.
 */
object PlayerLifecycle {
    @Volatile
    var onAppStopped: (() -> Unit)? = null
}
