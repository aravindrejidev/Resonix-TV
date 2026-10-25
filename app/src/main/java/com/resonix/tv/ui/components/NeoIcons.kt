package com.resonix.tv.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.resonix.tv.ui.theme.NeoColors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Icons are drawn with the Canvas, so the app needs no icon library. */
enum class NeoIcon {
    HOME, EQ, SETTINGS, UPDATE, DRIVE, USB, HISTORY, PLAY, CHEVRON,
    WIFI, ETHERNET, OFFLINE, FOLDER, VIDEO, INFO,
}

private fun DrawScope.drawNeoIcon(icon: NeoIcon, color: Color) {
    val s = size.minDimension / 24f
    val stroke = Stroke(width = 2f * s, cap = StrokeCap.Round, join = StrokeJoin.Round)
    fun at(x: Float, y: Float) = Offset(x * s, y * s)

    fun wifi(alpha: Float) {
        val c = at(12f, 19f)
        for (r in listOf(5f, 9f, 13f)) {
            drawArc(
                color = color.copy(alpha = alpha),
                startAngle = 225f,
                sweepAngle = 90f,
                useCenter = false,
                topLeft = Offset(c.x - r * s, c.y - r * s),
                size = Size(2f * r * s, 2f * r * s),
                style = stroke,
            )
        }
        drawCircle(color.copy(alpha = alpha), 1.6f * s, at(12f, 19.4f))
    }

    when (icon) {
        NeoIcon.HOME -> {
            val path = Path().apply {
                moveTo(3f * s, 11f * s); lineTo(12f * s, 3.5f * s); lineTo(21f * s, 11f * s)
                moveTo(5.5f * s, 9.5f * s); lineTo(5.5f * s, 20.5f * s); lineTo(18.5f * s, 20.5f * s); lineTo(18.5f * s, 9.5f * s)
                moveTo(10f * s, 20.5f * s); lineTo(10f * s, 14f * s); lineTo(14f * s, 14f * s); lineTo(14f * s, 20.5f * s)
            }
            drawPath(path, color, style = stroke)
        }
        NeoIcon.EQ -> {
            for (x in listOf(6f, 12f, 18f)) drawLine(color, at(x, 4f), at(x, 20f), strokeWidth = 2f * s, cap = StrokeCap.Round)
            drawCircle(color, 2.7f * s, at(6f, 15f))
            drawCircle(color, 2.7f * s, at(12f, 8f))
            drawCircle(color, 2.7f * s, at(18f, 13f))
        }
        NeoIcon.SETTINGS -> {
            val c = at(12f, 12f)
            drawCircle(color, 6.3f * s, c, style = stroke)
            drawCircle(color, 2.6f * s, c, style = stroke)
            for (i in 0 until 8) {
                val a = i * PI.toFloat() / 4f
                drawLine(
                    color,
                    Offset(c.x + cos(a) * 6.3f * s, c.y + sin(a) * 6.3f * s),
                    Offset(c.x + cos(a) * 9.4f * s, c.y + sin(a) * 9.4f * s),
                    strokeWidth = 3f * s,
                    cap = StrokeCap.Round,
                )
            }
        }
        NeoIcon.UPDATE -> {
            val c = at(12f, 12f)
            val r = 7.5f * s
            drawArc(
                color = color,
                startAngle = -50f,
                sweepAngle = 290f,
                useCenter = false,
                topLeft = Offset(c.x - r, c.y - r),
                size = Size(2f * r, 2f * r),
                style = stroke,
            )
            val a = 240f * PI.toFloat() / 180f
            val end = Offset(c.x + cos(a) * r, c.y + sin(a) * r)
            val tip = Offset(end.x - sin(a) * 4.2f * s, end.y + cos(a) * 4.2f * s)
            val b1 = Offset(end.x + cos(a) * 3.8f * s, end.y + sin(a) * 3.8f * s)
            val b2 = Offset(end.x - cos(a) * 3.8f * s, end.y - sin(a) * 3.8f * s)
            val head = Path().apply { moveTo(tip.x, tip.y); lineTo(b1.x, b1.y); lineTo(b2.x, b2.y); close() }
            drawPath(head, color)
        }
        NeoIcon.DRIVE -> {
            drawRoundRect(color, at(3f, 7f), Size(18f * s, 11f * s), CornerRadius(3f * s), style = stroke)
            drawLine(color, at(6.5f, 13f), at(13.5f, 13f), strokeWidth = 2f * s, cap = StrokeCap.Round)
            drawCircle(color, 1.2f * s, at(17.3f, 13f))
        }
        NeoIcon.USB -> {
            rotate(35f, at(12f, 12f)) {
                drawRoundRect(color, at(8f, 8.5f), Size(8f * s, 13f * s), CornerRadius(2.2f * s), style = stroke)
                drawRoundRect(color, at(9.8f, 2.5f), Size(4.4f * s, 6f * s), CornerRadius(1f * s), style = stroke)
            }
        }
        NeoIcon.HISTORY -> {
            drawCircle(color, 9f * s, at(12f, 12f), style = stroke)
            val hands = Path().apply { moveTo(12f * s, 7f * s); lineTo(12f * s, 12f * s); lineTo(15.5f * s, 14f * s) }
            drawPath(hands, color, style = stroke)
        }
        NeoIcon.PLAY -> {
            val tri = Path().apply { moveTo(8f * s, 5f * s); lineTo(19f * s, 12f * s); lineTo(8f * s, 19f * s); close() }
            drawPath(tri, color)
        }
        NeoIcon.CHEVRON -> {
            val v = Path().apply { moveTo(9f * s, 5f * s); lineTo(16f * s, 12f * s); lineTo(9f * s, 19f * s) }
            drawPath(v, color, style = stroke)
        }
        NeoIcon.WIFI -> wifi(1f)
        NeoIcon.OFFLINE -> {
            wifi(0.35f)
            drawLine(color, at(4f, 4f), at(20f, 20f), strokeWidth = 2f * s, cap = StrokeCap.Round)
        }
        NeoIcon.ETHERNET -> {
            drawRoundRect(color, at(4f, 5f), Size(16f * s, 10f * s), CornerRadius(2f * s), style = stroke)
            drawLine(color, at(12f, 15f), at(12f, 19f), strokeWidth = 2f * s, cap = StrokeCap.Round)
            drawLine(color, at(7f, 19f), at(17f, 19f), strokeWidth = 2f * s, cap = StrokeCap.Round)
        }
        NeoIcon.FOLDER -> {
            val f = Path().apply {
                moveTo(3f * s, 6.5f * s); lineTo(9.5f * s, 6.5f * s); lineTo(11.5f * s, 9f * s)
                lineTo(21f * s, 9f * s); lineTo(21f * s, 19f * s); lineTo(3f * s, 19f * s); close()
            }
            drawPath(f, color, style = stroke)
        }
        NeoIcon.VIDEO -> {
            drawRoundRect(color, at(3f, 5f), Size(18f * s, 14f * s), CornerRadius(3f * s), style = stroke)
            val tri = Path().apply { moveTo(10f * s, 9f * s); lineTo(15f * s, 12f * s); lineTo(10f * s, 15f * s); close() }
            drawPath(tri, color)
        }
        NeoIcon.INFO -> {
            drawCircle(color, 9f * s, at(12f, 12f), style = stroke)
            drawLine(color, at(12f, 11f), at(12f, 17f), strokeWidth = 2f * s, cap = StrokeCap.Round)
            drawCircle(color, 1.2f * s, at(12f, 7.8f))
        }
    }
}

