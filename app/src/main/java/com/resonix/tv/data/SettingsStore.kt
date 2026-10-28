package com.resonix.tv.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** App settings. Values are Compose state, so screens update the moment something changes. */
class SettingsStore private constructor(private val prefs: SharedPreferences) {

    var seekStepSec by mutableIntStateOf(prefs.getInt("seek_step", 10))
        private set
    /** Size of the whole interface in percent (70 = everything 30 % smaller than the design size). */
    var uiScalePercent by mutableIntStateOf(loadUiScale())
        private set
    var osdSeconds by mutableIntStateOf(prefs.getInt("osd_seconds", 4))
        private set
    var resumePlayback by mutableStateOf(prefs.getBoolean("resume", true))
        private set
    var autoSubtitles by mutableStateOf(prefs.getBoolean("auto_subtitles", true))
        private set

    /**
     * The first time this version starts, the interface becomes smaller: the old default (80 %) was
     * too big. A smaller size chosen earlier is kept; nothing ever becomes bigger than before.
     */
    private fun loadUiScale(): Int {
        if (prefs.contains(KEY_UI_SCALE)) return prefs.getInt(KEY_UI_SCALE, DEFAULT_UI_SCALE)
        val old = prefs.getInt("ui_scale", -1)
        val migrated = if (old > 0) minOf(old, DEFAULT_UI_SCALE) else DEFAULT_UI_SCALE
        prefs.edit().putInt(KEY_UI_SCALE, migrated).apply()
        return migrated
    }

    fun stepUiScale(delta: Int) {
        val current = UI_SCALES.indexOf(uiScalePercent).let { if (it < 0) UI_SCALES.indexOf(DEFAULT_UI_SCALE) else it }
        uiScalePercent = UI_SCALES[(current + delta).coerceIn(0, UI_SCALES.lastIndex)]
        prefs.edit().putInt(KEY_UI_SCALE, uiScalePercent).apply()
    }

    fun cycleUiScale() {
        uiScalePercent = next(UI_SCALES, uiScalePercent)
        prefs.edit().putInt(KEY_UI_SCALE, uiScalePercent).apply()
    }

    fun cycleSeekStep() {
        seekStepSec = next(SEEK_STEPS, seekStepSec)
        prefs.edit().putInt("seek_step", seekStepSec).apply()
    }

    fun cycleOsdSeconds() {
        osdSeconds = next(OSD_SECONDS, osdSeconds)
        prefs.edit().putInt("osd_seconds", osdSeconds).apply()
    }

    fun toggleResume() {
        resumePlayback = !resumePlayback
        prefs.edit().putBoolean("resume", resumePlayback).apply()
    }

    fun toggleAutoSubtitles() {
        autoSubtitles = !autoSubtitles
        prefs.edit().putBoolean("auto_subtitles", autoSubtitles).apply()
    }

    private fun next(values: List<Int>, current: Int): Int {
        val index = values.indexOf(current)
        return values[(index + 1).mod(values.size)]
    }

    companion object {
        private const val KEY_UI_SCALE = "ui_scale_v2"
        const val DEFAULT_UI_SCALE = 70

        /** 50 % ... 120 % in 5 % steps. */
        val UI_SCALES: List<Int> = (50..120 step 5).toList()
        val SEEK_STEPS = listOf(5, 10, 15, 30)
        val OSD_SECONDS = listOf(3, 4, 6, 8, 12)

        @Volatile
        private var instance: SettingsStore? = null

        fun get(context: Context): SettingsStore =
            instance ?: synchronized(this) {
                instance ?: SettingsStore(
                    context.applicationContext.getSharedPreferences("resonix_settings", Context.MODE_PRIVATE),
                ).also { instance = it }
            }
    }
}
