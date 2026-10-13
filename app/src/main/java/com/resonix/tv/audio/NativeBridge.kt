package com.resonix.tv.audio

/**
 * Thin JNI entry point into libresonix_native.so (C++ audio engine, Oboe, DSP).
 * Loading happens lazily the first time this object is touched.
 */
object NativeBridge {
    init {
        System.loadLibrary("resonix_native")
    }

    /** Human readable status of the native engine (Oboe / AAudio availability). */
    external fun nativeVersion(): String
}
