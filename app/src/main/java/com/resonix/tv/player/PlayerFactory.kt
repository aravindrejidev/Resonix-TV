package com.resonix.tv.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import com.resonix.tv.audio.AudioPipeline

/** Builds the ExoPlayer used by the app: FFmpeg audio decoders (if built) + the native DSP chain. */
object PlayerFactory {

    @OptIn(UnstableApi::class)
    fun create(context: Context): ExoPlayer {
        val renderersFactory = ResonixRenderersFactory(context, AudioPipeline.processor)
            // PREFER: FFmpeg decoders (DTS, TrueHD, E-AC3 ...) win over MediaCodec when available.
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
            .setEnableDecoderFallback(true)

        val player = ExoPlayer.Builder(context, renderersFactory)
            // Keeps the CPU awake while a local file plays, even if the TV turns its screen off.
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        // Jump to the nearest keyframe instead of decoding up to the exact frame: LEFT / RIGHT
        // become instant, also in big 4K files. (The picture may land a moment off the target.)
        player.setSeekParameters(SeekParameters.CLOSEST_SYNC)
        player.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(),
            /* handleAudioFocus = */ true,
        )
        player.setHandleAudioBecomingNoisy(true)
        return player
    }
}
