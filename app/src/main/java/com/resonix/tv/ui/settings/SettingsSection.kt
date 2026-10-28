package com.resonix.tv.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.resonix.tv.BuildConfig
import com.resonix.tv.audio.AudioPipeline
import com.resonix.tv.data.HistoryStore
import com.resonix.tv.data.SettingsStore
import com.resonix.tv.data.Thumbnails
import com.resonix.tv.input.RemoteKey
import com.resonix.tv.ui.components.NeoIcon
import com.resonix.tv.ui.components.NeoSectionHeader
import com.resonix.tv.ui.components.NeoSettingRow
import com.resonix.tv.ui.components.NeoText
import com.resonix.tv.ui.shell.SectionKeys
import com.resonix.tv.ui.theme.NeoColors
import com.resonix.tv.update.Updater

private class SettingEntry(
    val title: String,
    val subtitle: String?,
    val icon: NeoIcon,
    val valueText: String? = null,
    val toggle: Boolean? = null,
    val chevron: Boolean = false,
    val onSelect: () -> Unit,
    val onAdjust: ((Int) -> Unit)? = null,
)

private sealed interface SettingLine {
    data class Header(val text: String) : SettingLine
    class Entry(val entry: SettingEntry) : SettingLine
}

/** Settings page. UP/DOWN choose, OK changes the highlighted setting. */
@Composable
fun SettingsSection(active: Boolean, keys: SectionKeys, onOpenDiagnostics: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { SettingsStore.get(context) }
    val history = remember { HistoryStore.get(context) }
    val dsp = remember { AudioPipeline.controller(context) }
    val currentOnDiagnostics by rememberUpdatedState(onOpenDiagnostics)

    var message by remember { mutableStateOf("") }
    var cursor by remember { mutableIntStateOf(1) }

    val lines: List<SettingLine> = listOf(
        SettingLine.Header("DISPLAY"),
        SettingLine.Entry(
            SettingEntry("Interface size", "LEFT / RIGHT makes everything smaller or larger", NeoIcon.VIDEO,
                valueText = "${settings.uiScalePercent} %",
                onSelect = { settings.cycleUiScale() },
                onAdjust = { settings.stepUiScale(it) }),
        ),
        SettingLine.Header("PLAYBACK"),
        SettingLine.Entry(
            SettingEntry("Seek step", "How far LEFT and RIGHT jump while watching", NeoIcon.PLAY,
                valueText = "${settings.seekStepSec} s", onSelect = { settings.cycleSeekStep() }),
        ),
        SettingLine.Entry(
            SettingEntry("Hide controls after", "Seconds until the on-screen controls fade away", NeoIcon.VIDEO,
                valueText = "${settings.osdSeconds} s", onSelect = { settings.cycleOsdSeconds() }),
        ),
        SettingLine.Entry(
            SettingEntry("Resume where you stopped", "Continue a movie from the last position", NeoIcon.HISTORY,
                toggle = settings.resumePlayback, onSelect = { settings.toggleResume() }),
        ),
        SettingLine.Entry(
            SettingEntry("Show subtitle files automatically", "Turns on a subtitle file found next to the movie", NeoIcon.FOLDER,
                toggle = settings.autoSubtitles, onSelect = { settings.toggleAutoSubtitles() }),
        ),
        SettingLine.Header("AUDIO"),
        SettingLine.Entry(
            SettingEntry("Channel mixer and EQ", "Off = sound goes through untouched (graphic and parametric EQ)", NeoIcon.EQ,
                toggle = dsp.processingEnabled, onSelect = { dsp.setProcessing(!dsp.processingEnabled) }),
        ),
        SettingLine.Entry(
            SettingEntry("Reset audio settings", "Flat graphic EQ, every channel at 0 dB, nothing muted. Your saved parametric EQ is kept", NeoIcon.EQ,
                chevron = true, onSelect = {
                    dsp.resetAll()
                    message = "Audio settings were reset."
                }),
        ),
        SettingLine.Header("LIBRARY"),
        SettingLine.Entry(
            SettingEntry("Clear watch history", "Removes Recently Watched and the preview pictures", NeoIcon.HISTORY,
                chevron = true, onSelect = {
                    history.clear()
                    Thumbnails.clear(context)
                    message = "Watch history was cleared."
                }),
        ),
        SettingLine.Header("SYSTEM"),
        SettingLine.Entry(
            SettingEntry("Diagnostics", "Audio engine, FFmpeg decoders, remote keys", NeoIcon.SETTINGS,
                chevron = true, onSelect = { currentOnDiagnostics() }),
        ),
        SettingLine.Entry(
            SettingEntry("About", "Version ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})  |  " +
                (if (Updater.repo.isBlank()) "no update source" else "github.com/${Updater.repo}"),
                NeoIcon.INFO, onSelect = {}),
        ),
    )
    val latestLines by rememberUpdatedState(lines)

    fun move(delta: Int) {
        val all = latestLines
        var index = cursor + delta
        while (index in all.indices && all[index] is SettingLine.Header) index += delta
        if (index in all.indices) cursor = index
    }

    DisposableEffect(Unit) {
        val handler: (RemoteKey, Int) -> Boolean = { key, _ ->
            when (key) {
                RemoteKey.UP -> {
                    move(-1)
                    true
                }
                RemoteKey.DOWN -> {
                    move(1)
                    true
                }
                RemoteKey.LEFT, RemoteKey.RIGHT -> {
                    val line = latestLines.getOrNull(cursor)
                    val adjust = (line as? SettingLine.Entry)?.entry?.onAdjust
                    if (adjust != null) {
                        adjust(if (key == RemoteKey.RIGHT) 1 else -1)
                        true
                    } else {
                        false
                    }
                }
                RemoteKey.SELECT -> {
                    val line = latestLines.getOrNull(cursor)
                    if (line is SettingLine.Entry) line.entry.onSelect()
                    true
                }
                else -> false
            }
        }
        keys.handler = handler
        onDispose { if (keys.handler === handler) keys.handler = null }
    }

    val listState = rememberLazyListState()
    LaunchedEffect(cursor) { listState.animateScrollToItem((cursor - 2).coerceAtLeast(0)) }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        NeoSectionHeader("Settings", "Playback, audio and library", NeoIcon.SETTINGS)
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 14.dp),
        ) {
            itemsIndexed(lines) { index, line ->
                when (line) {
                    is SettingLine.Header -> NeoText(
                        text = line.text,
                        size = 13.sp,
                        color = NeoColors.TextDim,
                        weight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 6.dp, top = 8.dp),
                    )
                    is SettingLine.Entry -> NeoSettingRow(
                        title = line.entry.title,
                        subtitle = line.entry.subtitle,
                        icon = line.entry.icon,
                        focused = active && index == cursor,
                        valueText = line.entry.valueText,
                        toggle = line.entry.toggle,
                        chevron = line.entry.chevron,
                    )
                }
            }
        }
        if (message.isNotEmpty()) NeoText(message, size = 15.sp, color = NeoColors.Cyan)
    }
}
