package com.resonix.tv.ui.panel

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Tracks
import com.resonix.tv.audio.ChannelLayouts
import com.resonix.tv.audio.DspController
import com.resonix.tv.audio.DspPresets
import com.resonix.tv.audio.EqMode
import com.resonix.tv.input.RemoteKey
import com.resonix.tv.ui.channelLayoutName
import com.resonix.tv.ui.components.NeoEqCurve
import com.resonix.tv.ui.components.NeoFader
import com.resonix.tv.ui.components.NeoModeCard
import com.resonix.tv.ui.components.NeoRow
import com.resonix.tv.ui.components.NeoTab
import com.resonix.tv.ui.components.NeoText
import com.resonix.tv.ui.components.NeoToggleCard
import com.resonix.tv.ui.formatDb
import com.resonix.tv.ui.formatFreq
import com.resonix.tv.ui.theme.NeoColors
import com.resonix.tv.ui.theme.neuRaised
import java.util.Locale

enum class PanelTab(val title: String) {
    TRACKS("Audio & Subtitles"),
    CHANNELS("Channels"),
    EQUALIZER("Equalizer"),
    PRESETS("Presets"),
}

/** Where the D-pad cursor is inside the sliding panel. */
@Stable
class PanelState {
    var open by mutableStateOf(false)
        private set
    var tab by mutableStateOf(PanelTab.TRACKS)
    var inContent by mutableStateOf(false) // false = cursor on the tab bar
    var cursor by mutableIntStateOf(0)

    fun show() {
        open = true
        inContent = false
        cursor = 0
    }

    fun hide() {
        open = false
        inContent = false
    }

    /** Back: content -> tab bar -> closed. */
    fun back() {
        if (inContent) inContent = false else hide()
    }
}

// ---------------------------------------------------------------- audio / subtitle track list

sealed interface TrackItem {
    data class Header(val text: String) : TrackItem

    data class Choice(
        val label: String,
        val detail: String,
        val selected: Boolean,
        val isText: Boolean,
        val group: Tracks.Group?, // null = "Subtitles off"
        val trackIndex: Int,
    ) : TrackItem
}

fun codecName(mime: String?): String = when (mime) {
    MimeTypes.AUDIO_AC3 -> "AC3"
    MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC -> "E-AC3"
    MimeTypes.AUDIO_TRUEHD -> "TrueHD"
    MimeTypes.AUDIO_DTS -> "DTS"
    MimeTypes.AUDIO_DTS_HD -> "DTS-HD"
    MimeTypes.AUDIO_DTS_EXPRESS -> "DTS Express"
    MimeTypes.AUDIO_AAC -> "AAC"
    MimeTypes.AUDIO_FLAC -> "FLAC"
    MimeTypes.AUDIO_OPUS -> "Opus"
    MimeTypes.AUDIO_VORBIS -> "Vorbis"
    MimeTypes.AUDIO_MPEG -> "MP3"
    else -> mime.orEmpty().substringAfter('/')
}

private fun languageName(code: String?): String? {
    if (code.isNullOrBlank() || code == "und") return null
    val name = Locale.forLanguageTag(code).getDisplayLanguage(Locale.ENGLISH)
    return name.ifBlank { code }
}

/** The audio format ExoPlayer is playing right now (null before playback starts). */
fun selectedAudioFormat(tracks: Tracks): Format? {
    for (group in tracks.groups) {
        if (group.type != C.TRACK_TYPE_AUDIO) continue
        for (i in 0 until group.length) {
            if (group.isTrackSelected(i)) return group.getTrackFormat(i)
        }
    }
    return null
}

fun buildTrackItems(tracks: Tracks): List<TrackItem> {
    val audio = ArrayList<TrackItem>()
    val text = ArrayList<TrackItem>()
    var anyTextSelected = false
    for (group in tracks.groups) {
        for (i in 0 until group.length) {
            if (!group.isTrackSupported(i)) continue
            val format = group.getTrackFormat(i)
            val selected = group.isTrackSelected(i)
            when (group.type) {
                C.TRACK_TYPE_AUDIO -> {
                    val detail = listOf(
                        codecName(format.sampleMimeType),
                        if (format.channelCount > 0) channelLayoutName(format.channelCount) else "",
                    ).filter { it.isNotEmpty() }.joinToString(" | ")
                    audio.add(
                        TrackItem.Choice(
                            label = format.label ?: languageName(format.language) ?: "Track ${audio.size + 1}",
                            detail = detail,
                            selected = selected,
                            isText = false,
                            group = group,
                            trackIndex = i,
                        ),
                    )
                }
                C.TRACK_TYPE_TEXT -> {
                    if (selected) anyTextSelected = true
                    text.add(
                        TrackItem.Choice(
                            label = format.label ?: languageName(format.language) ?: "Subtitle ${text.size + 1}",
                            detail = format.sampleMimeType?.substringAfter('/') ?: "",
                            selected = selected,
                            isText = true,
                            group = group,
                            trackIndex = i,
                        ),
                    )
                }
                else -> Unit
            }
        }
    }
    val result = ArrayList<TrackItem>()
    result.add(TrackItem.Header("AUDIO"))
    if (audio.isEmpty()) result.add(TrackItem.Header("(no audio tracks yet)")) else result.addAll(audio)
    result.add(TrackItem.Header("SUBTITLES"))
    result.add(TrackItem.Choice("Off", "", !anyTextSelected, true, null, 0))
    result.addAll(text)
    return result
}

