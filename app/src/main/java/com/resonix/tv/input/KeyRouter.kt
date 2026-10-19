package com.resonix.tv.input

/**
 * Routes remote-control keys from the Activity to whichever screen is showing.
 *
 * The UI is driven by this router instead of Compose's focus system: each screen keeps its own
 * "cursor" state and reacts to [RemoteKey] events, which makes D-pad control predictable on
 * every TV, whatever the focus behaviour of the device's input stack.
 */
object KeyRouter {
    /** Return true when the key was consumed. `repeat` is 0 for the first press, >0 while held. */
    @Volatile
    var handler: ((RemoteKey, Int) -> Boolean)? = null

    fun dispatch(key: RemoteKey, repeat: Int): Boolean = handler?.invoke(key, repeat) ?: false
}
