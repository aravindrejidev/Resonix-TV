package com.resonix.tv.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.resonix.tv.ui.theme.NeoColors
import com.resonix.tv.ui.theme.neuFocusRing
import com.resonix.tv.ui.theme.neuInset
import com.resonix.tv.ui.theme.neuRaised

/** Thin inset bar with a gradient fill, used for storage usage. */
@Composable
fun NeoUsageBar(fraction: Float, accent: Color, accent2: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxWidth().height(8.dp)) {
        val radius = CornerRadius(size.height / 2f)
        drawRoundRect(color = NeoColors.Inset, cornerRadius = radius)
        val filled = size.width * fraction.coerceIn(0f, 1f)
        if (filled > 0f) {
            drawRoundRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(accent, accent2),
                    startX = 0f,
                    endX = maxOf(filled, 1f),
                ),
                size = Size(filled, size.height),
                cornerRadius = radius,
            )
        }
    }
}

/** Big card for one storage device (Home screen). The caller sets the width. */
@Composable
fun NeoStorageCard(
    title: String,
    subtitle: String,
    usageText: String,
    fraction: Float,
    icon: NeoIcon,
    accent: Color,
    accent2: Color,
    focused: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .neuRaised(22.dp, 7.dp)
            .neuFocusRing(focused, 22.dp, accent)
            .padding(horizontal = 22.dp, vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NeoIconTile(icon, accent, tileSize = 60.dp)
        Spacer(Modifier.width(18.dp))
        Column(Modifier.weight(1f)) {
            NeoText(
                text = title,
                size = 21.sp,
                color = if (focused) Color.White else NeoColors.TextPrimary,
                weight = FontWeight.SemiBold,
            )
            NeoText(subtitle, size = 14.sp, color = NeoColors.TextSecondary)
            Spacer(Modifier.height(10.dp))
            NeoUsageBar(fraction, accent, accent2)
            Spacer(Modifier.height(6.dp))
            NeoText(usageText, size = 13.sp, color = NeoColors.TextSecondary)
        }
        Spacer(Modifier.width(10.dp))
        NeoIconView(NeoIcon.CHEVRON, tint = NeoColors.TextSecondary, iconSize = 22.dp)
    }
}

/** Movie preview with play button, duration and "how far you watched" bar. */
@Composable
fun NeoHistoryCard(
    title: String,
    durationText: String,
    progress: Float,
    thumbnail: ImageBitmap?,
    focused: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier.width(200.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .neuRaised(16.dp, 5.dp)
                .neuFocusRing(focused, 16.dp)
                .clip(RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (thumbnail != null) {
                Image(
                    bitmap = thumbnail,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                NeoIconView(NeoIcon.VIDEO, tint = NeoColors.TextDim, iconSize = 36.dp)
            }
            Box(
                Modifier.fillMaxSize().drawBehind {
                    drawRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f)),
                            startY = size.height * 0.4f,
                            endY = size.height,
                        ),
                    )
                },
            )
            Canvas(Modifier.size(46.dp)) {
                val r = size.minDimension / 2f
                drawCircle(color = Color.Black.copy(alpha = 0.35f), radius = r)
                drawCircle(color = Color.White.copy(alpha = 0.85f), radius = r - 1.dp.toPx(), style = Stroke(width = 2.dp.toPx()))
                val tri = Path().apply {
                    moveTo(size.width * 0.40f, size.height * 0.30f)
                    lineTo(size.width * 0.72f, size.height * 0.50f)
                    lineTo(size.width * 0.40f, size.height * 0.70f)
                    close()
                }
                drawPath(tri, Color.White)
            }
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.Bottom,
            ) {
                NeoText(durationText, size = 12.sp, color = Color.White)
                Spacer(Modifier.height(4.dp))
                Canvas(Modifier.fillMaxWidth().height(4.dp)) {
                    val radius = CornerRadius(size.height / 2f)
                    drawRoundRect(color = Color.White.copy(alpha = 0.25f), cornerRadius = radius)
                    val filled = size.width * progress.coerceIn(0f, 1f)
                    if (filled > 0f) {
                        drawRoundRect(
                            brush = Brush.horizontalGradient(
                                colors = listOf(NeoColors.Cyan, NeoColors.Blue),
                                startX = 0f,
                                endX = maxOf(filled, 1f),
                            ),
                            size = Size(filled, size.height),
                            cornerRadius = radius,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        NeoText(
            text = title,
            size = 14.sp,
            color = if (focused) NeoColors.Cyan else NeoColors.TextSecondary,
        )
    }
}

/** Last tile of the "Recently Watched" row. */
@Composable
fun NeoViewAllTile(focused: Boolean, modifier: Modifier = Modifier) {
    Column(modifier.width(140.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(140f / 124f)
                .neuRaised(16.dp, 5.dp)
                .neuFocusRing(focused, 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                NeoIconView(NeoIcon.HISTORY, tint = if (focused) NeoColors.Cyan else NeoColors.TextSecondary, iconSize = 30.dp)
                NeoText("View all", size = 15.sp, color = if (focused) NeoColors.Cyan else NeoColors.TextSecondary)
            }
        }
    }
}

/** Entry of the left navigation bar. */
@Composable
fun NeoSidebarItem(
    label: String,
    icon: NeoIcon,
    selected: Boolean,
    focused: Boolean,
    badge: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(54.dp)
            .drawBehind {
                if (selected) {
                    val radius = CornerRadius(16.dp.toPx())
                    drawRoundRect(
                        brush = Brush.horizontalGradient(
                            colors = listOf(NeoColors.Blue.copy(alpha = 0.55f), NeoColors.Cyan.copy(alpha = 0.16f)),
                        ),
                        cornerRadius = radius,
                    )
                    drawRoundRect(
                        color = NeoColors.Cyan.copy(alpha = 0.55f),
                        cornerRadius = radius,
                        style = Stroke(width = 1.5.dp.toPx()),
                    )
                    drawRoundRect(
                        color = NeoColors.Cyan,
                        topLeft = Offset(0f, size.height * 0.2f),
                        size = Size(4.dp.toPx(), size.height * 0.6f),
                        cornerRadius = CornerRadius(2.dp.toPx()),
                    )
                }
            }
            .neuFocusRing(focused, 16.dp)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NeoIconView(
            icon = icon,
            tint = if (selected) Color.White else NeoColors.TextSecondary,
            iconSize = 26.dp,
        )
        Spacer(Modifier.width(16.dp))
        NeoText(
            text = label,
            size = 19.sp,
            color = if (selected) Color.White else NeoColors.TextSecondary,
            weight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.weight(1f),
        )
        if (badge) {
            Canvas(Modifier.size(10.dp)) { drawCircle(NeoColors.Warning) }
        }
    }
}

@Composable
fun NeoSwitch(on: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier.size(width = 52.dp, height = 28.dp)) {
        val radius = CornerRadius(size.height / 2f)
        drawRoundRect(color = NeoColors.Inset, cornerRadius = radius)
        if (on) {
            drawRoundRect(
                brush = Brush.horizontalGradient(colors = listOf(NeoColors.Cyan.copy(alpha = 0.9f), NeoColors.Blue)),
                cornerRadius = radius,
            )
        }
        val knob = size.height / 2f - 3.dp.toPx()
        val cx = if (on) size.width - size.height / 2f else size.height / 2f
        val cy = size.height / 2f
        drawCircle(color = Color.Black.copy(alpha = 0.4f), radius = knob + 1.dp.toPx(), center = Offset(cx + 1.dp.toPx(), cy + 1.dp.toPx()))
        drawCircle(color = if (on) Color.White else NeoColors.TextSecondary, radius = knob, center = Offset(cx, cy))
    }
}

