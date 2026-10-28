package com.resonix.tv.ui.eq

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.resonix.tv.audio.AudioPipeline
import com.resonix.tv.audio.DspController
import com.resonix.tv.audio.EqBand
import com.resonix.tv.audio.EqMode
import com.resonix.tv.input.RemoteKey
import com.resonix.tv.ui.components.NeoActionCard
import com.resonix.tv.ui.components.NeoChip
import com.resonix.tv.ui.components.NeoEqCurve
import com.resonix.tv.ui.components.NeoFader
import com.resonix.tv.ui.components.NeoIcon
import com.resonix.tv.ui.components.NeoParamRow
import com.resonix.tv.ui.components.NeoRow
import com.resonix.tv.ui.components.NeoSectionHeader
import com.resonix.tv.ui.components.NeoSettingRow
import com.resonix.tv.ui.components.NeoText
import com.resonix.tv.ui.components.NeoToggleCard
import com.resonix.tv.ui.formatDb
import com.resonix.tv.ui.formatFreq
import com.resonix.tv.ui.formatFreqLong
import com.resonix.tv.ui.formatQ
import com.resonix.tv.ui.shell.SectionKeys
import com.resonix.tv.ui.theme.NeoColors
import com.resonix.tv.ui.theme.neuFocusRing
import com.resonix.tv.ui.theme.neuInset
import com.resonix.tv.ui.theme.neuRaised

// ---------------------------------------------------------------- state + remote-control logic

enum class EqPage { CHOOSER, GRAPHIC, PARAMETRIC }

// rows of the chooser
private const val CHOOSER_GRAPHIC = 0
private const val CHOOSER_PARAMETRIC = 1
private const val CHOOSER_ROWS = 3 // the last one is the EQ on / off switch

// rows of the parametric editor
private const val ROW_IN_USE = 0
private const val ROW_BAND = 1
private const val ROW_TYPE = 2
private const val ROW_FREQ = 3
private const val ROW_GAIN = 4
private const val ROW_Q = 5
private const val ROW_ENABLED = 6
private const val ROW_RESET_BAND = 7
private const val ROW_RESET_ALL = 8
private const val PARAM_ROWS = 9

/** Which page is open and where the D-pad cursor is on it. */
@Stable
class EqUiState {
    var page by mutableStateOf(EqPage.CHOOSER)
    var chooserCursor by mutableIntStateOf(0)

    /** Graphic editor: 0 = "use this EQ" card, 1..10 = the bands, 11 = reset card. */
    var graphicCursor by mutableIntStateOf(1)

    /** Parametric editor: the selected band (0..9) and the highlighted setting row. */
    var paramBand by mutableIntStateOf(0)
    var paramRow by mutableIntStateOf(ROW_BAND)
}

/**
 * Remote-control logic of the EQ page.
 *   list:        UP / DOWN choose, OK opens an editor (or switches the EQ on / off).
 *   graphic:     LEFT / RIGHT choose a band, UP / DOWN change its level, OK resets it.
 *   parametric:  UP / DOWN choose a setting, LEFT / RIGHT change it (hold = faster), OK toggles / resets.
 *   BACK:        editor -> list -> side menu.
 */
