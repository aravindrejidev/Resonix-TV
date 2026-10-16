package com.resonix.tv.player;

import android.content.Context;
import androidx.annotation.Nullable;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.DefaultAudioSink;

/**
 * DefaultRenderersFactory whose audio sink contains our DSP processors.
 *
 * Kept in Java on purpose: Media3 changed the parameter list of buildAudioSink between
 * versions. Declaring both overloads (without @Override) compiles and works with either one.
 */
@UnstableApi
public final class ResonixRenderersFactory extends DefaultRenderersFactory {

    private final AudioProcessor[] processors;

    public ResonixRenderersFactory(Context context, AudioProcessor... processors) {
        super(context);
        this.processors = processors;
    }

    @Nullable
    protected AudioSink buildAudioSink(
            Context context, boolean enableFloatOutput, boolean enableAudioTrackPlaybackParams) {
        return createSink(context, enableFloatOutput);
    }

    @Nullable
    protected AudioSink buildAudioSink(
            Context context,
            boolean enableFloatOutput,
            boolean enableAudioTrackPlaybackParams,
            boolean enableOffload) {
        return createSink(context, enableFloatOutput);
    }

    private AudioSink createSink(Context context, boolean enableFloatOutput) {
        return new DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(enableFloatOutput)
                .setAudioProcessors(processors)
                .build();
    }
}