// ---------------------------------------------------------------- key handling

/**
 * Remote-control logic of the panel.
 *   tab bar:  LEFT/RIGHT change tab, DOWN/OK go into the content, UP/MENU close.
 *   lists:    UP/DOWN move, OK selects.
 *   faders:   LEFT/RIGHT choose a channel/band, UP/DOWN change the level, OK = mute / reset.
 *   EQ tab:   first the EQ on/off card, then the Graphic/Parametric card (UP = graphic, DOWN = parametric),
 *             then (graphic only) the ten bands. The parametric EQ is edited in the main menu (EQ).
 *   BACK is handled by the player screen (content -> tab bar -> closed).
 */
class AudioPanelController(
    val state: PanelState,
    private val dsp: DspController,
    private val channelCount: () -> Int,
    private val trackItems: () -> List<TrackItem>,
    private val onChoose: (TrackItem.Choice) -> Unit,
) {
    fun displayedChannels(): Int = channelCount().let { if (it <= 0) 2 else it }.coerceAtMost(8)

    /** Returns true when the key was used by the panel. */
    fun handleKey(key: RemoteKey, repeat: Int): Boolean {
        if (!state.open) return false
        // Let play/pause through so you can pause while tuning.
        if (key == RemoteKey.PLAY_PAUSE || key == RemoteKey.PLAY || key == RemoteKey.PAUSE) return false

        if (!state.inContent) {
            when (key) {
                RemoteKey.LEFT -> switchTab(-1)
                RemoteKey.RIGHT -> switchTab(1)
                RemoteKey.DOWN, RemoteKey.SELECT -> enterContent()
                RemoteKey.UP, RemoteKey.MENU -> state.hide()
                else -> Unit
            }
            return true
        }
        when (state.tab) {
            PanelTab.TRACKS, PanelTab.PRESETS -> listKey(key)
            PanelTab.CHANNELS -> faderKey(key, repeat)
            PanelTab.EQUALIZER -> eqKey(key, repeat)
        }
        return true
    }

    private fun switchTab(delta: Int) {
        val tabs = PanelTab.entries
        state.tab = tabs[(state.tab.ordinal + delta).coerceIn(0, tabs.lastIndex)]
        state.cursor = 0
    }

    private fun enterContent() {
        state.inContent = true
        state.cursor = if (state.tab == PanelTab.TRACKS) {
            val items = trackItems()
            val active = items.indexOfFirst { it is TrackItem.Choice && it.selected }
            if (active >= 0) active else items.indexOfFirst { it is TrackItem.Choice }.coerceAtLeast(0)
        } else {
            0
        }
    }

    private fun listCount(): Int =
        if (state.tab == PanelTab.TRACKS) trackItems().size else DspPresets.ALL.size + 1

    private fun selectable(index: Int): Boolean =
        if (state.tab == PanelTab.TRACKS) trackItems().getOrNull(index) is TrackItem.Choice
        else index in 0 until listCount()

    private fun listKey(key: RemoteKey) {
        when (key) {
            RemoteKey.UP -> {
                val previous = (state.cursor - 1 downTo 0).firstOrNull { selectable(it) }
                if (previous == null) state.inContent = false else state.cursor = previous
            }
            RemoteKey.DOWN -> {
                val next = (state.cursor + 1 until listCount()).firstOrNull { selectable(it) }
                if (next != null) state.cursor = next
            }
            RemoteKey.SELECT -> activate()
            else -> Unit
        }
    }

    private fun activate() {
        if (state.tab == PanelTab.TRACKS) {
            val item = trackItems().getOrNull(state.cursor)
            if (item is TrackItem.Choice) onChoose(item)
        } else {
            val preset = DspPresets.ALL.getOrNull(state.cursor)
            if (preset != null) dsp.applyPreset(preset) else dsp.resetAll()
        }
    }

    /** Channel mixer: column 0 = master, then one fader per channel. */
    private fun faderKey(key: RemoteKey, repeat: Int) {
        val columns = displayedChannels() + 1
        when (key) {
            RemoteKey.LEFT -> state.cursor = (state.cursor - 1).coerceAtLeast(0)
            RemoteKey.RIGHT -> state.cursor = (state.cursor + 1).coerceAtMost(columns - 1)
            RemoteKey.UP, RemoteKey.DOWN -> {
                val step = if (repeat > 6) 1.0f else 0.5f
                val delta = if (key == RemoteKey.UP) step else -step
                if (state.cursor == 0) dsp.adjustMaster(delta) else dsp.adjustChannel(state.cursor - 1, delta)
            }
            RemoteKey.SELECT -> {
                if (state.cursor == 0) dsp.setMaster(0f) else dsp.toggleMute(state.cursor - 1)
            }
            else -> Unit
        }
    }

    /**
     * Equalizer tab. Column 0 = EQ on/off, column 1 = which EQ (graphic / parametric),
     * columns 2.. = the graphic EQ bands (not shown while the parametric EQ is selected).
     */
    private fun eqKey(key: RemoteKey, repeat: Int) {
        val graphic = dsp.eqMode == EqMode.GRAPHIC
        val columns = if (graphic) dsp.bands.size + 2 else 2
        when (key) {
            RemoteKey.LEFT -> state.cursor = (state.cursor - 1).coerceAtLeast(0)
            RemoteKey.RIGHT -> state.cursor = (state.cursor + 1).coerceAtMost(columns - 1)
            RemoteKey.UP, RemoteKey.DOWN -> {
                val up = key == RemoteKey.UP
                when (state.cursor) {
                    0 -> dsp.enableEq(up)
                    1 -> dsp.setEqMode(if (up) EqMode.GRAPHIC else EqMode.PARAMETRIC)
                    else -> {
                        val step = if (repeat > 6) 1.0f else 0.5f
                        dsp.adjustBand(state.cursor - 2, if (up) step else -step)
                    }
                }
            }
            RemoteKey.SELECT -> {
                when (state.cursor) {
                    0 -> dsp.toggleEq()
                    1 -> dsp.toggleEqMode()
                    else -> dsp.resetBand(state.cursor - 2)
                }
            }
            else -> Unit
        }
    }
}

