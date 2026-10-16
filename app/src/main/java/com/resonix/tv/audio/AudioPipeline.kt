package com.resonix.tv.audio

/** App-wide audio chain: one DSP engine shared by the player and the (module 4) control panel. */
object AudioPipeline {
    val engine: DspEngine by lazy { DspEngine() }
    val processor: DspAudioProcessor by lazy { DspAudioProcessor(engine) }
}
