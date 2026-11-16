# ---- ResonixTV R8 / ProGuard rules ----

# JNI bridge: native code resolves these by name.
-keep class com.resonix.tv.audio.NativeBridge { *; }
-keep class com.resonix.tv.audio.DspNative { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}

# Media3 FFmpeg decoder extension (module 3). DefaultRenderersFactory creates it through
# reflection, so it has to survive shrinking in release builds.
-keep class androidx.media3.decoder.ffmpeg.** { *; }

# Compile-time-only annotation libraries (used by the Media3 FFmpeg extension sources).
-dontwarn org.checkerframework.**
-dontwarn javax.annotation.**
-dontwarn org.jetbrains.annotations.**

# ---- Network shares (smbj: SMB2/SMB3 in pure Java) ----
# smbj, its event bus (mbassador) and BouncyCastle find parts of themselves through reflection
# (annotated @Handler methods, algorithm classes by name), so R8 must not strip or rename them.
-keep class com.hierynomus.** { *; }
-keep class net.engio.mbassy.** { *; }
-keep class org.bouncycastle.** { *; }
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*

# Optional pieces that smbj / BouncyCastle / mbassador mention but that Android does not have
# (Kerberos login, GSS-API, JNDI, the javax.el expression language, the sun.* internals) or that
# are only plug-in points (the slf4j log back-end). They are never used by ResonixTV (NTLM login),
# so R8 may ignore the missing classes instead of stopping the build.
-dontwarn org.ietf.jgss.**
-dontwarn javax.security.auth.kerberos.**
-dontwarn javax.security.auth.login.**
-dontwarn javax.naming.**
-dontwarn javax.el.**
-dontwarn org.slf4j.**
-dontwarn org.bouncycastle.**
-dontwarn net.engio.mbassy.**
-dontwarn com.hierynomus.**
-dontwarn sun.security.**
-dontwarn com.sun.**
-dontwarn java.lang.management.**
