package com.resonix.tv.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import com.resonix.tv.audio.AudioPipeline

/** Builds the ExoPlayer used by the app: FFmpeg audio decoders (if built) + the native DSP chain. */
object PlayerFactory {

    @OptIn(UnstableApi::class)
    fun create(context: Context): ExoPlayer {
        val renderersFactory = ResonixRenderersFactory(context, AudioPipeline.processor)
            // PREFER: FFmpeg decoders (DTS, TrueHD, E-AC3 ...) win over MediaCodec when available.
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
            .setEnableDecoderFallback(true)

        val player = ExoPlayer.Builder(context, renderersFactory).build()
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
