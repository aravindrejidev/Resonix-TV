// JNI entry points of libresonix_native.so.
// Step 1: only proves that the native library loads and that Oboe is linked.
// The real audio engine / DSP code arrives in module 3 (cpp/audio, cpp/dsp).

#include <jni.h>
#include <android/log.h>
#include <oboe/Oboe.h>

#include <string>

#define RESONIX_TAG "ResonixNative"
#define RESONIX_LOGI(...) __android_log_print(ANDROID_LOG_INFO, RESONIX_TAG, __VA_ARGS__)

extern "C" JNIEXPORT jstring JNICALL
Java_com_resonix_tv_audio_NativeBridge_nativeVersion(JNIEnv *env, jobject /* thiz */) {
    const bool aaudioSupported = oboe::AudioStreamBuilder::isAAudioSupported();
    const bool aaudioRecommended = oboe::AudioStreamBuilder::isAAudioRecommended();

    std::string info = "Native engine 0.1.0 | Oboe linked | AAudio supported: ";
    info += aaudioSupported ? "yes" : "no";
    info += ", recommended: ";
    info += aaudioRecommended ? "yes" : "no";

    RESONIX_LOGI("%s", info.c_str());
    return env->NewStringUTF(info.c_str());
}
