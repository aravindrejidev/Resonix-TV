package com.resonix.tv.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.resonix.tv.audio.EqBand
import com.resonix.tv.audio.EqMath
import com.resonix.tv.ui.theme.NeoColors
import com.resonix.tv.ui.theme.neuFocusRing
import com.resonix.tv.ui.theme.neuInset
import com.resonix.tv.ui.theme.neuRaised

private const val CURVE_POINTS = 160
private val GRID_FREQUENCIES = listOf(50f, 100f, 200f, 500f, 1000f, 2000f, 5000f, 10000f)
private val MAJOR_FREQUENCIES = listOf(100f, 1000f, 10000f)
private val FREQUENCY_LABELS = listOf(20f to "20", 100f to "100", 1000f to "1k", 10000f to "10k", 20000f to "20k")

/**
 * Frequency response of an EQ (20 Hz ... 20 kHz on the bottom, +/- [rangeDb] on the side).
 * [dimmed] = this EQ is not the one in use. [showMarkers] puts a dot on every switched-on band,
 * [selectedBand] (index or -1) makes one of them bigger. The caller sets the height.
 */
@Composable
fun NeoEqCurve(
    bands: List<EqBand>,
    sampleRate: Int,
    dimmed: Boolean,
    modifier: Modifier = Modifier,
    selectedBand: Int = -1,
    showMarkers: Boolean = false,
    rangeDb: Float = 18f,
) {
    val curve = remember(bands, sampleRate) { EqMath.response(bands, sampleRate, CURVE_POINTS) }
    val line = if (dimmed) NeoColors.TextDim else NeoColors.Cyan

    Column(modifier) {
        Canvas(Modifier.weight(1f).fillMaxWidth().neuInset(14.dp)) {
            val w = size.width
            val h = size.height
            val mid = h / 2f
            fun yOf(db: Float): Float = mid - (db.coerceIn(-rangeDb, rangeDb) / rangeDb) * (mid - 4.dp.toPx())
            fun xOf(freq: Float): Float = EqMath.freqToFraction(freq) * w

            // grid: frequency lines, then dB lines (0 dB is the brightest)
            for (f in GRID_FREQUENCIES) {
                val major = MAJOR_FREQUENCIES.contains(f)
                drawLine(
                    color = Color.White.copy(alpha = if (major) 0.10f else 0.05f),
                    start = Offset(xOf(f), 0f),
                    end = Offset(xOf(f), h),
                    strokeWidth = 1.dp.toPx(),
                )
            }
            var level = -rangeDb + 6f
            while (level < rangeDb - 0.5f) {
                val zero = level > -0.5f && level < 0.5f
                drawLine(
                    color = Color.White.copy(alpha = if (zero) 0.28f else 0.07f),
                    start = Offset(0f, yOf(level)),
                    end = Offset(w, yOf(level)),
                    strokeWidth = if (zero) 1.5f.dp.toPx() else 1.dp.toPx(),
                )
                level += 6f
            }

            // filled area between the curve and 0 dB, then the curve itself
            val stroke = Path()
            val area = Path()
            for (i in 0 until CURVE_POINTS) {
                val x = w * i / (CURVE_POINTS - 1)
                val y = yOf(curve[i])
                if (i == 0) {
                    stroke.moveTo(x, y)
                    area.moveTo(x, mid)
                    area.lineTo(x, y)
                } else {
                    stroke.lineTo(x, y)
                    area.lineTo(x, y)
                }
            }
            area.lineTo(w, mid)
            area.close()
            drawPath(area, line.copy(alpha = if (dimmed) 0.06f else 0.16f))
            drawPath(
                path = stroke,
                color = line,
                style = Stroke(width = 2.5f.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
            )

            if (showMarkers) {
                for (i in bands.indices) {
                    val band = bands[i]
                    if (!band.enabled || i == selectedBand) continue
                    val cx = xOf(band.freqHz)
                    val cy = yOf(EqMath.responseAt(bands, sampleRate, band.freqHz))
                    drawCircle(color = NeoColors.Surface, radius = 5.dp.toPx(), center = Offset(cx, cy))
                    drawCircle(color = NeoColors.TextSecondary, radius = 5.dp.toPx(), center = Offset(cx, cy), style = Stroke(width = 1.5f.dp.toPx()))
                }
                val selected = bands.getOrNull(selectedBand)
                if (selected != null) {
                    val cx = xOf(selected.freqHz)
                    val cy = yOf(EqMath.responseAt(bands, sampleRate, selected.freqHz))
                    // a thin guide line makes the selected band easy to find, even when it is switched off
                    drawLine(
                        color = NeoColors.Cyan.copy(alpha = if (selected.enabled) 0.35f else 0.15f),
                        start = Offset(cx, 0f),
                        end = Offset(cx, h),
                        strokeWidth = 1.dp.toPx(),
                    )
                    if (selected.enabled) {
                        drawCircle(color = NeoColors.Cyan.copy(alpha = 0.25f), radius = 13.dp.toPx(), center = Offset(cx, cy))
                        drawCircle(color = NeoColors.Cyan, radius = 7.dp.toPx(), center = Offset(cx, cy))
                        drawCircle(color = Color.White, radius = 7.dp.toPx(), center = Offset(cx, cy), style = Stroke(width = 2.dp.toPx()))
                    }
                }
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth().height(16.dp)) {
            val totalDp = maxWidth.value
            for (entry in FREQUENCY_LABELS) {
                val xDp = (totalDp * EqMath.freqToFraction(entry.first) - 10f).coerceIn(0f, totalDp - 26f)
                NeoText(
                    text = entry.second,
                    size = 11.sp,
                    color = NeoColors.TextDim,
                    modifier = Modifier.offset(x = xDp.dp),
                )
            }
        }
    }
}

/** Card with two stacked choices (e.g. GRAPHIC above PARAM); the chosen one is pressed in. */
@Composable
fun NeoModeCard(
    label: String,
    topText: String,
    bottomText: String,
    topSelected: Boolean,
    focused: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .width(80.dp)
            .neuRaised(14.dp, 4.dp)
            .neuFocusRing(focused, 14.dp)
            .padding(horizontal = 6.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        NeoText(label, size = 15.sp, color = if (focused) NeoColors.Cyan else NeoColors.TextSecondary, weight = FontWeight.SemiBold)
        Spacer(Modifier.height(12.dp))
        ModeLine(topText, topSelected)
        Spacer(Modifier.height(8.dp))
        ModeLine(bottomText, !topSelected)
    }
}

@Composable
private fun ModeLine(text: String, selected: Boolean) {
    val base = if (selected) Modifier.neuInset(8.dp) else Modifier
    Box(base.padding(horizontal = 6.dp, vertical = 5.dp), contentAlignment = Alignment.Center) {
        NeoText(
            text = text,
            size = 11.sp,
            color = if (selected) NeoColors.Cyan else NeoColors.TextDim,
            weight = FontWeight.SemiBold,
        )
    }
}

/** Small push button that sits at the end of a fader row (e.g. RESET). */
@Composable
fun NeoActionCard(label: String, focused: Boolean, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .width(64.dp)
            .neuRaised(14.dp, 4.dp)
            .neuFocusRing(focused, 14.dp)
            .padding(horizontal = 6.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        NeoText(label, size = 14.sp, color = if (focused) NeoColors.Cyan else NeoColors.TextSecondary, weight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        NeoText("press OK", size = 10.sp, color = NeoColors.TextDim)
    }
}

/**
 * One compact line of the parametric editor: name on the left, on the right either a switch
 * ([toggle]), a value ("< 1.06 kHz >" while focused, so you see that LEFT / RIGHT change it) or a plain label.
 */
@Composable
fun NeoParamRow(
    title: String,
    focused: Boolean,
    modifier: Modifier = Modifier,
    valueText: String? = null,
    toggle: Boolean? = null,
    adjustable: Boolean = false,
    dimmed: Boolean = false,
) {
    val titleColor = when {
        focused -> Color.White
        dimmed -> NeoColors.TextDim
        else -> NeoColors.TextPrimary
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .neuRaised(14.dp, 4.dp)
            .neuFocusRing(focused, 14.dp)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NeoText(
            text = title,
            size = 16.sp,
            color = titleColor,
            weight = if (focused) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(10.dp))
        if (toggle != null) {
            NeoSwitch(toggle)
        } else if (valueText != null) {
            val shown = if (focused && adjustable) "<  $valueText  >" else valueText
            Box(Modifier.neuInset(10.dp).padding(horizontal = 12.dp, vertical = 5.dp)) {
                NeoText(
                    text = shown,
                    size = 15.sp,
                    color = if (dimmed) NeoColors.TextDim else NeoColors.Cyan,
                    weight = FontWeight.SemiBold,
                )
            }
        }
    }
}
