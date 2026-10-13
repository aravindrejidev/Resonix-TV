# ---- ResonixTV R8 / ProGuard rules ----

# JNI bridge: native code resolves these by name.
-keep class com.resonix.tv.audio.NativeBridge { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}

# Media3 FFmpeg decoder extension (module 3). DefaultRenderersFactory creates it through
# reflection, so it has to survive shrinking in release builds.
-keep class androidx.media3.decoder.ffmpeg.** { *; }
