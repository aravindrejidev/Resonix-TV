package com.resonix.tv.ui.theme

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp

/** Dark neumorphism palette: obsidian background, slightly raised charcoal surfaces, cyan/blue accents. */
object NeoColors {
    val Background = Color(0xFF121418)
    val Surface = Color(0xFF1C1F26)
    val Inset = Color(0xFF0E1013)
    val Highlight = Color.White.copy(alpha = 0.05f)   // top-left light edge
    val Shadow = Color.Black.copy(alpha = 0.6f)       // bottom-right deep shadow
    val Cyan = Color(0xFF00E5FF)
    val Blue = Color(0xFF3B82F6)
    val TextPrimary = Color(0xFFE6E8EC)
    val TextSecondary = Color(0xFF9AA0AC)
    val TextDim = Color(0xFF5C6370)
    val Warning = Color(0xFFFFB74D)
    val Danger = Color(0xFFFF5252)
}

fun neoText(
    size: TextUnit,
    color: Color = NeoColors.TextPrimary,
    weight: FontWeight = FontWeight.Normal,
) = TextStyle(color = color, fontSize = size, fontWeight = weight)

/** Soft shadow made of a few translucent rounded rectangles (works on every Android version). */
private fun DrawScope.softShadow(
    color: Color,
    corner: Float,
    spread: Float,
    dx: Float,
    dy: Float,
    layers: Int = 5,
) {
    for (i in 1..layers) {
        val t = i.toFloat() / layers
        val grow = spread * t
        val alpha = (color.alpha * (1f - t * 0.85f) / layers * 2.2f).coerceIn(0f, 1f)
        drawRoundRect(
            color = color.copy(alpha = alpha),
            topLeft = Offset(dx - grow, dy - grow),
            size = Size(size.width + 2 * grow, size.height + 2 * grow),
            cornerRadius = CornerRadius(corner + grow),
        )
    }
}

/** Extruded surface: light edge top-left, deep shadow bottom-right. */
fun Modifier.neuRaised(corner: Dp = 18.dp, depth: Dp = 6.dp): Modifier = drawBehind {
    val c = corner.toPx()
    val d = depth.toPx()
    softShadow(NeoColors.Shadow, c, d * 1.8f, d, d)
    softShadow(NeoColors.Highlight, c, d * 1.8f, -d, -d)
    drawRoundRect(
        brush = Brush.linearGradient(
            colors = listOf(Color(0xFF22262E), Color(0xFF1A1D23)),
            start = Offset.Zero,
            end = Offset(size.width, size.height),
        ),
        cornerRadius = CornerRadius(c),
    )
}

/** Pressed-in well: dark fill with an inner shadow along the top edge (tracks, selected tabs). */
fun Modifier.neuInset(corner: Dp = 12.dp): Modifier = drawBehind {
    val c = corner.toPx()
    drawRoundRect(color = NeoColors.Inset, cornerRadius = CornerRadius(c))
    val depth = minOf(size.height * 0.5f, 10.dp.toPx())
    drawRoundRect(
        brush = Brush.verticalGradient(
            colors = listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent),
            startY = 0f,
            endY = depth,
        ),
        cornerRadius = CornerRadius(c),
    )
}

/** Neon cyan glow + outline that marks the D-pad cursor. */
fun Modifier.neuFocusRing(visible: Boolean, corner: Dp = 18.dp): Modifier =
    if (!visible) this else drawBehind {
        val c = corner.toPx()
        for (i in 4 downTo 1) {
            val g = i * 3.dp.toPx()
            drawRoundRect(
                color = NeoColors.Cyan.copy(alpha = 0.07f * (5 - i)),
                topLeft = Offset(-g, -g),
                size = Size(size.width + 2 * g, size.height + 2 * g),
                cornerRadius = CornerRadius(c + g),
                style = Stroke(width = 3.dp.toPx()),
            )
        }
        drawRoundRect(
            color = NeoColors.Cyan,
            cornerRadius = CornerRadius(c),
            style = Stroke(width = 2.dp.toPx()),
        )
    }
