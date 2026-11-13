package com.resonix.tv.player

import android.app.Activity
import android.view.Display
import java.util.Locale
import kotlin.math.abs

/**
 * "Match frame rate": a film is shot at 24 pictures a second, but a TV normally shows 60. 24 does
 * not fit into 60, so motion judders. This asks the TV to switch to a display mode whose refresh
 * rate fits the film (24 / 48 / 72 Hz for 23.976 fps, 50 Hz for 25 fps, 60 Hz for 30 fps ...).
 * The screen goes black for a moment while the TV changes mode.
 */
object FrameRateMatcher {

    /**
     * A display mode with the same picture size as now whose refresh rate equals [fps] or a whole
     * multiple of it (the lowest multiple wins). Null when the TV offers nothing that fits.
     */
    fun choose(display: Display, fps: Float): Display.Mode? {
        val current = display.mode
        var best: Display.Mode? = null
        var bestScore = Float.MAX_VALUE
        for (mode in display.supportedModes) {
            if (mode.physicalWidth != current.physicalWidth || mode.physicalHeight != current.physicalHeight) continue
            for (multiple in 1..5) {
                val difference = abs(mode.refreshRate - fps * multiple)
                if (difference < 0.1f) {
                    val score = multiple * 10f + difference
                    if (score < bestScore) {
                        best = mode
                        bestScore = score
                    }
                }
            }
        }
        return best
    }

    /** Asks for [mode], or (null) hands the choice back to the system. */
    fun apply(activity: Activity, mode: Display.Mode?) {
        val attributes = activity.window.attributes
        attributes.preferredDisplayModeId = mode?.modeId ?: 0
        activity.window.attributes = attributes
    }

    fun describe(mode: Display.Mode): String =
        "${mode.physicalWidth}x${mode.physicalHeight} @ ${String.format(Locale.US, "%.2f", mode.refreshRate)} Hz"
}