class EqPageController(
    private val state: EqUiState,
    private val dsp: DspController,
) {
    /** Returns true when the key was used by the page. */
    fun handleKey(key: RemoteKey, repeat: Int): Boolean = when (state.page) {
        EqPage.CHOOSER -> chooserKey(key)
        EqPage.GRAPHIC -> graphicKey(key, repeat)
        EqPage.PARAMETRIC -> parametricKey(key, repeat)
    }

    /** BACK inside an editor goes to the list. False = already on the list (the shell goes to the side menu). */
    fun back(): Boolean {
        if (state.page == EqPage.CHOOSER) return false
        state.page = EqPage.CHOOSER
        return true
    }

    private fun chooserKey(key: RemoteKey): Boolean {
        when (key) {
            RemoteKey.UP -> state.chooserCursor = (state.chooserCursor - 1).coerceAtLeast(0)
            RemoteKey.DOWN -> state.chooserCursor = (state.chooserCursor + 1).coerceAtMost(CHOOSER_ROWS - 1)
            RemoteKey.SELECT -> when (state.chooserCursor) {
                CHOOSER_GRAPHIC -> state.page = EqPage.GRAPHIC
                CHOOSER_PARAMETRIC -> state.page = EqPage.PARAMETRIC
                else -> dsp.toggleEq()
            }
            else -> return false
        }
        return true
    }

    private fun graphicKey(key: RemoteKey, repeat: Int): Boolean {
        val cursor = state.graphicCursor
        val resetCard = dsp.bands.size + 1
        when (key) {
            RemoteKey.LEFT -> {
                if (cursor == 0) return false // leftmost: back to the side menu
                state.graphicCursor = cursor - 1
            }
            RemoteKey.RIGHT -> state.graphicCursor = (cursor + 1).coerceAtMost(resetCard)
            RemoteKey.UP, RemoteKey.DOWN -> {
                val up = key == RemoteKey.UP
                if (cursor == 0) {
                    dsp.setInUse(EqMode.GRAPHIC, up)
                } else if (cursor < resetCard) {
                    val step = if (repeat > 6) 1.0f else 0.5f
                    dsp.adjustBand(cursor - 1, if (up) step else -step)
                }
            }
            RemoteKey.SELECT -> {
                if (cursor == 0) {
                    dsp.setInUse(EqMode.GRAPHIC, !dsp.isInUse(EqMode.GRAPHIC))
                } else if (cursor < resetCard) {
                    dsp.resetBand(cursor - 1)
                } else {
                    dsp.resetGraphic()
                }
            }
            else -> return false
        }
        return true
    }

    private fun parametricKey(key: RemoteKey, repeat: Int): Boolean {
        when (key) {
            RemoteKey.UP -> state.paramRow = (state.paramRow - 1).coerceAtLeast(0)
            RemoteKey.DOWN -> state.paramRow = (state.paramRow + 1).coerceAtMost(PARAM_ROWS - 1)
            RemoteKey.LEFT -> return adjust(-1, repeat)
            RemoteKey.RIGHT -> return adjust(1, repeat)
            RemoteKey.SELECT -> activate()
            else -> return false
        }
        return true
    }

    /** LEFT (-1) / RIGHT (+1) on the highlighted row. False = the row has nothing to adjust. */
    private fun adjust(direction: Int, repeat: Int): Boolean {
        val band = state.paramBand
        val coarse = repeat > 5
        when (state.paramRow) {
            ROW_IN_USE -> dsp.setInUse(EqMode.PARAMETRIC, direction > 0)
            ROW_BAND -> state.paramBand = (band + direction).coerceIn(0, dsp.paramBands.size - 1)
            ROW_TYPE -> if (repeat == 0) dsp.cycleParamType(band, direction)
            ROW_FREQ -> dsp.adjustParamFreq(band, direction, coarse)
            ROW_GAIN -> dsp.adjustParamGain(band, direction, coarse)
            ROW_Q -> dsp.adjustParamQ(band, direction, coarse)
            ROW_ENABLED -> dsp.setParamEnabled(band, direction > 0)
            else -> return false
        }
        return true
    }

    private fun activate() {
        val band = state.paramBand
        when (state.paramRow) {
            ROW_IN_USE -> dsp.setInUse(EqMode.PARAMETRIC, !dsp.isInUse(EqMode.PARAMETRIC))
            ROW_TYPE -> dsp.cycleParamType(band, 1)
            ROW_ENABLED -> dsp.toggleParamBand(band)
            ROW_RESET_BAND -> dsp.resetParamBand(band)
            ROW_RESET_ALL -> dsp.resetParametric()
            else -> Unit
        }
    }
}

// ---------------------------------------------------------------- page

/**
 * "EQ" page of the main menu. Pick the graphic or the parametric equalizer, change it and it is
 * saved on the spot. While a movie plays, the EQ panel uses whichever of the two is in use.
 */