/** One line of the settings page: icon, texts, and a switch / value / arrow on the right. */
@Composable
fun NeoSettingRow(
    title: String,
    subtitle: String?,
    icon: NeoIcon,
    focused: Boolean,
    modifier: Modifier = Modifier,
    valueText: String? = null,
    toggle: Boolean? = null,
    chevron: Boolean = false,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .neuRaised(16.dp, 4.dp)
            .neuFocusRing(focused, 16.dp)
            .padding(horizontal = 18.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NeoIconTile(icon, NeoColors.Cyan, tileSize = 40.dp)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            NeoText(
                text = title,
                size = 19.sp,
                color = if (focused) Color.White else NeoColors.TextPrimary,
                weight = if (focused) FontWeight.SemiBold else FontWeight.Normal,
            )
            if (subtitle != null) NeoText(subtitle, size = 13.sp, color = NeoColors.TextSecondary)
        }
        Spacer(Modifier.width(12.dp))
        if (toggle != null) {
            NeoSwitch(toggle)
        } else if (valueText != null) {
            Box(Modifier.neuInset(10.dp).padding(horizontal = 16.dp, vertical = 6.dp)) {
                NeoText(valueText, size = 16.sp, color = NeoColors.Cyan, weight = FontWeight.SemiBold)
            }
        } else if (chevron) {
            NeoIconView(NeoIcon.CHEVRON, tint = NeoColors.TextSecondary, iconSize = 20.dp)
        }
    }
}

@Composable
fun NeoSectionHeader(title: String, subtitle: String?, icon: NeoIcon, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        NeoIconView(icon, tint = NeoColors.TextSecondary, iconSize = 32.dp)
        Spacer(Modifier.width(16.dp))
        Column {
            NeoText(title, size = 28.sp, weight = FontWeight.SemiBold)
            if (subtitle != null) NeoText(subtitle, size = 15.sp, color = NeoColors.TextSecondary)
        }
    }
}

/** Static raised card with a small caption, a big value and an optional detail line. */
@Composable
fun NeoInfoCard(
    title: String,
    value: String,
    detail: String?,
    modifier: Modifier = Modifier,
    accent: Color = NeoColors.Cyan,
) {
    Column(
        modifier = modifier.neuRaised(20.dp, 6.dp).padding(horizontal = 24.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        NeoText(title.uppercase(), size = 12.sp, color = NeoColors.TextDim, weight = FontWeight.SemiBold)
        NeoText(value, size = 26.sp, color = accent, weight = FontWeight.Bold)
        if (detail != null) NeoText(detail, size = 14.sp, color = NeoColors.TextSecondary, maxLines = 2)
    }
}
