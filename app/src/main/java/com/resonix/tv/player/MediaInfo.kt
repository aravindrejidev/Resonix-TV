package com.resonix.tv.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Tracks

/** The video format ExoPlayer is playing right now (null before playback starts). */
fun selectedVideoFormat(tracks: Tracks): Format? {
    for (group in tracks.groups) {
        if (group.type != C.TRACK_TYPE_VIDEO) continue
        for (i in 0 until group.length) {
            if (group.isTrackSelected(i)) return group.getTrackFormat(i)
        }
    }
    return null
}

fun videoCodecName(mime: String?): String = when (mime) {
    "video/hevc" -> "HEVC (H.265)"
    "video/avc" -> "H.264"
    "video/av01" -> "AV1"
    "video/x-vnd.on2.vp9" -> "VP9"
    "video/x-vnd.on2.vp8" -> "VP8"
    "video/dolby-vision" -> "Dolby Vision"
    "video/mpeg2" -> "MPEG-2"
    "video/wvc1" -> "VC-1"
    else -> mime.orEmpty().substringAfter('/').ifBlank { "unknown" }
}

/** SDR, HDR10 (PQ), HLG or Dolby Vision, as far as the file says. */
fun hdrName(format: Format): String {
    if (format.sampleMimeType == "video/dolby-vision") return "Dolby Vision"
    val info = format.colorInfo ?: return "SDR"
    return when (info.colorTransfer) {
        C.COLOR_TRANSFER_ST2084 -> "HDR10 (PQ)"
        C.COLOR_TRANSFER_HLG -> "HLG"
        else -> "SDR"
    }
}

/** "10-bit" when the file says so, else an empty text. */
fun bitDepthText(format: Format): String {
    val depth = format.colorInfo?.lumaBitdepth ?: -1
    return if (depth > 8) "$depth-bit" else ""
}

/** Software decoders are slow for big pictures; this is a good guess from the decoder's name. */
fun looksLikeSoftwareDecoder(name: String): Boolean {
    val n = name.lowercase()
    return n.startsWith("c2.android.") || n.startsWith("omx.google.") || n.contains("ffmpeg") ||
        n.contains("libvpx") || n.contains("dav1d") || n.contains("libgav1") || n.endsWith(".sw")
}

fun videoDecoderLabel(name: String): String = when {
    name.isBlank() -> "-"
    looksLikeSoftwareDecoder(name) -> "$name (software)"
    else -> "$name (hardware)"
}

fun audioDecoderLabel(name: String): String = when {
    name.isBlank() -> "-"
    name.lowercase().contains("ffmpeg") -> "FFmpeg"
    else -> name
}