@Composable
fun EqSection(active: Boolean, keys: SectionKeys) {
    val context = LocalContext.current
    val dsp = remember { AudioPipeline.controller(context) }
    val state = remember { EqUiState() }
    val controller = remember { EqPageController(state, dsp) }
    val streamFormat by AudioPipeline.engine.format.collectAsState()

    DisposableEffect(Unit) {
        val handler: (RemoteKey, Int) -> Boolean = { key, repeat -> controller.handleKey(key, repeat) }
        val back: () -> Boolean = { controller.back() }
        keys.handler = handler
        keys.onBack = back
        onDispose {
            if (keys.handler === handler) keys.handler = null
            if (keys.onBack === back) keys.onBack = null
        }
    }

    when (state.page) {
        EqPage.CHOOSER -> ChooserPage(state, dsp, active, streamFormat.sampleRate)
        EqPage.GRAPHIC -> GraphicPage(state, dsp, active, streamFormat.sampleRate)
        EqPage.PARAMETRIC -> ParametricPage(state, dsp, active, streamFormat.sampleRate)
    }
}

@Composable
private fun HintLine(text: String) {
    NeoText(text, size = 12.sp, color = NeoColors.TextDim)
}

@Composable
private fun ChooserPage(state: EqUiState, dsp: DspController, active: Boolean, sampleRate: Int) {
    val usedBands: List<EqBand> = (if (dsp.eqMode == EqMode.PARAMETRIC) dsp.paramBands else dsp.bands).toList()
    val curveTitle = when {
        !dsp.eqEnabled -> "Sound curve: equalizer is off"
        dsp.eqMode == EqMode.PARAMETRIC -> "Sound curve now: Parametric EQ"
        else -> "Sound curve now: Graphic EQ"
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        NeoSectionHeader("Equalizer", "Choose the EQ you want to change. Only one EQ works at a time.", NeoIcon.EQ)
        NeoRow(
            title = "Graphic EQ",
            subtitle = "10 sliders at fixed frequencies. Also adjustable while a movie plays.",
            focused = active && state.chooserCursor == CHOOSER_GRAPHIC,
            marker = if (dsp.isInUse(EqMode.GRAPHIC)) "IN USE" else null,
            icon = NeoIcon.EQ,
        )
        NeoRow(
            title = "Parametric EQ",
            subtitle = "10 filters: type, frequency, gain and Q factor. Saved automatically.",
            focused = active && state.chooserCursor == CHOOSER_PARAMETRIC,
            marker = if (dsp.isInUse(EqMode.PARAMETRIC)) "IN USE" else null,
            icon = NeoIcon.EQ,
        )
        NeoSettingRow(
            title = "Equalizer on / off",
            subtitle = "Off = no equalizing at all. The channel mixer keeps working.",
            icon = NeoIcon.SETTINGS,
            focused = active && state.chooserCursor == CHOOSER_ROWS - 1,
            toggle = dsp.eqEnabled,
        )
        NeoText(curveTitle, size = 14.sp, color = NeoColors.TextSecondary, modifier = Modifier.padding(top = 6.dp))
        NeoEqCurve(
            bands = usedBands,
            sampleRate = sampleRate,
            dimmed = !dsp.eqEnabled,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
        HintLine("UP / DOWN  choose      OK  open or switch      LEFT / BACK  side menu")
    }
}

@Composable
private fun GraphicPage(state: EqUiState, dsp: DspController, active: Boolean, sampleRate: Int) {
    val inUse = dsp.isInUse(EqMode.GRAPHIC)
    val snapshot = dsp.bands.toList()
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            NeoSectionHeader("Graphic EQ", "Every change is saved automatically", NeoIcon.EQ)
            Spacer(Modifier.weight(1f))
            NeoChip(if (inUse) "IN USE" else "NOT IN USE")
        }
        NeoEqCurve(
            bands = snapshot,
            sampleRate = sampleRate,
            dimmed = !inUse,
            modifier = Modifier.weight(0.4f).fillMaxWidth(),
        )
        Row(
            modifier = Modifier.weight(0.6f).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        ) {
            NeoToggleCard(
                label = "USE",
                on = inUse,
                focused = active && state.graphicCursor == 0,
                modifier = Modifier.fillMaxHeight(),
            )
            for (i in snapshot.indices) {
                val band = snapshot[i]
                NeoFader(
                    label = formatFreq(band.freqHz),
                    valueText = formatDb(band.gainDb),
                    value = (band.gainDb - DspController.GAIN_MIN) / (DspController.GAIN_MAX - DspController.GAIN_MIN),
                    focused = active && state.graphicCursor == i + 1,
                    muted = !inUse,
                    zeroMark = 0.5f,
                    modifier = Modifier.fillMaxHeight(),
                )
            }
            NeoActionCard(
                label = "RESET",
                focused = active && state.graphicCursor == snapshot.size + 1,
                modifier = Modifier.fillMaxHeight(),
            )
        }
        HintLine("LEFT / RIGHT  choose      UP / DOWN  change level      OK  reset band      BACK  EQ list")
    }
}