@Composable
fun NeoIconView(
    icon: NeoIcon,
    modifier: Modifier = Modifier,
    tint: Color = NeoColors.TextSecondary,
    iconSize: Dp = 24.dp,
) {
    Canvas(modifier.size(iconSize)) { drawNeoIcon(icon, tint) }
}

/** Rounded tile with a soft gradient of the accent colour and an icon on it. */
@Composable
fun NeoIconTile(
    icon: NeoIcon,
    accent: Color,
    modifier: Modifier = Modifier,
    tileSize: Dp = 56.dp,
) {
    Box(
        modifier = modifier
            .size(tileSize)
            .drawBehind {
                val radius = CornerRadius(this.size.minDimension * 0.28f)
                drawRoundRect(
                    brush = Brush.linearGradient(
                        colors = listOf(accent.copy(alpha = 0.50f), accent.copy(alpha = 0.14f)),
                        start = Offset.Zero,
                        end = Offset(this.size.width, this.size.height),
                    ),
                    cornerRadius = radius,
                )
                drawRoundRect(
                    color = accent.copy(alpha = 0.55f),
                    cornerRadius = radius,
                    style = Stroke(width = 1.5f.dp.toPx()),
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        NeoIconView(icon, tint = Color.White.copy(alpha = 0.95f), iconSize = tileSize * 0.5f)
    }
}
