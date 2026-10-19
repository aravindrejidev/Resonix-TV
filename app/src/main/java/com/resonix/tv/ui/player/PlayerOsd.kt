package com.resonix.tv.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.resonix.tv.ui.components.NeoChip
import com.resonix.tv.ui.components.NeoPlayGlyph
import com.resonix.tv.ui.components.NeoProgress
import com.resonix.tv.ui.components.NeoText
import com.resonix.tv.ui.formatTime
import com.resonix.tv.ui.theme.NeoColors
import com.resonix.tv.ui.theme.neuRaised

/** On-screen display at the bottom of the video: title, progress, times and key hints. */
@Composable
fun PlayerOsd(
    title: String,
    audioInfo: String,
    positionMs: Long,
    durationMs: Long,
    bufferedMs: Long,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
) {
    val progress = if (durationMs > 0) positionMs.toFloat() / durationMs else 0f
    val buffered = if (durationMs > 0) bufferedMs.toFloat() / durationMs else 0f
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 48.dp, vertical = 28.dp)
            .neuRaised(22.dp, 8.dp)
            .padding(horizontal = 28.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            NeoPlayGlyph(playing = isPlaying)
            Spacer(Modifier.width(14.dp))
            NeoText(
                text = title,
                size = 24.sp,
                weight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            if (audioInfo.isNotEmpty()) {
                NeoText(audioInfo, size = 16.sp, color = NeoColors.TextSecondary)
            }
        }
        NeoProgress(progress = progress, buffered = buffered)
        Row {
            NeoText(formatTime(positionMs), size = 16.sp, color = NeoColors.TextSecondary)
            Spacer(Modifier.weight(1f))
            NeoText(if (durationMs > 0) formatTime(durationMs) else "--:--", size = 16.sp, color = NeoColors.TextSecondary)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            NeoChip("LEFT/RIGHT  seek")
            NeoChip("OK  play/pause")
            NeoChip("DOWN  audio & EQ")
            NeoChip("BACK  exit")
        }
    }
}
