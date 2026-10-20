package com.resonix.tv.player

import android.net.Uri

/** A subtitle file that sits next to the video (found by the file browser). */
data class SubtitleSource(val uri: Uri, val mimeType: String, val label: String)

/** Everything the player needs to start: what to play and which side-car subtitles to offer. */
data class PlayRequest(
    val uri: Uri,
    val title: String,
    val subtitles: List<SubtitleSource> = emptyList(),
)
