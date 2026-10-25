package com.resonix.tv.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.resonix.tv.ui.theme.NeoColors
import com.resonix.tv.ui.theme.neoText
import com.resonix.tv.ui.theme.neuFocusRing
import com.resonix.tv.ui.theme.neuInset
import com.resonix.tv.ui.theme.neuRaised

@Composable
fun NeoText(
    text: String,
    modifier: Modifier = Modifier,
    size: TextUnit = 18.sp,
    color: Color = NeoColors.TextPrimary,
    weight: FontWeight = FontWeight.Normal,
    maxLines: Int = 1,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = neoText(size, color, weight),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

/** List row / button. [focused] = the D-pad cursor is on it. */
@Composable
fun NeoRow(
    title: String,
    subtitle: String?,
    focused: Boolean,
    modifier: Modifier = Modifier,
    marker: String? = null,
    icon: NeoIcon? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .neuRaised(corner = 14.dp, depth = 4.dp)
            .neuFocusRing(focused, 14.dp)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            NeoIconTile(icon, NeoColors.Cyan, tileSize = 40.dp)
            Spacer(Modifier.width(16.dp))
        }
        Column(Modifier.weight(1f)) {
            NeoText(
                text = title,
                size = 20.sp,
                color = if (focused) NeoColors.Cyan else NeoColors.TextPrimary,
                weight = if (focused) FontWeight.SemiBold else FontWeight.Normal,
            )
            if (subtitle != null) NeoText(subtitle, size = 14.sp, color = NeoColors.TextSecondary)
        }
        if (marker != null) NeoText(marker, size = 13.sp, color = NeoColors.Cyan, weight = FontWeight.SemiBold)
    }
}

/** Tab: pressed-in (inset) when selected, raised otherwise. */
@Composable
fun NeoTab(text: String, selected: Boolean, focused: Boolean, modifier: Modifier = Modifier) {
    val base = if (selected) modifier.neuInset(12.dp) else modifier.neuRaised(12.dp, 3.dp)
    Box(
        modifier = base.neuFocusRing(focused, 12.dp).padding(horizontal = 20.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        NeoText(
            text = text,
            size = 17.sp,
            color = if (selected) NeoColors.Cyan else NeoColors.TextSecondary,
            weight = FontWeight.Medium,
        )
    }
}

/** Small inset pill used for key hints. */
@Composable
fun NeoChip(text: String, modifier: Modifier = Modifier) {
    Box(modifier.neuInset(10.dp).padding(horizontal = 14.dp, vertical = 6.dp)) {
        NeoText(text, size = 13.sp, color = NeoColors.TextSecondary)
    }
}

@Composable
fun NeoPlayGlyph(playing: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier.size(26.dp)) {
        if (playing) {
            val barWidth = size.width * 0.28f
            drawRoundRect(NeoColors.Cyan, Offset(size.width * 0.12f, 0f), Size(barWidth, size.height), CornerRadius(3.dp.toPx()))
            drawRoundRect(NeoColors.Cyan, Offset(size.width * 0.60f, 0f), Size(barWidth, size.height), CornerRadius(3.dp.toPx()))
        } else {
            val path = Path().apply {
                moveTo(size.width * 0.15f, 0f)
                lineTo(size.width, size.height / 2f)
                lineTo(size.width * 0.15f, size.height)
                close()
            }
            drawPath(path, NeoColors.Cyan)
        }
    }
}

/** Inset progress track with buffered area, glowing fill and a knob. Values 0..1. */
@Composable
fun NeoProgress(progress: Float, buffered: Float, modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxWidth().height(24.dp)) {
        val trackH = 12.dp.toPx()
        val top = (size.height - trackH) / 2f
        val radius = CornerRadius(trackH / 2f)
        drawRoundRect(color = NeoColors.Inset, topLeft = Offset(0f, top), size = Size(size.width, trackH), cornerRadius = radius)
        drawRoundRect(
            brush = Brush.verticalGradient(
                colors = listOf(Color.Black.copy(alpha = 0.5f), Color.Transparent),
                startY = top,
                endY = top + trackH * 0.6f,
            ),
            topLeft = Offset(0f, top),
            size = Size(size.width, trackH),
            cornerRadius = radius,
        )
        val bufferedX = buffered.coerceIn(0f, 1f) * size.width
        if (bufferedX > 0f) {
            drawRoundRect(color = Color.White.copy(alpha = 0.08f), topLeft = Offset(0f, top), size = Size(bufferedX, trackH), cornerRadius = radius)
        }
        val playedX = progress.coerceIn(0f, 1f) * size.width
        if (playedX > 0f) {
            drawRoundRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(NeoColors.Cyan, NeoColors.Blue),
                    startX = 0f,
                    endX = maxOf(playedX, 1f),
                ),
                topLeft = Offset(0f, top),
                size = Size(playedX, trackH),
                cornerRadius = radius,
            )
        }
        val knobR = 9.dp.toPx()
        val cx = playedX.coerceIn(knobR, size.width - knobR)
        val cy = size.height / 2f
        drawCircle(color = Color.Black.copy(alpha = 0.5f), radius = knobR + 2.dp.toPx(), center = Offset(cx + 2.dp.toPx(), cy + 2.dp.toPx()))
        drawCircle(color = NeoColors.Surface, radius = knobR, center = Offset(cx, cy))
        drawCircle(color = NeoColors.Cyan, radius = knobR, center = Offset(cx, cy), style = Stroke(width = 2.dp.toPx()))
    }
}