// ---------------------------------------------------------------- UI

private const val FADER_HEIGHT_DP = 214

@Composable
fun AudioPanel(
    controller: AudioPanelController,
    dsp: DspController,
    tracks: List<TrackItem>,
    channelCount: Int,
    formatInfo: String,
    meters: List<Float>,
    sampleRate: Int,
    modifier: Modifier = Modifier,
) {
    val state = controller.state
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 36.dp, vertical = 16.dp)
            .neuRaised(24.dp, 8.dp)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            PanelTab.entries.forEach { tab ->
                NeoTab(
                    text = tab.title,
                    selected = state.tab == tab,
                    focused = !state.inContent && state.tab == tab,
                )
            }
            Spacer(Modifier.weight(1f))
            NeoText(formatInfo, size = 14.sp, color = NeoColors.TextSecondary)
        }

        Box(Modifier.fillMaxWidth().height((FADER_HEIGHT_DP + 4).dp)) {
            when (state.tab) {
                PanelTab.TRACKS -> TracksContent(state, tracks)
                PanelTab.CHANNELS -> ChannelsContent(state, dsp, controller.displayedChannels(), channelCount, meters)
                PanelTab.EQUALIZER -> EqualizerContent(state, dsp, sampleRate)
                PanelTab.PRESETS -> PresetsContent(state, dsp)
            }
        }

        NeoText(
            text = "LEFT / RIGHT  choose      UP / DOWN  change      OK  mute or reset      BACK  up one level",
            size = 12.sp,
            color = NeoColors.TextDim,
        )
    }
}

