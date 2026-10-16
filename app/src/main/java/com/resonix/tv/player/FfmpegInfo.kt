package com.resonix.tv.player

import androidx.media3.common.MimeTypes

/**
 * Reports whether the FFmpeg decoder extension is part of this build and which codecs it handles.
 * Uses reflection so the app compiles and runs with or without the extension
 * (it is fetched and built by scripts/setup_ffmpeg.sh in CI).
 */
object FfmpegInfo {
    private const val LIBRARY_CLASS = "androidx.media3.decoder.ffmpeg.FfmpegLibrary"

    fun describe(): String {
        val library = runCatching { Class.forName(LIBRARY_CLASS) }.getOrNull()
            ?: return "FFmpeg decoders: not part of this build"
        return runCatching {
            val available = library.getMethod("isAvailable").invoke(null) as Boolean
            if (!available) return@runCatching "FFmpeg decoders: native library failed to load"

            val version = library.getMethod("getVersion").invoke(null) as? String ?: "?"
            val supportsFormat = library.getMethod("supportsFormat", String::class.java)
            val codecs = listOf(
                MimeTypes.AUDIO_AC3 to "AC3",
                MimeTypes.AUDIO_E_AC3 to "E-AC3",
                MimeTypes.AUDIO_DTS to "DTS",
                MimeTypes.AUDIO_DTS_HD to "DTS-HD",
                MimeTypes.AUDIO_TRUEHD to "TrueHD",
                MimeTypes.AUDIO_FLAC to "FLAC",
            ).filter { (mime, _) -> supportsFormat.invoke(null, mime) as Boolean }
                .joinToString(", ") { it.second }
            "FFmpeg $version | $codecs"
        }.getOrElse { "FFmpeg decoders: ${it.javaClass.simpleName}" }
    }
}