/**
 * Vertical fader on an inset track (channel gains, EQ bands).
 * [value] 0..1, [meter] 0..1 live level, [zeroMark] 0..1 position of the 0 dB tick (<0 = none).
 * The caller sets the height; the width is fixed.
 */
@Composable
fun NeoFader(
    label: String,
    valueText: String,
    value: Float,
    focused: Boolean,
    modifier: Modifier = Modifier,
    meter: Float = 0f,
    muted: Boolean = false,
    zeroMark: Float = -1f,
) {
    val dim = if (muted) 0.35f else 1f
    Column(
        modifier = modifier
            .width(64.dp)
            .neuRaised(14.dp, 4.dp)
            .neuFocusRing(focused, 14.dp)
            .padding(horizontal = 6.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        NeoText(valueText, size = 13.sp, color = if (muted) NeoColors.TextDim else NeoColors.TextPrimary)
        Spacer(Modifier.height(6.dp))
        Canvas(Modifier.weight(1f).fillMaxWidth()) {
            val knobR = 10.dp.toPx()
            val trackW = 12.dp.toPx()
            val left = (size.width - trackW) / 2f - 5.dp.toPx()
            val top = knobR
            val bottom = size.height - knobR
            val knobY = bottom - value.coerceIn(0f, 1f) * (bottom - top)
            val radius = CornerRadius(trackW / 2f)

            drawRoundRect(color = NeoColors.Inset, topLeft = Offset(left, 0f), size = Size(trackW, size.height), cornerRadius = radius)
            drawRoundRect(
                brush = Brush.verticalGradient(
                    colors = listOf(NeoColors.Cyan.copy(alpha = dim), NeoColors.Blue.copy(alpha = dim)),
                    startY = knobY,
                    endY = size.height,
                ),
                topLeft = Offset(left, knobY),
                size = Size(trackW, size.height - knobY),
                cornerRadius = radius,
            )
            if (zeroMark >= 0f) {
                val y = bottom - zeroMark.coerceIn(0f, 1f) * (bottom - top)
                drawLine(
                    color = Color.White.copy(alpha = 0.25f),
                    start = Offset(left - 6.dp.toPx(), y),
                    end = Offset(left + trackW + 6.dp.toPx(), y),
                    strokeWidth = 1.5f.dp.toPx(),
                )
            }
            val meterX = left + trackW + 9.dp.toPx()
            val meterW = 4.dp.toPx()
            drawRoundRect(color = Color.White.copy(alpha = 0.06f), topLeft = Offset(meterX, 0f), size = Size(meterW, size.height), cornerRadius = CornerRadius(meterW / 2f))
            val meterH = size.height * meter.coerceIn(0f, 1f)
            if (meterH > 0f) {
                drawRoundRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(NeoColors.Warning, Color(0xFF00E5A0)),
                        startY = size.height - meterH,
                        endY = size.height,
                    ),
                    topLeft = Offset(meterX, size.height - meterH),
                    size = Size(meterW, meterH),
                    cornerRadius = CornerRadius(meterW / 2f),
                )
            }
            val cx = left + trackW / 2f
            drawCircle(color = Color.Black.copy(alpha = 0.5f), radius = knobR + 2.dp.toPx(), center = Offset(cx + 2.dp.toPx(), knobY + 2.dp.toPx()))
            drawCircle(color = NeoColors.Surface, radius = knobR, center = Offset(cx, knobY))
            drawCircle(color = (if (focused) NeoColors.Cyan else NeoColors.TextSecondary).copy(alpha = dim), radius = knobR, center = Offset(cx, knobY), style = Stroke(width = 2.dp.toPx()))
        }
        Spacer(Modifier.height(6.dp))
        NeoText(label, size = 14.sp, color = if (focused) NeoColors.Cyan else NeoColors.TextSecondary, weight = FontWeight.SemiBold)
        NeoText(if (muted) "MUTED" else " ", size = 10.sp, color = NeoColors.Warning)
    }
}

/** On/off card that sits next to the EQ faders. */
@Composable
fun NeoToggleCard(label: String, on: Boolean, focused: Boolean, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .width(64.dp)
            .neuRaised(14.dp, 4.dp)
            .neuFocusRing(focused, 14.dp)
            .padding(horizontal = 6.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        NeoText(label, size = 15.sp, color = if (focused) NeoColors.Cyan else NeoColors.TextSecondary, weight = FontWeight.SemiBold)
        Spacer(Modifier.height(14.dp))
        Canvas(Modifier.size(22.dp)) {
            if (on) {
                drawCircle(color = NeoColors.Cyan.copy(alpha = 0.25f), radius = size.minDimension / 2f)
            }
            drawCircle(
                color = if (on) NeoColors.Cyan else NeoColors.Inset,
                radius = size.minDimension / 3f,
            )
        }
        Spacer(Modifier.height(10.dp))
        NeoText(if (on) "ON" else "OFF", size = 13.sp, color = if (on) NeoColors.Cyan else NeoColors.TextDim, weight = FontWeight.SemiBold)
    }
}
