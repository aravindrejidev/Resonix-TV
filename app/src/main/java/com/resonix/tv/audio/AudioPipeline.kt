package com.resonix.tv.audio

import android.content.Context

/** App-wide audio chain: one DSP engine shared by the player and the audio control panel. */
object AudioPipeline {
    val engine: DspEngine by lazy { DspEngine() }
    val processor: DspAudioProcessor by lazy { DspAudioProcessor(engine) }

    @Volatile
    private var controllerInstance: DspController? = null

    /** UI-facing settings (saved on the device). Created on first use and pushed into the engine. */
    fun controller(context: Context): DspController =
        controllerInstance ?: synchronized(this) {
            controllerInstance ?: DspController(
                engine,
                context.applicationContext.getSharedPreferences("resonix_dsp", Context.MODE_PRIVATE),
            ).also { controllerInstance = it }
        }
}