@Composable
private fun ParametricPage(state: EqUiState, dsp: DspController, active: Boolean, sampleRate: Int) {
    val inUse = dsp.isInUse(EqMode.PARAMETRIC)
    val snapshot = dsp.paramBands.toList()
    val bandIndex = state.paramBand.coerceIn(0, snapshot.size - 1)
    val band = snapshot[bandIndex]
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            NeoSectionHeader("Parametric EQ", "Every change is saved automatically", NeoIcon.EQ)
            Spacer(Modifier.weight(1f))
            NeoChip(if (inUse) "IN USE" else "NOT IN USE")
        }
        Row(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                NeoEqCurve(
                    bands = snapshot,
                    sampleRate = sampleRate,
                    dimmed = !inUse,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    selectedBand = bandIndex,
                    showMarkers = true,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (i in snapshot.indices) {
                        BandChip(
                            index = i,
                            band = snapshot[i],
                            selected = i == bandIndex,
                            focused = active && state.paramRow == ROW_BAND && i == bandIndex,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
            ParamRows(
                state = state,
                dsp = dsp,
                active = active,
                band = band,
                bandIndex = bandIndex,
                inUse = inUse,
                modifier = Modifier.width(360.dp).fillMaxHeight(),
            )
        }
        HintLine("UP / DOWN  choose a setting      LEFT / RIGHT  change it (hold = faster)      OK  switch / reset      BACK  EQ list")
    }
}

@Composable
private fun BandChip(index: Int, band: EqBand, selected: Boolean, focused: Boolean, modifier: Modifier = Modifier) {
    val base = if (selected) modifier.neuInset(12.dp) else modifier.neuRaised(12.dp, 3.dp)
    Column(
        modifier = base.neuFocusRing(focused, 12.dp).padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        NeoText(
            text = "${index + 1}",
            size = 13.sp,
            color = if (selected) NeoColors.Cyan else NeoColors.TextSecondary,
            weight = FontWeight.SemiBold,
        )
        NeoText(
            text = formatFreq(band.freqHz),
            size = 12.sp,
            color = if (band.enabled) NeoColors.TextPrimary else NeoColors.TextDim,
        )
    }
}

@Composable
private fun ParamRows(
    state: EqUiState,
    dsp: DspController,
    active: Boolean,
    band: EqBand,
    bandIndex: Int,
    inUse: Boolean,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(state.paramRow) { listState.animateScrollToItem((state.paramRow - 2).coerceAtLeast(0)) }
    LazyColumn(
        state = listState,
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp),
    ) {
        items(PARAM_ROWS) { row ->
            val focused = active && state.paramRow == row
            when (row) {
                ROW_IN_USE -> NeoParamRow("Use this EQ", focused, toggle = inUse)
                ROW_BAND -> NeoParamRow(
                    "Band", focused,
                    valueText = "${bandIndex + 1} of ${dsp.paramBands.size}", adjustable = true,
                )
                ROW_TYPE -> NeoParamRow("Filter type", focused, valueText = band.type.label, adjustable = true)
                ROW_FREQ -> NeoParamRow("Frequency", focused, valueText = formatFreqLong(band.freqHz), adjustable = true)
                ROW_GAIN -> NeoParamRow(
                    "Gain", focused,
                    valueText = if (band.type.usesGain) "${formatDb(band.gainDb)} dB" else "none",
                    adjustable = band.type.usesGain,
                    dimmed = !band.type.usesGain,
                )
                ROW_Q -> NeoParamRow("Q factor", focused, valueText = formatQ(band.q), adjustable = true)
                ROW_ENABLED -> NeoParamRow("This band is on", focused, toggle = band.enabled)
                ROW_RESET_BAND -> NeoParamRow("Reset this band", focused, valueText = "OK")
                else -> NeoParamRow("Reset all bands", focused, valueText = "OK")
            }
        }
    }
}