@Composable
private fun TracksContent(state: PanelState, items: List<TrackItem>) {
    val listState = rememberLazyListState()
    LaunchedEffect(state.cursor, state.inContent) {
        if (state.inContent) listState.animateScrollToItem((state.cursor - 2).coerceAtLeast(0))
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        itemsIndexed(items) { index, item ->
            when (item) {
                is TrackItem.Header -> NeoText(
                    text = item.text,
                    size = 13.sp,
                    color = NeoColors.TextDim,
                    weight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 6.dp, top = 4.dp),
                )
                is TrackItem.Choice -> NeoRow(
                    title = item.label,
                    subtitle = item.detail.ifEmpty { null },
                    focused = state.inContent && state.cursor == index,
                    marker = if (item.selected) "ACTIVE" else null,
                )
            }
        }
    }
}

@Composable
private fun ChannelsContent(
    state: PanelState,
    dsp: DspController,
    shown: Int,
    channelCount: Int,
    meters: List<Float>,
) {
    val roles = ChannelLayouts.forChannelCount(shown)
    Row(
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        NeoFader(
            label = "MASTER",
            valueText = formatDb(dsp.masterDb),
            value = (dsp.masterDb - DspController.MASTER_MIN) / (DspController.MASTER_MAX - DspController.MASTER_MIN),
            focused = state.inContent && state.cursor == 0,
            zeroMark = -DspController.MASTER_MIN / (DspController.MASTER_MAX - DspController.MASTER_MIN),
            modifier = Modifier.height(FADER_HEIGHT_DP.dp),
        )
        for (i in 0 until shown) {
            NeoFader(
                label = roles.getOrNull(i)?.shortName ?: "${i + 1}",
                valueText = formatDb(dsp.channelDb[i]),
                value = (dsp.channelDb[i] - DspController.GAIN_MIN) / (DspController.GAIN_MAX - DspController.GAIN_MIN),
                focused = state.inContent && state.cursor == i + 1,
                meter = if (channelCount > 0) meters.getOrElse(i) { 0f } else 0f,
                muted = dsp.channelMute[i],
                zeroMark = 0.5f,
                modifier = Modifier.height(FADER_HEIGHT_DP.dp),
            )
        }
    }
}

@Composable
private fun EqualizerContent(state: PanelState, dsp: DspController, sampleRate: Int) {
    val graphic = dsp.eqMode == EqMode.GRAPHIC
    Row(
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, if (graphic) Alignment.CenterHorizontally else Alignment.Start),
    ) {
        NeoToggleCard(
            label = "EQ",
            on = dsp.eqEnabled,
            focused = state.inContent && state.cursor == 0,
            modifier = Modifier.height(FADER_HEIGHT_DP.dp),
        )
        NeoModeCard(
            label = "TYPE",
            topText = "GRAPHIC",
            bottomText = "PARAM",
            topSelected = graphic,
            focused = state.inContent && state.cursor == 1,
            modifier = Modifier.height(FADER_HEIGHT_DP.dp),
        )
        if (graphic) {
            for (i in 0 until dsp.bands.size) {
                val band = dsp.bands[i]
                NeoFader(
                    label = formatFreq(band.freqHz),
                    valueText = formatDb(band.gainDb),
                    value = (band.gainDb - DspController.GAIN_MIN) / (DspController.GAIN_MAX - DspController.GAIN_MIN),
                    focused = state.inContent && state.cursor == i + 2,
                    muted = !dsp.eqEnabled,
                    zeroMark = 0.5f,
                    modifier = Modifier.height(FADER_HEIGHT_DP.dp),
                )
            }
        } else {
            // The saved parametric EQ: shown here, changed in the main menu (EQ).
            Column(
                modifier = Modifier.weight(1f).height(FADER_HEIGHT_DP.dp).padding(start = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                NeoText(
                    text = "Parametric EQ (saved)  -  change it in the main menu: EQ",
                    size = 13.sp,
                    color = NeoColors.TextSecondary,
                )
                NeoEqCurve(
                    bands = dsp.paramBands.toList(),
                    sampleRate = sampleRate,
                    dimmed = !dsp.eqEnabled,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    showMarkers = true,
                )
            }
        }
    }
}

@Composable
private fun PresetsContent(state: PanelState, dsp: DspController) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        DspPresets.ALL.forEachIndexed { index, preset ->
            val gains = preset.channelGainsDb.entries.joinToString("   ") { "${it.key.label} ${formatDb(it.value)} dB" }
            NeoRow(
                title = preset.name,
                subtitle = gains.ifEmpty { "No channel changes" },
                focused = state.inContent && state.cursor == index,
                marker = if (dsp.presetName == preset.name) "ACTIVE" else null,
            )
        }
        NeoRow(
            title = "Reset everything",
            subtitle = "Flat EQ, 0 dB on every channel, nothing muted",
            focused = state.inContent && state.cursor == DspPresets.ALL.size,
        )
    }
}
